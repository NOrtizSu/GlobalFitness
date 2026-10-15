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
import androidx.health.connect.client.records.metadata.Device
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.units.Mass
import androidx.health.connect.client.units.Percentage
import androidx.health.connect.client.units.Power
import com.enjoyyourtime.globalfitness.data.Repository
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
    /** Spanish result of the last history export / error. */
    val message: String? = null,
    val error: String? = null,
)

/**
 * Writes the profile's scale measurements to Health Connect (weight, body fat, bone mass, lean
 * mass, BMR, body water) and deletes them again when a measurement is deleted or unassigned.
 * Observes the [Repository] read-only; what was written is tracked in the cache's ledger.
 */
class ScaleExporter(
    private val gateway: HealthConnectGateway,
    private val cache: HealthCache,
    private val settings: HealthSettings,
    private val repository: Repository,
    private val scope: CoroutineScope,
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

    /** Switch "Enviar mediciones…"; turning it on only sends measurements taken from now on. */
    fun setEnabled(enabled: Boolean) {
        if (enabled) settings.setExportAfterId(repository.measurements.value.maxOfOrNull { it.id } ?: 0L)
        settings.setExportChoice(enabled)
    }

    /** "Exportar historial a Health Connect". */
    suspend fun exportHistory() {
        _state.update { it.copy(running = true, message = null, error = null) }
        val written = runCatchingReconcile(backfill = true)
        _state.update {
            it.copy(
                running = false,
                message = when (written) {
                    null -> it.message
                    0 -> "El historial ya estaba en Health Connect"
                    1 -> "Exportada 1 medición"
                    else -> "Exportadas $written mediciones"
                },
            )
        }
    }

    private suspend fun runCatchingReconcile(backfill: Boolean): Int? = try {
        reconcile(backfill).also { _state.update { s -> s.copy(error = null) } }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "Health Connect export failed", e)
        _state.update { it.copy(running = false, error = "No se pudo escribir en Health Connect: ${HealthSync.describe(e)}") }
        null
    }

    /** Returns the number of measurements written. */
    private suspend fun reconcile(backfill: Boolean): Int = mutex.withLock {
        if (!repository.loaded.value) return 0
        val granted = gateway.granted.value
        val allowed = ExportRecordType.entries.filter { HealthPermissions.canWrite(granted, it.dataType) }.toSet()
        if (allowed.isEmpty()) return 0
        val client = gateway.client() ?: return 0
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
            )
            if (plan.isEmpty) return@withContext 0
            execute(client, plan, allowed)
            plan.writes.size
        }
        val count = withContext(io) { cache.ledgerCount() }
        _state.update { it.copy(exportedCount = count) }
        written
    }

    private suspend fun execute(client: HealthConnectClient, plan: ExportPlan, allowed: Set<ExportRecordType>) {
        val now = clock()
        // Deletions: measurements that were removed or unassigned.
        val deletable = plan.deletes.associateWith { it.types intersect allowed }
        deleteRecords(client, deletable.flatMap { (entry, types) -> types.map { entry.measurementId to it } })
        for ((entry, types) in deletable) {
            val remaining = entry.types - types
            if (remaining.isEmpty()) cache.removeLedger(listOf(entry.measurementId)) else cache.putLedger(entry.copy(types = remaining), now)
        }
        // (Re-)writes in chunks; Health Connect upserts by clientRecordId + version.
        val zone = zone()
        for (chunk in plan.writes.chunked(WRITE_CHUNK)) {
            client.insertRecords(chunk.flatMap { buildRecords(it.payload, it.version, zone) })
            deleteRecords(client, chunk.flatMap { w -> (w.staleTypes intersect allowed).map { w.payload.measurementId to it } })
            for (w in chunk) {
                cache.putLedger(LedgerEntry(w.payload.measurementId, w.payload.signature, w.version, w.payload.types), now)
            }
        }
    }

    private suspend fun deleteRecords(client: HealthConnectClient, items: List<Pair<Long, ExportRecordType>>) {
        for ((type, list) in items.groupBy({ it.second }, { it.first })) {
            for (ids in list.chunked(DELETE_CHUNK)) {
                client.deleteRecords(recordClass(type), emptyList(), ids.map { ClientRecordIds.of(it, type) })
            }
        }
    }

    companion object {
        private const val TAG = "ScaleExporter"
        private const val DEBOUNCE_MS = 1_000L
        private const val WRITE_CHUNK = 50
        private const val DELETE_CHUNK = 500

        private val SCALE = Device(type = Device.TYPE_SCALE, manufacturer = "Xiaomi", model = "Mi Body Composition Scale")

        fun recordClass(type: ExportRecordType): KClass<out Record> = when (type) {
            ExportRecordType.WEIGHT -> WeightRecord::class
            ExportRecordType.BODY_FAT -> BodyFatRecord::class
            ExportRecordType.BONE_MASS -> BoneMassRecord::class
            ExportRecordType.LEAN_BODY_MASS -> LeanBodyMassRecord::class
            ExportRecordType.BASAL_METABOLIC_RATE -> BasalMetabolicRateRecord::class
            ExportRecordType.BODY_WATER_MASS -> BodyWaterMassRecord::class
        }

        /** Health Connect records of one payload; scale readings carry a scale device. */
        fun buildRecords(p: ExportPayload, version: Long, zone: ZoneId): List<Record> {
            val time = Instant.ofEpochMilli(p.timestamp)
            val offset = zone.rules.getOffset(time)
            fun meta(type: ExportRecordType): Metadata {
                val id = ClientRecordIds.of(p.measurementId, type)
                return if (p.fromScale) Metadata.autoRecorded(SCALE, id, version) else Metadata.manualEntry(id, version)
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
