// Formulas ported from openScale (GPL-3.0), https://github.com/oliexdev/openScale
// (originally https://github.com/ronnnnnnnnnnnnn/etekcity_esf551_ble)
package com.enjoyyourtime.globalfitness.scale.libs

import kotlin.math.floor

/** Chipsea / Etekcity BIA model used for the Honor CH100S. */
class EtekcityLib(isMale: Boolean, val age: Int, val weightKg: Double, val heightM: Double, val impedance: Double) {
    private val male = isMale
    val bmi: Double = weightKg / (heightM * heightM)

    val bodyFatPercentage: Double by lazy {
        val ageFactor = if (male) 0.103 else 0.097
        val bmiFactor = if (male) 1.524 else 1.545
        val constant = if (male) 22.0 else 12.7
        (floor((ageFactor * age + bmiFactor * bmi - 500.0 / impedance - constant) * 10) / 10.0).coerceIn(5.0, 75.0)
    }

    val fatFreeWeight: Double by lazy { weightKg * (1 - bodyFatPercentage / 100) }

    val visceralFat: Double by lazy {
        val bmiFactor = if (male) 0.8666 else 0.8895
        val bfpFactor = if (male) 0.0082 else 0.0943
        val fatFactor = if (male) 0.026 else -0.0534
        val constant = if (male) 14.2692 else 16.215
        (bmiFactor * bmi + bfpFactor * bodyFatPercentage + fatFactor * (weightKg - fatFreeWeight) - constant).coerceIn(1.0, 30.0)
    }

    val boneMass: Double by lazy { maxOf(1.0, (if (male) 0.05 else 0.06) * fatFreeWeight) }

    val water: Double by lazy {
        val ff2Factor = if (male) 0.76 else 0.73
        (ff2Factor * (fatFreeWeight - boneMass) / weightKg * 100.0).coerceIn(10.0, 80.0)
    }

    val basalMetabolicRate: Double by lazy { (fatFreeWeight * 21.6 + 370).coerceIn(900.0, 2500.0) }

    val skeletalMusclePercentage: Double by lazy {
        val ff2Factor = if (male) 0.68 else 0.62
        ff2Factor * (fatFreeWeight - boneMass) / weightKg * 100.0
    }

    val proteinPercentage: Double by lazy {
        val bfpFactor = if (male) 1.0 else 1.05
        maxOf(5.0, 100 - bfpFactor * bodyFatPercentage - boneMass / weightKg * 100 - water)
    }

    private val weightScore: Int by lazy {
        val heightFactor = if (male) 100 else 137
        val constant = if (male) 80 else 110
        val factor = if (male) 0.7 else 0.45
        val res = factor * (heightFactor * heightM - constant)
        when {
            res <= weightKg -> if (1.3 * res < weightKg) 50 else (100 - 50 * (weightKg - res) / (0.3 * res)).toInt()
            res * 0.7 < weightKg -> (100 - 50 * (res - weightKg) / (0.3 * res)).toInt()
            else -> (0 until 6).firstOrNull { x -> res * x / 10 > weightKg }?.let { it * 10 } ?: 0
        }
    }

    private val fatScore: Int by lazy {
        val constant = if (male) 16 else 26
        when {
            constant < bodyFatPercentage && bodyFatPercentage >= 45 -> 50
            constant < bodyFatPercentage -> (100 - 50 * (bodyFatPercentage - constant) / (45 - constant)).toInt()
            else -> (100 - 50 * (constant - bodyFatPercentage) / (constant - 5)).toInt()
        }
    }

    private val bmiScore: Int by lazy {
        when {
            bmi >= 35 -> 50
            bmi >= 22 -> (100 - 3.85 * (bmi - 22)).toInt()
            bmi >= 15 -> (100 - 3.85 * (22 - bmi)).toInt()
            bmi >= 10 -> 40
            bmi >= 5 -> 30
            else -> 20
        }
    }

    val metabolicAge: Int by lazy {
        val healthScore = (weightScore + fatScore + bmiScore) / 3
        val adjustment = when {
            healthScore < 50 -> 0
            healthScore < 60 -> 1
            healthScore < 65 -> 2
            healthScore < 68 -> 3
            healthScore < 70 -> 4
            healthScore < 73 -> 5
            healthScore < 75 -> 6
            healthScore < 80 -> 7
            healthScore < 85 -> 8
            healthScore < 88 -> 9
            healthScore < 90 -> 10
            healthScore < 93 -> 11
            healthScore < 95 -> 12
            healthScore < 97 -> 13
            healthScore < 98 -> 14
            healthScore < 99 -> 15
            else -> 16
        }
        maxOf(18, age + 8 - adjustment)
    }
}
