package com.enjoyyourtime.globalfitness.domain

import com.enjoyyourtime.globalfitness.ble.CompletedMeasurement
import com.enjoyyourtime.globalfitness.data.Measurement
import com.enjoyyourtime.globalfitness.data.MeasurementSource
import com.enjoyyourtime.globalfitness.data.Profile
import com.enjoyyourtime.globalfitness.data.Repository
import com.enjoyyourtime.globalfitness.data.SettingsStore
import kotlin.math.abs

/** Result of persisting a scale measurement. */
sealed interface RecordOutcome {
    val measurement: Measurement

    /** New row. [profile] is null when the measurement is pending assignment. */
    data class Saved(override val measurement: Measurement, val profile: Profile?) : RecordOutcome

    /** Impedance added to an existing weight-only row. */
    data class Upgraded(override val measurement: Measurement, val profile: Profile?) : RecordOutcome

    /** Already stored (e.g. re-broadcast after an app restart). */
    data class Duplicate(override val measurement: Measurement) : RecordOutcome
}

/** Persists assembled measurements exactly once and auto-assigns them to a profile. */
class MeasurementRecorder(
    private val repository: Repository,
    private val settings: SettingsStore,
) {
    /** Returns null when nothing was stored (e.g. an upgrade for a row the user discarded). */
    suspend fun record(c: CompletedMeasurement): RecordOutcome? {
        repository.awaitLoaded()
        val all = repository.measurements.value
        val existing = all.firstOrNull { it.scaleKey == c.scaleKey } ?: findNear(all, c)

        if (c.isImpedanceUpgrade && existing == null) {
            // The weight-only row is gone (discarded/deleted): do not resurrect it.
            return null
        }

        if (existing != null) {
            if (existing.impedance == null && c.impedance != null) {
                val updated = repository.setImpedance(existing.id, c.impedance) ?: return null
                return RecordOutcome.Upgraded(updated, profileOf(updated))
            }
            return RecordOutcome.Duplicate(existing)
        }

        val profileId = ProfileMatcher.match(
            weightKg = c.weightKg,
            timestamp = c.receivedAtMillis,
            profiles = repository.profiles.value,
            measurements = all,
            toleranceKg = settings.toleranceKg.value,
        )
        val stored = repository.insertMeasurement(
            Measurement(
                id = 0,
                profileId = profileId,
                timestamp = c.receivedAtMillis,
                weightKg = c.weightKg,
                impedance = c.impedance,
                scaleKey = c.scaleKey,
                source = MeasurementSource.SCALE,
            ),
        )
        if (stored == null) {
            // Unique scale_key conflict: someone stored it meanwhile.
            val dup = repository.measurements.value.firstOrNull { it.scaleKey == c.scaleKey } ?: return null
            return RecordOutcome.Duplicate(dup)
        }
        return RecordOutcome.Saved(stored, profileOf(stored))
    }

    private fun profileOf(m: Measurement): Profile? =
        m.profileId?.let { id -> repository.profiles.value.firstOrNull { it.id == id } }

    private fun findNear(all: List<Measurement>, c: CompletedMeasurement): Measurement? =
        all.lastOrNull {
            abs(it.timestamp - c.receivedAtMillis) <= DUPLICATE_WINDOW_MS && WeightMath.sameReading(it.weightKg, c.weightKg)
        }

    companion object {
        const val DUPLICATE_WINDOW_MS = 3 * 60_000L
    }
}
