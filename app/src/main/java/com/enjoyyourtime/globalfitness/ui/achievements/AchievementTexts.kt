package com.enjoyyourtime.globalfitness.ui.achievements

import androidx.annotation.StringRes
import com.enjoyyourtime.globalfitness.R
import com.enjoyyourtime.globalfitness.training.domain.Achievement
import com.enjoyyourtime.globalfitness.training.domain.AchievementBlocker
import com.enjoyyourtime.globalfitness.training.domain.AchievementCategory
import com.enjoyyourtime.globalfitness.training.domain.AchievementState
import com.enjoyyourtime.globalfitness.training.domain.Achievements
import com.enjoyyourtime.globalfitness.training.domain.Celebration
import com.enjoyyourtime.globalfitness.training.model.LoadUnit
import com.enjoyyourtime.globalfitness.ui.Fmt
import com.enjoyyourtime.globalfitness.ui.training.TrainingFormat
import com.enjoyyourtime.globalfitness.ui.training.kmText
import com.enjoyyourtime.globalfitness.ui.training.labelRes
import com.enjoyyourtime.globalfitness.ui.training.volumeText
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * Words and numbers of the achievements: what a level asks for ("Finish 25 workouts"), the
 * congratulation ("You've lifted 10 t in total"), how it is counted, and values in their unit (loads
 * in the user's [LoadUnit], body weights in kg as on the body pages).
 */
object AchievementTexts {
    @StringRes
    fun title(a: Achievement): Int = when (a) {
        Achievement.WORKOUTS -> R.string.ach_workouts_title
        Achievement.WEEK_STREAK -> R.string.ach_week_streak_title
        Achievement.BALANCED_WEEKS -> R.string.ach_balanced_weeks_title
        Achievement.EXERCISE_VARIETY -> R.string.ach_variety_title
        Achievement.CARDIO_DISTANCE -> R.string.ach_cardio_km_title
        Achievement.VOLUME -> R.string.ach_volume_title
        Achievement.RECORDS -> R.string.ach_records_title
        Achievement.BENCH_PRESS -> R.string.ach_bench_bw_title
        Achievement.SQUAT -> R.string.ach_squat_bw_title
        Achievement.DEADLIFT -> R.string.ach_deadlift_bw_title
        Achievement.OVERHEAD_PRESS -> R.string.ach_ohp_bw_title
        Achievement.PULL_UPS -> R.string.ach_pull_ups_title
        Achievement.GOAL_REACHED -> R.string.ach_goal_title
        Achievement.GOAL_PROGRESS -> R.string.ach_goal_progress_title
        Achievement.BODY_FAT -> R.string.ach_fat_down_title
        Achievement.MUSCLE_MASS -> R.string.ach_muscle_up_title
    }

    @StringRes
    fun category(c: AchievementCategory): Int = when (c) {
        AchievementCategory.TRAINING -> R.string.achievements_category_training
        AchievementCategory.STRENGTH -> R.string.achievements_category_strength
        AchievementCategory.BODY -> R.string.achievements_category_body
    }

    /** How the achievement is counted. */
    fun rule(a: Achievement): String = when (a) {
        Achievement.WORKOUTS -> Fmt.string(R.string.ach_workouts_rule)
        Achievement.WEEK_STREAK -> Fmt.string(R.string.ach_week_streak_rule)
        Achievement.BALANCED_WEEKS -> Fmt.string(R.string.ach_balanced_weeks_rule, Fmt.list(Achievements.BALANCE_MUSCLES.map { Fmt.string(it.labelRes) }))
        Achievement.EXERCISE_VARIETY -> Fmt.string(R.string.ach_variety_rule)
        Achievement.CARDIO_DISTANCE -> Fmt.string(R.string.ach_cardio_km_rule)
        Achievement.VOLUME -> Fmt.string(R.string.ach_volume_rule)
        Achievement.RECORDS -> Fmt.string(R.string.ach_records_rule)
        Achievement.BENCH_PRESS -> Fmt.string(R.string.ach_bench_bw_rule)
        Achievement.SQUAT -> Fmt.string(R.string.ach_squat_bw_rule)
        Achievement.DEADLIFT -> Fmt.string(R.string.ach_deadlift_bw_rule)
        Achievement.OVERHEAD_PRESS -> Fmt.string(R.string.ach_ohp_bw_rule)
        Achievement.PULL_UPS -> Fmt.string(R.string.ach_pull_ups_rule)
        Achievement.GOAL_REACHED -> Fmt.string(R.string.ach_goal_rule)
        Achievement.GOAL_PROGRESS -> Fmt.string(R.string.ach_goal_progress_rule_target)
        Achievement.BODY_FAT -> Fmt.string(R.string.ach_fat_down_rule)
        Achievement.MUSCLE_MASS -> Fmt.string(R.string.ach_muscle_up_rule)
    }

    @StringRes
    fun blocker(b: AchievementBlocker): Int = when (b) {
        AchievementBlocker.NEEDS_BODYWEIGHT -> R.string.achievements_needs_bodyweight
        AchievementBlocker.NEEDS_WEIGHINGS -> R.string.achievements_needs_weighings
        AchievementBlocker.NEEDS_TARGET -> R.string.achievements_needs_target
        AchievementBlocker.TARGET_IS_START -> R.string.achievements_target_is_start
        AchievementBlocker.NEEDS_COMPOSITION -> R.string.achievements_needs_composition
    }

    /** What level [level] (1-based) of [s] asks for: "Finish 25 workouts". */
    fun goal(s: AchievementState, level: Int, unit: LoadUnit): String =
        goal(s.achievement, s.thresholds[level - 1], unit, s.losing, s.targetKg)

    fun goal(a: Achievement, threshold: Double, unit: LoadUnit, losing: Boolean, targetKg: Double?): String {
        val n = threshold.roundToInt()
        val value = value(a, threshold, unit)
        return when (a) {
            Achievement.WORKOUTS -> Fmt.plural(R.plurals.ach_workouts_goal, n, value)
            Achievement.WEEK_STREAK -> Fmt.plural(R.plurals.ach_week_streak_goal, n, value)
            Achievement.BALANCED_WEEKS -> Fmt.plural(R.plurals.ach_balanced_weeks_goal, n, value)
            Achievement.EXERCISE_VARIETY -> Fmt.plural(R.plurals.ach_variety_goal, n, value)
            Achievement.CARDIO_DISTANCE -> Fmt.string(R.string.ach_cardio_km_goal, value)
            Achievement.VOLUME -> Fmt.string(R.string.ach_volume_goal, value)
            Achievement.RECORDS -> Fmt.plural(R.plurals.ach_records_goal, n, value)
            Achievement.BENCH_PRESS -> Fmt.string(R.string.ach_bench_bw_goal, value)
            Achievement.SQUAT -> Fmt.string(R.string.ach_squat_bw_goal, value)
            Achievement.DEADLIFT -> Fmt.string(R.string.ach_deadlift_bw_goal, value)
            Achievement.OVERHEAD_PRESS -> Fmt.string(R.string.ach_ohp_bw_goal, value)
            Achievement.PULL_UPS -> Fmt.plural(R.plurals.ach_pull_ups_goal, n, value)
            Achievement.GOAL_REACHED -> Fmt.string(R.string.ach_goal_goal, targetKg?.let { Fmt.kg(it, 1) } ?: value)
            Achievement.GOAL_PROGRESS -> Fmt.string(if (losing) R.string.ach_goal_progress_goal_loss else R.string.ach_goal_progress_goal_gain, value)
            Achievement.BODY_FAT -> Fmt.plural(R.plurals.ach_fat_down_goal, n, value)
            Achievement.MUSCLE_MASS -> Fmt.string(R.string.ach_muscle_up_goal, value)
        }
    }

    /** The congratulation for a level just reached: "You've lifted 10 t in total". */
    fun done(c: Celebration, unit: LoadUnit): String {
        val n = c.threshold.roundToInt()
        val value = value(c.achievement, c.threshold, unit)
        return when (c.achievement) {
            Achievement.WORKOUTS -> Fmt.plural(R.plurals.ach_workouts_done, n, value)
            Achievement.WEEK_STREAK -> Fmt.plural(R.plurals.ach_week_streak_done, n, value)
            Achievement.BALANCED_WEEKS -> Fmt.plural(R.plurals.ach_balanced_weeks_done, n, value)
            Achievement.EXERCISE_VARIETY -> Fmt.plural(R.plurals.ach_variety_done, n, value)
            Achievement.CARDIO_DISTANCE -> Fmt.string(R.string.ach_cardio_km_done, value)
            Achievement.VOLUME -> Fmt.string(R.string.ach_volume_done, value)
            Achievement.RECORDS -> Fmt.plural(R.plurals.ach_records_done, n, value)
            Achievement.BENCH_PRESS -> Fmt.string(R.string.ach_bench_bw_done, value)
            Achievement.SQUAT -> Fmt.string(R.string.ach_squat_bw_done, value)
            Achievement.DEADLIFT -> Fmt.string(R.string.ach_deadlift_bw_done, value)
            Achievement.OVERHEAD_PRESS -> Fmt.string(R.string.ach_ohp_bw_done, value)
            Achievement.PULL_UPS -> Fmt.plural(R.plurals.ach_pull_ups_done, n, value)
            Achievement.GOAL_REACHED -> Fmt.string(R.string.ach_goal_done, c.targetKg?.let { Fmt.kg(it, 1) } ?: value)
            Achievement.GOAL_PROGRESS -> Fmt.string(if (c.losing) R.string.ach_goal_progress_done_loss else R.string.ach_goal_progress_done_gain, value)
            Achievement.BODY_FAT -> Fmt.plural(R.plurals.ach_fat_down_done, n, value)
            Achievement.MUSCLE_MASS -> Fmt.string(R.string.ach_muscle_up_done, value)
        }
    }

    /** "23 / 25", "12,3 t / 50 t" (the next level's value with the current one). */
    fun progress(s: AchievementState, unit: LoadUnit): String? {
        val next = s.next ?: return null
        val value = s.progress ?: return null
        // Counts never show a fraction (and never round up to the goal).
        val current = if (isCount(s.achievement)) floor(value) else value
        return Fmt.string(R.string.achievements_progress, value(s.achievement, current, unit), value(s.achievement, next, unit))
    }

    /** A value in the achievement's unit. */
    fun value(a: Achievement, v: Double, unit: LoadUnit): String {
        val locale = Fmt.locale
        return when (a) {
            Achievement.CARDIO_DISTANCE -> kmText(TrainingFormat.number(v, if (v >= 100) 0 else 1, locale))
            Achievement.VOLUME -> when (unit) {
                LoadUnit.KG -> (v / 1000.0).let { t -> Fmt.string(R.string.achievements_value_tonnes, TrainingFormat.number(t, if (t >= 100) 0 else 1, locale)) }
                LoadUnit.LB -> volumeText(v, unit)
            }
            Achievement.BENCH_PRESS, Achievement.SQUAT, Achievement.DEADLIFT, Achievement.OVERHEAD_PRESS ->
                Fmt.string(R.string.achievements_value_ratio, TrainingFormat.number(v, 2, locale))
            Achievement.GOAL_REACHED, Achievement.GOAL_PROGRESS, Achievement.MUSCLE_MASS -> Fmt.kg(v, if (v % 1.0 == 0.0) 0 else 1)
            Achievement.BODY_FAT -> TrainingFormat.number(v, 1, locale)
            else -> Fmt.count(floor(v).toLong())
        }
    }

    private fun isCount(a: Achievement): Boolean = when (a) {
        Achievement.WORKOUTS, Achievement.WEEK_STREAK, Achievement.BALANCED_WEEKS, Achievement.EXERCISE_VARIETY,
        Achievement.RECORDS, Achievement.PULL_UPS,
        -> true
        else -> false
    }
}
