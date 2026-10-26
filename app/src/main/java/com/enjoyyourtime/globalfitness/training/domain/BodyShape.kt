package com.enjoyyourtime.globalfitness.training.domain

import com.enjoyyourtime.globalfitness.data.Measurement
import com.enjoyyourtime.globalfitness.data.Profile
import com.enjoyyourtime.globalfitness.data.Sex
import com.enjoyyourtime.globalfitness.domain.BodyComposition
import com.enjoyyourtime.globalfitness.domain.BodyMetrics
import com.enjoyyourtime.globalfitness.training.model.Exercise
import com.enjoyyourtime.globalfitness.training.model.Muscle
import com.enjoyyourtime.globalfitness.training.model.Workout
import java.time.Duration
import kotlin.math.pow

/**
 * How the body figure is drawn: [fatness] and [muscularity] from 0 to 1, where 0.5 is an average
 * body (the figure exactly as drawn). An illustration of the user's trend, never a measurement.
 */
data class BodyShape(val fatness: Float, val muscularity: Float) {
    /** The nearest of [steps] + 1 levels per axis, so figures drawn for close shapes can be shared. */
    fun quantised(steps: Int = QUANTISATION_STEPS): BodyShape =
        BodyShape(Math.round(fatness * steps).toFloat() / steps, Math.round(muscularity * steps).toFloat() / steps)

    companion object {
        val NEUTRAL = BodyShape(0.5f, 0.5f)
        const val QUANTISATION_STEPS = 20
    }
}

/** A [shape] and what it was drawn from (a caption can say which data shaped the figure). */
data class BodyShapeEstimate(val shape: BodyShape, val fromScale: Boolean, val fromTraining: Boolean) {
    companion object {
        val NONE = BodyShapeEstimate(BodyShape.NEUTRAL, fromScale = false, fromTraining = false)
    }
}

/**
 * The weighings that report body fat, each read once into its composition with the profile, newest
 * first (see [BodyShapeModel.readings]). Built when the weighings or the profile change, so drawing
 * the figure at another moment does not sort and read them all again.
 */
class BodyReadings internal constructor(private val times: LongArray, private val compositions: List<BodyComposition>) {
    /** When the first of them was taken; null without any. */
    val firstAt: Long? get() = times.lastOrNull()

    /**
     * The latest at or before [time], newest first: up to [count], none older than [window] before
     * the latest of them nor [maxAge] before [time].
     */
    internal fun recent(time: Long, count: Int, window: Long, maxAge: Long): List<BodyComposition> {
        // The first one at or before [time] (the times go down).
        var lo = 0
        var hi = times.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (times[mid] > time) lo = mid + 1 else hi = mid
        }
        if (lo == times.size) return emptyList()
        val oldest = maxOf(times[lo] - window, time - maxAge)
        val result = ArrayList<BodyComposition>(count)
        var i = lo
        while (i < times.size && result.size < count && times[i] >= oldest) result += compositions[i++]
        return result
    }

    companion object {
        val NONE = BodyReadings(LongArray(0), emptyList())
    }
}

/**
 * The body figure's shape at a moment, from the data known at that moment only:
 *
 * - **Fatness** comes from the scale's body fat %, the median of the last few weighings with one
 *   (so one odd reading does not move the figure), mapped with ranges per sex: fat % differs by
 *   about ten points between men and women at the same build (essential fat). The ACE bands —
 *   men: athletes 6–13 %, average 18–24 %, obese 25 % and more; women: athletes 14–20 %, average
 *   25–31 %, obese 32 % and more — give the anchors: men 8 % → 0, 18 % → 0.5, 30 % → 1; women
 *   16 % → 0, 27 % → 0.5, 40 % → 1 (linear in between, held beyond: the figure is fullest a few
 *   points into the obese band, so a few kilos lost there still show). Without body fat (no
 *   profile, or weighings without impedance) fatness stays at 0.5: the figure only shows training.
 *   Readings more than [MAX_READING_AGE_MS] (six months) before the moment say nothing about it:
 *   without newer ones the figure is drawn as without a scale (the same for its muscle mass).
 * - **Muscularity** blends two estimates when both exist (half each), else uses the one there is,
 *   else 0.5:
 *   - the scale's muscle mass for the user's height, a fat-free mass index (muscle kg / m²) with
 *     the fat mass index above average partly discounted ([LEAN_PER_FAT]: heavier bodies carry
 *     more lean tissue just to move their weight, which is not visible muscle). Anchors from
 *     population fat-free mass index percentiles (scale muscle ≈ fat-free mass minus bone): men
 *     16 → 0, 18 → 0.5, 22 → 1; women 12.5 → 0, 14.5 → 0.5, 17.5 → 1. Skeletal muscle % is not
 *     used: brands define it differently (some report 30–40 % of the weight, others 45–60 %), so
 *     it fits no fixed norm.
 *   - training: 0.5 + ½ × a score from 0 (no training) to 1. The score is the weekly hard sets of
 *     each muscle against the low end of its target range (capped at 1, big muscles weighing more),
 *     averaged over the last [VOLUME_WEEKS] weeks with older weeks fading (half-life
 *     [HALF_LIFE_WEEKS] weeks), so a new routine shows gradually and a long break fades slowly;
 *     multiplied by 0.7 plus 0.3 × the strength gain (the estimated 1RM change of the last
 *     [STRENGTH_WEEKS] weeks, [FULL_GAIN_PERCENT] % or more counting fully). Training never makes
 *     the figure thinner than average: not training is simply no evidence of muscle.
 */
object BodyShapeModel {
    private val WEEK_MS = Duration.ofDays(7).toMillis()

    /** Weighings (with body fat) whose median is used, and how far back from the latest they may be. */
    const val SMOOTHING_READINGS = 5
    val SMOOTHING_WINDOW_MS: Long = Duration.ofDays(30).toMillis()

    /** How long before the moment drawn a weighing still counts. */
    val MAX_READING_AGE_MS: Long = Duration.ofDays(180).toMillis()

    const val VOLUME_WEEKS = 26
    const val HALF_LIFE_WEEKS = 6.0
    const val STRENGTH_WEEKS = 16L
    const val FULL_GAIN_PERCENT = 25.0

    /** Fat mass index points that count as one point less of muscle index (see the class comment). */
    const val LEAN_PER_FAT = 0.3

    /** Share of the training score that needs no strength gain (consistency alone). */
    private const val CONSISTENCY_SHARE = 0.7

    /** Weight of the scale's estimate when both it and training are known. */
    private const val SCALE_WEIGHT = 0.5

    /**
     * How much each muscle's weekly sets count towards the training score: the big muscles that
     * shape the figure most count fully, small or rarely trained ones less.
     */
    private val VISUAL_WEIGHT: Map<Muscle, Double> = mapOf(
        Muscle.CHEST to 1.0,
        Muscle.SHOULDERS to 1.0,
        Muscle.LATS to 1.0,
        Muscle.QUADS to 1.0,
        Muscle.BICEPS to 0.6,
        Muscle.TRICEPS to 0.6,
        Muscle.GLUTES to 0.6,
        Muscle.HAMSTRINGS to 0.6,
        Muscle.TRAPS to 0.4,
        Muscle.CALVES to 0.4,
        Muscle.ABS to 0.3,
        Muscle.FOREARMS to 0.3,
        Muscle.OBLIQUES to 0.2,
        Muscle.LOWER_BACK to 0.2,
        Muscle.ADDUCTORS to 0.2,
        Muscle.NECK to 0.1,
    )

    /** The weighings with body fat read with [profile] (none without a profile), for [at] and [firstDataAt]. */
    fun readings(measurements: List<Measurement>, profile: Profile?): BodyReadings {
        if (profile == null) return BodyReadings.NONE
        val withFat = measurements.sortedByDescending { it.timestamp }.mapNotNull { m ->
            composition(profile, m).takeIf { it.fatPercent != null }?.let { m.timestamp to it }
        }
        return BodyReadings(LongArray(withFat.size) { withFat[it].first }, withFat.map { it.second })
    }

    /**
     * The shape at [time] from the weighings ([readings]), workouts and sets at or before it (later
     * data is ignored, so a past moment is drawn as it was). [load] gives bodyweight exercises their
     * load for the strength trend, as on the muscle map.
     */
    fun at(
        time: Long,
        readings: BodyReadings,
        workouts: List<Workout>,
        exercises: (String) -> Exercise,
        load: LoadModel = LoadModel.WITHOUT_BODYWEIGHT,
    ): BodyShapeEstimate {
        // Up to the last few weighings with body fat, close to the latest and recent enough for [time].
        val recent = readings.recent(time, SMOOTHING_READINGS, SMOOTHING_WINDOW_MS, MAX_READING_AGE_MS)
        val fat = recent.firstOrNull()?.let { latest -> median(recent.mapNotNull { it.fatPercent })?.let { fatness(latest.sex, it) } }
        val scaleMuscle = median(recent.mapNotNull { scaleMuscularity(it) })
        val training = trainingScore(time, workouts, exercises, load)
        val muscularity = muscularity(scaleMuscle, training)
        return BodyShapeEstimate(
            shape = BodyShape((fat ?: 0.5).toFloat(), muscularity.toFloat()),
            fromScale = fat != null,
            fromTraining = training != null,
        )
    }

    /**
     * When the comparison with the past can start: the first weighing with body fat when the user
     * has any, else the first workout; null without either.
     */
    fun firstDataAt(readings: BodyReadings, workouts: List<Workout>): Long? = readings.firstAt ?: workouts.minOfOrNull { it.startedAt }

    /** 0 (lean) to 1 (high body fat), 0.5 at an average body fat for [sex]. */
    fun fatness(sex: Sex, fatPercent: Double): Double =
        if (sex == Sex.FEMALE) {
            piecewise(fatPercent, FEMALE_LEAN_FAT, FEMALE_AVERAGE_FAT, FEMALE_HIGH_FAT)
        } else {
            piecewise(fatPercent, MALE_LEAN_FAT, MALE_AVERAGE_FAT, MALE_HIGH_FAT)
        }

    /** Muscularity from one weighing's composition (see the class comment), or null without muscle mass. */
    fun scaleMuscularity(c: BodyComposition): Double? {
        val muscle = c.muscleKg ?: return null
        val fat = c.fatMassKg ?: return null
        val h2 = (c.heightCm / 100.0).pow(2)
        if (h2 <= 0.0) return null
        val female = c.sex == Sex.FEMALE
        val muscleIndex = muscle / h2
        val fatIndex = fat / h2
        if (muscleIndex !in PLAUSIBLE_MUSCLE_INDEX) return null
        val adjusted = muscleIndex - LEAN_PER_FAT * (fatIndex - if (female) FEMALE_AVERAGE_FAT_INDEX else MALE_AVERAGE_FAT_INDEX)
        return if (female) {
            piecewise(adjusted, FEMALE_LOW_MUSCLE_INDEX, FEMALE_AVERAGE_MUSCLE_INDEX, FEMALE_HIGH_MUSCLE_INDEX)
        } else {
            piecewise(adjusted, MALE_LOW_MUSCLE_INDEX, MALE_AVERAGE_MUSCLE_INDEX, MALE_HIGH_MUSCLE_INDEX)
        }
    }

    /** The blend of the scale's and training's estimates (0.5 when neither is known). */
    fun muscularity(scale: Double?, training: Double?): Double {
        val fromTraining = training?.let { 0.5 + 0.5 * it }
        val value = when {
            scale != null && fromTraining != null -> SCALE_WEIGHT * scale + (1 - SCALE_WEIGHT) * fromTraining
            scale != null -> scale
            fromTraining != null -> fromTraining
            else -> 0.5
        }
        return value.coerceIn(0.0, 1.0)
    }

    /**
     * Training score at [time], 0 to 1 (see the class comment); null when no workout had started by
     * then (nothing is known about the user's training).
     */
    fun trainingScore(time: Long, workouts: List<Workout>, exercises: (String) -> Exercise, load: LoadModel): Double? {
        val past = workouts.filter { it.startedAt <= time }
        if (past.isEmpty()) return null
        val weeks = Array(VOLUME_WEEKS) { DoubleArray(Muscle.entries.size) }
        for (w in past) {
            for (e in w.entries) {
                val ex = exercises(e.exerciseId)
                if (!TrainingMath.countsForMuscles(ex.kind)) continue
                val secondary = ex.secondary - ex.primary
                for (s in e.sets) {
                    if (!s.isWorking) continue
                    val at = s.doneAt ?: w.startedAt
                    if (at > time) continue
                    val week = ((time - at) / WEEK_MS).toInt()
                    if (week >= VOLUME_WEEKS) continue
                    for (m in ex.primary) weeks[week][m.ordinal] += TrainingMath.PRIMARY_SHARE
                    for (m in secondary) weeks[week][m.ordinal] += TrainingMath.SECONDARY_SHARE
                }
            }
        }
        var sum = 0.0
        var weight = 0.0
        for (k in 0 until VOLUME_WEEKS) {
            val fade = 0.5.pow(k / HALF_LIFE_WEEKS)
            sum += fade * weekScore(weeks[k])
            weight += fade
        }
        val volume = sum / weight
        return volume * (CONSISTENCY_SHARE + (1 - CONSISTENCY_SHARE) * strengthGain(time, past, exercises, load))
    }

    /** One week's sets against each muscle's target, 0 to 1 (weighted by [VISUAL_WEIGHT]). */
    private fun weekScore(sets: DoubleArray): Double {
        var sum = 0.0
        var weight = 0.0
        for (m in Muscle.entries) {
            val w = VISUAL_WEIGHT[m] ?: continue
            sum += w * (sets[m.ordinal] / m.weeklySets.first).coerceIn(0.0, 1.0)
            weight += w
        }
        return sum / weight
    }

    /**
     * Mean estimated 1RM change of the last [STRENGTH_WEEKS] weeks as 0 (none or a loss) to 1: the
     * exercises that train muscles, weighted by their sessions, each counted once however many
     * primary muscles it has.
     */
    internal fun strengthGain(time: Long, workouts: List<Workout>, exercises: (String) -> Exercise, load: LoadModel): Double {
        val trends = MuscleStats.exerciseTrends(workouts, exercises, from = time - STRENGTH_WEEKS * WEEK_MS, to = time + 1, load = load)
            .filter { it.exercise.primary.isNotEmpty() }
        val sessions = trends.sumOf { it.sessions }
        if (sessions == 0) return 0.0
        val change = trends.sumOf { it.change * it.sessions } / sessions * 100.0
        return (change / FULL_GAIN_PERCENT).coerceIn(0.0, 1.0)
    }

    private fun composition(profile: Profile, m: Measurement): BodyComposition =
        BodyMetrics.forMeasurement(profile.sex, profile.heightCm, profile.ageAt(m.timestamp), m)

    private fun median(values: List<Double>): Double? {
        if (values.isEmpty()) return null
        val sorted = values.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2
    }

    /** 0 at [low], 0.5 at [average], 1 at [high], linear in between and held beyond. */
    private fun piecewise(x: Double, low: Double, average: Double, high: Double): Double = when {
        x <= low -> 0.0
        x <= average -> 0.5 * (x - low) / (average - low)
        x <= high -> 0.5 + 0.5 * (x - average) / (high - average)
        else -> 1.0
    }

    private const val MALE_LEAN_FAT = 8.0
    private const val MALE_AVERAGE_FAT = 18.0
    private const val MALE_HIGH_FAT = 30.0
    private const val FEMALE_LEAN_FAT = 16.0
    private const val FEMALE_AVERAGE_FAT = 27.0
    private const val FEMALE_HIGH_FAT = 40.0

    private const val MALE_LOW_MUSCLE_INDEX = 16.0
    private const val MALE_AVERAGE_MUSCLE_INDEX = 18.0
    private const val MALE_HIGH_MUSCLE_INDEX = 22.0
    private const val FEMALE_LOW_MUSCLE_INDEX = 12.5
    private const val FEMALE_AVERAGE_MUSCLE_INDEX = 14.5
    private const val FEMALE_HIGH_MUSCLE_INDEX = 17.5

    /** Fat mass index (kg / m²) of an average body: 18 % of a 78 kg, 1.78 m man; 27 % of a 62 kg, 1.65 m woman. */
    private const val MALE_AVERAGE_FAT_INDEX = 4.4
    private const val FEMALE_AVERAGE_FAT_INDEX = 6.1

    /** Muscle indexes outside this range are a misreading (or a skeletal-muscle value), not a body. */
    private val PLAUSIBLE_MUSCLE_INDEX = 8.0..32.0
}
