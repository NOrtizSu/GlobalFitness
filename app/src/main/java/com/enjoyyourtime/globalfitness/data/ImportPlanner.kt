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

data class ImportPlan(
    /** Profile to create first (only when the app has none yet and the file describes it). */
    val newProfile: Profile?,
    /** Measurements to insert; owned by the existing profile, or by [newProfile] once created. */
    val measurements: List<Measurement>,
    val duplicates: Int,
    /** Rows of other people in an old multi-profile export. */
    val skippedOtherProfiles: Int,
    /** Profile name whose rows were imported (null for files without profile column). */
    val sourceProfileName: String?,
)

/**
 * Pure planning of a CSV import into the app's single profile.
 *
 * Old exports may contain several profiles: only one person's rows are imported — the profile
 * whose name matches the local profile, otherwise the one with most rows. Rows without a profile
 * name are imported only when the file has at most one named profile (in a multi-profile file
 * they were someone's unassigned weighings). Duplicates (same scale key, or same reading within
 * ±60 s) are dropped. Uses a hash set and a time-sorted index, so it is O(n log n).
 */
object ImportPlanner {
    const val DUPLICATE_WINDOW_MS = 60_000L

    fun plan(rows: List<CsvImportRow>, profile: Profile?, existing: List<Measurement>, now: Long): ImportPlan {
        val counts = LinkedHashMap<String, Int>()
        val displayNames = HashMap<String, String>()
        for (row in rows) {
            val name = row.profileName?.trim()?.takeIf { it.isNotEmpty() } ?: continue
            val key = nameKey(name)
            counts[key] = (counts[key] ?: 0) + 1
            displayNames.putIfAbsent(key, name)
        }
        val chosenKey: String? = when {
            counts.isEmpty() -> null
            profile != null && nameKey(profile.name) in counts -> nameKey(profile.name)
            else -> counts.entries.maxByOrNull { it.value }?.key // first max wins (file order)
        }
        val acceptNameless = counts.size <= 1

        val accepted = rows.filter { row ->
            val name = row.profileName?.trim()?.takeIf { it.isNotEmpty() }
            if (name == null) acceptNameless else nameKey(name) == chosenKey
        }
        val skippedOther = rows.size - accepted.size

        val newProfile = if (profile == null) {
            accepted.firstOrNull { r ->
                r.profileName?.isNotBlank() == true && r.sex != null && r.birthDate != null && r.heightCm != null && r.heightCm > 0
            }?.let { r ->
                Profile(
                    id = 0,
                    name = r.profileName!!.trim(),
                    sex = r.sex!!,
                    birthDate = r.birthDate!!,
                    heightCm = r.heightCm!!,
                    targetWeightKg = r.targetWeightKg,
                    colorIndex = 0,
                    createdAt = now,
                )
            }
        } else {
            null
        }

        val keys = HashSet<String>()
        val byTime = TreeMap<Long, MutableList<Long>>()
        fun index(scaleKey: String?, timestamp: Long, weightKg: Double) {
            if (scaleKey != null) keys += scaleKey
            byTime.getOrPut(timestamp) { ArrayList(1) }.add(WeightMath.grams(weightKg))
        }
        existing.forEach { index(it.scaleKey, it.timestamp, it.weightKg) }

        val planned = ArrayList<Measurement>()
        var duplicates = 0
        for (row in accepted) {
            val grams = WeightMath.grams(row.weightKg)
            val isDuplicate = (row.scaleKey != null && row.scaleKey in keys) ||
                byTime.subMap(row.timestamp - DUPLICATE_WINDOW_MS, true, row.timestamp + DUPLICATE_WINDOW_MS, true)
                    .values.any { list -> list.any { abs(it - grams) <= WeightMath.SAME_READING_TOLERANCE_G } }
            if (isDuplicate) {
                duplicates++
                continue
            }
            planned += Measurement(
                id = 0,
                profileId = profile?.id,
                timestamp = row.timestamp,
                weightKg = row.weightKg,
                impedance = row.impedance,
                scaleKey = row.scaleKey,
                source = row.source,
                composition = row.composition,
                compositionSource = row.compositionSource,
                formulaLib = row.formulaLib,
                deviceDriver = row.deviceDriver,
                deviceAddress = row.deviceAddress,
                impedanceLow = row.impedanceLow,
            )
            index(row.scaleKey, row.timestamp, row.weightKg)
        }
        return ImportPlan(
            newProfile = newProfile,
            measurements = planned,
            duplicates = duplicates,
            skippedOtherProfiles = skippedOther,
            sourceProfileName = chosenKey?.let { displayNames[it] },
        )
    }

    private fun nameKey(name: String): String = name.trim().lowercase(Locale.ROOT)
}
