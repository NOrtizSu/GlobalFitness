package com.enjoyyourtime.globalfitness.training.domain

import com.enjoyyourtime.globalfitness.data.Measurement
import com.enjoyyourtime.globalfitness.training.model.Exercise
import com.enjoyyourtime.globalfitness.training.model.ExerciseKind
import com.enjoyyourtime.globalfitness.training.model.WorkoutSet

/**
 * The user's body weight over time, from the scale and manual measurements. A set uses the last
 * weighing at or before it; sets older than the first weighing use that first one (better than no
 * estimate, and body weight changes slowly).
 */
class BodyweightTimeline(points: List<Pair<Long, Double>>) {
    private val times: LongArray
    private val weights: DoubleArray

    init {
        val valid = points.filter { it.second > 0.0 }.sortedBy { it.first }
        times = LongArray(valid.size) { valid[it].first }
        weights = DoubleArray(valid.size) { valid[it].second }
    }

    val isEmpty: Boolean get() = times.isEmpty()

    /** Body weight (kg) at [time], or null when the user never weighed in. */
    fun at(time: Long): Double? {
        if (times.isEmpty()) return null
        var lo = 0
        var hi = times.size - 1
        var found = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (times[mid] <= time) {
                found = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        return weights[if (found >= 0) found else 0]
    }

    companion object {
        val EMPTY = BodyweightTimeline(emptyList())

        fun fromMeasurements(measurements: List<Measurement>): BodyweightTimeline =
            BodyweightTimeline(measurements.map { it.timestamp to it.weightKg })
    }
}

/**
 * The weight a set actually moves, so the estimated 1RM, volume and strength trends compare like
 * with like:
 * - weight × reps and timed sets: the weight on the bar or the stack;
 * - bodyweight exercises with a [Exercise.bodyweightFactor]: that share of the body weight plus the
 *   extra load (a pull-up with +10 kg at 80 kg moves 90 kg);
 * - assisted machines: share of the body weight minus the assistance (80 kg with 30 kg of
 *   assistance lifts 50 kg), so less assistance shows as more strength; a set without an
 *   assistance value has no load;
 * - bodyweight exercises without a factor (crunches, burpees...): only the extra load, if any.
 * Null when there is no meaningful load (no body weight known, cardio, nothing on the bar).
 */
class LoadModel(private val bodyweight: BodyweightTimeline) {

    fun loadKg(exercise: Exercise, set: WorkoutSet, at: Long): Double? = loadKg(exercise.kind, exercise.bodyweightFactor, set, at)

    fun loadKg(kind: ExerciseKind, bodyweightFactor: Double?, set: WorkoutSet, at: Long): Double? {
        val extra = set.weightKg ?: 0.0
        return when (kind) {
            ExerciseKind.WEIGHT_REPS, ExerciseKind.DURATION -> extra.takeIf { it > 0.0 }
            ExerciseKind.BODYWEIGHT_REPS ->
                if (bodyweightFactor == null) {
                    extra.takeIf { it > 0.0 }
                } else {
                    bodyweight.at(at)?.let { it * bodyweightFactor + extra }
                }
            // A blank assistance is unknown, not zero (zero would read as a full pull-up): no load. The
            // user types 0 for a set done without help.
            ExerciseKind.ASSISTED_REPS -> {
                val assistance = set.weightKg ?: return null
                bodyweight.at(at)?.let { it * (bodyweightFactor ?: 1.0) - assistance }?.takeIf { it > 0.0 }
            }
            ExerciseKind.CARDIO -> null
        }
    }

    /** True when the exercise's load depends on the body weight and none is known yet. */
    fun needsBodyweight(exercise: Exercise): Boolean = bodyweight.isEmpty &&
        (exercise.kind == ExerciseKind.ASSISTED_REPS || (exercise.kind == ExerciseKind.BODYWEIGHT_REPS && exercise.bodyweightFactor != null))

    companion object {
        /** Without any body weight: loaded exercises still work; assisted and bodyweight ones get no load. */
        val WITHOUT_BODYWEIGHT = LoadModel(BodyweightTimeline.EMPTY)
    }
}
