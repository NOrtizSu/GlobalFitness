package com.enjoyyourtime.globalfitness.domain

import com.enjoyyourtime.globalfitness.data.Measurement
import com.enjoyyourtime.globalfitness.data.Sex
import com.enjoyyourtime.globalfitness.scale.CompositionSource
import com.enjoyyourtime.globalfitness.scale.DeviceComposition
import com.enjoyyourtime.globalfitness.scale.FormulaLib
import com.enjoyyourtime.globalfitness.scale.libs.FormulaLibs
import kotlin.math.pow

/** All metrics derived from one weighing. Impedance-dependent values are null without impedance. */
data class BodyComposition(
    val weightKg: Double,
    val heightCm: Double,
    val age: Int,
    val sex: Sex,
    val impedance: Int?,
    val bmi: Double,
    val fatPercent: Double?,
    val fatMassKg: Double?,
    val waterPercent: Double?,
    val boneKg: Double?,
    val muscleKg: Double?,
    val visceralFat: Double,
    val bmr: Double,
    val metabolicAge: Double?,
    val proteinPercent: Double?,
    val idealWeightKg: Double,
    val healthyWeightMinKg: Double,
    val healthyWeightMaxKg: Double,
    /**
     * Skeletal muscle % when the scale or its formula library estimates it. A different quantity
     * from [muscleKg] (total muscle, "Masa muscular"): label it "Músculo esquelético".
     */
    val skeletalMusclePercent: Double? = null,
) {
    val hasImpedance: Boolean get() = impedance != null
}

/**
 * Body composition estimates using the reverse-engineered Xiaomi / Mi Fit formulas
 * (same as openScale's MiScaleLib). These are estimates, not medical measurements.
 */
object BodyMetrics {

    fun compute(sex: Sex, heightCm: Double, age: Int, weightKg: Double, impedance: Int?): BodyComposition {
        val h = heightCm
        val w = weightKg
        val female = sex == Sex.FEMALE

        val bmi = bmi(w, h)
        val visceral = visceralFat(sex, h, w, age)
        val bmr = bmr(sex, h, w, age)
        val ideal = idealWeight(sex, h)
        val hm = h / 100.0

        var fat: Double? = null
        var water: Double? = null
        var bone: Double? = null
        var muscle: Double? = null
        var metabolicAge: Double? = null
        var protein: Double? = null

        if (impedance != null) {
            val lbm = lbmCoefficient(h, w, age, impedance)
            val f = fatPercent(female, h, w, age, lbm)
            val wa = waterPercent(f)
            val b = boneMass(female, lbm)
            val m = muscleMass(female, w, f, b)
            fat = f
            water = wa
            bone = b
            muscle = m
            metabolicAge = metabolicAge(female, h, w, age, impedance)
            protein = (m / w * 100.0 - wa).coerceIn(5.0, 32.0)
        }

        return BodyComposition(
            weightKg = w,
            heightCm = h,
            age = age,
            sex = sex,
            impedance = impedance,
            bmi = bmi,
            fatPercent = fat,
            fatMassKg = fat?.let { w * it / 100.0 },
            waterPercent = water,
            boneKg = bone,
            muscleKg = muscle,
            visceralFat = visceral,
            bmr = bmr,
            metabolicAge = metabolicAge,
            proteinPercent = protein,
            idealWeightKg = ideal,
            healthyWeightMinKg = 18.5 * hm * hm,
            healthyWeightMaxKg = 24.9 * hm * hm,
        )
    }

    /**
     * Composition of a stored measurement: values provided by the scale win; missing ones come
     * from the measurement's formula library applied to its impedance (legacy rows without a
     * library are Mi Scale 2 rows: Mi Fit formulas); without impedance only weight-based metrics.
     * The impedance of another scale that declared no library is kept but not interpreted.
     */
    fun forMeasurement(sex: Sex, heightCm: Double, age: Int, m: Measurement): BodyComposition {
        val interpretable = m.formulaLib != null || m.deviceDriver == null
        val source = m.compositionSource
        // Stored values of a library that is fully recomputable from the impedance were computed
        // with the profile of that day: recompute so profile corrections apply to history too.
        val recomputable = source is CompositionSource.Formula && source.lib in FormulaLibs.RECOMPUTABLE && source.lib == m.formulaLib
        return compute(
            sex, heightCm, age, m.weightKg, m.impedance.takeIf { interpretable }, m.formulaLib, m.composition,
            impedanceLow = m.impedanceLow, libWins = recomputable,
        ).copy(impedance = m.impedance)
    }

    /**
     * Combines, value by value: the scale's own values ([device]) win, then the formula library
     * applied to the impedance, then the Mi Fit / weight-only estimates. With [libWins] the
     * library comes first (stored values were computed by the same library).
     */
    fun compute(
        sex: Sex,
        heightCm: Double,
        age: Int,
        weightKg: Double,
        impedance: Int?,
        lib: FormulaLib?,
        device: DeviceComposition?,
        impedanceLow: Int? = null,
        libWins: Boolean = false,
    ): BodyComposition {
        val libComposition = if (impedance != null && lib != null && lib != FormulaLib.MI_SCALE) {
            FormulaLibs.compute(lib, sex == Sex.MALE, age, heightCm, weightKg, impedance, impedanceLow, deviceFat = device?.fatPercent)
        } else {
            null
        }
        val miImpedance = if (lib == null || lib == FormulaLib.MI_SCALE) impedance else null
        val base = compute(sex, heightCm, age, weightKg, miImpedance).copy(impedance = impedance)
        if (device == null && libComposition == null) return base
        return if (libWins) applyOverlay(base, libComposition, device) else applyOverlay(base, device, libComposition)
    }

    private fun applyOverlay(base: BodyComposition, first: DeviceComposition?, second: DeviceComposition?): BodyComposition {
        val w = base.weightKg
        val female = base.sex == Sex.FEMALE
        fun pick(f: (DeviceComposition) -> Double?): Double? = first?.let(f) ?: second?.let(f)
        val fat = pick { it.fatPercent } ?: base.fatPercent
        // Water from the Mi Fit relation when only fat is known (scales that report fat alone).
        val water = pick { it.waterPercent } ?: base.waterPercent ?: fat?.let { waterPercent(it) }
        val bone = pick { it.boneKg } ?: base.boneKg
        // Total muscle, resolved per source so one source's kg never overrides another's %.
        val reportedMuscle = first?.muscleKgFor(w) ?: second?.muscleKgFor(w)
        val muscle = reportedMuscle ?: base.muscleKg ?: if (fat != null && bone != null) muscleMass(female, w, fat, bone) else null
        // Protein only from total muscle (the Mi Fit relation), never from skeletal muscle.
        val protein = pick { it.proteinPercent }
            ?: if (muscle != null && water != null) (muscle / w * 100.0 - water).coerceIn(5.0, 32.0) else base.proteinPercent
        return base.copy(
            fatPercent = fat,
            fatMassKg = fat?.let { w * it / 100.0 },
            waterPercent = water,
            boneKg = bone,
            muscleKg = muscle,
            visceralFat = pick { it.visceralFat } ?: base.visceralFat,
            bmr = pick { it.bmrKcal } ?: base.bmr,
            metabolicAge = pick { it.metabolicAge } ?: base.metabolicAge,
            proteinPercent = protein,
            skeletalMusclePercent = pick { it.skeletalMusclePercent },
        )
    }

    fun bmi(weightKg: Double, heightCm: Double): Double = weightKg / (heightCm / 100.0).pow(2)

    fun idealWeight(sex: Sex, heightCm: Double): Double =
        if (sex == Sex.MALE) (heightCm - 80.0) * 0.7 else (heightCm - 70.0) * 0.6

    fun lbmCoefficient(h: Double, w: Double, age: Int, impedance: Int): Double =
        (h * 9.058 / 100.0) * (h / 100.0) + w * 0.32 + 12.226 - impedance * 0.0068 - age * 0.0542

    private fun fatPercent(female: Boolean, h: Double, w: Double, age: Int, lbm: Double): Double {
        val sub = when {
            female && age <= 49 -> 9.25
            female -> 7.25
            else -> 0.8
        }
        val heightFactor = if (h > 160) 1.03 else 1.0
        val coeff = when {
            !female && w < 61 -> 0.98
            female && w > 60 -> 0.96 * heightFactor
            female && w < 50 -> 1.02 * heightFactor
            else -> 1.0
        }
        var fat = (1.0 - ((lbm - sub) * coeff) / w) * 100.0
        if (fat > 63) fat = 75.0
        return fat.coerceIn(5.0, 75.0)
    }

    private fun waterPercent(fat: Double): Double {
        var wv = (100.0 - fat) * 0.7
        wv *= if (wv <= 50) 1.02 else 0.98
        return wv.coerceIn(35.0, 75.0)
    }

    private fun boneMass(female: Boolean, lbm: Double): Double {
        val base = if (female) 0.245691014 else 0.18016894
        var b = -(base - lbm * 0.05158)
        b += if (b > 2.2) 0.1 else -0.1
        if ((female && b > 5.1) || (!female && b > 5.2)) b = 8.0
        return b.coerceIn(0.5, 8.0)
    }

    private fun muscleMass(female: Boolean, w: Double, fat: Double, bone: Double): Double {
        var m = w - fat / 100.0 * w - bone
        if ((female && m >= 84) || (!female && m >= 93.5)) m = 120.0
        return m.coerceIn(10.0, 120.0)
    }

    fun visceralFat(sex: Sex, h: Double, w: Double, age: Int): Double {
        val vf = if (sex == Sex.FEMALE) {
            if (w > h * 0.5 - 13) {
                val ss = (h * 1.45 + h * 0.1158 * h) - 120
                (w * 500 / ss - 6) + age * 0.07
            } else {
                val s = 0.691 - 0.0024 * h - 0.0024 * h
                -((h * 0.027) - s * w) + age * 0.07 - age
            }
        } else {
            if (h < w * 1.6) {
                val s = -((h * 0.4) - h * (h * 0.0826))
                (w * 305) / (s + 48) - 2.9 + age * 0.15
            } else {
                val s = 0.765 - 0.0015 * h
                -((h * 0.143) - w * s) + age * 0.15 - 5.0
            }
        }
        return vf.coerceIn(1.0, 50.0)
    }

    fun bmr(sex: Sex, h: Double, w: Double, age: Int): Double {
        val v = if (sex == Sex.FEMALE) {
            864.6 + w * 10.2036 - h * 0.39336 - age * 6.204
        } else {
            877.8 + w * 14.916 - h * 0.726 - age * 8.976
        }
        return v.coerceIn(500.0, 10000.0)
    }

    private fun metabolicAge(female: Boolean, h: Double, w: Double, age: Int, impedance: Int): Double {
        val v = if (female) {
            -1.1165 * h + 1.5784 * w + 0.4615 * age + 0.0415 * impedance + 83.2548
        } else {
            -0.7471 * h + 0.9161 * w + 0.4184 * age + 0.0517 * impedance + 54.2267
        }
        return v.coerceIn(15.0, 80.0)
    }
}
