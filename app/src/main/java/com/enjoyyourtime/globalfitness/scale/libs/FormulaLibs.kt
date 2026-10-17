package com.enjoyyourtime.globalfitness.scale.libs

import com.enjoyyourtime.globalfitness.scale.ActivityLevel
import com.enjoyyourtime.globalfitness.scale.DeviceComposition
import com.enjoyyourtime.globalfitness.scale.FormulaLib
import kotlin.math.roundToInt

/**
 * Computes body composition from the impedance(s) with the formula library a measurement was
 * taken with, so history is always recomputed consistently. [FormulaLib.MI_SCALE] is handled by
 * the app's own BodyMetrics (the original Mi Fit formulas) and returns null here.
 *
 * Muscle meaning: [DeviceComposition.musclePercent]/[DeviceComposition.muscleKg] are total muscle
 * (Mi Fit "masa muscular"); the libraries that estimate skeletal muscle report it as
 * [DeviceComposition.skeletalMusclePercent].
 */
object FormulaLibs {
    /** Impedances outside this window are electrode/contact errors, not body measurements. */
    val PLAUSIBLE_IMPEDANCE = 150..1500

    /** Libraries whose composition is fully recomputable from the stored impedance and profile. */
    val RECOMPUTABLE = setOf(FormulaLib.OKOK_V2, FormulaLib.TRISA, FormulaLib.TRISA_RESISTANCE, FormulaLib.STANDARD_IMPEDANCE)

    /**
     * [impedanceLow] is the low-frequency impedance of dual-frequency scales (S400 / Keep S3);
     * [deviceFat] is the fat % the scale measured itself, used by libraries that derive the rest
     * from body fat.
     */
    fun compute(
        lib: FormulaLib,
        isMale: Boolean,
        age: Int,
        heightCm: Double,
        weightKg: Double,
        impedance: Int,
        impedanceLow: Int? = null,
        deviceFat: Double? = null,
        activity: ActivityLevel = ActivityLevel.SEDENTARY,
    ): DeviceComposition? {
        // The Trisa library takes the small derived impedance (0.3·(R−400)), not ohms.
        val window = if (lib == FormulaLib.TRISA) 1..PLAUSIBLE_IMPEDANCE.last else PLAUSIBLE_IMPEDANCE
        if (impedance !in window || !weightKg.isFinite() || weightKg <= 0.0 || !heightCm.isFinite() || heightCm <= 0.0) return null
        val h = heightCm.toFloat()
        val w = weightKg.toFloat()
        val imp = impedance.toFloat()
        val fatIn = deviceFat?.takeIf { it.isFinite() && it in 2.0..75.0 }?.toFloat()
        return when (lib) {
            FormulaLib.MI_SCALE -> null
            // Needs all ten impedances: only the values computed at capture time exist.
            FormulaLib.WLA25 -> null
            FormulaLib.S400 -> {
                val low = impedanceLow?.takeIf { it in PLAUSIBLE_IMPEDANCE }
                if (low == null) {
                    // Single band (no low-frequency value stored): the S400 model's contact check
                    // needs both, so use the standard model on the ~50 kHz equivalent.
                    compute(FormulaLib.STANDARD_IMPEDANCE, isMale, age, heightCm, weightKg, (impedance * 1.10).roundToInt())
                } else {
                    val r = S400BodyComposition.compute(S400Inputs(age, isMale, h, w, imp, low.toFloat()))
                    composition(
                        fat = r.bfPct, water = r.tbwPct, skeletalPct = r.smmPct, bone = r.boneKg,
                        visceral = r.vfi, bmr = r.bmrKcal, lbm = r.ffmKg, protein = r.proteinPct,
                    )
                }
            }
            FormulaLib.STANDARD_IMPEDANCE -> {
                val l = StandardImpedanceLib(isMale, age, weightKg, heightCm / 100.0, impedance.toDouble())
                val fat = l.totalFatPercentage
                if (!fat.isFinite() || fat !in 3.0..75.0) {
                    null
                } else {
                    composition(
                        fat = fat.toFloat(),
                        water = l.totalBodyWaterPercentage.toFloat(),
                        skeletalPct = l.skeletalMusclePercentage.toFloat(),
                        bone = l.boneMassKg.toFloat(),
                        lbm = l.fatFreeMassKg.toFloat(),
                        bmr = l.basalMetabolicRate.toFloat(),
                    )
                }
            }
            FormulaLib.YUNMAI -> {
                val l = YunmaiLib(if (isMale) 1 else 0, h, activity)
                val fat = fatIn ?: l.getFat(age, w, impedance)
                if (fat <= 0f) {
                    null
                } else {
                    composition(
                        fat = fat, water = l.getWater(fat), skeletalPct = l.getSkeletalMuscle(fat), bone = l.getBoneMass(l.getMuscle(fat), w),
                        lbm = l.getLeanBodyMass(w, fat), visceral = l.getVisceralFat(fat, age),
                    )
                }
            }
            FormulaLib.TRISA, FormulaLib.TRISA_RESISTANCE -> {
                val l = TrisaBodyAnalyzeLib(if (isMale) 1 else 0, age, h)
                val z = if (lib == FormulaLib.TRISA_RESISTANCE) trisaImpedanceFromResistance(imp) else imp
                composition(fat = l.getFat(w, z), water = l.getWater(w, z), skeletalPct = l.getMuscle(w, z), bone = l.getBone(w, z))
            }
            FormulaLib.OKOK_V2 -> {
                if (age <= 5) {
                    null
                } else {
                    val l = OkOkV2Lib(age, if (isMale) 1 else 0, h)
                    composition(
                        fat = l.getBodyFat(w, imp), water = l.getWater(w, imp), skeletalPct = l.getMuscle(w, imp),
                        bone = l.getBoneMass(w, imp), lbm = l.getLBM(w, imp), visceral = l.getVisceralFat(w, imp),
                        bmr = l.getBMR(w), protein = l.getProtein(w, imp), metabolicAge = l.getBodyAge(w, imp).toFloat(),
                    )
                }
            }
            FormulaLib.WLA07 -> {
                val l = Wla07BodyComposition(w, heightCm.roundToInt(), impedance.toDouble(), age, isMale)
                val fat = l.bodyFatPercent.toFloat()
                composition(
                    fat = fat, musclePct = l.musclePercent.toFloat(), visceral = l.visceralFat.toFloat(), bone = l.boneMass.toFloat(),
                    water = l.moisturePercent.toFloat(), lbm = w * (100f - fat) / 100f, bmr = l.bmr.toFloat(), protein = l.protein.toFloat(),
                )
            }
            FormulaLib.RENPHO_ES26BB -> {
                val fat = fatIn ?: RenphoEs26bbLib.bodyFatPercent(w, h / 100f, age, isMale, impedance)
                composition(
                    fat = fat, water = RenphoEs26bbLib.waterPercent(fat, isMale), skeletalPct = RenphoEs26bbLib.skeletalMusclePercent(fat, isMale),
                    bone = RenphoEs26bbLib.boneMass(w, fat, isMale), protein = RenphoEs26bbLib.proteinPercent(fat, isMale), lbm = w * (100f - fat) / 100f,
                )
            }
            FormulaLib.ONE_BYONE -> {
                val l = OneByoneLib(if (isMale) 1 else 0, age, h, oneByonePeopleType(activity))
                val fat = fatIn ?: l.getBodyFat(w, imp)
                composition(
                    fat = fat, water = l.getWater(fat), bone = l.getBoneMass(w, imp), visceral = l.getVisceralFat(w),
                    skeletalPct = l.getMuscle(w, imp), lbm = l.getLBM(w, fat),
                )
            }
            FormulaLib.ONE_BYONE_NEW -> {
                val l = OneByoneNewLib(if (isMale) 1 else 0, age, h, oneByonePeopleType(activity))
                composition(
                    fat = l.getBodyFatPercentage(w, impedance), water = l.getWaterPercentage(w, impedance),
                    bone = l.getBoneMass(w, impedance), visceral = l.getVisceralFat(w),
                    skeletalPct = l.getSkeletonMusclePercentage(w, impedance), lbm = l.getLBM(w, impedance),
                    protein = l.getProteinPercentage(w, impedance),
                )
            }
            FormulaLib.AFU_B1 -> {
                val sex = if (isMale) 1 else 0
                val fat = fatIn ?: AfuB1Lib.bodyFatPercent(weightKg, heightCm, age, sex, impedance.toDouble())
                val c = fat?.let { AfuB1Lib.bodyComposition(sex, age, h, w, it) }
                if (fat == null || c == null) {
                    null
                } else {
                    composition(
                        fat = fat, water = c.waterPercent.toFloat(), skeletalPct = c.skeletalMusclePercent.toFloat(), bone = c.boneMassKg,
                        visceral = c.visceralFatIndex.toFloat(), protein = c.proteinPercent.toFloat(), lbm = c.fatFreeMassKg,
                    )
                }
            }
            FormulaLib.ETEKCITY -> {
                val l = EtekcityLib(isMale, age, weightKg, heightCm / 100.0, impedance.toDouble())
                composition(
                    fat = l.bodyFatPercentage.toFloat(), water = l.water.toFloat(), skeletalPct = l.skeletalMusclePercentage.toFloat(),
                    bone = l.boneMass.toFloat(), bmr = l.basalMetabolicRate.toFloat(), visceral = l.visceralFat.toFloat(),
                    protein = l.proteinPercentage.toFloat(), metabolicAge = l.metabolicAge.takeIf { it in 10..99 }?.toFloat(),
                )
            }
            FormulaLib.KEEP_S3 -> {
                // 50 kHz is stored as the low impedance; rows without it use the single band.
                val low = impedanceLow?.takeIf { it in 200..1200 } ?: impedance
                KeepS3BodyComposition.calculate(isMale, age, heightCm.roundToInt(), w, low, impedance)?.let { c ->
                    composition(
                        fat = c.bodyFatPercent, water = c.waterPercent, musclePct = c.musclePercent, skeletalPct = c.skeletalMusclePercent,
                        bone = c.boneKg, visceral = c.visceralFatLevel.toFloat(), bmr = c.basalMetabolicRateKcal.toFloat(), lbm = c.fatFreeMassKg,
                        protein = c.proteinPercent, metabolicAge = c.bodyAge.takeIf { it in 10..99 }?.toFloat(),
                    )
                }
            }
            FormulaLib.SOEHNLE -> {
                val l = SoehnleLib(isMale, age, h, SoehnleLib.activity(activity))
                composition(fat = l.getFat(w, imp), water = l.getWater(w, imp))
            }
        }
    }

    /** Library outputs go through the same sanity limits as device values ([DeviceComposition.of]). */
    internal fun composition(
        fat: Float? = null,
        water: Float? = null,
        musclePct: Float? = null,
        muscleKg: Float? = null,
        skeletalPct: Float? = null,
        bone: Float? = null,
        visceral: Float? = null,
        bmr: Float? = null,
        lbm: Float? = null,
        protein: Float? = null,
        metabolicAge: Float? = null,
    ): DeviceComposition? = DeviceComposition.of(
        DeviceComposition(
            fatPercent = fat?.toDouble(),
            waterPercent = water?.toDouble(),
            musclePercent = musclePct?.toDouble(),
            muscleKg = muscleKg?.toDouble(),
            boneKg = bone?.toDouble(),
            visceralFat = visceral?.toDouble(),
            bmrKcal = bmr?.toDouble(),
            lbmKg = lbm?.toDouble(),
            proteinPercent = protein?.toDouble(),
            metabolicAge = metabolicAge?.toDouble(),
            skeletalMusclePercent = skeletalPct?.toDouble(),
        ),
    )
}
