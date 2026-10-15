package com.enjoyyourtime.globalfitness.data

import com.enjoyyourtime.globalfitness.domain.WeightMath
import java.util.Locale
import java.util.TreeMap
import kotlin.math.abs

/** Categorical color slots for profiles, assigned in a fixed order. */
object ProfileColors {
    const val SLOTS = 8

    /** Lowest slot not used by an existing profile; cycles only when all 8 are taken. */
    fun nextColorIndex(profiles: List<Profile>): Int {
        val used = profiles.map { it.colorIndex }.toSet()
        return (0 until SLOTS).firstOrNull { it !in used } ?: (profiles.size % SLOTS)
    }
}

/** A measurement to insert; its owner is an existing profile id or a new profile of the plan. */
data class PlannedMeasurement(val measurement: Measurement, val newProfileIndex: Int?)

data class ImportPlan(
    /** Profiles to create (id = 0) in this order. */
    val newProfiles: List<Profile>,
    val measurements: List<PlannedMeasurement>,
    val duplicates: Int,
)

/**
 * Pure planning of a CSV import: resolves profiles by name (creating missing ones when the row
 * carries sex, birth date and height) and drops duplicates (same scale key, or same reading
 * within ±60 s). Uses a hash set and a time-sorted index, so it is O(n log n).
 */
object ImportPlanner {
    const val DUPLICATE_WINDOW_MS = 60_000L

    fun plan(rows: List<CsvImportRow>, profiles: List<Profile>, existing: List<Measurement>, now: Long): ImportPlan {
        val existingByName = HashMap<String, Long>()
        for (p in profiles) existingByName.putIfAbsent(nameKey(p.name), p.id)
        val newIndexByName = HashMap<String, Int>()
        val newProfiles = ArrayList<Profile>()
        val colorPool = profiles.toMutableList()

        val keys = HashSet<String>()
        val byTime = TreeMap<Long, MutableList<Long>>()
        fun index(scaleKey: String?, timestamp: Long, weightKg: Double) {
            if (scaleKey != null) keys += scaleKey
            byTime.getOrPut(timestamp) { ArrayList(1) }.add(WeightMath.grams(weightKg))
        }
        existing.forEach { index(it.scaleKey, it.timestamp, it.weightKg) }

        val planned = ArrayList<PlannedMeasurement>()
        var duplicates = 0
        for (row in rows) {
            val grams = WeightMath.grams(row.weightKg)
            val isDuplicate = (row.scaleKey != null && row.scaleKey in keys) ||
                byTime.subMap(row.timestamp - DUPLICATE_WINDOW_MS, true, row.timestamp + DUPLICATE_WINDOW_MS, true)
                    .values.any { list -> list.any { abs(it - grams) <= WeightMath.SAME_READING_TOLERANCE_G } }
            if (isDuplicate) {
                duplicates++
                continue
            }

            var profileId: Long? = null
            var newIndex: Int? = null
            val name = row.profileName?.trim()?.takeIf { it.isNotEmpty() }
            if (name != null) {
                val key = nameKey(name)
                profileId = existingByName[key]
                if (profileId == null) {
                    newIndex = newIndexByName[key]
                    if (newIndex == null && row.sex != null && row.birthDate != null && row.heightCm != null && row.heightCm > 0) {
                        val profile = Profile(
                            id = 0,
                            name = name,
                            sex = row.sex,
                            birthDate = row.birthDate,
                            heightCm = row.heightCm,
                            targetWeightKg = row.targetWeightKg,
                            colorIndex = ProfileColors.nextColorIndex(colorPool),
                            createdAt = now,
                        )
                        newProfiles += profile
                        colorPool += profile
                        newIndex = newProfiles.lastIndex
                        newIndexByName[key] = newIndex
                    }
                }
            }

            planned += PlannedMeasurement(
                measurement = Measurement(
                    id = 0,
                    profileId = profileId,
                    timestamp = row.timestamp,
                    weightKg = row.weightKg,
                    impedance = row.impedance,
                    scaleKey = row.scaleKey,
                    source = row.source,
                ),
                newProfileIndex = newIndex,
            )
            index(row.scaleKey, row.timestamp, row.weightKg)
        }
        return ImportPlan(newProfiles, planned, duplicates)
    }

    private fun nameKey(name: String): String = name.trim().lowercase(Locale.ROOT)
}
