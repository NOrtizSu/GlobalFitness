package com.enjoyyourtime.globalfitness.scale

import kotlin.math.roundToLong

/**
 * Body composition values reported (or computed at capture time) by a scale. All optional.
 * [musclePercent]/[muscleKg] are total muscle (Mi Fit "masa muscular"); [skeletalMusclePercent]
 * is skeletal muscle, a different (smaller) quantity reported by many BIA libraries and scales.
 */
data class DeviceComposition(
    val fatPercent: Double? = null,
    val waterPercent: Double? = null,
    val musclePercent: Double? = null,
    val muscleKg: Double? = null,
    val boneKg: Double? = null,
    val visceralFat: Double? = null,
    val bmrKcal: Double? = null,
    val lbmKg: Double? = null,
    val proteinPercent: Double? = null,
    val metabolicAge: Double? = null,
    val skeletalMusclePercent: Double? = null,
) {
    val isEmpty: Boolean
        get() = fatPercent == null && waterPercent == null && musclePercent == null && muscleKg == null &&
            boneKg == null && visceralFat == null && bmrKcal == null && lbmKg == null &&
            proteinPercent == null && metabolicAge == null && skeletalMusclePercent == null

    /** Total muscle in kg for [weightKg], from whichever form this source reported. */
    fun muscleKgFor(weightKg: Double): Double? = muscleKg ?: musclePercent?.let { weightKg * it / 100.0 }

    /**
     * Only physiologically possible values (non-finite, non-positive and out-of-range values
     * dropped one by one), so nothing absurd can be stored or exported.
     */
    fun sanitized(): DeviceComposition = DeviceComposition(
        fatPercent = fatPercent.within(1.0, 80.0),
        waterPercent = waterPercent.within(15.0, 85.0),
        musclePercent = musclePercent.within(10.0, 95.0),
        muscleKg = muscleKg.within(5.0, 200.0),
        boneKg = boneKg.within(0.3, 10.0),
        visceralFat = visceralFat.within(0.5, 60.0),
        bmrKcal = bmrKcal.within(300.0, 6000.0),
        lbmKg = lbmKg.within(5.0, 300.0),
        proteinPercent = proteinPercent.within(2.0, 45.0),
        metabolicAge = metabolicAge.within(5.0, 120.0),
        skeletalMusclePercent = skeletalMusclePercent.within(5.0, 75.0),
    )

    /** Values of this object win; missing ones are taken from [other]. */
    fun mergedWith(other: DeviceComposition?): DeviceComposition {
        if (other == null) return this
        return DeviceComposition(
            fatPercent = fatPercent ?: other.fatPercent,
            waterPercent = waterPercent ?: other.waterPercent,
            musclePercent = musclePercent ?: other.musclePercent,
            muscleKg = muscleKg ?: other.muscleKg,
            boneKg = boneKg ?: other.boneKg,
            visceralFat = visceralFat ?: other.visceralFat,
            bmrKcal = bmrKcal ?: other.bmrKcal,
            lbmKg = lbmKg ?: other.lbmKg,
            proteinPercent = proteinPercent ?: other.proteinPercent,
            metabolicAge = metabolicAge ?: other.metabolicAge,
            skeletalMusclePercent = skeletalMusclePercent ?: other.skeletalMusclePercent,
        )
    }

    companion object {
        /** Sanitized copy ([sanitized]); null when nothing usable is left. */
        fun of(c: DeviceComposition): DeviceComposition? = c.sanitized().takeUnless { it.isEmpty }

        private fun Double?.within(min: Double, max: Double): Double? = this?.takeIf { it.isFinite() && it >= min && it <= max }
    }
}

/**
 * Body-composition formula library used to derive composition from impedance. The id is
 * persisted with each measurement so history is always recomputed with the right formula.
 */
enum class FormulaLib(val id: String, val label: String) {
    MI_SCALE("MI_SCALE", "Xiaomi Mi Fit"),
    S400("S400", "Xiaomi S400"),
    STANDARD_IMPEDANCE("STANDARD_IMPEDANCE", "Impedancia estándar (Sun 2003)"),
    YUNMAI("YUNMAI", "Yunmai"),
    TRISA("TRISA", "Trisa"),
    /** Trisa formulas fed with the impedance derived from the stored raw resistance. */
    TRISA_RESISTANCE("TRISA_R", "Trisa (resistencia)"),
    SOEHNLE("SOEHNLE", "Soehnle"),
    OKOK_V2("OKOK_V2", "OKOK"),
    WLA25("WLA25", "Fitdays WLA25 (10 impedancias)"),
    WLA07("WLA07", "icomon WLA07"),
    RENPHO_ES26BB("RENPHO_ES26BB", "RENPHO ES-26BB"),
    ONE_BYONE("ONE_BYONE", "1byone / Eufy"),
    ONE_BYONE_NEW("ONE_BYONE_NEW", "1byone (nuevo)"),
    AFU_B1("AFU_B1", "AFU-BH-TZ-B1"),
    ETEKCITY("ETEKCITY", "Chipsea / Etekcity"),
    KEEP_S3("KEEP_S3", "Keep S3 (doble frecuencia)"),
    ;

    companion object {
        fun fromId(id: String?): FormulaLib? = id?.let { v -> entries.firstOrNull { it.id == v } }
    }
}

/** Where the composition of a measurement comes from. Persisted as [code]. */
sealed interface CompositionSource {
    val code: String

    /** Values measured/computed by the scale firmware itself. */
    data object Device : CompositionSource {
        override val code = "DEVICE"
    }

    /** Computed from impedance with [lib] (stored values, if any, were computed at capture time). */
    data class Formula(val lib: FormulaLib) : CompositionSource {
        override val code: String get() = "FORMULA_${lib.id}"
    }

    /** Weight only. */
    data object None : CompositionSource {
        override val code = "NONE"
    }

    companion object {
        fun fromCode(code: String?): CompositionSource? = when {
            code == null -> null
            code == Device.code -> Device
            code == None.code -> None
            code.startsWith("FORMULA_") -> FormulaLib.fromId(code.removePrefix("FORMULA_"))?.let { Formula(it) }
            else -> null
        }
    }
}

/** One finished weighing produced by a driver, ready to be persisted. */
data class ScaleReading(
    val weightKg: Double,
    /** Impedance in ohms (the primary / high-frequency one for multi-frequency scales). */
    val impedance: Int? = null,
    val composition: DeviceComposition? = null,
    /** Low-frequency impedance of dual-frequency scales, needed to recompute their composition. */
    val impedanceLow: Int? = null,
    /** Scale time when plausible, otherwise the receive time. Becomes the measurement timestamp. */
    val measuredAtMillis: Long,
    val receivedAtMillis: Long,
    /** Unique key for one physical weighing (null: dedupe by time/weight only). */
    val dedupeKey: String?,
    val compositionSource: CompositionSource,
    /** Library to (re)compute composition from [impedance] when [composition] is incomplete. */
    val formulaLib: FormulaLib?,
    val driverId: String,
    val deviceAddress: String?,
    /** User slot reported by the scale, when the protocol has one. */
    val userSlot: Int? = null,
    /**
     * True when this only adds the impedance/composition to a measurement already committed
     * weight-only under [dedupeKey]; the receiver must update instead of inserting.
     */
    val isImpedanceUpgrade: Boolean = false,
    /** Read back from the scale memory (offline / history) rather than measured just now. */
    val fromHistory: Boolean = false,
) {
    companion object {
        /** Scale clocks are often unset: accept scale time only within this window. */
        private const val PAST_WINDOW_MS = 5L * 365 * 24 * 3_600_000
        private const val FUTURE_WINDOW_MS = 24L * 3_600_000

        /** A live weighing's scale clock may be off by at most this much (else the phone's time is used). */
        const val LIVE_CLOCK_TOLERANCE_MS = 10L * 60_000

        fun plausibleTime(scaleTime: Long?, receivedAt: Long): Long =
            if (scaleTime != null && scaleTime in (receivedAt - PAST_WINDOW_MS)..(receivedAt + FUTURE_WINDOW_MS)) {
                scaleTime
            } else {
                receivedAt
            }

        fun grams(kg: Double): Long = (kg * 1000.0).roundToLong()
    }
}
