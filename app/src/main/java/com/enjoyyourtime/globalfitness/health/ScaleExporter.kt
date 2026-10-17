package com.enjoyyourtime.globalfitness.health

import android.util.Log
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.records.BasalMetabolicRateRecord
import androidx.health.connect.client.records.BodyFatRecord
import androidx.health.connect.client.records.BodyWaterMassRecord
import androidx.health.connect.client.records.BoneMassRecord
import androidx.health.connect.client.records.LeanBodyMassRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.records.metadata.DataOrigin
import androidx.health.connect.client.records.metadata.Device
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import androidx.health.connect.client.units.Mass
import androidx.health.connect.client.units.Percentage
import androidx.health.connect.client.units.Power
import com.enjoyyourtime.globalfitness.R
import com.enjoyyourtime.globalfitness.UiText
import com.enjoyyourtime.globalfitness.data.Measurement
import com.enjoyyourtime.globalfitness.data.Repository
import com.enjoyyourtime.globalfitness.scale.ScaleRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import kotlin.reflect.KClass

data class ExportState(
    val running: Boolean = false,
    /** Measurements currently written to Health Connect. */
    val exportedCount: Int = 0,
    /** Result of the last history export / error. */
    val message: UiText? = null,
    val error: UiText? = null,
)

/**
 * Writes the profile's scale measurements to Health Connect (weight, body fat, bone mass, lean
 * mass, BMR, body water) and deletes them again when a measurement is deleted or unassigned.
 * Observes the [Repository] read-only; what was written is tracked in the cache's ledger. Records
 * left by an earlier installation in the legacy id scheme are swept once ([LegacyRecordSweep]).
 */
class ScaleExporter(
    private val gateway: HealthConnectGateway,
    private val cache: HealthCache,
    private val settings: HealthSettings,
    private val repository: Repository,
    /** Scale drivers, for the device (brand / model) of each reading. */
    private val scales: ScaleRegistry,
    private val scope: CoroutineScope,
    private val ownPackage: String,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val clock: () -> Long = System::currentTimeMillis,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
) {
    private val mutex = Mutex()
    private val _state = MutableStateFlow(ExportState())
    val state: StateFlow<ExportState> = _state.asStateFlow()

    fun start() {
        scope.launch {
            val count = withContext(io) { cache.ledgerCount() }
            _state.update { it.copy(exportedCount = count) }
            combine(
                repository.loaded,
                repository.profiles,
                repository.measurements,
                settings.exportChoice,
                gateway.granted,
            ) { loaded, _, _, _, granted -> loaded && HealthPermissions.writableTypes(granted).isNotEmpty() }
                .collectLatest { ready ->
                    if (!ready) return@collectLatest
                    delay(DEBOUNCE_MS)
                    runCatchingReconcile(backfill = false)
                }
        }
    }

    /** Switch "Send scale measurements…"; turning it on only sends measurements taken from now on. */
    fun setEnabled(enabled: Boolean) {
        if (enabled) settings.setExportAfterId(repository.measurements.value.maxOfOrNull { it.id } ?: 0L)
        settings.setExportChoice(enabled)
    }

    /** "Export history to Health Connect" (also sweeps legacy records again). */
    suspend fun exportHistory() {
        _state.update { it.copy(running = true, message = null, error = null) }
        val result = runCatchingReconcile(backfill = true)
        _state.update { it.copy(running = false, message = result?.let(::historyMessage) ?: it.message) }
    }

    private fun historyMessage(result: Reconciled): UiText {
        val written = when (result.written) {
            0 -> UiText.res(R.string.hc_export_already_done)
            else -> UiText.plural(R.plurals.msg_csv_exported, result.written)
        }
        return when (result.legacyDeleted) {
            0 -> written
            else -> UiText.Joined(listOf(written, UiText.plural(R.plurals.hc_export_legacy_deleted, result.legacyDeleted)), " · ")
        }
    }

    private suspend fun runCatchingReconcile(backfill: Boolean): Reconciled? = try {
        reconcile(backfill).also { _state.update { s -> s.copy(error = null) } }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "Health Connect export failed", e)
        _state.update { it.copy(running = false, error = UiText.res(R.string.hc_export_failed, HealthSync.describe(e))) }
        null
    }

    /** Measurements written and legacy records deleted by one reconciliation. */
    private data class Reconciled(val written: Int, val legacyDeleted: Int)

    private suspend fun reconcile(backfill: Boolean): Reconciled = mutex.withLock {
        val nothing = Reconciled(0, 0)
        if (!repository.loaded.value) return nothing
        val granted = gateway.granted.value
        val allowed = ExportRecordType.entries.filter { HealthPermissions.canWrite(granted, it.dataType) }.toSet()
        if (allowed.isEmpty()) return nothing
        val client = gateway.client() ?: return nothing
        val canExport = HealthPermissions.canExport(granted)
        val enabled = HealthSettings.exportEnabled(settings.exportChoice.value, canExport)
        val measurements = repository.measurements.value
        if (enabled && settings.exportAfterId < 0) settings.setExportAfterId(measurements.maxOfOrNull { it.id } ?: 0L)

        // Single-profile app: Health Connect data belongs to the (only) profile.
        val profile = repository.profiles.value.firstOrNull()
        val owned = if (profile == null) emptyList() else measurements.filter { it.profileId == profile.id }
        val payloads = if (profile == null) emptyList() else owned.mapNotNull { ExportPayload.from(it, profile, allowed) }
        val written = withContext(io) {
            val plan = ScaleExportPlanner.plan(
                ownedIds = owned.mapTo(HashSet()) { it.id },
                payloads = payloads,
                ledger = cache.loadLedger(),
                autoAfterId = settings.exportAfterId,
                backfill = backfill,
                writeEnabled = if (backfill) canExport else enabled,
                now = clock(),
                deletable = allowed,
            )
            if (plan.isEmpty) return@withContext 0
            execute(client, plan, allowed)
            plan.writes.size
        }
        val legacyDeleted = withContext(io) { sweepLegacy(client, allowed, measurements, force = backfill) }
        val count = withContext(io) { cache.ledgerCount() }
        _state.update { it.copy(exportedCount = count) }
        Reconciled(written, legacyDeleted)
    }

    /**
     * Deletes this app's own records left in the legacy `gf-` scheme that the ledger does not track
     * ([LegacyRecordSweep]). Each record type is swept once (remembered in health.db, which is not
     * backed up, so a reinstall sweeps again) and again on every history export. A type that
     * cannot be read right now (partial grant) or a failure is retried on a later run; the export
     * itself is never affected. Returns the number of records deleted.
     */
    private suspend fun sweepLegacy(
        client: HealthConnectClient,
        writable: Set<ExportRecordType>,
        measurements: List<Measurement>,
        force: Boolean,
    ): Int {
        val swept = ExportRecordType.entries.filterTo(HashSet()) { cache.meta(sweepKey(it)) != null }
        val types = LegacyRecordSweep.pendingTypes(writable, swept, force)
        if (types.isEmpty()) return 0
        val ledger = cache.loadLedger()
        var deleted = 0
        try {
            for (type in types) {
                val stale = try {
                    LegacyRecordSweep.select(readOwnRecords(client, type), ownPackage, ledger, measurements)
                } catch (e: SecurityException) {
                    Log.i(TAG, "Legacy sweep of ${type.code} skipped: ${e.message}")
                    continue
                }
                for (chunk in stale.chunked(DELETE_CHUNK)) client.deleteRecords(recordClass(type), chunk, emptyList())
                cache.setMeta(sweepKey(type), clock().toString())
                deleted += stale.size
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Legacy record sweep failed", e)
        }
        if (deleted > 0) Log.i(TAG, "Deleted $deleted legacy Health Connect records")
        return deleted
    }

    /** Every record of [type] written by this app (all pages). */
    private suspend fun readOwnRecords(client: HealthConnectClient, type: ExportRecordType): List<StoredRecord> {
        val records = ArrayList<StoredRecord>()
        var pageToken: String? = null
        do {
            val response = client.readRecords(
                ReadRecordsRequest(
                    recordType = recordClass(type),
                    timeRangeFilter = TimeRangeFilter.after(Instant.EPOCH),
                    dataOriginFilter = setOf(DataOrigin(ownPackage)),
                    pageSize = READ_PAGE,
                    pageToken = pageToken,
                ),
            )
            response.records.mapTo(records) { r ->
                StoredRecord(r.metadata.id, r.metadata.clientRecordId, r.metadata.dataOrigin.packageName, timeOf(r)?.toEpochMilli())
            }
            pageToken = response.pageToken
        } while (pageToken != null)
        return records
    }

    private suspend fun execute(client: HealthConnectClient, plan: ExportPlan, allowed: Set<ExportRecordType>) {
        val now = clock()
        // Deletions: measurements that were removed or unassigned.
        val deletable = plan.deletes.associateWith { it.types intersect allowed }
        deleteRecords(client, deletable.flatMap { (entry, types) -> entry.refs(types) })
        for ((entry, types) in deletable) {
            val remaining = entry.types - types
            if (remaining.isEmpty()) cache.removeLedger(listOf(entry.measurementId)) else cache.putLedger(entry.copy(types = remaining), now)
        }
        // (Re-)writes in chunks; Health Connect upserts by clientRecordId + version. Stale records
        // (dropped values, or legacy `gf-<id>` ids being migrated) are deleted after the insert.
        val zone = zone()
        for (chunk in plan.writes.chunked(WRITE_CHUNK)) {
            client.insertRecords(chunk.flatMap { buildRecords(it.payload, it.version, zone, scales) })
            deleteRecords(client, chunk.flatMap { w -> w.stale.filter { it.type in allowed } })
            for (w in chunk) {
                val p = w.payload
                cache.putLedger(LedgerEntry(p.measurementId, p.signature, w.version, p.types, p.recordKey), now)
            }
        }
    }

    private suspend fun deleteRecords(client: HealthConnectClient, refs: List<RecordRef>) {
        for ((type, ids) in refs.groupBy({ it.type }, { it.clientId })) {
            for (chunk in ids.chunked(DELETE_CHUNK)) {
                client.deleteRecords(recordClass(type), emptyList(), chunk)
            }
        }
    }

    companion object {
        private const val TAG = "ScaleExporter"
        private const val DEBOUNCE_MS = 1_000L
        private const val WRITE_CHUNK = 50
        private const val DELETE_CHUNK = 500
        private const val READ_PAGE = 1_000

        private fun sweepKey(type: ExportRecordType) = "legacy_sweep_${type.code}"

        private fun timeOf(record: Record): Instant? = when (record) {
            is WeightRecord -> record.time
            is BodyFatRecord -> record.time
            is BoneMassRecord -> record.time
            is LeanBodyMassRecord -> record.time
            is BasalMetabolicRateRecord -> record.time
            is BodyWaterMassRecord -> record.time
            else -> null
        }

        fun recordClass(type: ExportRecordType): KClass<out Record> = when (type) {
            ExportRecordType.WEIGHT -> WeightRecord::class
            ExportRecordType.BODY_FAT -> BodyFatRecord::class
            ExportRecordType.BONE_MASS -> BoneMassRecord::class
            ExportRecordType.LEAN_BODY_MASS -> LeanBodyMassRecord::class
            ExportRecordType.BASAL_METABOLIC_RATE -> BasalMetabolicRateRecord::class
            ExportRecordType.BODY_WATER_MASS -> BodyWaterMassRecord::class
        }

        /**
         * The scale behind a reading: brand and model of its driver in [scales], or a scale of
         * unknown make (readings stored before multi-scale support, or a driver that no longer exists).
         */
        fun scaleDevice(driverId: String?, scales: ScaleRegistry): Device {
            val driver = scales.find(driverId) ?: return Device(type = Device.TYPE_SCALE)
            // "Xiaomi Mi Body Composition Scale 2" by "Xiaomi" -> model "Mi Body Composition Scale 2".
            val model = driver.displayName.removePrefix("${driver.brand} ").ifBlank { driver.displayName }
            return Device(type = Device.TYPE_SCALE, manufacturer = driver.brand, model = model)
        }

        /** Health Connect records of one payload; scale readings carry their scale, manual ones no device. */
        fun buildRecords(p: ExportPayload, version: Long, zone: ZoneId, scales: ScaleRegistry): List<Record> {
            val time = Instant.ofEpochMilli(p.timestamp)
            val offset = zone.rules.getOffset(time)
            val device = if (p.fromScale) scaleDevice(p.deviceDriver, scales) else null
            fun meta(type: ExportRecordType): Metadata {
                val id = ClientRecordIds.of(p.recordKey, type)
                return if (device != null) Metadata.autoRecorded(device, id, version) else Metadata.manualEntry(id, version)
            }
            return listOfNotNull<Record>(
                WeightRecord(time, offset, Mass.kilograms(p.weightKg), meta(ExportRecordType.WEIGHT)),
                p.bodyFatPercent?.let { BodyFatRecord(time, offset, Percentage(it), meta(ExportRecordType.BODY_FAT)) },
                p.boneKg?.let { BoneMassRecord(time, offset, Mass.kilograms(it), meta(ExportRecordType.BONE_MASS)) },
                p.leanKg?.let { LeanBodyMassRecord(time, offset, Mass.kilograms(it), meta(ExportRecordType.LEAN_BODY_MASS)) },
                p.bmrKcalPerDay?.let {
                    BasalMetabolicRateRecord(time, offset, Power.kilocaloriesPerDay(it), meta(ExportRecordType.BASAL_METABOLIC_RATE))
                },
                p.waterKg?.let { BodyWaterMassRecord(time, offset, Mass.kilograms(it), meta(ExportRecordType.BODY_WATER_MASS)) },
            )
        }
    }
}
