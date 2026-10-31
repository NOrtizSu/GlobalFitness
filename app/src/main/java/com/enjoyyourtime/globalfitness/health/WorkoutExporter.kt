package com.enjoyyourtime.globalfitness.health

import android.os.RemoteException
import android.util.Log
import androidx.annotation.StringRes
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.records.ExerciseSegment
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.metadata.DataOrigin
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import com.enjoyyourtime.globalfitness.R
import com.enjoyyourtime.globalfitness.UiText
import com.enjoyyourtime.globalfitness.training.data.TrainingRepository
import com.enjoyyourtime.globalfitness.training.data.TrainingSettings
import com.enjoyyourtime.globalfitness.training.model.Workout
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import java.time.Instant
import java.time.ZoneId

data class WorkoutExportState(
    /** Workouts of this app in Health Connect after the last run. */
    val savedCount: Int = 0,
    /** Message of the last failed run (null after a clean one, or when there is nothing to do). */
    val error: UiText? = null,
)

/**
 * Saves the finished workouts of the training log to Health Connect, one `ExerciseSessionRecord`
 * per workout ([WorkoutExportPlanner] decides what and how), and deletes a workout's session when
 * the workout is deleted in the app (or on another phone, through the cloud backup).
 *
 * Runs on start, after every change of the training log (debounced; sets ticked in a workout in
 * progress cost nothing, those workouts are not written), when a quiet period ends and when the
 * app comes to the foreground. After a failure it backs off ([backoffMs]) instead of trying again
 * on every resume. Needs only the exercise write permission: an app can read back its own records
 * without the read permission, and if that read fails anyway the plan relies on the ledger.
 *
 * Deletions: a session is deleted only when this process saw its workout disappear from the
 * training log ([WorkoutExportPlanner.disappeared]); that is written to the ledger at once, so it
 * survives the process. A training log that is suddenly empty (a database recreated after
 * corruption) or never contained a workout deletes nothing.
 *
 * Turning the switch off stops writing but deletes nothing: what is already in Health Connect is
 * the user's training history there (they can remove it in Health Connect), as with the scale
 * switch. A mixed workout stays one strength session; see [WorkoutExportPlanner.segmentType]. A
 * workout that is one sport is saved as that sport, titled with its name unless it has its own (a
 * workout of several sports: their names, "Football + Walking"; see [WorkoutExerciseInfo.of]).
 */
class WorkoutExporter(
    private val gateway: HealthConnectGateway,
    private val cache: HealthCache,
    private val repository: TrainingRepository,
    private val settings: TrainingSettings,
    private val scope: CoroutineScope,
    private val ownPackage: String,
    /** Localized title for a workout without a name, by exercise type (resolved at export time). */
    private val genericTitle: (exerciseType: Int) -> String,
    /** An exercise's name in the current language: the title of a workout that is one sport (see [WorkoutExerciseInfo.title]). */
    private val exerciseName: (UiText) -> String? = { null },
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val clock: () -> Long = System::currentTimeMillis,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
) {
    private val mutex = Mutex()
    private val _state = MutableStateFlow(WorkoutExportState())
    val state: StateFlow<WorkoutExportState> = _state.asStateFlow()

    /** The user's choice: null until the switch is touched, which means on once the permission is granted. */
    val choice: StateFlow<Boolean?> get() = settings.healthExportChoice

    /** Inputs of the last clean run that left nothing waiting: another run with them has nothing to do. */
    @Volatile
    private var lastInputs: Inputs? = null

    @Volatile
    private var lastRunAt = 0L

    /** Workouts seen by this process (id → finished); only touched by the observing coroutine. */
    private var seen: Map<String, Boolean>? = null

    /** Guards the back-off and the scheduled run below. */
    private val timing = Any()
    private var failures = 0
    private var retryAt = 0L
    private var grantedAtFailure: Set<String>? = null
    private var scheduled: Job? = null
    private var scheduledAt = Long.MAX_VALUE

    /** Finished workouts with their versions and the ids of all workouts (for the deletions). */
    private data class Inputs(val enabled: Boolean, val finished: Map<String, Long>, val ids: Set<String>) {
        companion object {
            fun of(enabled: Boolean, workouts: List<Workout>) = Inputs(
                enabled = enabled,
                finished = workouts.filterNot { it.inProgress }.associate { it.id to it.updatedAt },
                ids = workouts.mapTo(HashSet()) { it.id },
            )
        }
    }

    fun start() {
        scope.launch {
            val count = withContext(io) { cache.workoutLedgerCount() }
            _state.update { it.copy(savedCount = count) }
        }
        scope.launch {
            repository.loaded.first { it }
            repository.workouts.collect { observe(it) }
        }
        scope.launch {
            combine(
                repository.loaded,
                repository.workouts,
                settings.healthExportChoice,
                gateway.granted,
            ) { loaded, _, _, granted -> loaded && HealthPermissions.canExportWorkouts(granted) }
                .collectLatest { ready ->
                    if (!ready) return@collectLatest
                    delay(DEBOUNCE_MS)
                    runCatchingReconcile(force = false)
                }
        }
    }

    /**
     * App in the foreground: retries a run that failed (once its back-off is over) and, every
     * [REFRESH_MS], reads Health Connect again even if nothing changed in the app.
     */
    fun onForeground() {
        scope.launch { runCatchingReconcile(force = clock() - lastRunAt >= REFRESH_MS) }
    }

    /** "Save workouts to Health Connect". */
    fun setEnabled(enabled: Boolean) = settings.setHealthExport(enabled)

    /** Notes the workouts that disappeared since the last emission as deletions to do (under the lock, see [reconcile]). */
    private suspend fun observe(workouts: List<Workout>) {
        val previous = seen
        seen = WorkoutExportPlanner.snapshot(workouts)
        if (previous == null) return
        val gone = WorkoutExportPlanner.disappeared(previous, workouts)
        if (gone.isEmpty) return
        // Under the lock: a run in progress must not overwrite the mark with the entry it is writing.
        withContext(io) { mutex.withLock { cache.markWorkoutsDeleted(gone.finished, gone.inProgress, clock()) } }
    }

    private suspend fun runCatchingReconcile(force: Boolean) {
        val granted = gateway.granted.value
        // A new grant (the permission came back) ends the back-off.
        val waitUntil = synchronized(timing) { if (grantedAtFailure == granted) retryAt else 0L }
        if (clock() < waitUntil) {
            schedule(waitUntil)
            return
        }
        try {
            val next = reconcile(force)
            synchronized(timing) {
                failures = 0
                retryAt = 0L
                grantedAtFailure = null
            }
            next?.let(::schedule)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Health Connect workout export failed", e)
            val retry = synchronized(timing) {
                failures++
                (clock() + backoffMs(failures, isRateLimit(e))).also {
                    retryAt = it
                    grantedAtFailure = granted
                }
            }
            _state.update { it.copy(error = UiText.res(R.string.hc_export_failed, HealthSync.describe(e))) }
            schedule(retry)
        }
    }

    /** Runs the export at [at] (end of a quiet period or of a back-off); the earliest pending run wins. */
    private fun schedule(at: Long) {
        synchronized(timing) {
            if (scheduled?.isActive == true && scheduledAt <= at) return
            scheduled?.cancel()
            scheduledAt = at
            scheduled = scope.launch {
                delay((at - clock()).coerceAtLeast(0L))
                synchronized(timing) {
                    if (scheduled === coroutineContext[Job]) {
                        scheduled = null
                        scheduledAt = Long.MAX_VALUE
                    }
                }
                runCatchingReconcile(force = false)
            }
        }
    }

    /** One run; returns when to run again for a quiet period still running, or null. */
    private suspend fun reconcile(force: Boolean): Long? = withContext(io) {
        mutex.withLock {
            if (!repository.loaded.value) return@withLock null
            if (!HealthPermissions.canExportWorkouts(gateway.granted.value)) {
                _state.update { it.copy(error = null) }
                return@withLock null
            }
            val enabled = HealthSettings.exportEnabled(settings.healthExportChoice.value, canExport = true)
            val workouts = repository.workouts.value
            val inputs = Inputs.of(enabled, workouts)
            if (!force && inputs == lastInputs) return@withLock null
            val client = gateway.client()
            if (client == null) {
                _state.update { it.copy(error = null) }
                return@withLock null
            }
            val now = clock()
            val plan = WorkoutExportPlanner.plan(
                workouts = workouts,
                exercise = ::exerciseInfo,
                stored = readOwnSessions(client),
                ledger = cache.loadWorkoutLedger(),
                writeEnabled = enabled,
                now = now,
            )
            if (!plan.isEmpty) execute(client, plan)
            lastInputs = if (plan.nextRunAt == null) inputs else null
            lastRunAt = now
            _state.value = WorkoutExportState(savedCount = plan.savedCount)
            plan.nextRunAt
        }
    }

    private fun exerciseInfo(id: String): WorkoutExerciseInfo = WorkoutExerciseInfo.of(repository.exercise(id), exerciseName)

    /** This app's exercise sessions in Health Connect (all pages); null when they cannot be read now. */
    private suspend fun readOwnSessions(client: HealthConnectClient): List<StoredSession>? = try {
        val sessions = ArrayList<StoredSession>()
        var pageToken: String? = null
        do {
            val response = client.readRecords(
                ReadRecordsRequest(
                    recordType = ExerciseSessionRecord::class,
                    timeRangeFilter = TimeRangeFilter.after(Instant.EPOCH),
                    dataOriginFilter = setOf(DataOrigin(ownPackage)),
                    pageSize = READ_PAGE,
                    pageToken = pageToken,
                ),
            )
            response.records.mapTo(sessions) { StoredSession(it.metadata.clientRecordId, it.metadata.clientRecordVersion) }
            pageToken = response.pageToken
        } while (pageToken != null)
        sessions
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.i(TAG, "Own exercise sessions not readable, planning from the ledger: ${e.message}")
        null
    }

    private suspend fun execute(client: HealthConnectClient, plan: WorkoutExportPlan) {
        val now = clock()
        // Deleting a client id Health Connect does not have is a no-op; the ledger keeps the
        // deletion until the call succeeded.
        for (chunk in plan.deletes.chunked(DELETE_CHUNK)) {
            client.deleteRecords(ExerciseSessionRecord::class, emptyList(), chunk)
        }
        cache.removeWorkoutLedger(plan.ledgerRemove)
        cache.undeleteWorkouts(plan.ledgerUndelete)
        val zone = zone()
        for (chunk in plan.writes.chunked(WRITE_CHUNK)) {
            val records = chunk.map { buildRecord(it, zone, genericTitle) }
            try {
                client.insertRecords(records)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (isTransient(e)) throw e
                Log.w(TAG, "Health Connect rejected ${chunk.size} sessions at once, writing them one by one", e)
                insertOneByOne(client, chunk, records, now)
                continue
            }
            cache.putWorkoutLedger(chunk.map { it.ledgerEntry() }, now)
        }
    }

    /**
     * Writes a rejected batch record by record, so one session Health Connect refuses does not
     * hold back the others. The refused ones are noted in the ledger as handled (not retried until
     * their workout changes). If every one fails, the cause is more likely Health Connect itself:
     * the error is passed on and the run is retried after a back-off.
     */
    private suspend fun insertOneByOne(client: HealthConnectClient, chunk: List<PlannedSession>, records: List<ExerciseSessionRecord>, now: Long) {
        val rejected = ArrayList<PlannedSession>()
        var lastError: Exception? = null
        for ((planned, record) in chunk.zip(records)) {
            try {
                client.insertRecords(listOf(record))
                cache.putWorkoutLedger(listOf(planned.ledgerEntry()), now)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (isTransient(e)) throw e
                rejected += planned
                lastError = e
            }
        }
        if (rejected.size == chunk.size && lastError != null) throw lastError
        for (p in rejected) Log.w(TAG, "Health Connect rejected ${p.clientRecordId}; skipped until the workout changes")
        cache.putWorkoutLedger(rejected.map { it.ledgerEntry() }, now)
    }

    companion object {
        private const val TAG = "WorkoutExporter"
        private const val DEBOUNCE_MS = 2_000L
        private const val REFRESH_MS = 15 * 60_000L
        private const val WRITE_CHUNK = 25
        private const val DELETE_CHUNK = 500
        private const val READ_PAGE = 500
        private const val BACKOFF_BASE_MS = 60_000L
        private const val BACKOFF_MAX_MS = 60 * 60_000L
        private const val RATE_LIMIT_BACKOFF_MS = 15 * 60_000L

        /** Title of a workout without a name: the name of its exercise type ("Strength training"). */
        @StringRes
        fun genericTitleRes(exerciseType: Int): Int =
            if (exerciseType == ExerciseSessionRecord.EXERCISE_TYPE_OTHER_WORKOUT) R.string.hc_workout_title else HealthMappings.exerciseName(exerciseType)

        /**
         * Wait after [failures] failures in a row: 1, 2, 4... minutes, at most an hour; at least 15
         * minutes when Health Connect rate-limits the app, since its quotas refill slowly.
         */
        fun backoffMs(failures: Int, rateLimited: Boolean): Long {
            val exponential = (BACKOFF_BASE_MS shl (failures - 1).coerceIn(0, 6)).coerceAtMost(BACKOFF_MAX_MS)
            return if (rateLimited) maxOf(exponential, RATE_LIMIT_BACKOFF_MS) else exponential
        }

        fun isRateLimit(e: Throwable): Boolean {
            val message = e.message.orEmpty()
            return message.contains("quota", ignoreCase = true) || message.contains("rate limit", ignoreCase = true)
        }

        /**
         * A failure of Health Connect itself (permission revoked, quota, not responding, app in the
         * background) rather than of one record: never blamed on a record, the run is retried later.
         */
        fun isTransient(e: Throwable): Boolean =
            e is SecurityException || e is RemoteException || e is IOException || isRateLimit(e) ||
                e.message.orEmpty().contains("background", ignoreCase = true)

        /**
         * The Health Connect record of a planned session, entered by hand in the app (manual entry,
         * no device, like manual weights). The planner only produces segments the library accepts;
         * should a newer library tighten its rules, the session is still saved, without segments.
         */
        fun buildRecord(p: PlannedSession, zone: ZoneId, genericTitle: (Int) -> String): ExerciseSessionRecord {
            val start = Instant.ofEpochMilli(p.start)
            val end = Instant.ofEpochMilli(p.end)
            val metadata = Metadata.manualEntry(p.clientRecordId, p.version)
            val title = p.title ?: genericTitle(p.exerciseType)
            fun record(segments: List<ExerciseSegment>) = ExerciseSessionRecord(
                startTime = start,
                startZoneOffset = zone.rules.getOffset(start),
                endTime = end,
                endZoneOffset = zone.rules.getOffset(end),
                metadata = metadata,
                exerciseType = p.exerciseType,
                title = title,
                notes = p.notes,
                segments = segments,
            )
            return try {
                record(p.segments.map { ExerciseSegment(Instant.ofEpochMilli(it.start), Instant.ofEpochMilli(it.end), it.type, it.repetitions) })
            } catch (e: IllegalArgumentException) {
                Log.w(TAG, "Segments of ${p.clientRecordId} rejected, saving the session without them", e)
                record(emptyList())
            }
        }
    }
}
