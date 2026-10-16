package com.enjoyyourtime.globalfitness.band

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import androidx.core.database.sqlite.transaction

/** The band.db schema and its additive migrations (plain SQL, unit-tested). */
object BandDbSchema {
    const val VERSION = 2

    val CREATE: List<String> = listOf(
        """
        CREATE TABLE hr_samples (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            timestamp INTEGER NOT NULL,
            bpm INTEGER NOT NULL,
            source TEXT NOT NULL,
            device_address TEXT,
            driver TEXT,
            UNIQUE (timestamp, source)
        )
        """.trimIndent(),
        "CREATE INDEX idx_hr_samples_time ON hr_samples(timestamp)",
    )

    /**
     * Statements upgrading [oldVersion] to [newVersion]. Only additive changes: rows recorded by older
     * versions are kept (their new columns are NULL).
     */
    fun migrations(oldVersion: Int, newVersion: Int): List<String> = buildList {
        // v2: which wearable driver recorded the sample (null = the original Smart Band 9 mode).
        if (oldVersion < 2 && newVersion >= 2) add("ALTER TABLE hr_samples ADD COLUMN driver TEXT")
    }
}

/**
 * `band.db`: data recorded in the direct band / wearable modes, kept apart from the main database so
 * it can be charted or merged with Health Connect later. All methods are blocking.
 */
class BandDatabase(context: Context) : SQLiteOpenHelper(context, DB_NAME, null, BandDbSchema.VERSION) {

    override fun onCreate(db: SQLiteDatabase) {
        BandDbSchema.CREATE.forEach(db::execSQL)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        BandDbSchema.migrations(oldVersion, newVersion).forEach(db::execSQL)
    }

    /** Inserts samples, ignoring duplicates (same timestamp and source). [driverId] null = Smart Band 9 mode. */
    fun insertHeartRate(samples: List<HeartRateSample>, deviceAddress: String?, driverId: String? = null) {
        if (samples.isEmpty()) return
        writableDatabase.transaction {
            for (s in samples) {
                val values = ContentValues().apply {
                    put("timestamp", s.timestampMs)
                    put("bpm", s.bpm)
                    put("source", s.source)
                    put("device_address", deviceAddress)
                    put("driver", driverId)
                }
                insertWithOnConflict("hr_samples", null, values, SQLiteDatabase.CONFLICT_IGNORE)
            }
        }
    }

    fun heartRateSamples(fromMs: Long, toMs: Long): List<HeartRateSample> =
        readableDatabase.rawQuery(
            "SELECT timestamp, bpm, source FROM hr_samples WHERE timestamp BETWEEN ? AND ? ORDER BY timestamp",
            arrayOf(fromMs.toString(), toMs.toString()),
        ).use { c ->
            buildList {
                while (c.moveToNext()) add(HeartRateSample(c.getLong(0), c.getInt(1), c.getString(2)))
            }
        }

    fun heartRateCount(): Long =
        readableDatabase.rawQuery("SELECT COUNT(*) FROM hr_samples", null).use { c -> if (c.moveToFirst()) c.getLong(0) else 0L }

    private companion object {
        const val DB_NAME = "band.db"
    }
}
