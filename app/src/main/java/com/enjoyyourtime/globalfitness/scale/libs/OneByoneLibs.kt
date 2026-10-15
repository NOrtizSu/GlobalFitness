// Formulas ported from openScale (GPL-3.0), https://github.com/oliexdev/openScale
package com.enjoyyourtime.globalfitness.scale.libs

/** 1byone / Eufy (classic) formulas. sex: 1 male, 0 female; peopleType: 0 low, 1 medium, 2 high activity. */
class OneByoneLib(private val sex: Int, private val age: Int, private val height: Float, private val peopleType: Int) {
    fun getLBM(weight: Float, bodyFat: Float): Float = weight - bodyFat / 100.0f * weight

    fun getMuscle(weight: Float, impedance: Float): Float =
        ((height * height / impedance * 0.401) + (sex * 3.825) - (age * 0.071) + 5.102).toFloat() / weight * 100.0f

    fun getWater(bodyFat: Float): Float {
        val water = (100.0f - bodyFat) * 0.7f
        return (if (water < 50) 1.02f else 0.98f) * water
    }

    fun getBoneMass(weight: Float, impedance: Float): Float {
        val peopleCoeff = when (peopleType) {
            0 -> 1.0f
            1 -> 1.0427f
            2 -> 1.0958f
            else -> 0.0f
        }
        var bone = (9.058f * (height / 100.0f) * (height / 100.0f) + 12.226f + 0.32f * weight) - 0.0068f * impedance
        bone = bone - (if (sex == 1) 3.49305f else 4.76325f) - (age * 0.0542f) * peopleCoeff
        bone = if (bone <= 2.2f) bone - 0.1f else bone + 0.1f
        bone *= 0.05158f
        return bone.coerceIn(0.5f, 8.0f)
    }

    fun getVisceralFat(weight: Float): Float {
        val vf = if (sex == 1) {
            if (height < 1.6f * weight + 63.0f) {
                (weight * 305.0f / (0.0826f * height * height - 0.4f * height + 48.0f) - 2.9f) + age * 0.15f
            } else {
                (age * 0.15f + (weight * (-0.0015f * height + 0.765f) - height * 0.143f)) - 5.0f
            }
        } else {
            if (0.5f * height - 13.0f > weight) {
                (age * 0.07f + (weight * (-0.0024f * height + 0.691f) - height * 0.027f)) - 10.5f
            } else {
                weight * 500.0f / ((1.45f * height + 0.1158f * height * height) - 120.0f) - 6.0f + age * 0.07f
            }
        }
        return if (peopleType == 0) vf else subVisceralA(vf)
    }

    private fun subVisceralA(vf: Float): Float = if (10.0f <= vf) subVisceralB(vf) else vf - 4.0f

    private fun subVisceralB(vf: Float): Float = when {
        vf < 10.0f -> vf * 0.85f
        20.0f < vf -> vf * 0.85f
        else -> vf * 0.8f
    }

    fun getBodyFat(weight: Float, impedance: Float): Float {
        var fatConst = when {
            impedance >= 1200.0f -> 8.16f
            impedance >= 200.0f -> 0.0068f * impedance
            impedance >= 50.0f -> 1.36f
            else -> 0f
        }
        val peopleCoeff = when (peopleType) {
            0 -> 1.0f
            1 -> 1.0427f
            else -> 1.0958f
        }
        var v = 9.058f * height / 100.0f
        v *= height
        v = v / 100.0f + 12.226f
        v += 0.32f * weight
        v -= fatConst
        fatConst = if (age > 0x31) {
            if (sex == 1) 0.8f else 7.25f
        } else {
            if (sex == 1) 0.8f else 9.25f
        }
        v -= fatConst
        v -= age * 0.0542f
        v *= peopleCoeff
        if (sex != 0) {
            if (61.0f > weight) v *= 0.98f
        } else {
            if (50.0f > weight) v *= 1.02f
            if (weight > 60.0f) v *= 0.96f
            if (height > 160.0f) v *= 1.03f
        }
        v /= weight
        return (100.0f * (1.0f - v)).coerceIn(1.0f, 45.0f)
    }
}

/** 1byone "new" scale formulas (impedance in ohms). */
class OneByoneNewLib(private val sex: Int, private val age: Int, private val height: Float, @Suppress("unused") private val peopleType: Int) {
    fun getLBM(weight: Float, impedance: Int): Float {
        var c = height / 100 * height / 100 * 9.058f
        c += 12.226f
        c += (weight * 0.32).toFloat()
        c -= (impedance * 0.0068).toFloat()
        c -= (age * 0.0542).toFloat()
        return c
    }

    fun getBodyFatPercentage(weight: Float, impedance: Int): Float {
        var fat = getLBM(weight, impedance)
        fat -= if (sex == 0) (if (age < 0x32) 9.25f else 7.25f) else 0.8f
        if (sex == 0) {
            if (weight < 50) fat *= 1.02f else if (weight > 60) fat *= 0.96f
            if (height > 160) fat *= 1.03f
        } else if (weight < 61) {
            fat *= 0.98f
        }
        return 100 * (1 - fat / weight)
    }

    fun getBoneMass(weight: Float, impedance: Int): Float {
        val c = getLBM(weight, impedance) * 0.05158f - if (sex == 1) 0.18016894f else 0.245691014f
        val bone = if (c <= 2.2) c - 0.1f else c + 0.1f
        return bone.coerceIn(0.5f, 8f)
    }

    fun getSkeletonMusclePercentage(weight: Float, impedance: Int): Float {
        var smm = getWaterPercentage(weight, impedance)
        smm *= weight
        smm *= 0.8422f * 0.01f
        smm -= 2.9903f
        smm /= weight
        return smm * 100
    }

    fun getVisceralFat(weight: Float): Float {
        val vf = if (sex == 1) {
            if (height < weight * 1.6 + 63.0) {
                age * 0.15f + (weight * 305.0f / ((height * 0.0826f * height - height * 0.4f) + 48.0f) - 2.9f)
            } else {
                age * 0.15f + (weight * (height * -0.0015f + 0.765f) - height * 0.143f) - 5.0f
            }
        } else {
            if (weight <= height * 0.5 - 13.0) {
                age * 0.07f + (weight * (height * -0.0024f + 0.691f) - height * 0.027f) - 10.5f
            } else {
                age * 0.07f + (weight * 500.0f / ((height * 1.45f + height * 0.1158f * height) - 120.0f) - 6.0f)
            }
        }
        return vf.coerceIn(1f, 50f)
    }

    fun getWaterPercentage(weight: Float, impedance: Int): Float {
        var w = (100 - getBodyFatPercentage(weight, impedance)) * 0.7f
        w *= if (w > 50) 0.98f else 1.02f
        return w.coerceIn(35f, 75f)
    }
}
