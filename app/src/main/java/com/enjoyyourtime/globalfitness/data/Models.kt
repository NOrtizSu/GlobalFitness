package com.enjoyyourtime.globalfitness.data

import com.enjoyyourtime.globalfitness.scale.CompositionSource
import com.enjoyyourtime.globalfitness.scale.DeviceComposition
import com.enjoyyourtime.globalfitness.scale.FormulaLib
import java.time.Instant
import java.time.LocalDate
import java.time.Period
import java.time.ZoneId

enum class Sex(val code: String, val label: String) {
    MALE("M", "Hombre"),
    FEMALE("F", "Mujer");

    companion object {
        fun fromCode(code: String?): Sex = if (code.equals("F", ignoreCase = true)) FEMALE else MALE
    }
}

enum class MeasurementSource(val code: String) {
    SCALE("scale"),
    MANUAL("manual");

    companion object {
        fun fromCode(code: String?): MeasurementSource = if (code == MANUAL.code) MANUAL else SCALE
    }
}

data class Profile(
    val id: Long,
    val name: String,
    val sex: Sex,
    val birthDate: LocalDate,
    val heightCm: Double,
    val targetWeightKg: Double?,
    val colorIndex: Int,
    val createdAt: Long,
) {
    /** Full years at the given instant (local calendar date). */
    fun ageAt(epochMillis: Long, zone: ZoneId = ZoneId.systemDefault()): Int {
        val date = Instant.ofEpochMilli(epochMillis).atZone(zone).toLocalDate()
        return Period.between(birthDate, date).years.coerceAtLeast(0)
    }
}

/** Values entered when creating or editing a profile. */
data class ProfileDraft(
    val name: String,
    val sex: Sex,
    val birthDate: LocalDate,
    val heightCm: Double,
    val targetWeightKg: Double?,
)

data class Measurement(
    val id: Long,
    /** Owner profile. The app has a single profile; null only before it exists (adopted on creation). */
    val profileId: Long?,
    val timestamp: Long,
    val weightKg: Double,
    val impedance: Int?,
    val scaleKey: String?,
    val source: MeasurementSource,
    /** Composition reported (or computed at capture time) by the scale, if any. */
    val composition: DeviceComposition? = null,
    /** Null for rows stored before multi-scale support (Mi Scale 2 / manual). */
    val compositionSource: CompositionSource? = null,
    /** Formula library used to compute composition from [impedance]; null = Mi Fit (legacy rows). */
    val formulaLib: FormulaLib? = null,
    /** Driver id of the scale that produced it (null for manual / legacy rows). */
    val deviceDriver: String? = null,
    val deviceAddress: String? = null,
    /** Low-frequency impedance of dual-frequency scales (S400, Keep S3). */
    val impedanceLow: Int? = null,
)
