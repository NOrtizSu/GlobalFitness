// Formulas ported from openScale (GPL-3.0), https://github.com/oliexdev/openScale
package com.enjoyyourtime.globalfitness.scale.libs

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * icomon "WLA07" body-composition calculator (single impedance), as used by the Active Era
 * BF-06: regression rows over height, weight, age and impedance with the vendor's rounding.
 */
class Wla07BodyComposition(
    private val weightKg: Float,
    private val heightCm: Int,
    private val impedanceOhm: Double,
    private val age: Int,
    private val isMale: Boolean,
) {
    private val bfrRaw = bfrRaw()
    private val muscleRaw = muscleRaw()

    private fun round1(value: Double): Double {
        var f = (value % 1.0) * 10.0
        f = if (f % 1.0 > 0.5) ceil(f) else floor(f)
        return value.toInt() + f / 10.0
    }

    private fun clamp(value: Double, lo: Double, hi: Double): Double = min(max(value, lo), hi)

    private fun regress(row: Int): Double = TABLE[row].let {
        (heightCm * it[0] + weightKg.toDouble() * it[1] + age * it[2] + impedanceOhm * it[3] + it[4]) / 10000.0
    }

    private fun bfrRaw(): Double {
        val raw = regress(if (isMale) 1 else 0) / weightKg * 100.0
        return if (raw <= 45.0) max(raw, 5.0) else 45.0
    }

    val bodyFatPercent: Double get() = round1(bfrRaw)

    private fun muscleRaw(): Double {
        val bfr = clamp(bfrRaw, 5.0, 45.0)
        val muscleRegression = regress(if (isMale) 3 else 2) / 10.0
        val ffmResidual = weightKg * (1.0 - bfr / 100.0) - muscleRegression
        return when {
            ffmResidual >= 4.0 -> muscleRegression + ffmResidual - 4.0
            ffmResidual > 1.0 -> muscleRegression
            else -> muscleRegression + ffmResidual - 1.0
        }
    }

    val musclePercent: Double get() = round1(muscleRaw / weightKg * 100.0)

    val boneMass: Double
        get() {
            val bfr = clamp(bfrRaw, 5.0, 45.0)
            return round1(clamp(weightKg - bfr * weightKg / 100.0 - muscleRaw, 1.0, 4.0))
        }

    val visceralFat: Double
        get() {
            val truncated = (regress(if (isMale) 9 else 8) * 10.0).toInt()
            val base = truncated / 10 * 10
            val rounded = if (truncated % 10 < 6) base else base + 5
            return round1(clamp(rounded / 10.0, 1.0, 59.0))
        }

    val bmr: Int get() = Math.round(clamp(regress(if (isMale) 7 else 6), 400.0, 3500.0)).toInt()

    private fun waterRegression(): Double = regress(if (isMale) 5 else 4) / weightKg

    val moisturePercent: Double
        get() {
            val musclePct = muscleRaw / weightKg * 100.0
            val raw = waterRegression()
            val residual = musclePct - raw
            val corrected = when {
                residual >= 32.0 -> musclePct - 32.0
                residual > 5.0 -> raw
                else -> musclePct - 5.0
            }
            return round1(clamp(corrected, 20.0, 85.0))
        }

    val protein: Double
        get() = round1(clamp(muscleRaw / weightKg * 100.0 - clamp(waterRegression(), 20.0, 85.0), 5.0, 32.0))

    private companion object {
        /** height, weight, age, impedance, constant — rows: bfr F/M, muscle F/M, water F/M, BMR F/M, visceral F/M. */
        val TABLE = arrayOf(
            intArrayOf(-3332, 7509, 196, 72, 227193),
            intArrayOf(-3315, 6216, 183, 85, 225540),
            intArrayOf(31860, 19340, -2060, -1320, -1645560),
            intArrayOf(28670, 38940, -4080, -1235, -1576650),
            intArrayOf(87700, 297300, 12800, -6030, 517500),
            intArrayOf(93900, 375800, -3200, -6925, 97000),
            intArrayOf(75432, 99474, -34382, -3090, -2882821),
            intArrayOf(75037, 131523, -43376, -3486, -3117751),
            intArrayOf(-1651, 2628, 649, 24, 123445),
            intArrayOf(-2675, 4200, 1462, 123, 139871),
        )
    }
}
