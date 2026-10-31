package com.enjoyyourtime.globalfitness.health

import androidx.health.connect.client.records.ExerciseSegment
import androidx.health.connect.client.records.ExerciseSessionRecord
import com.enjoyyourtime.globalfitness.UiText
import com.enjoyyourtime.globalfitness.training.domain.TrainingMath
import com.enjoyyourtime.globalfitness.training.domain.WorkoutTime
import com.enjoyyourtime.globalfitness.training.model.Exercise
import com.enjoyyourtime.globalfitness.training.model.ExerciseKind
import com.enjoyyourtime.globalfitness.training.model.Workout
import com.enjoyyourtime.globalfitness.training.model.WorkoutPause
import com.enjoyyourtime.globalfitness.training.model.WorkoutSet

/** What the export needs to know about one exercise of the training log. */
data class WorkoutExerciseInfo(
    /** Logged as time / distance on a cardio machine. */
    val cardio: Boolean,
    /** `ExerciseSegment.EXERCISE_SEGMENT_TYPE_*` of one set; null for custom exercises. */
    val hcSegment: Int? = null,
    /** `ExerciseSessionRecord.EXERCISE_TYPE_*` of a cardio exercise; null otherwise. */
    val hcSession: Int? = null,
    /**
     * The name of a sport or activity, in the current language: the title of an unnamed workout
     * that is only this activity (padel is saved as tennis, futsal as soccer, an own sport as
     * "other workout", but each keeps its own name). Null for the other exercises.
     */
    val title: String? = null,
    /** A stretch or mobility drill: it does not decide the session type unless the workout is only stretching. */
    val mobility: Boolean = false,
) {
    companion object {
        /**
         * What the planner needs of [e] (the exporter and the tests use this one mapping); a sport or
         * activity ([TrainingMath.isSport]) also brings its [name] for the title.
         */
        fun of(e: Exercise, name: (UiText) -> String?): WorkoutExerciseInfo = WorkoutExerciseInfo(
            cardio = e.kind == ExerciseKind.CARDIO,
            hcSegment = e.hcSegment,
            hcSession = e.hcSession,
            title = if (TrainingMath.isSport(e)) name(e.name) else null,
            mobility = e.mobility,
        )
    }
}

/** One completed set (or a pause) as a Health Connect exercise segment (epoch millis). */
data class PlannedSegment(val start: Long, val end: Long, val type: Int, val repetitions: Int)

/** The exercise session written for one finished workout. */
data class PlannedSession(
    val workoutId: String,
    val clientRecordId: String,
    /** `clientRecordVersion`: higher than any version written before ([WorkoutExportPlanner.exportVersion]). */
    val version: Long,
    /** The workout's `updatedAt` this session was built from. */
    val sourceUpdatedAt: Long,
    val start: Long,
    val end: Long,
    /** `ExerciseSessionRecord.EXERCISE_TYPE_*`. */
    val exerciseType: Int,
    /** The workout's own name; null when it has none (the exporter writes a localized generic title). */
    val title: String?,
    val notes: String?,
    val segments: List<PlannedSegment>,
) {
    /** What the ledger remembers once this session is written. */
    fun ledgerEntry(): WorkoutLedgerEntry = WorkoutLedgerEntry(workoutId, version, sourceUpdatedAt, WorkoutExportPlanner.FORMAT)
}

/** An exercise session written by this app, as read back from Health Connect. */
data class StoredSession(val clientRecordId: String?, val version: Long)

/**
 * What this installation knows about one workout's session (health.db, not backed up).
 * [version] 0 means nothing was written by this installation (a deletion noted for a workout whose
 * session may come from an earlier one).
 */
data class WorkoutLedgerEntry(
    val workoutId: String,
    /** Last `clientRecordVersion` written. */
    val version: Long,
    /** The workout's `updatedAt` that version was built from. */
    val sourceUpdatedAt: Long,
    /** [WorkoutExportPlanner.FORMAT] of that write. */
    val format: Int,
    /** The workout was seen disappearing (deleted here or through the cloud): its session must go. */
    val pendingDelete: Boolean = false,
) {
    val written: Boolean get() = version > 0
}

data class WorkoutExportPlan(
    val writes: List<PlannedSession>,
    /** Client record ids to delete: workouts seen disappearing, and workouts left without completed sets. */
    val deletes: List<String>,
    /** Ledger entries to drop once [deletes] succeeded. */
    val ledgerRemove: Set<String>,
    /** Workouts noted as deleted that exist again (undone, or restored by the cloud): not deleted after all. */
    val ledgerUndelete: Set<String>,
    /** End of the earliest quiet period still running ([WorkoutExportPlanner.QUIET_MS]): run again then. */
    val nextRunAt: Long?,
    /** Workouts of this app in Health Connect once the plan has been carried out. */
    val savedCount: Int,
) {
    val isEmpty: Boolean get() = writes.isEmpty() && deletes.isEmpty() && ledgerRemove.isEmpty() && ledgerUndelete.isEmpty()
}

/**
 * Pure planning of the workout export ([WorkoutExporter]): which finished workouts to (re-)write
 * as `ExerciseSessionRecord`s, what each record contains, and which records to delete.
 *
 * Records are identified by `clientRecordId = gf-workout-<workoutId>`. Health Connect replaces a
 * record with the same client id only when the new `clientRecordVersion` is not lower, and a
 * workout's `updatedAt` can go back (the cloud backup may adopt an older copy), so the version is
 * not `updatedAt` but [exportVersion]: above everything written before. Whether a session is up to
 * date is decided by the ledger instead (the `updatedAt` and [FORMAT] it was built from).
 *
 * The ledger also keeps a session the user deleted in Health Connect from coming back until the
 * workout changes, and it holds the deletions: a session is only deleted when this app saw its
 * workout disappear (noted as [WorkoutLedgerEntry.pendingDelete] by [disappeared]), never because
 * a workout is merely missing. A training log that comes back empty (reinstall, a database
 * recreated after corruption) therefore never wipes the user's history from Health Connect.
 */
object WorkoutExportPlanner {
    const val CLIENT_ID_PREFIX = "gf-workout-"

    /**
     * Version of the mapping from workouts to sessions. Bump it when that mapping changes (segments,
     * titles...): every session still in Health Connect is then written again with the new one.
     */
    const val FORMAT = 1

    /**
     * A finished workout is written once it has not changed for this long, so a past workout being
     * filled in (or a finished one being corrected) is written once, not after every edit.
     */
    const val QUIET_MS = 60_000L

    /** An `updatedAt` further in the future than this is a wrong clock: no quiet period is waited for it. */
    const val MAX_CLOCK_SKEW_MS = 86_400_000L

    /** A session needs a positive length: a workout that ended when it started lasts this long. */
    const val MIN_SESSION_MS = 60_000L

    /** Shorter segments (sets squeezed into the end of a session) are dropped. */
    const val MIN_SEGMENT_MS = 1_000L

    /** Expected length of a set of repetitions (3 s per repetition, 10 s to 2 min). */
    const val MS_PER_REP = 3_000L
    const val MIN_SET_MS = 10_000L
    const val MAX_SET_MS = 120_000L

    /** Expected length of a set with neither repetitions nor duration. */
    const val DEFAULT_SET_MS = 30_000L

    fun clientRecordId(workoutId: String): String = CLIENT_ID_PREFIX + workoutId

    /** The workout behind one of this app's client record ids, or null for any other id. */
    fun workoutIdOf(clientRecordId: String?): String? =
        clientRecordId?.takeIf { it.startsWith(CLIENT_ID_PREFIX) }?.removePrefix(CLIENT_ID_PREFIX)?.takeIf { it.isNotEmpty() }

    /**
     * @param workouts every workout of the training log. In progress ones are never written (they
     *   change with every set; one written before and resumed keeps its session until it ends
     *   again), nor finished ones without a completed set (a past workout just created, or one
     *   emptied: its session is deleted).
     * @param exercise what is known about an exercise id (catalog, custom or unknown).
     * @param stored this app's exercise sessions in Health Connect, or null when they could not be
     *   read: the plan then relies on the [ledger] alone. (Android 13 and lower may only return the
     *   last 30 days after a re-grant, which is why deletions never depend on this list.)
     * @param ledger what this installation knows, by workout id.
     * @param writeEnabled the "Save workouts" switch. When off nothing is written, but sessions of
     *   deleted or emptied workouts are still deleted, so Health Connect never keeps stale records.
     * @param now wall clock: quiet periods, and the floor of new versions.
     */
    fun plan(
        workouts: List<Workout>,
        exercise: (String) -> WorkoutExerciseInfo,
        stored: List<StoredSession>?,
        ledger: Map<String, WorkoutLedgerEntry>,
        writeEnabled: Boolean,
        now: Long,
    ): WorkoutExportPlan {
        val inHc: Map<String, Long>? = stored?.let { list ->
            val versions = HashMap<String, Long>()
            for (s in list) {
                val id = workoutIdOf(s.clientRecordId) ?: continue
                versions[id] = maxOf(versions[id] ?: Long.MIN_VALUE, s.version)
            }
            versions
        }
        val writes = ArrayList<PlannedSession>()
        val deletes = ArrayList<String>()
        val remove = HashSet<String>()
        val undelete = HashSet<String>()
        var nextRunAt: Long? = null
        for (w in workouts) {
            val entry = ledger[w.id]
            if (entry?.pendingDelete == true) undelete += w.id
            if (w.inProgress) continue
            val present = inHc?.get(w.id)
            val empty = w.doneSets == 0
            val due = when {
                empty -> entry?.written == true || present != null
                !writeEnabled -> false
                entry == null || !entry.written || entry.sourceUpdatedAt != w.updatedAt -> true
                // A new mapping only reaches sessions still there: one the user deleted stays deleted.
                entry.format != FORMAT -> present != null
                // Up to date, or deleted by the user in Health Connect: left alone.
                else -> false
            }
            if (!due) continue
            val quietEnd = w.updatedAt + QUIET_MS
            if (now < quietEnd && w.updatedAt - now <= MAX_CLOCK_SKEW_MS) {
                nextRunAt = minOf(nextRunAt ?: quietEnd, quietEnd)
                continue
            }
            if (empty) {
                deletes += clientRecordId(w.id)
                remove += w.id
            } else {
                writes += session(w, exercise).copy(version = exportVersion(now, entry, present))
            }
        }
        val existing = workouts.mapTo(HashSet()) { it.id }
        for (entry in ledger.values) {
            // Deleted by client id whether or not the read above returned it (absent ids are a no-op).
            if (entry.pendingDelete && entry.workoutId !in existing) {
                deletes += clientRecordId(entry.workoutId)
                remove += entry.workoutId
            }
        }
        val known = inHc?.keys ?: ledger.values.filter { it.written && !it.pendingDelete }.mapTo(HashSet()) { it.workoutId }
        val saved = (known - remove) + writes.map { it.workoutId }
        return WorkoutExportPlan(
            writes = writes,
            deletes = deletes.sorted(),
            ledgerRemove = remove,
            ledgerUndelete = undelete - remove - writes.mapTo(HashSet()) { it.workoutId },
            nextRunAt = nextRunAt,
            savedCount = saved.size,
        )
    }

    /**
     * `clientRecordVersion` of a new write: above the ledger's and Health Connect's (which may come
     * from an earlier installation) and at least [now], so it always replaces what is there, even
     * after the workout's `updatedAt` went back.
     */
    fun exportVersion(now: Long, entry: WorkoutLedgerEntry?, present: Long?): Long =
        maxOf(now, (entry?.version ?: 0L) + 1, (present ?: 0L) + 1)

    /** Workout id → finished, as [disappeared] compares them. */
    fun snapshot(workouts: List<Workout>): Map<String, Boolean> = workouts.associate { it.id to !it.inProgress }

    /** Workouts seen before and now missing, split by whether they were finished when last seen. */
    data class Disappeared(val finished: Set<String>, val inProgress: Set<String>) {
        val isEmpty: Boolean get() = finished.isEmpty() && inProgress.isEmpty()
    }

    /**
     * Workouts of [previous] (a [snapshot] taken while this process was running) that are missing
     * from [current]: deleted in the app or removed by the cloud backup. Only these sessions are
     * ever deleted. A finished one may have a session (even from an earlier installation); one in
     * progress only matters if this installation wrote it before it was resumed.
     */
    fun disappeared(previous: Map<String, Boolean>, current: List<Workout>): Disappeared {
        val now = current.mapTo(HashSet()) { it.id }
        val gone = previous.filterKeys { it !in now }
        return Disappeared(
            finished = gone.filterValues { it }.keys,
            inProgress = gone.filterValues { !it }.keys,
        )
    }

    /**
     * The record content of a finished workout (version: its `updatedAt`, replaced by [plan]): the
     * session from its start to its end, pauses included, with them as PAUSE segments.
     */
    fun session(workout: Workout, exercise: (String) -> WorkoutExerciseInfo): PlannedSession {
        val start = workout.startedAt
        val end = workout.endedAt?.takeIf { it > start } ?: (start + MIN_SESSION_MS)
        val logged = workout.entries.filter { e -> e.sets.any { it.done } }
        val infos = logged.map { exercise(it.exerciseId) }
        val type = sessionType(infos)
        val sets = logged.zip(infos).flatMap { (entry, info) ->
            val segmentType = segmentType(info, type)
            entry.sets.filter { it.done }.map { s -> LoggedSet(segmentType, (s.reps ?: 0).coerceAtLeast(0), nominalMs(s), s.doneAt) }
        }
        return PlannedSession(
            workoutId = workout.id,
            clientRecordId = clientRecordId(workout.id),
            version = workout.updatedAt,
            sourceUpdatedAt = workout.updatedAt,
            start = start,
            end = end,
            exerciseType = type,
            title = workout.name?.trim()?.takeIf { it.isNotEmpty() } ?: activityTitle(infos),
            notes = workout.note?.trim()?.takeIf { it.isNotEmpty() },
            segments = segments(sets, start, end, WorkoutTime.normalize(workout.pauses, start, end)),
        )
    }

    /**
     * The title of an unnamed workout whose logged entries are all sports or activities: their
     * names ([WorkoutExerciseInfo.title]) in the order logged, each once ("Fútbol + Caminata").
     * Stretches are left out, as in [sessionType]: padel with a cool-down is still "Pádel".
     */
    private fun activityTitle(infos: List<WorkoutExerciseInfo>): String? {
        val training = infos.filterNot { it.mobility }
        if (training.isEmpty()) return null
        val titles = training.map { it.title ?: return null }
        return titles.distinct().joinToString(TITLE_SEPARATOR)
    }

    /** Between the names of the sports of one workout ("Fútbol + Caminata"). */
    const val TITLE_SEPARATOR = " + "

    /**
     * Strength training, unless every logged exercise is cardio: then their common Health Connect
     * type (a treadmill-only workout is "Running (treadmill)"), or "Other workout" when they differ
     * or one has no type (a custom cardio machine), since none of them is strength training.
     * Stretches are left out of that choice (their segments are accepted by every session type), so
     * a cool-down does not turn a run into strength training; a workout of stretches only is
     * "Stretching".
     */
    fun sessionType(logged: List<WorkoutExerciseInfo>): Int {
        val training = logged.filterNot { it.mobility }
        if (training.isEmpty() && logged.isNotEmpty()) return ExerciseSessionRecord.EXERCISE_TYPE_STRETCHING
        if (training.isEmpty() || !training.all { it.cardio }) return ExerciseSessionRecord.EXERCISE_TYPE_STRENGTH_TRAINING
        return training.mapTo(HashSet()) { it.hcSession }.singleOrNull() ?: ExerciseSessionRecord.EXERCISE_TYPE_OTHER_WORKOUT
    }

    /**
     * The exercise's own segment type (weightlifting for a custom strength exercise, "other
     * workout" for a custom cardio one), downgraded to "other workout" — accepted by every session
     * type — when the session type does not allow it.
     *
     * That is how a mixed workout (a treadmill warm-up, then weights) is written: one strength
     * session whose cardio sets keep their time span as "other workout" segments, since Health
     * Connect only accepts treadmill, bike, elliptical, rower or stair segments in a session of
     * that same type. Writing the cardio part as a second session was rejected: it would overlap
     * the strength session (other apps would count that time twice) or split one gym visit into
     * several workouts.
     */
    fun segmentType(info: WorkoutExerciseInfo, sessionType: Int): Int {
        val preferred = info.hcSegment?.takeIf { it in KNOWN_SEGMENTS }
            ?: if (info.cardio) ExerciseSegment.EXERCISE_SEGMENT_TYPE_OTHER_WORKOUT else ExerciseSegment.EXERCISE_SEGMENT_TYPE_WEIGHTLIFTING
        return if (compatible(preferred, sessionType)) preferred else ExerciseSegment.EXERCISE_SEGMENT_TYPE_OTHER_WORKOUT
    }

    /**
     * Whether `ExerciseSessionRecord` accepts a segment of [segmentType] in a session of
     * [sessionType]. Mirrors the private rules of connect-client 1.1.0 (its constructor throws on
     * an incompatible segment); WorkoutExportPlanTest checks every pair against the library.
     */
    fun compatible(segmentType: Int, sessionType: Int): Boolean =
        sessionType in UNIVERSAL_SESSIONS || segmentType in UNIVERSAL_SEGMENTS || SESSION_SEGMENTS[sessionType]?.contains(segmentType) == true

    /** Expected length of a set: its duration, else estimated from its repetitions. */
    fun nominalMs(set: WorkoutSet): Long {
        val seconds = set.durationSec ?: 0
        val reps = set.reps ?: 0
        return when {
            seconds > 0 -> seconds * 1000L
            reps > 0 -> (reps * MS_PER_REP).coerceIn(MIN_SET_MS, MAX_SET_MS)
            else -> DEFAULT_SET_MS
        }
    }

    /** A completed set to place on the session's timeline. [doneAt] is when it was ticked, if known. */
    data class LoggedSet(val type: Int, val repetitions: Int, val durationMs: Long, val doneAt: Long?)

    /**
     * One segment per completed set, inside [start, end], sorted and without overlaps (what
     * `ExerciseSessionRecord` requires). A set ends when it was ticked done (moved into the session
     * if outside it) and lasts its [LoggedSet.durationMs]. Sets without a time (logged afterwards) are spread
     * evenly, in log order, between their timed neighbours or the session bounds. Sets are then
     * laid out from the last one backwards: one that would overlap the next is moved earlier, as
     * sets ticked together were really done one after the other, and one that no longer fits after
     * the session start is dropped.
     *
     * [pauses] (sorted, apart and inside the session: [WorkoutTime.normalize]) become PAUSE segments
     * (the short ones are only left out), and no set overlaps one: a set ending inside a pause (ticked
     * while paused, which resumes the workout) was done before it and ends where the pause began, and
     * a set never starts before the end of the pause that precedes it (it is shortened instead).
     */
    fun segments(sets: List<LoggedSet>, start: Long, end: Long, pauses: List<WorkoutPause> = emptyList()): List<PlannedSegment> {
        if (end <= start) return emptyList()
        val gaps = pauses.map { it.start to minOf(it.end ?: end, end) }
        val pauseSegments = gaps.filter { (from, to) -> to - from >= MIN_SEGMENT_MS }
            .map { (from, to) -> PlannedSegment(from, to, ExerciseSegment.EXERCISE_SEGMENT_TYPE_PAUSE, 0) }
        if (sets.isEmpty()) return pauseSegments
        val n = sets.size
        val ends = LongArray(n)
        var i = 0
        while (i < n) {
            val known = sets[i].doneAt
            if (known != null) {
                ends[i] = known.coerceIn(start, end)
                i++
                continue
            }
            var j = i
            while (j < n && sets[j].doneAt == null) j++
            val from = if (i > 0) ends[i - 1] else start
            val to = if (j < n) checkNotNull(sets[j].doneAt).coerceIn(start, end) else end
            val parts = (j - i + 1).toLong()
            for (k in i until j) ends[k] = from + (to - from) * (k - i + 1) / parts
            i = j
        }
        // A set ends no earlier than its own length after the session start: one ticked before the
        // start (the start time was edited afterwards) still fits instead of shrinking to nothing.
        for (k in 0 until n) ends[k] = ends[k].coerceIn(minOf(start + sets[k].durationMs, end), end)
        val placed = ArrayList<PlannedSegment>(n)
        var limit = end
        for (index in (0 until n).sortedWith(compareBy({ ends[it] }, { it })).asReversed()) {
            val set = sets[index]
            var segmentEnd = minOf(ends[index], limit)
            gaps.firstOrNull { (from, to) -> from < segmentEnd && segmentEnd <= to }?.let { (from, _) -> segmentEnd = from }
            val resumedAt = gaps.lastOrNull { (_, to) -> to <= segmentEnd }?.second ?: start
            val segmentStart = maxOf(segmentEnd - set.durationMs, start, resumedAt)
            if (segmentEnd - segmentStart < MIN_SEGMENT_MS) continue
            placed += PlannedSegment(segmentStart, segmentEnd, set.type, set.repetitions)
            limit = segmentStart
        }
        return if (pauseSegments.isEmpty()) placed.reversed() else (placed + pauseSegments).sortedBy { it.start }
    }

    // ---- Segment / session compatibility (connect-client 1.1.0) ---------------------------

    private val UNIVERSAL_SESSIONS: Set<Int> = with(ExerciseSessionRecord) {
        setOf(EXERCISE_TYPE_BOOT_CAMP, EXERCISE_TYPE_HIGH_INTENSITY_INTERVAL_TRAINING, EXERCISE_TYPE_OTHER_WORKOUT)
    }

    private val UNIVERSAL_SEGMENTS: Set<Int> = with(ExerciseSegment) {
        setOf(
            EXERCISE_SEGMENT_TYPE_OTHER_WORKOUT,
            EXERCISE_SEGMENT_TYPE_PAUSE,
            EXERCISE_SEGMENT_TYPE_REST,
            EXERCISE_SEGMENT_TYPE_STRETCHING,
            EXERCISE_SEGMENT_TYPE_UNKNOWN,
        )
    }

    /** Gym exercises, allowed in strength training, weightlifting, calisthenics and gymnastics. */
    private val EXERCISE_SEGMENTS: Set<Int> = with(ExerciseSegment) {
        setOf(
            EXERCISE_SEGMENT_TYPE_ARM_CURL,
            EXERCISE_SEGMENT_TYPE_BACK_EXTENSION,
            EXERCISE_SEGMENT_TYPE_BALL_SLAM,
            EXERCISE_SEGMENT_TYPE_BARBELL_SHOULDER_PRESS,
            EXERCISE_SEGMENT_TYPE_BENCH_PRESS,
            EXERCISE_SEGMENT_TYPE_BENCH_SIT_UP,
            EXERCISE_SEGMENT_TYPE_BURPEE,
            EXERCISE_SEGMENT_TYPE_CRUNCH,
            EXERCISE_SEGMENT_TYPE_DEADLIFT,
            EXERCISE_SEGMENT_TYPE_DOUBLE_ARM_TRICEPS_EXTENSION,
            EXERCISE_SEGMENT_TYPE_DUMBBELL_CURL_LEFT_ARM,
            EXERCISE_SEGMENT_TYPE_DUMBBELL_CURL_RIGHT_ARM,
            EXERCISE_SEGMENT_TYPE_DUMBBELL_FRONT_RAISE,
            EXERCISE_SEGMENT_TYPE_DUMBBELL_LATERAL_RAISE,
            EXERCISE_SEGMENT_TYPE_DUMBBELL_ROW,
            EXERCISE_SEGMENT_TYPE_DUMBBELL_TRICEPS_EXTENSION_LEFT_ARM,
            EXERCISE_SEGMENT_TYPE_DUMBBELL_TRICEPS_EXTENSION_RIGHT_ARM,
            EXERCISE_SEGMENT_TYPE_DUMBBELL_TRICEPS_EXTENSION_TWO_ARM,
            EXERCISE_SEGMENT_TYPE_FORWARD_TWIST,
            EXERCISE_SEGMENT_TYPE_FRONT_RAISE,
            EXERCISE_SEGMENT_TYPE_HIP_THRUST,
            EXERCISE_SEGMENT_TYPE_HULA_HOOP,
            EXERCISE_SEGMENT_TYPE_JUMPING_JACK,
            EXERCISE_SEGMENT_TYPE_JUMP_ROPE,
            EXERCISE_SEGMENT_TYPE_KETTLEBELL_SWING,
            EXERCISE_SEGMENT_TYPE_LATERAL_RAISE,
            EXERCISE_SEGMENT_TYPE_LAT_PULL_DOWN,
            EXERCISE_SEGMENT_TYPE_LEG_CURL,
            EXERCISE_SEGMENT_TYPE_LEG_EXTENSION,
            EXERCISE_SEGMENT_TYPE_LEG_PRESS,
            EXERCISE_SEGMENT_TYPE_LEG_RAISE,
            EXERCISE_SEGMENT_TYPE_LUNGE,
            EXERCISE_SEGMENT_TYPE_MOUNTAIN_CLIMBER,
            EXERCISE_SEGMENT_TYPE_PLANK,
            EXERCISE_SEGMENT_TYPE_PULL_UP,
            EXERCISE_SEGMENT_TYPE_PUNCH,
            EXERCISE_SEGMENT_TYPE_SHOULDER_PRESS,
            EXERCISE_SEGMENT_TYPE_SINGLE_ARM_TRICEPS_EXTENSION,
            EXERCISE_SEGMENT_TYPE_SIT_UP,
            EXERCISE_SEGMENT_TYPE_SQUAT,
            EXERCISE_SEGMENT_TYPE_UPPER_TWIST,
            EXERCISE_SEGMENT_TYPE_WEIGHTLIFTING,
        )
    }

    private val SWIMMING_SEGMENTS: Set<Int> = with(ExerciseSegment) {
        setOf(
            EXERCISE_SEGMENT_TYPE_SWIMMING_BACKSTROKE,
            EXERCISE_SEGMENT_TYPE_SWIMMING_BREASTSTROKE,
            EXERCISE_SEGMENT_TYPE_SWIMMING_BUTTERFLY,
            EXERCISE_SEGMENT_TYPE_SWIMMING_FREESTYLE,
            EXERCISE_SEGMENT_TYPE_SWIMMING_MIXED,
            EXERCISE_SEGMENT_TYPE_SWIMMING_OTHER,
        )
    }

    /** Segments each non-universal session type accepts besides the universal ones. */
    private val SESSION_SEGMENTS: Map<Int, Set<Int>> = with(ExerciseSessionRecord) {
        with(ExerciseSegment) {
            mapOf(
                EXERCISE_TYPE_BIKING to setOf(EXERCISE_SEGMENT_TYPE_BIKING),
                EXERCISE_TYPE_BIKING_STATIONARY to setOf(EXERCISE_SEGMENT_TYPE_BIKING_STATIONARY),
                EXERCISE_TYPE_CALISTHENICS to EXERCISE_SEGMENTS,
                EXERCISE_TYPE_ELLIPTICAL to setOf(EXERCISE_SEGMENT_TYPE_ELLIPTICAL),
                EXERCISE_TYPE_EXERCISE_CLASS to setOf(
                    EXERCISE_SEGMENT_TYPE_BIKING_STATIONARY,
                    EXERCISE_SEGMENT_TYPE_HIGH_INTENSITY_INTERVAL_TRAINING,
                    EXERCISE_SEGMENT_TYPE_PILATES,
                    EXERCISE_SEGMENT_TYPE_YOGA,
                ),
                EXERCISE_TYPE_GYMNASTICS to EXERCISE_SEGMENTS,
                EXERCISE_TYPE_HIKING to setOf(EXERCISE_SEGMENT_TYPE_WALKING, EXERCISE_SEGMENT_TYPE_WHEELCHAIR),
                EXERCISE_TYPE_PILATES to setOf(EXERCISE_SEGMENT_TYPE_PILATES),
                EXERCISE_TYPE_ROWING_MACHINE to setOf(EXERCISE_SEGMENT_TYPE_ROWING_MACHINE),
                EXERCISE_TYPE_RUNNING to setOf(EXERCISE_SEGMENT_TYPE_RUNNING, EXERCISE_SEGMENT_TYPE_WALKING),
                EXERCISE_TYPE_RUNNING_TREADMILL to setOf(EXERCISE_SEGMENT_TYPE_RUNNING_TREADMILL),
                EXERCISE_TYPE_STRENGTH_TRAINING to EXERCISE_SEGMENTS,
                EXERCISE_TYPE_STAIR_CLIMBING to setOf(EXERCISE_SEGMENT_TYPE_STAIR_CLIMBING),
                EXERCISE_TYPE_STAIR_CLIMBING_MACHINE to setOf(EXERCISE_SEGMENT_TYPE_STAIR_CLIMBING_MACHINE),
                EXERCISE_TYPE_SWIMMING_OPEN_WATER to SWIMMING_SEGMENTS + EXERCISE_SEGMENT_TYPE_SWIMMING_OPEN_WATER,
                EXERCISE_TYPE_SWIMMING_POOL to SWIMMING_SEGMENTS + EXERCISE_SEGMENT_TYPE_SWIMMING_POOL,
                EXERCISE_TYPE_WALKING to setOf(EXERCISE_SEGMENT_TYPE_WALKING),
                EXERCISE_TYPE_WHEELCHAIR to setOf(EXERCISE_SEGMENT_TYPE_WHEELCHAIR),
                EXERCISE_TYPE_WEIGHTLIFTING to EXERCISE_SEGMENTS,
                EXERCISE_TYPE_YOGA to setOf(EXERCISE_SEGMENT_TYPE_YOGA),
            )
        }
    }

    /** Every segment type of the library: anything else in the catalog is written as "other workout". */
    private val KNOWN_SEGMENTS: Set<Int> = UNIVERSAL_SEGMENTS + EXERCISE_SEGMENTS + SWIMMING_SEGMENTS + SESSION_SEGMENTS.values.flatten()
}
