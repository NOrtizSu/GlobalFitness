package com.enjoyyourtime.globalfitness.scale.libs

import com.enjoyyourtime.globalfitness.scale.ActivityLevel
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
            // Needs all ten impedances: only the values computed at capture time exist.
            FormulaLib.WLA25 -> null
            FormulaLib.S400 -> {
                // Stored rows keep only the high-frequency impedance: single-band fallback.
                val r = S400BodyComposition.compute(S400Inputs(age, isMale, h, w, imp, imp))
                composition(
                    fat = r.bfPct, water = r.tbwPct, musclePct = r.smmPct, muscleKg = r.smmKg, bone = r.boneKg,
                    visceral = r.vfi, bmr = r.bmrKcal, lbm = r.ffmKg, protein = r.proteinPct,
                )
            }
            FormulaLib.STANDARD_IMPEDANCE -> {
                val l = StandardImpedanceLib(isMale, age, weightKg, heightCm / 100.0, impedance.toDouble())
                composition(
                    fat = l.totalFatPercentage.toFloat().coerceIn(0f, 75f),
                    water = l.totalBodyWaterPercentage.toFloat().coerceIn(0f, 80f),
                    musclePct = l.skeletalMusclePercentage.toFloat().coerceIn(0f, 100f),
                    bone = l.boneMassKg.toFloat().coerceIn(0f, 10f),
                    lbm = l.fatFreeMassKg.toFloat().coerceIn(0f, 150f),
                    bmr = l.basalMetabolicRate.toFloat(),
                )
            }
            FormulaLib.YUNMAI -> {
                val l = YunmaiLib(if (isMale) 1 else 0, h, ActivityLevel.SEDENTARY)
                val fat = l.getFat(age, w, impedance)
                if (fat <= 0f) {
                    null
                } else {
                    val muscle = l.getMuscle(fat)
                    composition(
                        fat = fat, water = l.getWater(fat), musclePct = muscle, bone = l.getBoneMass(muscle, w),
                        lbm = l.getLeanBodyMass(w, fat), visceral = l.getVisceralFat(fat, age),
                    )
                }
            }
            FormulaLib.TRISA -> {
                val l = TrisaBodyAnalyzeLib(if (isMale) 1 else 0, age, h)
                composition(fat = l.getFat(w, imp), water = l.getWater(w, imp), musclePct = l.getMuscle(w, imp), bone = l.getBone(w, imp))
            }
            FormulaLib.OKOK_V2 -> {
                if (age <= 5) {
                    null
                } else {
                    val l = OkOkV2Lib(age, if (isMale) 1 else 0, h)
                    composition(
                        fat = l.getBodyFat(w, imp), water = l.getWater(w, imp), musclePct = l.getMuscle(w, imp),
                        bone = l.getBoneMass(w, imp), lbm = l.getLBM(w, imp), visceral = l.getVisceralFat(w, imp),
                        bmr = l.getBMR(w), protein = l.getProtein(w, imp), metabolicAge = l.getBodyAge(w, imp).toFloat(),
                    )
                }
            }
            FormulaLib.WLA07 -> {
                val l = Wla07BodyComposition(w, heightCm.toInt(), impedance.toDouble(), age, isMale)
                val fat = l.bodyFatPercent.toFloat()
                composition(
                    fat = fat, musclePct = l.musclePercent.toFloat(), visceral = l.visceralFat.toFloat(), bone = l.boneMass.toFloat(),
                    water = l.moisturePercent.toFloat(), lbm = w * (100f - fat) / 100f, bmr = l.bmr.toFloat(), protein = l.protein.toFloat(),
                )
            }
            FormulaLib.RENPHO_ES26BB -> {
                val fat = RenphoEs26bbLib.bodyFatPercent(w, h / 100f, age, isMale, impedance)
                composition(
                    fat = fat, water = RenphoEs26bbLib.waterPercent(fat, isMale), musclePct = RenphoEs26bbLib.skeletalMusclePercent(fat, isMale),
                    bone = RenphoEs26bbLib.boneMass(w, fat, isMale), protein = RenphoEs26bbLib.proteinPercent(fat, isMale), lbm = w * (100f - fat) / 100f,
                )
            }
            FormulaLib.ONE_BYONE -> {
                val l = OneByoneLib(if (isMale) 1 else 0, age, h, 0)
                val fat = l.getBodyFat(w, imp)
                composition(
                    fat = fat, water = l.getWater(fat), bone = l.getBoneMass(w, imp), visceral = l.getVisceralFat(w),
                    musclePct = l.getMuscle(w, imp), lbm = l.getLBM(w, fat),
                )
            }
            FormulaLib.ONE_BYONE_NEW -> {
                val l = OneByoneNewLib(if (isMale) 1 else 0, age, h, 0)
                composition(
                    fat = l.getBodyFatPercentage(w, impedance), water = l.getWaterPercentage(w, impedance),
                    bone = l.getBoneMass(w, impedance), visceral = l.getVisceralFat(w),
                    musclePct = l.getSkeletonMusclePercentage(w, impedance), lbm = l.getLBM(w, impedance),
                )
            }
            FormulaLib.AFU_B1 -> {
                val sex = if (isMale) 1 else 0
                val fat = AfuB1Lib.bodyFatPercent(weightKg, heightCm, age, sex, impedance.toDouble())
                val c = fat?.let { AfuB1Lib.bodyComposition(sex, age, h, w, it) }
                if (fat == null || c == null) {
                    null
                } else {
                    composition(
                        fat = fat, water = c.waterPercent.toFloat(), musclePct = c.skeletalMusclePercent.toFloat(), bone = c.boneMassKg,
                        visceral = c.visceralFatIndex.toFloat(), protein = c.proteinPercent.toFloat(), lbm = c.fatFreeMassKg,
                    )
                }
            }
            FormulaLib.ETEKCITY -> {
                if (impedance !in 1..3999) {
                    null
                } else {
                    val l = EtekcityLib(isMale, age, weightKg, heightCm / 100.0, impedance.toDouble())
                    composition(
                        fat = l.bodyFatPercentage.toFloat(), water = l.water.toFloat(), musclePct = l.skeletalMusclePercentage.toFloat(),
                        bone = l.boneMass.toFloat(), bmr = l.basalMetabolicRate.toFloat(), visceral = l.visceralFat.toFloat(),
                        metabolicAge = l.metabolicAge.takeIf { it in 10..99 }?.toFloat(),
                    )
                }
            }
            FormulaLib.KEEP_S3 -> {
                // Stored rows keep only the 100 kHz impedance: single-band fallback.
                KeepS3BodyComposition.calculate(isMale, age, heightCm.toInt(), w, impedance, impedance)?.let { c ->
                    composition(
                        fat = c.bodyFatPercent, water = c.waterPercent, musclePct = c.skeletalMusclePercent, bone = c.boneKg,
                        visceral = c.visceralFatLevel.toFloat(), bmr = c.basalMetabolicRateKcal.toFloat(), lbm = c.fatFreeMassKg,
                        protein = c.proteinPercent, metabolicAge = c.bodyAge.takeIf { it in 10..99 }?.toFloat(),
                    )
                }
            }
            FormulaLib.SOEHNLE -> {
                val l = SoehnleLib(isMale, age, h, SoehnleLib.activity(ActivityLevel.SEDENTARY))
                composition(fat = l.getFat(w, imp), water = l.getWater(w, imp))
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
