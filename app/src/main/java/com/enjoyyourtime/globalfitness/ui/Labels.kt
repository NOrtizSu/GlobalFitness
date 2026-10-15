package com.enjoyyourtime.globalfitness.ui

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.enjoyyourtime.globalfitness.R
import com.enjoyyourtime.globalfitness.UiText
import com.enjoyyourtime.globalfitness.domain.BodyType
import com.enjoyyourtime.globalfitness.domain.Level
import com.enjoyyourtime.globalfitness.domain.Metric

/** String resources for the pure-Kotlin domain enums. */

@get:StringRes
val Metric.labelRes: Int
    get() = when (this) {
        Metric.WEIGHT -> R.string.metric_weight
        Metric.BMI -> R.string.metric_bmi
        Metric.FAT_PERCENT -> R.string.metric_fat_percent
        Metric.FAT_MASS -> R.string.metric_fat_mass
        Metric.MUSCLE -> R.string.metric_muscle
        Metric.SKELETAL_MUSCLE -> R.string.metric_skeletal_muscle
        Metric.WATER -> R.string.metric_water
        Metric.BONE -> R.string.metric_bone
        Metric.VISCERAL -> R.string.metric_visceral
        Metric.BMR -> R.string.metric_bmr
        Metric.METABOLIC_AGE -> R.string.metric_metabolic_age
        Metric.PROTEIN -> R.string.metric_protein
        Metric.IDEAL_WEIGHT -> R.string.metric_ideal_weight
    }

@Composable
fun Metric.label(): String = stringResource(labelRes)

@get:StringRes
val Level.labelRes: Int
    get() = when (this) {
        Level.VERY_LOW -> R.string.class_very_low
        Level.LOW -> R.string.class_low
        Level.NORMAL -> R.string.class_normal
        Level.HIGH -> R.string.class_high
        Level.VERY_HIGH -> R.string.class_very_high
        Level.OVERWEIGHT -> R.string.class_overweight
        Level.OBESE -> R.string.class_obese
        Level.EXCELLENT -> R.string.class_excellent
        Level.OPTIMAL -> R.string.class_optimal
        Level.YOUNGER -> R.string.class_younger
        Level.OLDER_THAN_AGE -> R.string.class_older_than_age
        Level.IN_RANGE -> R.string.class_in_range
        Level.OUT_OF_RANGE -> R.string.class_out_of_range
    }

@get:StringRes
val BodyType.nameRes: Int
    get() = when (this) {
        BodyType.OBESE -> R.string.body_type_obese
        BodyType.OVERWEIGHT -> R.string.body_type_overweight
        BodyType.SOLIDLY_BUILT -> R.string.body_type_solid
        BodyType.LACKS_EXERCISE -> R.string.body_type_lack_exercise
        BodyType.BALANCED -> R.string.body_type_balanced
        BodyType.BALANCED_MUSCULAR -> R.string.body_type_balanced_muscular
        BodyType.SKINNY -> R.string.body_type_skinny
        BodyType.BALANCED_SKINNY -> R.string.body_type_balanced_skinny
        BodyType.SKINNY_MUSCULAR -> R.string.body_type_skinny_muscular
    }

/** Resolves a [UiText] in composition (recomposes on configuration changes). */
@Composable
fun UiText.asString(): String {
    androidx.compose.ui.platform.LocalConfiguration.current
    return resolve(androidx.compose.ui.platform.LocalContext.current)
}
