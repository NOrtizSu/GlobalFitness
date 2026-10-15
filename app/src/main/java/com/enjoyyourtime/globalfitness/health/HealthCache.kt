package com.enjoyyourtime.globalfitness.health

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import androidx.core.database.sqlite.transaction
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.time.LocalDate

/** Columns of the daily activity table, one per aggregated Health Connect type. */
enum class ActivityColumn(val column: String, val type: HealthDataType) {
    STEPS("steps", HealthDataType.STEPS),
    DISTANCE("distance_m", HealthDataType.DISTANCE),
    ACTIVE_KCAL("active_kcal", HealthDataType.ACTIVE_CALORIES),
    TOTAL_KCAL("total_kcal", HealthDataType.TOTAL_CALORIES),
}

data class PointRow(val recordId: String, val time: Long, val value: Double, val origin: String)

/**
 * Local cache of Health Connect data (`health.db`, separate from the app database) plus the
 * sync tokens and the scale-export ledger. All methods are blocking: call them on IO.
 */
class HealthCache(context: Context) : SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {

    private val _version = MutableStateFlow(0L)

    /** Incremented after every batch of changes so observers can reload. */
    val version: StateFlow<Long> = _version.asStateFlow()

    fun notifyChanged() = _version.update { it + 1 }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE daily_activity (
                date INTEGER PRIMARY KEY,
                steps INTEGER,
                distance_m REAL,
                active_kcal REAL,
                total_kcal REAL
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE hr_samples (
                record_id TEXT NOT NULL,
                time INTEGER NOT NULL,
                bpm INTEGER NOT NULL,
                origin TEXT NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX idx_hr_time ON hr_samples(time)")
        db.execSQL("CREATE INDEX idx_hr_record ON hr_samples(record_id)")
        db.execSQL(
            """
            CREATE TABLE points (
                kind TEXT NOT NULL,
                record_id TEXT NOT NULL,
                time INTEGER NOT NULL,
                value REAL NOT NULL,
                origin TEXT NOT NULL,
                PRIMARY KEY (kind, record_id)
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX idx_points_kind_time ON points(kind, time)")
        db.execSQL(
            """
            CREATE TABLE sleep_sessions (
                record_id TEXT PRIMARY KEY,
                start INTEGER NOT NULL,
                end_time INTEGER NOT NULL,
                title TEXT,
                origin TEXT NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX idx_sleep_end ON sleep_sessions(end_time)")
        db.execSQL(
            """
            CREATE TABLE sleep_stages (
                session_id TEXT NOT NULL,
                start INTEGER NOT NULL,
                end_time INTEGER NOT NULL,
                stage TEXT NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX idx_stages_session ON sleep_stages(session_id)")
        db.execSQL(
            """
            CREATE TABLE exercises (
                record_id TEXT PRIMARY KEY,
                start INTEGER NOT NULL,
                end_time INTEGER NOT NULL,
                type INTEGER NOT NULL,
                title TEXT,
                active_kcal REAL,
                distance_m REAL,
                origin TEXT NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX idx_exercises_start ON exercises(start)")
        db.execSQL("CREATE TABLE sync_meta (key TEXT PRIMARY KEY, value TEXT NOT NULL)")
        db.execSQL(
            """
            CREATE TABLE export_ledger (
                measurement_id INTEGER PRIMARY KEY,
                signature TEXT NOT NULL,
                version INTEGER NOT NULL,
                types TEXT NOT NULL,
                exported_at INTEGER NOT NULL
            )
            """.trimIndent(),
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Version 1 is the only schema so far.
    }

    // ---- Daily activity -------------------------------------------------------------------

    /** Sets [column] for every day in [from, toExclusive): the value from [values] or NULL. */
    fun setDaily(column: ActivityColumn, from: LocalDate, toExclusive: LocalDate, values: Map<LocalDate, Double>) {
        writableDatabase.transaction {
            var d = from
            while (d < toExclusive) {
                val day = d.toEpochDay()
                insertWithOnConflict("daily_activity", null, ContentValues().apply { put("date", day) }, SQLiteDatabase.CONFLICT_IGNORE)
                val cv = ContentValues().apply {
                    val v = values[d]
                    when {
                        v == null -> putNull(column.column)
                        column == ActivityColumn.STEPS -> put(column.column, v.toLong())
                        else -> put(column.column, v)
                    }
                }
                update("daily_activity", cv, "date = ?", arrayOf(day.toString()))
                d = d.plusDays(1)
            }
        }
    }

    fun clearDaily(column: ActivityColumn) {
        writableDatabase.update("daily_activity", ContentValues().apply { putNull(column.column) }, null, null)
    }

    fun loadDaily(from: LocalDate, toInclusive: LocalDate): List<DailyActivity> =
        readableDatabase.rawQuery(
            """
            SELECT date, steps, distance_m, active_kcal, total_kcal FROM daily_activity
            WHERE date BETWEEN ? AND ?
              AND (steps IS NOT NULL OR distance_m IS NOT NULL OR active_kcal IS NOT NULL OR total_kcal IS NOT NULL)
            ORDER BY date
            """.trimIndent(),
            arrayOf(from.toEpochDay().toString(), toInclusive.toEpochDay().toString()),
        ).use { c ->
            buildList {
                while (c.moveToNext()) {
                    add(
                        DailyActivity(
                            date = LocalDate.ofEpochDay(c.getLong(0)),
                            steps = if (c.isNull(1)) null else c.getLong(1),
                            distanceM = c.doubleOrNull(2),
                            activeKcal = c.doubleOrNull(3),
                            totalKcal = c.doubleOrNull(4),
                        ),
                    )
                }
            }
        }

    // ---- Heart rate -----------------------------------------------------------------------

    fun replaceHrRecords(records: List<Triple<String, String, List<HrSample>>>) {
        writableDatabase.transaction {
            for ((recordId, origin, samples) in records) {
                delete("hr_samples", "record_id = ?", arrayOf(recordId))
                for (s in samples) {
                    insert(
                        "hr_samples",
                        null,
                        ContentValues().apply {
                            put("record_id", recordId)
                            put("time", s.time)
                            put("bpm", s.bpm)
                            put("origin", origin)
                        },
                    )
                }
            }
        }
    }

    fun deleteHrRecords(ids: Collection<String>) = deleteIds("hr_samples", "record_id", ids)

    /** Removes samples from [fromTime] on (all when null). */
    fun clearHr(fromTime: Long?) {
        if (fromTime == null) writableDatabase.delete("hr_samples", null, null)
        else writableDatabase.delete("hr_samples", "time >= ?", arrayOf(fromTime.toString()))
    }

    fun loadHr(from: Long, toExclusive: Long): List<HrSample> =
        readableDatabase.rawQuery(
            "SELECT time, bpm FROM hr_samples WHERE time >= ? AND time < ? ORDER BY time",
            arrayOf(from.toString(), toExclusive.toString()),
        ).use { c ->
            buildList(c.count) { while (c.moveToNext()) add(HrSample(c.getLong(0), c.getInt(1))) }
        }

    // ---- Points (resting HR, HRV, SpO2, respiration, external weight/body fat) ------------

    fun upsertPoints(kind: PointKind, rows: List<PointRow>) {
        if (rows.isEmpty()) return
        writableDatabase.transaction {
            for (r in rows) {
                insertWithOnConflict(
                    "points",
                    null,
                    ContentValues().apply {
                        put("kind", kind.code)
                        put("record_id", r.recordId)
                        put("time", r.time)
                        put("value", r.value)
                        put("origin", r.origin)
                    },
                    SQLiteDatabase.CONFLICT_REPLACE,
                )
            }
        }
    }

    fun deletePoints(kind: PointKind, ids: Collection<String>) {
        if (ids.isEmpty()) return
        writableDatabase.transaction {
            for (id in ids) delete("points", "kind = ? AND record_id = ?", arrayOf(kind.code, id))
        }
    }

    fun clearPoints(kind: PointKind, fromTime: Long?) {
        if (fromTime == null) writableDatabase.delete("points", "kind = ?", arrayOf(kind.code))
        else writableDatabase.delete("points", "kind = ? AND time >= ?", arrayOf(kind.code, fromTime.toString()))
    }

    fun loadPoints(kind: PointKind, from: Long, toExclusive: Long): List<TimedValue> =
        readableDatabase.rawQuery(
            "SELECT time, value, origin FROM points WHERE kind = ? AND time >= ? AND time < ? ORDER BY time",
            arrayOf(kind.code, from.toString(), toExclusive.toString()),
        ).use { c ->
            buildList(c.count) { while (c.moveToNext()) add(TimedValue(c.getLong(0), c.getDouble(1), c.getString(2))) }
        }

    fun latestPoint(kind: PointKind): TimedValue? =
        readableDatabase.rawQuery(
            "SELECT time, value, origin FROM points WHERE kind = ? ORDER BY time DESC LIMIT 1",
            arrayOf(kind.code),
        ).use { c -> if (c.moveToFirst()) TimedValue(c.getLong(0), c.getDouble(1), c.getString(2)) else null }

    // ---- Sleep ----------------------------------------------------------------------------

    fun upsertSleep(sessions: List<SleepSession>) {
        if (sessions.isEmpty()) return
        writableDatabase.transaction {
            for (s in sessions) {
                delete("sleep_stages", "session_id = ?", arrayOf(s.id))
                insertWithOnConflict(
                    "sleep_sessions",
                    null,
                    ContentValues().apply {
                        put("record_id", s.id)
                        put("start", s.start)
                        put("end_time", s.end)
                        if (s.title == null) putNull("title") else put("title", s.title)
                        put("origin", s.origin)
                    },
                    SQLiteDatabase.CONFLICT_REPLACE,
                )
                for (st in s.stages) {
                    insert(
                        "sleep_stages",
                        null,
                        ContentValues().apply {
                            put("session_id", s.id)
                            put("start", st.start)
                            put("end_time", st.end)
                            put("stage", st.stage.name)
                        },
                    )
                }
            }
        }
    }

    fun deleteSleep(ids: Collection<String>) {
        if (ids.isEmpty()) return
        writableDatabase.transaction {
            for (id in ids) {
                delete("sleep_stages", "session_id = ?", arrayOf(id))
                delete("sleep_sessions", "record_id = ?", arrayOf(id))
            }
        }
    }

    /** Removes sessions starting at [fromTime] or later (all when null). */
    fun clearSleep(fromTime: Long?) {
        writableDatabase.transaction {
            if (fromTime == null) {
                delete("sleep_stages", null, null)
                delete("sleep_sessions", null, null)
            } else {
                val args = arrayOf(fromTime.toString())
                delete("sleep_stages", "session_id IN (SELECT record_id FROM sleep_sessions WHERE start >= ?)", args)
                delete("sleep_sessions", "start >= ?", args)
            }
        }
    }

    /** Sessions ending in [fromEnd, toEndExclusive), with their stages. */
    fun loadSleep(fromEnd: Long, toEndExclusive: Long): List<SleepSession> {
        val db = readableDatabase
        val sessions = db.rawQuery(
            "SELECT record_id, start, end_time, title, origin FROM sleep_sessions WHERE end_time >= ? AND end_time < ? ORDER BY end_time",
            arrayOf(fromEnd.toString(), toEndExclusive.toString()),
        ).use { c ->
            buildList {
                while (c.moveToNext()) {
                    add(SleepSession(c.getString(0), c.getLong(1), c.getLong(2), c.stringOrNull(3), c.getString(4), emptyList()))
                }
            }
        }
        if (sessions.isEmpty()) return sessions
        val stages = HashMap<String, MutableList<SleepStageInterval>>()
        db.rawQuery(
            """
            SELECT st.session_id, st.start, st.end_time, st.stage FROM sleep_stages st
            JOIN sleep_sessions s ON s.record_id = st.session_id
            WHERE s.end_time >= ? AND s.end_time < ? ORDER BY st.start
            """.trimIndent(),
            arrayOf(fromEnd.toString(), toEndExclusive.toString()),
        ).use { c ->
            while (c.moveToNext()) {
                val stage = runCatching { SleepStage.valueOf(c.getString(3)) }.getOrDefault(SleepStage.UNKNOWN)
                stages.getOrPut(c.getString(0)) { mutableListOf() } += SleepStageInterval(c.getLong(1), c.getLong(2), stage)
            }
        }
        return sessions.map { it.copy(stages = stages[it.id].orEmpty()) }
    }

    // ---- Exercise -------------------------------------------------------------------------

    fun upsertExercises(sessions: List<ExerciseSession>) {
        if (sessions.isEmpty()) return
        writableDatabase.transaction {
            for (e in sessions) {
                insertWithOnConflict(
                    "exercises",
                    null,
                    ContentValues().apply {
                        put("record_id", e.id)
                        put("start", e.start)
                        put("end_time", e.end)
                        put("type", e.type)
                        if (e.title == null) putNull("title") else put("title", e.title)
                        if (e.activeKcal == null) putNull("active_kcal") else put("active_kcal", e.activeKcal)
                        if (e.distanceM == null) putNull("distance_m") else put("distance_m", e.distanceM)
                        put("origin", e.origin)
                    },
                    SQLiteDatabase.CONFLICT_REPLACE,
                )
            }
        }
    }

    fun deleteExercises(ids: Collection<String>) = deleteIds("exercises", "record_id", ids)

    fun clearExercises(fromTime: Long?) {
        if (fromTime == null) writableDatabase.delete("exercises", null, null)
        else writableDatabase.delete("exercises", "start >= ?", arrayOf(fromTime.toString()))
    }

    /** Sessions starting in [from, toExclusive), newest first. */
    fun loadExercises(from: Long, toExclusive: Long): List<ExerciseSession> =
        readableDatabase.rawQuery(
            """
            SELECT record_id, start, end_time, type, title, active_kcal, distance_m, origin FROM exercises
            WHERE start >= ? AND start < ? ORDER BY start DESC
            """.trimIndent(),
            arrayOf(from.toString(), toExclusive.toString()),
        ).use { c ->
            buildList {
                while (c.moveToNext()) {
                    add(
                        ExerciseSession(
                            id = c.getString(0),
                            start = c.getLong(1),
                            end = c.getLong(2),
                            type = c.getInt(3),
                            title = c.stringOrNull(4),
                            activeKcal = c.doubleOrNull(5),
                            distanceM = c.doubleOrNull(6),
                            origin = c.getString(7),
                        ),
                    )
                }
            }
        }

    // ---- Housekeeping ---------------------------------------------------------------------

    /** Source app packages present in the cache. */
    fun origins(): List<String> =
        readableDatabase.rawQuery(
            """
            SELECT origin FROM (
                SELECT DISTINCT origin FROM hr_samples
                UNION SELECT DISTINCT origin FROM points
                UNION SELECT DISTINCT origin FROM sleep_sessions
                UNION SELECT DISTINCT origin FROM exercises
            ) ORDER BY origin
            """.trimIndent(),
            null,
        ).use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }

    /** Drops everything older than [beforeTime] / [beforeDate]. */
    fun prune(beforeTime: Long, beforeDate: LocalDate) {
        writableDatabase.transaction {
            val t = arrayOf(beforeTime.toString())
            delete("hr_samples", "time < ?", t)
            delete("points", "time < ?", t)
            delete("sleep_stages", "session_id IN (SELECT record_id FROM sleep_sessions WHERE end_time < ?)", t)
            delete("sleep_sessions", "end_time < ?", t)
            delete("exercises", "start < ?", t)
            delete("daily_activity", "date < ?", arrayOf(beforeDate.toEpochDay().toString()))
        }
    }

    // ---- Sync metadata --------------------------------------------------------------------

    fun meta(key: String): String? =
        readableDatabase.rawQuery("SELECT value FROM sync_meta WHERE key = ?", arrayOf(key)).use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }

    fun setMeta(key: String, value: String?) {
        if (value == null) {
            writableDatabase.delete("sync_meta", "key = ?", arrayOf(key))
        } else {
            writableDatabase.insertWithOnConflict(
                "sync_meta",
                null,
                ContentValues().apply {
                    put("key", key)
                    put("value", value)
                },
                SQLiteDatabase.CONFLICT_REPLACE,
            )
        }
    }

    // ---- Export ledger --------------------------------------------------------------------

    fun loadLedger(): List<LedgerEntry> =
        readableDatabase.rawQuery("SELECT measurement_id, signature, version, types FROM export_ledger", null).use { c ->
            buildList {
                while (c.moveToNext()) {
                    val types = c.getString(3).split(',').mapNotNull { ExportRecordType.fromCode(it) }.toSet()
                    add(LedgerEntry(c.getLong(0), c.getString(1), c.getLong(2), types))
                }
            }
        }

    fun putLedger(entry: LedgerEntry, now: Long) {
        writableDatabase.insertWithOnConflict(
            "export_ledger",
            null,
            ContentValues().apply {
                put("measurement_id", entry.measurementId)
                put("signature", entry.signature)
                put("version", entry.version)
                put("types", entry.types.joinToString(",") { it.code })
                put("exported_at", now)
            },
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    fun removeLedger(measurementIds: Collection<Long>) = deleteIds("export_ledger", "measurement_id", measurementIds.map { it.toString() })

    fun ledgerCount(): Int =
        readableDatabase.rawQuery("SELECT COUNT(*) FROM export_ledger", null).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }

    private fun deleteIds(table: String, column: String, ids: Collection<String>) {
        if (ids.isEmpty()) return
        writableDatabase.transaction {
            for (id in ids) delete(table, "$column = ?", arrayOf(id))
        }
    }

    private fun Cursor.doubleOrNull(index: Int): Double? = if (isNull(index)) null else getDouble(index)

    private fun Cursor.stringOrNull(index: Int): String? = if (isNull(index)) null else getString(index)

    companion object {
        const val DB_NAME = "health.db"
        const val DB_VERSION = 1
    }
}
