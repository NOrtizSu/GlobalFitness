package com.enjoyyourtime.globalfitness.domain

/** Metrics shown in tiles and charts. */
enum class Metric(
    val label: String,
    val unit: String,
    val decimals: Int,
    val needsImpedance: Boolean,
    val chartable: Boolean = true,
) {
    WEIGHT("Peso", "kg", 2, needsImpedance = false),
    BMI("IMC", "", 1, needsImpedance = false),
    FAT_PERCENT("Grasa", "%", 1, needsImpedance = true),
    FAT_MASS("Masa grasa", "kg", 1, needsImpedance = true),
    MUSCLE("Músculo", "kg", 1, needsImpedance = true),
    WATER("Agua", "%", 1, needsImpedance = true),
    BONE("Hueso", "kg", 2, needsImpedance = true),
    VISCERAL("Grasa visceral", "", 1, needsImpedance = false),
    BMR("Metabolismo basal", "kcal", 0, needsImpedance = false),
    METABOLIC_AGE("Edad metabólica", "años", 0, needsImpedance = true),
    PROTEIN("Proteína", "%", 1, needsImpedance = true),
    IDEAL_WEIGHT("Peso ideal", "kg", 1, needsImpedance = false, chartable = false);

    fun extract(c: BodyComposition): Double? = when (this) {
        WEIGHT -> c.weightKg
        BMI -> c.bmi
        FAT_PERCENT -> c.fatPercent
        FAT_MASS -> c.fatMassKg
        MUSCLE -> c.muscleKg
        WATER -> c.waterPercent
        BONE -> c.boneKg
        VISCERAL -> c.visceralFat
        BMR -> c.bmr
        METABOLIC_AGE -> c.metabolicAge
        PROTEIN -> c.proteinPercent
        IDEAL_WEIGHT -> c.idealWeightKg
    }

    /** Status chip for this metric, or null if the value is unavailable. */
    fun classify(c: BodyComposition): Classification? = when (this) {
        WEIGHT -> Classifier.bmi(c.bmi)
        BMI -> Classifier.bmi(c.bmi)
        FAT_PERCENT, FAT_MASS -> c.fatPercent?.let { Classifier.fat(it, c.sex, c.age) }
        MUSCLE -> c.muscleKg?.let { Classifier.muscle(it, c.sex, c.heightCm) }
        WATER -> c.waterPercent?.let { Classifier.water(it, c.sex) }
        BONE -> c.boneKg?.let { Classifier.bone(it, c.sex, c.weightKg) }
        VISCERAL -> Classifier.visceral(c.visceralFat)
        BMR -> Classifier.bmr(c.bmr, c.weightKg, c.sex, c.age)
        METABOLIC_AGE -> c.metabolicAge?.let { Classifier.metabolicAge(it, c.age) }
        PROTEIN -> c.proteinPercent?.let { Classifier.protein(it) }
        IDEAL_WEIGHT -> Classifier.idealWeight(c.weightKg, c.healthyWeightMinKg, c.healthyWeightMaxKg)
    }

    /**
     * Range considered healthy for chart bands (values with a "good" status), based on the
     * profile/latest composition. Null when the metric has no closed normal range.
     */
    fun normalRange(c: BodyComposition): ClosedFloatingPointRange<Double>? = when (this) {
        WEIGHT -> c.healthyWeightMinKg..c.healthyWeightMaxKg
        BMI -> 18.5..24.9
        FAT_PERCENT -> Classifier.fatThresholds(c.sex, c.age).let { it[0]..it[2] }
        MUSCLE -> Classifier.muscleRange(c.sex, c.heightCm)
        WATER -> Classifier.waterRange(c.sex)
        BONE -> Classifier.boneRange(c.sex, c.weightKg)
        VISCERAL -> 1.0..9.9
        PROTEIN -> 16.0..20.0
        FAT_MASS, BMR, METABOLIC_AGE, IDEAL_WEIGHT -> null
    }

    companion object {
        val chartMetrics: List<Metric> = entries.filter { it.chartable }
    }
}
