package com.enjoyyourtime.globalfitness.domain

import com.enjoyyourtime.globalfitness.data.Measurement
import com.enjoyyourtime.globalfitness.data.Profile
import kotlin.math.abs

/** Decides which profile a new scale measurement belongs to. */
object ProfileMatcher {
    const val CLEAR_WINNER_MARGIN_KG = 1.0

    /**
     * Returns the profile id to auto-assign, or null when the measurement must stay pending.
     *
     * - Exactly one profile: always that profile.
     * - Several profiles: if any profile has no measurement at all, we cannot tell, so pending.
     *   Otherwise the reference weight of each profile is its most recent measurement (of any
     *   age); candidates are within [toleranceKg]. One candidate, or a closest candidate at least
     *   [CLEAR_WINNER_MARGIN_KG] closer than the second, is assigned; anything else is pending.
     */
    fun match(
        weightKg: Double,
        timestamp: Long,
        profiles: List<Profile>,
        measurements: List<Measurement>,
        toleranceKg: Double,
    ): Long? {
        if (profiles.isEmpty()) return null
        if (profiles.size == 1) return profiles.first().id

        val references = profiles.associate { it.id to referenceWeight(it.id, measurements, timestamp) }
        if (references.values.any { it == null }) return null

        val candidates = references.mapNotNull { (id, reference) ->
            val distance = abs(reference!! - weightKg)
            if (distance <= toleranceKg) id to distance else null
        }.sortedBy { it.second }

        return when {
            candidates.isEmpty() -> null
            candidates.size == 1 -> candidates[0].first
            candidates[1].second - candidates[0].second >= CLEAR_WINNER_MARGIN_KG -> candidates[0].first
            else -> null
        }
    }

    /**
     * Most recent weight of the profile at or before [at]; if the profile only has later
     * measurements (imports, manual entries), the earliest of those.
     */
    fun referenceWeight(profileId: Long, measurements: List<Measurement>, at: Long): Double? {
        val own = measurements.filter { it.profileId == profileId }
        return (own.filter { it.timestamp <= at }.maxByOrNull { it.timestamp } ?: own.minByOrNull { it.timestamp })
            ?.weightKg
    }
}
