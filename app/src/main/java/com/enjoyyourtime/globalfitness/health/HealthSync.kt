package com.enjoyyourtime.globalfitness.health

import android.os.RemoteException
import android.util.Log
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.aggregate.AggregateMetric
import androidx.health.connect.client.aggregate.AggregationResult
import androidx.health.connect.client.changes.DeletionChange
import androidx.health.connect.client.changes.UpsertionChange
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.BodyFatRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.HeartRateVariabilityRmssdRecord
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.RespiratoryRateRecord
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.request.AggregateGroupByPeriodRequest
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.request.ChangesTokenRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import com.enjoyyourtime.globalfitness.R
import com.enjoyyourtime.globalfitness.UiText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import java.io.IOException
import java.time.Instant
import java.time.LocalDate
import java.time.Period
import java.time.ZoneId
import kotlin.reflect.KClass

data class SyncState(
    val running: Boolean = false,
    val lastSyncAt: Long? = null,
    /** Message of the last failed sync (null after a clean one). */
    val error: UiText? = null,
)

/**
 * Imports wearable data from Health Connect into [HealthCache].
 *
 * First run per data type: full read of the last 30 days (365 with the history permission),
 * then incremental sync with one changes token per type. An expired or invalid token, or a
 * larger window after the history permission is granted, triggers a full re-read. Daily
 * steps / distance / calories come from the aggregate API (de-duplicated by Health Connect):
 * changes only tell which days to re-aggregate. Each type is synced independently, so a
 * partial grant or a failure in one type does not affect the others.
 */
class HealthSync(
    private val gateway: HealthConnectGateway,
    private val cache: HealthCache,
    private val settings: HealthSettings,
    private val scope: CoroutineScope,
    private val ownPackage: String,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val clock: () -> Long = System::currentTimeMillis,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
) {
    private val mutex = Mutex()
    private val _state = MutableStateFlow(SyncState(lastSyncAt = settings.lastSyncAt.value))
    val state: StateFlow<SyncState> = _state.asStateFlow()

    @Volatile
    private var lastAttemptAt = 0L

    /** Syncs unless one ran in the last [THROTTLE_MS] (app start / resume). */
    fun syncIfStale() {
        if (clock() - lastAttemptAt < THROTTLE_MS) return
        requestSync()
    }

    /** "Sync now". */
    fun requestSync(): Job = scope.launch { sync() }

    /** Runs one sync; returns false when skipped (already running / unavailable) or failed. */
    suspend fun sync(): Boolean {
        if (!mutex.tryLock()) return false
        try {
            lastAttemptAt = clock()
            if (gateway.client() == null) {
                _state.update { it.copy(running = false, error = UiText.res(R.string.hc_not_available)) }
                return false
            }
            val granted = gateway.refresh()
            val client = gateway.client() ?: return false
            if (!HealthPermissions.hasAnyWearableRead(granted)) {
                withContext(io) { forgetUnreadable(granted) }
                cache.notifyChanged()
                _state.update { it.copy(running = false, error = null) }
                return false
            }
            _state.update { it.copy(running = true, error = null) }
            val errors = withContext(io) { runAll(client, granted) }
            cache.notifyChanged()
            val now = clock()
            settings.setLastSyncAt(now)
            _state.value = SyncState(
                running = false,
                lastSyncAt = now,
                error = errors.takeIf { it.isNotEmpty() }?.let { UiText.res(R.string.hc_sync_failed, UiText.Joined(it, "; ")) },
            )
            return errors.isEmpty()
        } catch (e: CancellationException) {
            _state.update { it.copy(running = false) }
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Sync failed", e)
            _state.update { it.copy(running = false, error = UiText.res(R.string.hc_sync_failed, describe(e))) }
            return false
        } finally {
            mutex.unlock()
        }
    }

    private class Run(val client: HealthConnectClient, val granted: Set<String>, val now: Long, val zone: ZoneId) {
        val today: LocalDate = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    }

    private suspend fun runAll(client: HealthConnectClient, granted: Set<String>): List<UiText> {
        val run = Run(client, granted, clock(), zone())
        val windowDays = HealthPermissions.importWindowDays(granted)
        val errors = mutableListOf<UiText>()
        forgetUnreadable(granted)

        for (spec in activitySpecs) {
            if (!HealthPermissions.canRead(granted, spec.column.type)) continue
            guarded(spec.column.type, errors) { syncActivity(run, spec, windowDays) }
        }
        for (channel in channels) {
            if (!HealthPermissions.canRead(granted, channel.type)) continue
            guarded(channel.type, errors) { syncChannel(run, channel, windowDays) }
        }
        cache.prune(run.now - RETENTION_DAYS * DAY_MS, run.today.minusDays(RETENTION_DAYS))
        return errors
    }

    private suspend fun guarded(type: HealthDataType, errors: MutableList<UiText>, block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Sync of $type failed", e)
            errors += UiText.res(R.string.hc_sync_type_error, UiText.res(type.labelRes), describe(e))
        }
    }

    /** Drops cached data and tokens of types whose read permission is gone. */
    private fun forgetUnreadable(granted: Set<String>) {
        for (spec in activitySpecs) {
            val type = spec.column.type
            if (!HealthPermissions.canRead(granted, type) && cache.meta(tokenKey(type)) != null) {
                cache.clearDaily(spec.column)
                clearTokens(type)
            }
        }
        for (channel in channels) {
            if (!HealthPermissions.canRead(granted, channel.type) && cache.meta(tokenKey(channel.type)) != null) {
                channel.clear(null)
                clearTokens(channel.type)
            }
        }
    }

    // ---- Daily aggregates -----------------------------------------------------------------

    private class ActivitySpec(
        val column: ActivityColumn,
        val recordClass: KClass<out Record>,
        val read: (AggregationResult) -> Double?,
        val metric: AggregateMetric<*>,
    )

    private val activitySpecs = listOf(
        ActivitySpec(ActivityColumn.STEPS, StepsRecord::class, { it[StepsRecord.COUNT_TOTAL]?.toDouble() }, StepsRecord.COUNT_TOTAL),
        ActivitySpec(ActivityColumn.DISTANCE, DistanceRecord::class, { it[DistanceRecord.DISTANCE_TOTAL]?.inMeters }, DistanceRecord.DISTANCE_TOTAL),
        ActivitySpec(
            ActivityColumn.ACTIVE_KCAL,
            ActiveCaloriesBurnedRecord::class,
            { it[ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL]?.inKilocalories },
            ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL,
        ),
        ActivitySpec(
            ActivityColumn.TOTAL_KCAL,
            TotalCaloriesBurnedRecord::class,
            { it[TotalCaloriesBurnedRecord.ENERGY_TOTAL]?.inKilocalories },
            TotalCaloriesBurnedRecord.ENERGY_TOTAL,
        ),
    )

    private suspend fun syncActivity(run: Run, spec: ActivitySpec, windowDays: Int) {
        val type = spec.column.type
        val windowStart = run.today.minusDays(windowDays - 1L)
        val token = cache.meta(tokenKey(type))
        val storedWindow = cache.meta(windowKey(type))?.toIntOrNull() ?: 0
        if (token != null && storedWindow >= windowDays) {
            // Always re-aggregate the last 30 days (the user may change source priorities in
            // Health Connect, which changes totals without record changes); extend to changed days.
            var from = run.today.minusDays(HealthPermissions.DEFAULT_WINDOW_DAYS - 1L)
            val ok = consumeChanges(run.client, type, token) { upserts, deletions ->
                if (deletions.isNotEmpty()) from = windowStart
                for (record in upserts) {
                    val start = activityStart(record) ?: continue
                    val date = start.atZone(run.zone).toLocalDate()
                    if (date < from) from = date
                }
            }
            if (ok) {
                aggregateDaily(run, spec, maxOf(from, windowStart), run.today.plusDays(1))
                return
            }
        }
        val newToken = run.client.getChangesToken(ChangesTokenRequest(setOf(spec.recordClass)))
        aggregateDaily(run, spec, windowStart, run.today.plusDays(1))
        saveTokens(type, newToken, windowDays)
    }

    private fun activityStart(record: Record): Instant? = when (record) {
        is StepsRecord -> record.startTime
        is DistanceRecord -> record.startTime
        is ActiveCaloriesBurnedRecord -> record.startTime
        is TotalCaloriesBurnedRecord -> record.startTime
        else -> null
    }

    private suspend fun aggregateDaily(run: Run, spec: ActivitySpec, from: LocalDate, toExclusive: LocalDate) {
        val groups = run.client.aggregateGroupByPeriod(
            AggregateGroupByPeriodRequest(
                metrics = setOf(spec.metric),
                timeRangeFilter = TimeRangeFilter.between(from.atStartOfDay(), toExclusive.atStartOfDay()),
                timeRangeSlicer = Period.ofDays(1),
            ),
        )
        val values = HashMap<LocalDate, Double>()
        for (g in groups) spec.read(g.result)?.let { values[g.startTime.toLocalDate()] = it }
        cache.setDaily(spec.column, from, toExclusive, values)
    }

    // ---- Record channels ------------------------------------------------------------------

    private class Channel<T : Record>(
        val type: HealthDataType,
        val recordClass: KClass<T>,
        val clear: (Long?) -> Unit,
        val delete: (List<String>) -> Unit,
        val store: suspend (Run, List<T>) -> Unit,
    )

    private fun origin(r: Record): String = r.metadata.dataOrigin.packageName

    private fun <T : Record> pointChannel(
        type: HealthDataType,
        recordClass: KClass<T>,
        kind: PointKind,
        excludeOwn: Boolean = false,
        mapper: (T) -> Pair<Long, Double>,
    ) = Channel(
        type = type,
        recordClass = recordClass,
        clear = { from -> cache.clearPoints(kind, from) },
        delete = { ids -> cache.deletePoints(kind, ids) },
        store = { _, records ->
            val rows = records
                .filter { !excludeOwn || origin(it) != ownPackage }
                .map { r -> mapper(r).let { (t, v) -> PointRow(r.metadata.id, t, v, origin(r)) } }
            cache.upsertPoints(kind, rows)
        },
    )

    private val channels: List<Channel<out Record>> = listOf(
        Channel(
            type = HealthDataType.HEART_RATE,
            recordClass = HeartRateRecord::class,
            clear = { from -> cache.clearHr(from) },
            delete = { ids -> cache.deleteHrRecords(ids) },
            store = { _, records ->
                cache.replaceHrRecords(
                    records.map { r ->
                        Triple(r.metadata.id, origin(r), r.samples.map { HrSample(it.time.toEpochMilli(), it.beatsPerMinute.toInt()) })
                    },
                )
            },
        ),
        pointChannel(HealthDataType.RESTING_HEART_RATE, RestingHeartRateRecord::class, PointKind.RESTING_HR) {
            it.time.toEpochMilli() to it.beatsPerMinute.toDouble()
        },
        pointChannel(HealthDataType.HEART_RATE_VARIABILITY, HeartRateVariabilityRmssdRecord::class, PointKind.HRV) {
            it.time.toEpochMilli() to it.heartRateVariabilityMillis
        },
        pointChannel(HealthDataType.OXYGEN_SATURATION, OxygenSaturationRecord::class, PointKind.SPO2) {
            it.time.toEpochMilli() to it.percentage.value
        },
        pointChannel(HealthDataType.RESPIRATORY_RATE, RespiratoryRateRecord::class, PointKind.RESPIRATORY_RATE) {
            it.time.toEpochMilli() to it.rate
        },
        pointChannel(HealthDataType.WEIGHT, WeightRecord::class, PointKind.WEIGHT, excludeOwn = true) {
            it.time.toEpochMilli() to it.weight.inKilograms
        },
        pointChannel(HealthDataType.BODY_FAT, BodyFatRecord::class, PointKind.BODY_FAT, excludeOwn = true) {
            it.time.toEpochMilli() to it.percentage.value
        },
        Channel(
            type = HealthDataType.SLEEP,
            recordClass = SleepSessionRecord::class,
            clear = { from -> cache.clearSleep(from) },
            delete = { ids -> cache.deleteSleep(ids) },
            store = { _, records ->
                cache.upsertSleep(
                    records.map { r ->
                        SleepSession(
                            id = r.metadata.id,
                            start = r.startTime.toEpochMilli(),
                            end = r.endTime.toEpochMilli(),
                            title = r.title,
                            origin = origin(r),
                            stages = r.stages.map {
                                SleepStageInterval(it.startTime.toEpochMilli(), it.endTime.toEpochMilli(), HealthMappings.sleepStage(it.stage))
                            },
                        )
                    },
                )
            },
        ),
        Channel(
            type = HealthDataType.EXERCISE,
            recordClass = ExerciseSessionRecord::class,
            clear = { from -> cache.clearExercises(from) },
            delete = { ids -> cache.deleteExercises(ids) },
            store = { run, records ->
                val totalsFrom = run.now - EXERCISE_TOTALS_DAYS * DAY_MS
                // The app's own workouts (WorkoutExporter) are shown by the training log, not as band workouts.
                cache.upsertExercises(
                    records.filter { origin(it) != ownPackage }.map { r ->
                        val start = r.startTime.toEpochMilli()
                        val end = r.endTime.toEpochMilli()
                        val totals = if (start >= totalsFrom) exerciseTotals(run.client, run.granted, start, end) else null
                        ExerciseSession(
                            id = r.metadata.id,
                            start = start,
                            end = end,
                            type = r.exerciseType,
                            title = r.title,
                            activeKcal = totals?.first,
                            distanceM = totals?.second,
                            origin = origin(r),
                        )
                    },
                )
            },
        ),
    )

    private suspend fun <T : Record> syncChannel(run: Run, channel: Channel<T>, windowDays: Int) {
        val type = channel.type
        val token = cache.meta(tokenKey(type))
        val storedWindow = cache.meta(windowKey(type))?.toIntOrNull() ?: 0
        if (token != null && storedWindow >= windowDays) {
            val ok = consumeChanges(run.client, type, token) { upserts, deletions ->
                if (deletions.isNotEmpty()) channel.delete(deletions)
                val typed = upserts.filterIsInstance(channel.recordClass.java)
                if (typed.isNotEmpty()) channel.store(run, typed)
            }
            if (ok) return
        }
        // Full read of the window. Take the token first so nothing written meanwhile is missed.
        val windowStart = run.now - windowDays * DAY_MS
        val newToken = run.client.getChangesToken(ChangesTokenRequest(setOf(channel.recordClass)))
        channel.clear(windowStart)
        var pageToken: String? = null
        do {
            val response = run.client.readRecords(
                ReadRecordsRequest(
                    recordType = channel.recordClass,
                    timeRangeFilter = TimeRangeFilter.after(Instant.ofEpochMilli(windowStart)),
                    pageSize = PAGE_SIZE,
                    pageToken = pageToken,
                ),
            )
            channel.store(run, response.records)
            pageToken = response.pageToken
        } while (pageToken != null)
        saveTokens(type, newToken, windowDays)
    }

    /**
     * Applies all pending changes of [token] in order (batches of upserts / deletions). Returns
     * false when the token expired or is no longer valid, so the caller re-reads the window.
     */
    private suspend fun consumeChanges(
        client: HealthConnectClient,
        type: HealthDataType,
        token: String,
        apply: suspend (upserts: List<Record>, deletions: List<String>) -> Unit,
    ): Boolean {
        var next = token
        while (true) {
            val response = try {
                client.getChanges(next)
            } catch (e: IllegalArgumentException) {
                Log.i(TAG, "Changes token of $type rejected, re-reading", e)
                return false
            }
            if (response.changesTokenExpired) return false
            val upserts = ArrayList<Record>()
            val deletions = ArrayList<String>()
            for (change in response.changes) {
                when (change) {
                    is UpsertionChange -> {
                        if (deletions.isNotEmpty()) {
                            apply(emptyList(), deletions.toList())
                            deletions.clear()
                        }
                        upserts += change.record
                    }
                    is DeletionChange -> {
                        if (upserts.isNotEmpty()) {
                            apply(upserts.toList(), emptyList())
                            upserts.clear()
                        }
                        deletions += change.recordId
                    }
                }
            }
            if (upserts.isNotEmpty() || deletions.isNotEmpty()) apply(upserts, deletions)
            next = response.nextChangesToken
            cache.setMeta(tokenKey(type), next)
            if (!response.hasMore) return true
        }
    }

    /** Active kcal and distance during an exercise, from the aggregate API (if readable). */
    private suspend fun exerciseTotals(client: HealthConnectClient, granted: Set<String>, start: Long, end: Long): Pair<Double?, Double?>? {
        val metrics = buildSet {
            if (HealthPermissions.canRead(granted, HealthDataType.ACTIVE_CALORIES)) add(ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL)
            if (HealthPermissions.canRead(granted, HealthDataType.DISTANCE)) add(DistanceRecord.DISTANCE_TOTAL)
        }
        if (metrics.isEmpty() || end <= start) return null
        val result = client.aggregate(
            AggregateRequest(metrics, TimeRangeFilter.between(Instant.ofEpochMilli(start), Instant.ofEpochMilli(end))),
        )
        return result[ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL]?.inKilocalories to result[DistanceRecord.DISTANCE_TOTAL]?.inMeters
    }

    /**
     * Fills kcal / distance of an older exercise (outside the window computed during sync) when
     * its details are opened. Returns the updated session or null if nothing changed.
     */
    suspend fun completeExercise(session: ExerciseSession): ExerciseSession? {
        if (session.activeKcal != null || session.distanceM != null) return null
        val client = gateway.client() ?: return null
        return withContext(io) {
            val totals = try {
                exerciseTotals(client, gateway.granted.value, session.start, session.end)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Exercise totals failed", e)
                null
            } ?: return@withContext null
            if (totals.first == null && totals.second == null) return@withContext null
            val updated = session.copy(activeKcal = totals.first, distanceM = totals.second)
            cache.upsertExercises(listOf(updated))
            updated
        }.also { if (it != null) cache.notifyChanged() }
    }

    private fun tokenKey(type: HealthDataType) = "token.${type.name}"

    private fun windowKey(type: HealthDataType) = "window.${type.name}"

    private fun saveTokens(type: HealthDataType, token: String, windowDays: Int) {
        cache.setMeta(tokenKey(type), token)
        cache.setMeta(windowKey(type), windowDays.toString())
    }

    private fun clearTokens(type: HealthDataType) {
        cache.setMeta(tokenKey(type), null)
        cache.setMeta(windowKey(type), null)
    }

    companion object {
        private const val TAG = "HealthSync"
        const val THROTTLE_MS = 5 * 60_000L
        private const val DAY_MS = 86_400_000L
        private const val PAGE_SIZE = 1000
        private const val RETENTION_DAYS = 400L
        private const val EXERCISE_TOTALS_DAYS = 90L

        /** Short reason for a Health Connect failure. */
        fun describe(e: Throwable): UiText {
            val message = e.message.orEmpty()
            return when {
                message.contains("quota", ignoreCase = true) || message.contains("rate limit", ignoreCase = true) ->
                    UiText.res(R.string.hc_error_rate_limit)
                message.contains("background", ignoreCase = true) -> UiText.res(R.string.hc_error_background)
                e is SecurityException -> UiText.res(R.string.hc_error_permission_revoked)
                e is RemoteException || e is IOException -> UiText.res(R.string.hc_error_no_response)
                message.isNotBlank() -> UiText.raw(message)
                else -> UiText.raw(e.javaClass.simpleName)
            }
        }
    }
}
