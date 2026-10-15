package com.enjoyyourtime.globalfitness.health

import com.enjoyyourtime.globalfitness.cloud.MeasurementIds
import com.enjoyyourtime.globalfitness.data.Measurement
import com.enjoyyourtime.globalfitness.data.MeasurementSource
import com.enjoyyourtime.globalfitness.data.Profile
import com.enjoyyourtime.globalfitness.domain.BodyMetrics
import java.util.Locale

/** Health Connect record types written for a scale measurement. */
enum class ExportRecordType(val code: String, val dataType: HealthDataType) {
    WEIGHT("weight", HealthDataType.WEIGHT),
    BODY_FAT("bodyfat", HealthDataType.BODY_FAT),
    BONE_MASS("bone", HealthDataType.BONE_MASS),
    LEAN_BODY_MASS("lean", HealthDataType.LEAN_BODY_MASS),
    BASAL_METABOLIC_RATE("bmr", HealthDataType.BASAL_METABOLIC_RATE),
    BODY_WATER_MASS("water", HealthDataType.BODY_WATER_MASS),
    ;

    companion object {
        fun fromCode(code: String): ExportRecordType? = entries.firstOrNull { it.code == code }
    }
}

/**
 * `clientRecordId` scheme: `<recordKey>-<type>`, where the record key is the measurement's stable
 * natural id `m_<epochMillis>_<weightGrams>` (the same id as the cloud backup, [MeasurementIds]).
 * It does not depend on the local database id, which restarts at 1 after a reinstall or a data
 * wipe, so a new measurement can never overwrite the records of an older one. Health Connect
 * upserts records with the same client id when the `clientRecordVersion` is not lower, so
 * re-writes are idempotent.
 *
 * Older builds used `gf-<measurementId>-<type>` ([legacy]); ledger entries written that way have no
 * record key and are migrated (re-written with the natural id, legacy records deleted).
 */
object ClientRecordIds {
    private const val LEGACY_PREFIX = "gf-"

    fun recordKey(m: Measurement): String = MeasurementIds.idOf(m.timestamp, m.weightKg)

    fun of(recordKey: String, type: ExportRecordType): String = "$recordKey-${type.code}"

    fun legacy(measurementId: Long, type: ExportRecordType): String = "$LEGACY_PREFIX$measurementId-${type.code}"

    /** True for a client id in the legacy `gf-<measurementId>-<type>` scheme. */
    fun isLegacy(clientId: String?): Boolean = clientId?.startsWith(LEGACY_PREFIX) == true
}

/** One Health Connect record of a measurement: its type and client id. */
data class RecordRef(val type: ExportRecordType, val clientId: String)

/** Values of one measurement to write. Null values are not written. */
data class ExportPayload(
    val measurementId: Long,
    /** Stable natural id the client record ids are built from ([ClientRecordIds]). */
    val recordKey: String,
    val timestamp: Long,
    val fromScale: Boolean,
    /** Driver id of the scale that took it ([Measurement.deviceDriver]); null when unknown or manual. */
    val deviceDriver: String?,
    val weightKg: Double,
    val bodyFatPercent: Double?,
    val boneKg: Double?,
    val leanKg: Double?,
    val bmrKcalPerDay: Double?,
    val waterKg: Double?,
) {
    val types: Set<ExportRecordType>
        get() = buildSet {
            add(ExportRecordType.WEIGHT)
            if (bodyFatPercent != null) add(ExportRecordType.BODY_FAT)
            if (boneKg != null) add(ExportRecordType.BONE_MASS)
            if (leanKg != null) add(ExportRecordType.LEAN_BODY_MASS)
            if (bmrKcalPerDay != null) add(ExportRecordType.BASAL_METABOLIC_RATE)
            if (waterKg != null) add(ExportRecordType.BODY_WATER_MASS)
        }

    /** Stable fingerprint: a changed value (impedance added, profile edited...) triggers a re-write. */
    val signature: String
        get() = listOf(
            timestamp.toString(),
            if (fromScale) "s" else "m",
            fmt(weightKg),
            fmt(bodyFatPercent),
            fmt(boneKg),
            fmt(leanKg),
            fmt(bmrKcalPerDay),
            fmt(waterKg),
        ).joinToString("|")

    private fun fmt(v: Double?): String = v?.let { String.format(Locale.ROOT, "%.2f", it) } ?: "-"

    companion object {
        /**
         * Builds the payload of [m] for [profile], keeping only the record types in [allowed]
         * (those with a granted write permission). Null when weight cannot be written.
         * Composition reported by the scale itself wins over the formulas, as everywhere else.
         */
        fun from(m: Measurement, profile: Profile, allowed: Set<ExportRecordType>): ExportPayload? {
            if (ExportRecordType.WEIGHT !in allowed) return null
            val c = BodyMetrics.forMeasurement(profile.sex, profile.heightCm, profile.ageAt(m.timestamp), m)
            fun <T> keep(type: ExportRecordType, value: T?): T? = if (type in allowed) value else null
            return ExportPayload(
                measurementId = m.id,
                recordKey = ClientRecordIds.recordKey(m),
                timestamp = m.timestamp,
                fromScale = m.source == MeasurementSource.SCALE,
                deviceDriver = m.deviceDriver,
                weightKg = m.weightKg,
                bodyFatPercent = keep(ExportRecordType.BODY_FAT, c.fatPercent),
                boneKg = keep(ExportRecordType.BONE_MASS, c.boneKg),
                leanKg = keep(ExportRecordType.LEAN_BODY_MASS, c.fatMassKg?.let { c.weightKg - it }),
                bmrKcalPerDay = keep(ExportRecordType.BASAL_METABOLIC_RATE, c.bmr),
                waterKg = keep(ExportRecordType.BODY_WATER_MASS, c.waterPercent?.let { c.weightKg * it / 100.0 }),
            )
        }
    }
}

/** What has been written to Health Connect for one measurement (persisted in health.db). */
data class LedgerEntry(
    val measurementId: Long,
    val signature: String,
    val version: Long,
    val types: Set<ExportRecordType>,
    /** Natural id the records were written with; null for records in the legacy `gf-<id>` scheme. */
    val recordKey: String?,
) {
    fun clientId(type: ExportRecordType): String =
        recordKey?.let { ClientRecordIds.of(it, type) } ?: ClientRecordIds.legacy(measurementId, type)

    fun refs(of: Set<ExportRecordType> = types): List<RecordRef> = of.map { RecordRef(it, clientId(it)) }
}

/** A (re-)write of [payload]; [stale] records were written before and must go (dropped values, old ids). */
data class ExportWrite(val payload: ExportPayload, val version: Long, val stale: List<RecordRef>)

data class ExportPlan(val writes: List<ExportWrite>, val deletes: List<LedgerEntry>) {
    val isEmpty: Boolean get() = writes.isEmpty() && deletes.isEmpty()
}

object ScaleExportPlanner {
    /**
     * @param ownedIds ids of the measurements that belong to the profile right now; anything
     *   else in the ledger (deleted, or unassigned) is deleted from Health Connect, even with
     *   the toggle off, so Health Connect never keeps stale records.
     * @param payloads values to write for (a subset of) [ownedIds].
     * @param ledger what was written before.
     * @param autoAfterId measurements with a larger id are "new" and written automatically;
     *   older ones are only written by a history export ([backfill]).
     * @param writeEnabled the "Enviar mediciones" toggle (or an explicit history export).
     * @param now wall clock. Record versions never go below it, so a write always supersedes
     *   records left with the same natural id by an earlier installation.
     * @param deletable record types this app may delete right now (write permission). A
     *   measurement only moves to new ids once every record written under the old ones can go;
     *   until then it keeps its ledger entry, so nothing is left behind untracked.
     */
    fun plan(
        ownedIds: Set<Long>,
        payloads: List<ExportPayload>,
        ledger: List<LedgerEntry>,
        autoAfterId: Long,
        backfill: Boolean,
        writeEnabled: Boolean,
        now: Long,
        deletable: Set<ExportRecordType> = ExportRecordType.entries.toSet(),
    ): ExportPlan {
        val byId = ledger.associateBy { it.measurementId }
        val deletes = ledger.filter { it.measurementId !in ownedIds }
        if (!writeEnabled) return ExportPlan(emptyList(), deletes)
        // Two rows with the same natural id (a manual weight saved twice in the same minute) map to the
        // same records: only the oldest one writes them.
        val owned = payloads.filter { it.measurementId in ownedIds }
            .groupBy { it.recordKey }
            .map { (_, sameKey) -> sameKey.minBy { it.measurementId } }
        val writes = owned.mapNotNull { p ->
            val previous = byId[p.measurementId]
            when {
                previous == null -> if (backfill || p.measurementId > autoAfterId) ExportWrite(p, now, emptyList()) else null
                previous.signature == p.signature && previous.recordKey == p.recordKey -> null
                previous.recordKey != p.recordKey && !deletable.containsAll(previous.types) -> null
                else -> {
                    // Same ids: only values that disappeared are stale. New ids (legacy scheme, or the
                    // weight or time changed): every record written before is stale.
                    val stale = if (previous.recordKey == p.recordKey) previous.refs(previous.types - p.types) else previous.refs()
                    ExportWrite(p, maxOf(now, previous.version + 1), stale)
                }
            }
        }
        return ExportPlan(writes, deletes)
    }
}
