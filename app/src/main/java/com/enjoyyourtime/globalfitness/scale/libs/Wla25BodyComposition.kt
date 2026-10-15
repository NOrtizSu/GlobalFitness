// Formulas ported from openScale (GPL-3.0), https://github.com/oliexdev/openScale
package com.enjoyyourtime.globalfitness.scale.libs

/**
 * Fitdays / icomon "WLA25" algorithm (10 impedances, two groups of five): fat mass from a
 * 13-term regression over height, weight, rounded BMI and all impedances; everything else from
 * fat-free mass. Weight, BMI and fat mass are rounded at the same points as the vendor library.
 */
object Wla25BodyComposition {
    private const val BFR_MIN = 3.0
    private const val BFR_MAX = 60.0
    const val SEX_MALE = 1
    const val SEX_FEMALE = 2

    data class Result(
        val weightKg: Float,
        val bmi: Float,
        val fat: Float,
        val water: Float,
        val musclePercent: Float,
        val muscleKg: Float,
        val boneKg: Float,
        val subcutaneousFat: Float,
        val visceralFat: Int,
        val protein: Float,
        val skeletalMuscle: Float,
        val bmrKcal: Int,
        val lbmKg: Float,
    )

    /** Half-up one-decimal rounding computed in float32, as the vendor library does. */
    fun round1(value: Double): Double {
        val v = value.toFloat()
        val whole = v.toInt()
        val tenths = (v % 1.0f) * 10.0f
        val carried = if (tenths % 1.0f > 0.5f) tenths + 1.0f else tenths
        return (carried.toInt() / 10.0f + whole).toDouble()
    }

    fun bmi(heightCm: Int, weightKg: Double): Double = weightKg * 10000.0 / (heightCm * heightCm)

    /** Slots 0 and 5 are the small leading values (≥ 1 Ω); the other eight must be ≥ 100 Ω. */
    fun impedancesValid(imps: DoubleArray): Boolean {
        if (imps.size != 10) return false
        if (imps[0] < 1.0 || imps[5] < 1.0) return false
        return intArrayOf(1, 2, 3, 4, 6, 7, 8, 9).all { imps[it] >= 100.0 }
    }

    private fun fatMass(heightCm: Int, weightKg: Double, imps: DoubleArray): Double {
        val scaled0 = imps[0] * 0.826
        val scaled5 = if (imps[5] <= imps[0]) imps[5] * 0.826 else scaled0 - 3.0
        return weightKg * -0.138 + heightCm * 0.164 + round1(bmi(heightCm, weightKg)) * 2.657 +
            imps[2] * -0.053 + imps[1] * -0.000491 + scaled0 * -0.03 + imps[4] * -0.127 + imps[3] * -0.052 +
            imps[7] * 0.07 + imps[6] * 0.019 + scaled5 * 0.439 + imps[9] * 0.153 + imps[8] * 0.07 - 88.052
    }

    fun compute(heightCm: Int, rawWeightKg: Double, imps: DoubleArray): Result? {
        if (!impedancesValid(imps) || heightCm <= 0) return null
        val weight = round1(rawWeightKg)
        val percent = (fatMass(heightCm, weight, imps) / weight * 100.0).coerceIn(BFR_MIN, BFR_MAX)
        val roundedFat = round1(percent / 100.0 * weight)
        val ffm = weight - roundedFat
        val waterMass = ffm * 0.733
        val musclePercent = round1((ffm * 0.733 + ffm * 0.2) / weight * 100.0)
        val bfr = round1(percent)
        val visceral = (ffm * -0.029 + roundedFat * 0.502 - 0.477).toInt().coerceIn(1, 20)
        return Result(
            weightKg = weight.toFloat(),
            bmi = round1(bmi(heightCm, weight)).toFloat(),
            fat = bfr.toFloat(),
            water = round1(waterMass / weight * 100.0).toFloat(),
            musclePercent = musclePercent.toFloat(),
            muscleKg = round1(musclePercent / 100.0 * weight).toFloat(),
            boneKg = round1(ffm * 0.067).toFloat(),
            subcutaneousFat = round1((bfr * -0.0002 + 0.72) * bfr).toFloat(),
            visceralFat = visceral,
            protein = round1(ffm * 0.2 / weight * 100.0).toFloat(),
            skeletalMuscle = round1((waterMass * 0.834 - 2.627) / weight * 100.0).toFloat(),
            bmrKcal = (ffm * 21.6 + 370.0).toInt(),
            lbmKg = ffm.toFloat(),
        )
    }

    /** Metabolic age: the user's age nudged by a per-sex body-fat band. */
    fun bodyAge(age: Int, fatPercent: Double, sex: Int): Int {
        if (age < 10) return age
        val bands = if (sex == SEX_MALE) {
            arrayOf(14.0 to -3, 19.0 to -2, 24.0 to -1, 27.0 to 1, 30.0 to 2, 33.0 to 3, 36.0 to 4)
        } else {
            arrayOf(24.0 to -3, 28.0 to -2, 32.0 to -1, 35.0 to 1, 38.0 to 2, 42.0 to 3, 45.0 to 4, 46.0 to 0)
        }
        for ((upper, delta) in bands) if (fatPercent < upper) return age + delta
        return age + 5
    }
}
