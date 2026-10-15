package com.enjoyyourtime.globalfitness.scale.libs

import com.enjoyyourtime.globalfitness.scale.DeviceComposition
import com.enjoyyourtime.globalfitness.scale.FormulaLib

/**
 * Computes body composition from a single impedance with the formula library a measurement was
 * taken with, so history is always recomputed consistently. [FormulaLib.MI_SCALE] is handled by
 * the app's own BodyMetrics (the original Mi Fit formulas) and returns null here.
 */
object FormulaLibs {
    fun compute(
        lib: FormulaLib,
        isMale: Boolean,
        age: Int,
        heightCm: Double,
        weightKg: Double,
        impedance: Int,
    ): DeviceComposition? {
        val h = heightCm.toFloat()
        val w = weightKg.toFloat()
        val imp = impedance.toFloat()
        return when (lib) {
            FormulaLib.MI_SCALE -> null
            FormulaLib.S400 -> {
                // Stored rows keep only the high-frequency impedance: single-band fallback.
                val r = S400BodyComposition.compute(S400Inputs(age, isMale, h, w, imp, imp))
                composition(
                    fat = r.bfPct, water = r.tbwPct, musclePct = r.smmPct, muscleKg = r.smmKg, bone = r.boneKg,
                    visceral = r.vfi, bmr = r.bmrKcal, lbm = r.ffmKg, protein = r.proteinPct,
                )
            }
        }
    }

    internal fun composition(
        fat: Float? = null,
        water: Float? = null,
        musclePct: Float? = null,
        muscleKg: Float? = null,
        bone: Float? = null,
        visceral: Float? = null,
        bmr: Float? = null,
        lbm: Float? = null,
        protein: Float? = null,
        metabolicAge: Float? = null,
    ): DeviceComposition? {
        fun Float?.ok(): Double? = this?.takeIf { it.isFinite() && it > 0f }?.toDouble()
        return DeviceComposition.of(
            DeviceComposition(
                fatPercent = fat.ok(),
                waterPercent = water.ok(),
                musclePercent = musclePct.ok(),
                muscleKg = muscleKg.ok(),
                boneKg = bone.ok(),
                visceralFat = visceral.ok(),
                bmrKcal = bmr.ok(),
                lbmKg = lbm.ok(),
                proteinPercent = protein.ok(),
                metabolicAge = metabolicAge.ok(),
            ),
        )
    }
}
