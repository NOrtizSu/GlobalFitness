// Formulas ported from openScale (GPL-3.0), https://github.com/oliexdev/openScale
package com.enjoyyourtime.globalfitness.scale.libs

import com.enjoyyourtime.globalfitness.scale.ActivityLevel
import kotlin.math.sqrt

/**
 * Science-based estimates from a single impedance (Sun 2003 FFM/TBW, Janssen SMM, Katch-McArdle
 * BMR), used by scales whose firmware does not compute composition.
 */
class StandardImpedanceLib(
    val isMale: Boolean,
    val age: Int,
    val weightKg: Double,
    val heightM: Double,
    val impedance: Double,
) {
    private val heightCm = heightM * 100.0
    private val h2r = heightCm * heightCm / impedance
    private val genderInt = if (isMale) 1 else 0

    val fatFreeMassKg: Double =
        if (isMale) -10.68 + 0.65 * h2r + 0.26 * weightKg + 0.02 * impedance else -9.53 + 0.69 * h2r + 0.17 * weightKg + 0.02 * impedance

    val totalFatPercentage: Double = (1.0 - fatFreeMassKg / weightKg) * 100.0

    val totalBodyWaterKg: Double = 0.99513 * if (isMale) 1.2 + 0.45 * h2r + 0.18 * weightKg else 3.75 + 0.45 * h2r + 0.11 * weightKg

    val totalBodyWaterPercentage: Double = totalBodyWaterKg / weightKg * 100.0

    val basalMetabolicRate: Double = fatFreeMassKg * 21.6 + 370

    val skeletalMuscleMassKg: Double = 0.401 * h2r + 3.825 * genderInt - 0.071 * age + 5.102

    val skeletalMusclePercentage: Double = skeletalMuscleMassKg / weightKg * 100.0

    val boneMassKg: Double = (if (isMale) 0.057 else 0.05) * fatFreeMassKg
}

/** Yunmai formulas (fat from resistance for firmware older than protocol 0x1E). */
class YunmaiLib(private val sex: Int, private val height: Float, activityLevel: ActivityLevel) {
    private val fitnessBodyType: Boolean = toYunmaiActivityLevel(activityLevel) == 1

    fun getWater(bodyFat: Float): Float = ((100.0f - bodyFat) * 0.726f * 100.0f + 0.5f) / 100.0f

    fun getFat(age: Int, weight: Float, resistance: Int): Float {
        var r = (resistance - 100.0f) / 100.0f
        val h = height / 100.0f
        if (r >= 1) r = sqrt(r.toDouble()).toFloat()
        var fat = weight * 1.5f / h / h + age * 0.08f
        if (sex == 1) fat -= 10.8f
        fat = fat - 7.4f + r
        if (fat < 5.0f || fat > 75.0f) fat = 0.0f
        return fat
    }

    fun getMuscle(bodyFat: Float): Float {
        val muscle = (100.0f - bodyFat) * if (fitnessBodyType) 0.7f else 0.67f
        return (muscle * 100.0f + 0.5f) / 100.0f
    }

    fun getSkeletalMuscle(bodyFat: Float): Float {
        val muscle = (100.0f - bodyFat) * if (fitnessBodyType) 0.6f else 0.53f
        return (muscle * 100.0f + 0.5f) / 100.0f
    }

    fun getBoneMass(muscle: Float, weight: Float): Float {
        val h = height - 170.0f
        val boneMass = if (sex == 1) {
            weight * (muscle / 100.0f) * 4.0f / 7.0f * 0.22f * 0.6f + h / 100.0f
        } else {
            weight * (muscle / 100.0f) * 4.0f / 7.0f * 0.34f * 0.45f + h / 100.0f
        }
        return (boneMass * 10.0f + 0.5f) / 10.0f
    }

    fun getLeanBodyMass(weight: Float, bodyFat: Float): Float = weight * (100.0f - bodyFat) / 100.0f

    fun getVisceralFat(bodyFat: Float, age: Int): Float {
        var f = bodyFat
        val a = if (age < 18 || age > 120) 18 else age
        if (!fitnessBodyType) {
            f -= if (sex == 1) {
                when {
                    a < 40 -> 21.0f
                    a < 60 -> 22.0f
                    else -> 24.0f
                }
            } else {
                when {
                    a < 40 -> 34.0f
                    a < 60 -> 35.0f
                    else -> 36.0f
                }
            }
            var d = if (sex == 1) 1.4f else 1.8f
            if (f > 0.0f) d = 1.1f
            return (f / d + 9.5f).coerceIn(1.0f, 30.0f)
        }
        val vf = if (bodyFat > 15.0f) (bodyFat - 15.0f) / 1.1f + 12.0f else -1 * (15.0f - bodyFat) / 1.4f + 12.0f
        return vf.coerceIn(1.0f, 9.0f)
    }

    companion object {
        fun toYunmaiActivityLevel(activityLevel: ActivityLevel): Int =
            if (activityLevel == ActivityLevel.HEAVY || activityLevel == ActivityLevel.EXTREME) 1 else 0
    }
}

/** Trisa Body Analyze formulas (also used by QN and ES-CS20M scales). */
class TrisaBodyAnalyzeLib(sex: Int, private val ageYears: Int, private val heightCm: Float) {
    private val isMale = sex == 1

    fun getBMI(weightKg: Float): Float = weightKg * 1e4f / (heightCm * heightCm)

    fun getWater(weightKg: Float, impedance: Float): Float {
        val bmi = getBMI(weightKg)
        return if (isMale) {
            87.51f + (-1.162f * bmi - 0.00813f * impedance + 0.07594f * ageYears)
        } else {
            77.721f + (-1.148f * bmi - 0.00573f * impedance + 0.06448f * ageYears)
        }
    }

    fun getFat(weightKg: Float, impedance: Float): Float {
        val bmi = getBMI(weightKg)
        return if (isMale) {
            bmi * (1.479f + 4.4e-4f * impedance) + 0.1f * ageYears - 21.764f
        } else {
            bmi * (1.506f + 3.908e-4f * impedance) + 0.1f * ageYears - 12.834f
        }
    }

    fun getMuscle(weightKg: Float, impedance: Float): Float {
        val bmi = getBMI(weightKg)
        return if (isMale) {
            74.627f + (-0.811f * bmi - 0.00565f * impedance - 0.367f * ageYears)
        } else {
            57.0f + (-0.694f * bmi - 0.00344f * impedance - 0.255f * ageYears)
        }
    }

    fun getBone(weightKg: Float, impedance: Float): Float {
        val bmi = getBMI(weightKg)
        return if (isMale) {
            7.829f + (-0.0855f * bmi - 5.92e-4f * impedance - 0.0389f * ageYears)
        } else {
            7.98f + (-0.0973f * bmi - 4.84e-4f * impedance - 0.036f * ageYears)
        }
    }
}

/** Soehnle formulas: impedance at 50 kHz (and 5 kHz for muscle); activity level 0..5. */
class SoehnleLib(private val isMale: Boolean, private val age: Int, private val height: Float, private val activityLevel: Int) {
    fun getFat(weight: Float, imp50: Float): Float {
        val activityCorr = when (activityLevel) {
            4 -> if (isMale) 2.5f else 2.3f
            5 -> if (isMale) 4.3f else 4.1f
            else -> 0.0f
        }
        val sexCorr = if (isMale) 0.250f else 0.214f
        val activitySexDiv = if (isMale) 65.5f else 55.1f
        return 1.847f * weight * 10000.0f / (height * height) + sexCorr * age + 0.062f * imp50 - (activitySexDiv - activityCorr)
    }

    fun getWater(weight: Float, imp50: Float): Float {
        val activityCorr = when (activityLevel) {
            1, 2, 3 -> if (isMale) 2.83f else 0.0f
            4 -> if (isMale) 3.93f else 0.4f
            5 -> if (isMale) 5.33f else 1.4f
            else -> 0.0f
        }
        return (0.3674f * height * height / imp50 + 0.17530f * weight - 0.11f * age + (6.53f + activityCorr)) / weight * 100.0f
    }

    fun getMuscle(weight: Float, imp50: Float, imp5: Float): Float {
        val activityCorr = when (activityLevel) {
            1, 2, 3 -> if (isMale) 3.6224f else 0.0f
            4 -> if (isMale) 4.3904f else 0.0f
            5 -> if (isMale) 5.4144f else 1.664f
            else -> 0.0f
        }
        return ((0.47027f / imp50 - 0.24196f / imp5) * height * height + 0.13796f * weight - 0.1152f * age + (5.12f + activityCorr)) / weight * 100.0f
    }

    companion object {
        /** openScale's mapping of the activity level to Soehnle's scale. */
        fun activity(level: ActivityLevel): Int = when (level) {
            ActivityLevel.SEDENTARY -> 0
            ActivityLevel.MILD -> 1
            ActivityLevel.MODERATE -> 2
            ActivityLevel.HEAVY -> 4
            ActivityLevel.EXTREME -> 5
        }
    }
}

/**
 * OKOK "V2" vendor formulas. The vendor clamps are written `min(lo, max(x, hi))` (arguments in
 * the vendor's swapped order); they are applied here as the intended `lo..hi` range — taken
 * literally with Kotlin's min/max they always returned the bound.
 */
class OkOkV2Lib(private val age: Int, private val sex: Int, private val height: Float) {
    private val male get() = sex == 1

    fun getLBM(weight: Float, impedance: Float) = weight - getFatMass(weight, impedance)

    fun getMuscle(weight: Float, impedance: Float): Float =
        ((height * 0.2573f) + (weight * 0.1745f) - (age * 0.0161f) + (sex * 2.4269f) - (impedance * 0.017f) - 20.2165f)
            .coerceIn(20f, 70f) * 100f / weight

    private fun getMuscleMass(weight: Float, impedance: Float): Float {
        val bodyFat = getBodyFat(weight, impedance)
        if (bodyFat > 45f) return weight - weight * 0.05f - 4f
        if (bodyFat < 5f) return weight - weight * 0.05f - 1f
        val raw = if (male) {
            (height * 0.2867f) + (weight * 0.3894f) - (age * 0.0408f) - (impedance * 0.01235f) - 15.7665f
        } else {
            (height * 0.3186f) + (weight * 0.1934f) - (age * 0.0206f) - (impedance * 0.0132f) - 16.4556f
        }
        return raw.coerceIn(20f, 70f)
    }

    fun getWater(weight: Float, impedance: Float): Float {
        if (age <= 17) return 0f
        val kg = if (male) {
            (height * 0.0939f) + (weight * 0.3758f) - (age * 0.0032f) - (impedance * 6.925E-3f) + 0.097f
        } else {
            (height * 0.0877f) + (weight * 0.2973f) - (age * 0.0128f) - (impedance * 0.00603f) + 0.5175f
        }
        return (kg / weight * 100f).coerceIn(20f, 85f)
    }

    private fun getWaterMass(weight: Float, impedance: Float): Float = if (age <= 17) 0f else getWater(weight, impedance) / 100f * weight

    fun getBoneMass(weight: Float, impedance: Float): Float =
        (weight - getFatMass(weight, impedance) - getMuscleMass(weight, impedance)).coerceIn(1f, 4f)

    fun getVisceralFat(weight: Float, impedance: Float): Float {
        if (age <= 17) return 0f
        val f = if (male) {
            (height * -0.2675f) + (weight * 0.42f) + (age * 0.1462f) + (impedance * 0.0123f) + 13.9871f
        } else {
            (height * -0.1651f) + (weight * 0.2628f) + (age * 0.0649f) + (impedance * 0.0024f) + 12.3445f
        }
        return f.coerceIn(1f, 60f)
    }

    private fun getFatMass(weight: Float, impedance: Float): Float =
        if (male) {
            (height * -0.3315f) + (weight * 0.6216f) + (age * 0.0183f) + (impedance * 0.0085f) + 22.554f
        } else {
            (height * -0.3332f) + (weight * 0.7509f) + (age * 0.0196f) + (impedance * 0.0072f) + 22.7193f
        }

    fun getBodyFat(weight: Float, impedance: Float): Float = (getFatMass(weight, impedance) / weight * 100f).coerceIn(5f, 45f)

    fun getBMR(weight: Float): Float =
        if (male) 66f + (weight * 13.7f) + (height * 5f) - (age * 6.8f) else 655f + (weight * 9.6f) + (height * 1.8f) - (age * 4.7f)

    fun getProtein(weight: Float, impedance: Float): Float =
        if (age <= 17) 0f else (getMuscleMass(weight, impedance) - getWaterMass(weight, impedance)) / weight * 100f

    fun getBodyAge(weight: Float, impedance: Float): Int {
        if (age <= 17) return 0
        val bodyAge = if (male) {
            (height * -0.7471f) + (weight * 0.9161f) + (age * 0.4184f) + (impedance * 0.0517f) + 54.2267f
        } else {
            (height * -1.1165f) + (weight * 1.5784f) + (age * 0.4615f) + (impedance * 0.0415f) + 83.2548f
        }
        return bodyAge.toInt().coerceIn(18, 80)
    }
}
