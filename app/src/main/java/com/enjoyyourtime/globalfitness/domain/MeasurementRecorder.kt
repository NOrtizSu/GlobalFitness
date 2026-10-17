package com.enjoyyourtime.globalfitness.domain

import com.enjoyyourtime.globalfitness.data.Measurement
import com.enjoyyourtime.globalfitness.data.MeasurementSource
import com.enjoyyourtime.globalfitness.data.MeasurementStore
import com.enjoyyourtime.globalfitness.scale.CompositionSource
import com.enjoyyourtime.globalfitness.scale.ScaleReading
import kotlin.math.abs

/** Result of persisting a scale reading. */
sealed interface RecordOutcome {
    val measurement: Measurement

    /** New row (owned by the single profile). */
    data class Saved(override val measurement: Measurement) : RecordOutcome

    /** Impedance and/or composition added to an existing row of the same weighing. */
    data class Upgraded(override val measurement: Measurement) : RecordOutcome

    /** Already stored (e.g. re-broadcast after an app restart, history downloaded again). */
    data class Duplicate(override val measurement: Measurement) : RecordOutcome
}

/**
 * Persists readings from any scale driver exactly once. Dedupe: same dedupe key, or same reading
 * (±25 g) within [DUPLICATE_WINDOW_MS]. A later reading of the same weighing that carries data the
 * stored row lacks (impedance, device composition) upgrades the row instead of inserting.
 */
class MeasurementRecorder(private val repository: MeasurementStore) {

    /** True when the database holds weighings of the Mi Body Composition Scale 2 (pre multi-scale versions). */
    suspend fun hasLegacyMiScaleRows(): Boolean {
        repository.awaitLoaded()
        return repository.measurements.value.any {
            it.source == MeasurementSource.SCALE && (it.deviceDriver == null || it.deviceDriver == LEGACY_MI_SCALE_DRIVER)
        }
    }

    /** Returns null when nothing was stored (a measurement the user deleted, or an upgrade for one). */
    suspend fun record(r: ScaleReading): RecordOutcome? {
        repository.awaitLoaded()
        if (r.dedupeKey != null && repository.isDeletedScaleKey(r.dedupeKey)) return null
        val all = repository.measurements.value
        val existing = r.dedupeKey?.let { key -> all.firstOrNull { it.scaleKey == key } } ?: findNear(all, r)

        if (r.isImpedanceUpgrade && existing == null) {
            // The weight-only row is gone (deleted): do not resurrect it.
            return null
        }

        if (existing != null) {
            val upgraded = upgradeOf(existing, r) ?: return RecordOutcome.Duplicate(existing)
            val stored = repository.updateScaleData(upgraded) ?: return null
            return RecordOutcome.Upgraded(stored)
        }

        val stored = repository.insertMeasurement(
            Measurement(
                id = 0,
                profileId = null,
                timestamp = r.measuredAtMillis,
                weightKg = r.weightKg,
                impedance = r.impedance,
                scaleKey = r.dedupeKey,
                source = MeasurementSource.SCALE,
                composition = r.composition,
                compositionSource = r.compositionSource,
                formulaLib = r.formulaLib,
                deviceDriver = r.driverId,
                deviceAddress = r.deviceAddress,
                impedanceLow = r.impedanceLow,
            ),
        )
        if (stored == null) {
            // Unique scale_key conflict: stored meanwhile.
            val dup = repository.measurements.value.firstOrNull { it.scaleKey == r.dedupeKey } ?: return null
            return RecordOutcome.Duplicate(dup)
        }
        return RecordOutcome.Saved(stored)
    }

    private fun upgradeOf(e: Measurement, r: ScaleReading): Measurement? {
        val addImpedance = e.impedance == null && r.impedance != null
        val addComposition = e.composition == null && r.composition != null
        if (!addImpedance && !addComposition) return null
        val source = when {
            addComposition -> r.compositionSource
            e.compositionSource == null || e.compositionSource == CompositionSource.None -> r.compositionSource
            else -> e.compositionSource
        }
        return e.copy(
            impedance = e.impedance ?: r.impedance,
            impedanceLow = e.impedanceLow ?: r.impedanceLow,
            composition = e.composition ?: r.composition,
            compositionSource = source,
            formulaLib = e.formulaLib ?: r.formulaLib,
            deviceDriver = e.deviceDriver ?: r.driverId,
            deviceAddress = e.deviceAddress ?: r.deviceAddress,
        )
    }

    private fun findNear(all: List<Measurement>, r: ScaleReading): Measurement? =
        all.lastOrNull {
            abs(it.timestamp - r.measuredAtMillis) <= DUPLICATE_WINDOW_MS && WeightMath.sameReading(it.weightKg, r.weightKg)
        }

    companion object {
        const val DUPLICATE_WINDOW_MS = 3 * 60_000L
        private const val LEGACY_MI_SCALE_DRIVER = "xiaomi_mi_scale_2"
    }
}
