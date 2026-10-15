package com.enjoyyourtime.globalfitness.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import androidx.core.database.sqlite.transaction
import java.time.LocalDate

/** Plain SQLite storage (no Room / annotation processing). All methods are blocking. */
class AppDatabase(context: Context) : SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE profiles (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                name TEXT NOT NULL,
                sex TEXT NOT NULL CHECK (sex IN ('M','F')),
                birth_date INTEGER NOT NULL,
                height_cm REAL NOT NULL,
                target_weight_kg REAL,
                color_index INTEGER NOT NULL,
                created_at INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE measurements (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                profile_id INTEGER,
                timestamp INTEGER NOT NULL,
                weight_kg REAL NOT NULL,
                impedance INTEGER,
                scale_key TEXT UNIQUE,
                source TEXT NOT NULL DEFAULT 'scale' CHECK (source IN ('scale','manual'))
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX idx_measurements_profile_time ON measurements(profile_id, timestamp)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Version 1 is the only schema so far.
    }

    fun loadProfiles(): List<Profile> =
        readableDatabase.rawQuery(
            "SELECT id, name, sex, birth_date, height_cm, target_weight_kg, color_index, created_at FROM profiles ORDER BY created_at, id",
            null,
        ).use { c ->
            buildList {
                while (c.moveToNext()) {
                    add(
                        Profile(
                            id = c.getLong(0),
                            name = c.getString(1),
                            sex = Sex.fromCode(c.getString(2)),
                            birthDate = LocalDate.ofEpochDay(c.getLong(3)),
                            heightCm = c.getDouble(4),
                            targetWeightKg = c.doubleOrNull(5),
                            colorIndex = c.getInt(6),
                            createdAt = c.getLong(7),
                        ),
                    )
                }
            }
        }

    fun loadMeasurements(): List<Measurement> =
        readableDatabase.rawQuery(
            "SELECT id, profile_id, timestamp, weight_kg, impedance, scale_key, source FROM measurements ORDER BY timestamp, id",
            null,
        ).use { c ->
            buildList {
                while (c.moveToNext()) {
                    add(
                        Measurement(
                            id = c.getLong(0),
                            profileId = if (c.isNull(1)) null else c.getLong(1),
                            timestamp = c.getLong(2),
                            weightKg = c.getDouble(3),
                            impedance = if (c.isNull(4)) null else c.getInt(4),
                            scaleKey = if (c.isNull(5)) null else c.getString(5),
                            source = MeasurementSource.fromCode(c.getString(6)),
                        ),
                    )
                }
            }
        }

    fun insertProfile(profile: Profile): Long =
        writableDatabase.insertOrThrow("profiles", null, profile.toValues())

    fun updateProfile(profile: Profile) {
        writableDatabase.update("profiles", profile.toValues(), "id = ?", arrayOf(profile.id.toString()))
    }

    /** Deletes the profile and either its measurements or leaves them pending (profile_id NULL). */
    fun deleteProfile(profileId: Long, deleteMeasurements: Boolean) {
        writableDatabase.transaction {
            val args = arrayOf(profileId.toString())
            if (deleteMeasurements) {
                delete("measurements", "profile_id = ?", args)
            } else {
                val values = ContentValues().apply { putNull("profile_id") }
                update("measurements", values, "profile_id = ?", args)
            }
            delete("profiles", "id = ?", args)
        }
    }

    /** Turns measurements whose profile no longer exists into pending ones. Returns rows changed. */
    fun fixOrphans(): Int {
        val values = ContentValues().apply { putNull("profile_id") }
        return writableDatabase.update(
            "measurements",
            values,
            "profile_id IS NOT NULL AND profile_id NOT IN (SELECT id FROM profiles)",
            null,
        )
    }

    /** Returns the new row id, or -1 when the unique scale_key already exists. */
    fun insertMeasurement(m: Measurement): Long =
        writableDatabase.insertWithOnConflict("measurements", null, m.toValues(), SQLiteDatabase.CONFLICT_IGNORE)

    /**
     * Applies an import plan in a single transaction: creates the new profiles, then inserts the
     * measurements (rows whose scale_key already exists are skipped). Returns what was stored.
     */
    fun applyImport(plan: ImportPlan): Pair<List<Profile>, List<Measurement>> =
        writableDatabase.transaction {
            val created = plan.newProfiles.map { p -> p.copy(id = insertOrThrow("profiles", null, p.toValues())) }
            val inserted = ArrayList<Measurement>(plan.measurements.size)
            for (planned in plan.measurements) {
                val owner = planned.newProfileIndex?.let { created[it].id } ?: planned.measurement.profileId
                val m = planned.measurement.copy(profileId = owner)
                val id = insertWithOnConflict("measurements", null, m.toValues(), SQLiteDatabase.CONFLICT_IGNORE)
                if (id >= 0) inserted += m.copy(id = id)
            }
            created to inserted
        }

    fun updateMeasurementImpedance(id: Long, impedance: Int) {
        val values = ContentValues().apply { put("impedance", impedance) }
        writableDatabase.update("measurements", values, "id = ?", arrayOf(id.toString()))
    }

    fun assignMeasurement(id: Long, profileId: Long?) {
        val values = ContentValues().apply {
            if (profileId == null) putNull("profile_id") else put("profile_id", profileId)
        }
        writableDatabase.update("measurements", values, "id = ?", arrayOf(id.toString()))
    }

    fun deleteMeasurement(id: Long) {
        writableDatabase.delete("measurements", "id = ?", arrayOf(id.toString()))
    }

    private fun Profile.toValues() = ContentValues().apply {
        put("name", name)
        put("sex", sex.code)
        put("birth_date", birthDate.toEpochDay())
        put("height_cm", heightCm)
        if (targetWeightKg == null) putNull("target_weight_kg") else put("target_weight_kg", targetWeightKg)
        put("color_index", colorIndex)
        put("created_at", createdAt)
    }

    private fun Measurement.toValues() = ContentValues().apply {
        if (profileId == null) putNull("profile_id") else put("profile_id", profileId)
        put("timestamp", timestamp)
        put("weight_kg", weightKg)
        if (impedance == null) putNull("impedance") else put("impedance", impedance)
        if (scaleKey == null) putNull("scale_key") else put("scale_key", scaleKey)
        put("source", source.code)
    }

    private fun Cursor.doubleOrNull(index: Int): Double? = if (isNull(index)) null else getDouble(index)

    companion object {
        const val DB_NAME = "globalfitness.db"
        const val DB_VERSION = 1
    }
}
