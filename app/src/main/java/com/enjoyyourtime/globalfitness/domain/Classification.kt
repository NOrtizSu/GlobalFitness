package com.enjoyyourtime.globalfitness.domain

import com.enjoyyourtime.globalfitness.data.Sex

enum class Status { GOOD, WARNING, SERIOUS, CRITICAL }

data class Classification(val label: String, val status: Status)

data class BodyType(val index: Int, val name: String, val description: String)

/** Reference bands (Mi Fit style) used for status chips and the "normal range" chart bands. */
object Classifier {

    fun bmi(bmi: Double): Classification = when {
        bmi < 18.5 -> Classification("Bajo", Status.WARNING)
        bmi < 25.0 -> Classification("Normal", Status.GOOD)
        bmi < 30.0 -> Classification("Sobrepeso", Status.WARNING)
        else -> Classification("Obesidad", Status.CRITICAL)
    }

    /** Fat % thresholds [t0, t1, t2, t3] by sex and age. */
    fun fatThresholds(sex: Sex, age: Int): DoubleArray {
        val female = sex == Sex.FEMALE
        val youngMale = doubleArrayOf(7.0, 16.0, 25.0, 30.0)
        return when {
            age < 12 -> if (female) doubleArrayOf(12.0, 21.0, 30.0, 34.0) else youngMale
            age < 14 -> if (female) doubleArrayOf(15.0, 24.0, 33.0, 37.0) else youngMale
            age < 16 -> if (female) doubleArrayOf(18.0, 27.0, 36.0, 40.0) else youngMale
            age < 18 -> if (female) doubleArrayOf(20.0, 28.0, 37.0, 41.0) else youngMale
            age < 40 ->if (female) doubleArrayOf(21.0, 28.0, 35.0, 40.0) else doubleArrayOf(11.0, 17.0, 22.0, 27.0)
            age < 60 -> if (female) doubleArrayOf(22.0, 29.0, 36.0, 41.0) else doubleArrayOf(12.0, 18.0, 23.0, 28.0)
            else -> if (female) doubleArrayOf(23.0, 30.0, 37.0, 42.0) else doubleArrayOf(14.0, 20.0, 25.0, 30.0)
        }
    }

    fun fat(fatPercent: Double, sex: Sex, age: Int): Classification {
        val t = fatThresholds(sex, age)
        return when {
            fatPercent < t[0] -> Classification("Muy bajo", Status.WARNING)
            fatPercent < t[1] -> Classification("Bajo", Status.GOOD)
            fatPercent < t[2] -> Classification("Normal", Status.GOOD)
            fatPercent < t[3] -> Classification("Alto", Status.WARNING)
            else -> Classification("Muy alto", Status.CRITICAL)
        }
    }

    /** Normal muscle mass range [low, high] in kg by sex and height. */
    fun muscleRange(sex: Sex, heightCm: Double): ClosedFloatingPointRange<Double> =
        if (sex == Sex.MALE) {
            when {
                heightCm >= 170 -> 49.5..59.4
                heightCm >= 160 -> 44.0..52.4
                else -> 38.5..46.5
            }
        } else {
            when {
                heightCm >= 160 -> 36.5..42.5
                heightCm >= 150 -> 32.9..37.5
                else -> 29.1..34.7
            }
        }

    fun muscle(muscleKg: Double, sex: Sex, heightCm: Double): Classification {
        val r = muscleRange(sex, heightCm)
        return when {
            muscleKg < r.start -> Classification("Bajo", Status.WARNING)
            muscleKg <= r.endInclusive -> Classification("Normal", Status.GOOD)
            else -> Classification("Excelente", Status.GOOD)
        }
    }

    /** Normal skeletal muscle % range by sex (BIA consumer-scale reference bands). */
    fun skeletalMuscleRange(sex: Sex): ClosedFloatingPointRange<Double> =
        if (sex == Sex.FEMALE) 24.3..30.3 else 33.3..39.3

    fun skeletalMuscle(percent: Double, sex: Sex): Classification {
        val r = skeletalMuscleRange(sex)
        return when {
            percent < r.start -> Classification("Bajo", Status.WARNING)
            percent <= r.endInclusive -> Classification("Normal", Status.GOOD)
            else -> Classification("Alto", Status.GOOD)
        }
    }

    fun waterRange(sex: Sex): ClosedFloatingPointRange<Double> =
        if (sex == Sex.FEMALE) 45.0..60.0 else 55.0..65.0

    fun water(waterPercent: Double, sex: Sex): Classification {
        val r = waterRange(sex)
        return when {
            waterPercent < r.start -> Classification("Bajo", Status.WARNING)
            waterPercent <= r.endInclusive -> Classification("Normal", Status.GOOD)
            else -> Classification("Alto", Status.GOOD)
        }
    }

    /** Reference bone mass by sex and weight (Mi Fit). */
    fun boneReference(sex: Sex, weightKg: Double): Double =
        if (sex == Sex.FEMALE) {
            when {
                weightKg >= 60 -> 2.5
                weightKg >= 45 -> 2.2
                else -> 1.8
            }
        } else {
            when {
                weightKg >= 75 -> 3.2
                weightKg >= 60 -> 2.9
                else -> 2.5
            }
        }

    /** Normal bone mass band: reference ± 1.0 kg. */
    fun boneRange(sex: Sex, weightKg: Double): ClosedFloatingPointRange<Double> {
        val ref = boneReference(sex, weightKg)
        return (ref - 1.0)..(ref + 1.0)
    }

    fun bone(boneKg: Double, sex: Sex, weightKg: Double): Classification {
        val r = boneRange(sex, weightKg)
        return when {
            boneKg < r.start -> Classification("Bajo", Status.WARNING)
            boneKg <= r.endInclusive -> Classification("Normal", Status.GOOD)
            else -> Classification("Alto", Status.GOOD)
        }
    }

    fun visceral(value: Double): Classification = when {
        value < 10 -> Classification("Normal", Status.GOOD)
        value < 15 -> Classification("Alto", Status.WARNING)
        else -> Classification("Muy alto", Status.CRITICAL)
    }

    fun bmrCoefficient(sex: Sex, age: Int): Double {
        val female = sex == Sex.FEMALE
        return when {
            age < 12 -> if (female) 34.0 else 36.0
            age < 15 -> if (female) 29.0 else 30.0
            age < 17 -> if (female) 24.0 else 26.0
            age < 29 -> if (female) 22.0 else 23.0
            age < 50 -> if (female) 20.7 else 21.6
            else -> if (female) 19.6 else 20.5
        }
    }

    fun bmr(bmr: Double, weightKg: Double, sex: Sex, age: Int): Classification =
        if (bmr >= weightKg * bmrCoefficient(sex, age)) {
            Classification("Óptimo", Status.GOOD)
        } else {
            Classification("Bajo", Status.WARNING)
        }

    fun protein(proteinPercent: Double): Classification = when {
        proteinPercent < 16 -> Classification("Bajo", Status.WARNING)
        proteinPercent <= 20 -> Classification("Normal", Status.GOOD)
        else -> Classification("Excelente", Status.GOOD)
    }

    fun metabolicAge(metabolicAge: Double, realAge: Int): Classification =
        if (metabolicAge <= realAge) {
            Classification("Más joven", Status.GOOD)
        } else {
            Classification("Mayor que tu edad", Status.WARNING)
        }

    fun idealWeight(weightKg: Double, healthyMin: Double, healthyMax: Double): Classification =
        if (weightKg in healthyMin..healthyMax) {
            Classification("En rango", Status.GOOD)
        } else {
            Classification("Fuera de rango", Status.WARNING)
        }

    private val BODY_TYPES = listOf(
        "Obeso" to "Grasa alta y poca masa muscular.",
        "Sobrepeso" to "Grasa alta con masa muscular normal.",
        "Corpulento" to "Grasa alta con mucha masa muscular.",
        "Falta de ejercicio" to "Grasa normal y poca masa muscular.",
        "Equilibrado" to "Grasa y músculo dentro de lo normal.",
        "Equilibrado musculoso" to "Grasa normal con mucha masa muscular.",
        "Delgado" to "Grasa baja y poca masa muscular.",
        "Delgado equilibrado" to "Grasa baja con masa muscular normal.",
        "Delgado musculoso" to "Grasa baja con mucha masa muscular.",
    )

    fun bodyTypeByIndex(index: Int): BodyType {
        val (name, description) = BODY_TYPES[index.coerceIn(0, 8)]
        return BodyType(index.coerceIn(0, 8), name, description)
    }

    /** Body type (0..8) or null without impedance. */
    fun bodyType(c: BodyComposition): BodyType? {
        val fat = c.fatPercent ?: return null
        val muscle = c.muscleKg ?: return null
        val t = fatThresholds(c.sex, c.age)
        val fatLevel = when {
            fat > t[2] -> 0 // high
            fat < t[1] -> 2 // low
            else -> 1 // normal
        }
        val range = muscleRange(c.sex, c.heightCm)
        val muscleLevel = when {
            muscle < range.start -> 0 // low
            muscle <= range.endInclusive -> 1 // normal
            else -> 2 // high
        }
        return bodyTypeByIndex(fatLevel * 3 + muscleLevel)
    }
}
