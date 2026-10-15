package com.enjoyyourtime.globalfitness.health

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
 * `clientRecordId` scheme: `gf-<measurementId>-<type>`. Health Connect upserts records with the
 * same client id when the `clientRecordVersion` is not lower, so re-writes are idempotent.
 */
object ClientRecordIds {
    private const val PREFIX = "gf-"

    fun of(measurementId: Long, type: ExportRecordType): String = "$PREFIX$measurementId-${type.code}"

    fun parse(id: String): Pair<Long, ExportRecordType>? {
        if (!id.startsWith(PREFIX)) return null
        val rest = id.removePrefix(PREFIX)
        val dash = rest.indexOf('-')
        if (dash <= 0) return null
        val measurementId = rest.substring(0, dash).toLongOrNull() ?: return null
        val type = ExportRecordType.fromCode(rest.substring(dash + 1)) ?: return null
        return measurementId to type
    }
}

/** Values of one measurement to write. Null values are not written. */
data class ExportPayload(
    val measurementId: Long,
    val timestamp: Long,
    val fromScale: Boolean,
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
         */
        fun from(m: Measurement, profile: Profile, allowed: Set<ExportRecordType>): ExportPayload? {
            if (ExportRecordType.WEIGHT !in allowed) return null
            val c = BodyMetrics.compute(profile.sex, profile.heightCm, profile.ageAt(m.timestamp), m.weightKg, m.impedance)
            fun <T> keep(type: ExportRecordType, value: T?): T? = if (type in allowed) value else null
            return ExportPayload(
                measurementId = m.id,
                timestamp = m.timestamp,
                fromScale = m.source == MeasurementSource.SCALE,
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
)

/** A (re-)write: [staleTypes] were written before but are no longer part of the payload. */
data class ExportWrite(val payload: ExportPayload, val version: Long, val staleTypes: Set<ExportRecordType>)

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
     */
    fun plan(
        ownedIds: Set<Long>,
        payloads: List<ExportPayload>,
        ledger: List<LedgerEntry>,
        autoAfterId: Long,
        backfill: Boolean,
        writeEnabled: Boolean,
    ): ExportPlan {
        val byId = ledger.associateBy { it.measurementId }
        val deletes = ledger.filter { it.measurementId !in ownedIds }
        if (!writeEnabled) return ExportPlan(emptyList(), deletes)
        val writes = payloads.filter { it.measurementId in ownedIds }.mapNotNull { p ->
            val previous = byId[p.measurementId]
            when {
                previous == null -> if (backfill || p.measurementId > autoAfterId) ExportWrite(p, 1, emptySet()) else null
                previous.signature == p.signature -> null
                else -> ExportWrite(p, previous.version + 1, previous.types - p.types)
            }
        }
        return ExportPlan(writes, deletes)
    }
}
