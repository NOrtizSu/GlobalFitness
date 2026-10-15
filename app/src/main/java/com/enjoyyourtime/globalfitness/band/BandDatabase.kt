package com.enjoyyourtime.globalfitness.band

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import androidx.core.database.sqlite.transaction

/**
 * `band.db`: data recorded in the direct band mode, kept apart from the main database so it can be
 * charted or merged with Health Connect later. All methods are blocking.
 */
class BandDatabase(context: Context) : SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE hr_samples (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                timestamp INTEGER NOT NULL,
                bpm INTEGER NOT NULL,
                source TEXT NOT NULL,
                device_address TEXT,
                UNIQUE (timestamp, source)
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX idx_hr_samples_time ON hr_samples(timestamp)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Version 1 is the only schema so far.
    }

    /** Inserts samples, ignoring duplicates (same timestamp and source). */
    fun insertHeartRate(samples: List<HeartRateSample>, deviceAddress: String?) {
        if (samples.isEmpty()) return
        writableDatabase.transaction {
            for (s in samples) {
                val values = ContentValues().apply {
                    put("timestamp", s.timestampMs)
                    put("bpm", s.bpm)
                    put("source", s.source)
                    put("device_address", deviceAddress)
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
        const val DB_VERSION = 1
    }
}
