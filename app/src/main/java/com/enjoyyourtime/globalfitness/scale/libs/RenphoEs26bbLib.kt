// Formulas ported from openScale (GPL-3.0), https://github.com/oliexdev/openScale
package com.enjoyyourtime.globalfitness.scale.libs

/** RENPHO ES-26BB / Elis Solar composition from the scale's resistance (vendor formulas). */
object RenphoEs26bbLib {
    fun bodyFatPercent(weightKg: Float, heightM: Float, age: Int, isMale: Boolean, resistance: Int): Float {
        val bmi = weightKg / (heightM * heightM)
        return if (isMale) {
            (1.524f * bmi + 0.103f * age - 21.992f - 500f / resistance).coerceIn(1f, 60f)
        } else {
            (1.545f * bmi + 0.097f * age - 12.689f - 500f / resistance).coerceIn(1f, 60f)
        }
    }

    fun waterPercent(bf: Float, isMale: Boolean) = (if (isMale) 72.202f - 0.72223f * bf else 68.651f - 0.68725f * bf).coerceIn(20f, 80f)

    fun skeletalMusclePercent(bf: Float, isMale: Boolean) = (if (isMale) 64.713f - 0.65508f * bf else 58.390f - 0.58654f * bf).coerceIn(17.5f, 70f)

    fun boneMass(weightKg: Float, bf: Float, isMale: Boolean): Float {
        val softLeanPct = (if (isMale) 94.992f - 0.94969f * bf else 93.988f - 0.93960f * bf).coerceIn(0f, 100f)
        val softLeanKg = (weightKg * softLeanPct / 100f).coerceIn(3.75f, 110f)
        return (weightKg - softLeanKg - bf * weightKg / 100f).coerceIn(1f, 7f)
    }

    fun proteinPercent(bf: Float, isMale: Boolean) = (if (isMale) 22.787f - 0.22735f * bf else 25.340f - 0.30245f * bf).coerceIn(5f, 24f)
}
