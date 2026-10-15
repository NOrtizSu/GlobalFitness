package com.enjoyyourtime.globalfitness.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import androidx.core.database.sqlite.transaction
import com.enjoyyourtime.globalfitness.scale.CompositionSource
import com.enjoyyourtime.globalfitness.scale.DeviceComposition
import com.enjoyyourtime.globalfitness.scale.FormulaLib
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
        migrateTo2(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) migrateTo2(db)
    }

    /**
     * v2: multi-scale support. Only adds nullable columns (existing rows are preserved) and tags
     * the pre-existing scale rows, which all came from the Mi Body Composition Scale 2.
     */
    private fun migrateTo2(db: SQLiteDatabase) {
        for ((column, type) in V2_COLUMNS) db.execSQL("ALTER TABLE measurements ADD COLUMN $column $type")
        db.execSQL(
            """
            UPDATE measurements SET
                device_driver = '$LEGACY_DRIVER',
                composition_source = CASE WHEN impedance IS NOT NULL THEN 'FORMULA_MI_SCALE' ELSE 'NONE' END,
                formula_lib = CASE WHEN impedance IS NOT NULL THEN 'MI_SCALE' ELSE NULL END
            WHERE source = 'scale'
            """.trimIndent(),
        )
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

    /** Number of measurements per profile id (rows without a valid profile are not counted). */
    fun countMeasurementsByProfile(): Map<Long, Int> =
        readableDatabase.rawQuery(
            "SELECT profile_id, COUNT(*) FROM measurements WHERE profile_id IS NOT NULL GROUP BY profile_id",
            null,
        ).use { c ->
            buildMap {
                while (c.moveToNext()) put(c.getLong(0), c.getInt(1))
            }
        }

    /**
     * Gives the single profile every measurement without a (valid) owner: rows stored before the
     * profile existed and rows whose profile was deleted by older versions. Returns rows changed.
     */
    fun adoptMeasurements(profileId: Long): Int {
        val values = ContentValues().apply { put("profile_id", profileId) }
        return writableDatabase.update(
            "measurements",
            values,
            "profile_id IS NULL OR profile_id NOT IN (SELECT id FROM profiles)",
            null,
        )
    }

    /**
     * Measurements of [profileId] (plus ownerless ones when null). Rows of other profiles left by
     * multi-profile versions stay untouched in the database but are not loaded.
     */
    fun loadMeasurements(profileId: Long?): List<Measurement> {
        val (where, args) = if (profileId == null) {
            "profile_id IS NULL" to null
        } else {
            "profile_id = ?" to arrayOf(profileId.toString())
        }
        return readableDatabase.rawQuery(
            "SELECT $MEASUREMENT_COLUMNS FROM measurements WHERE $where ORDER BY timestamp, id",
            args,
        ).use { c -> buildList { while (c.moveToNext()) add(c.toMeasurement()) } }
    }

    fun insertProfile(profile: Profile): Long =
        writableDatabase.insertOrThrow("profiles", null, profile.toValues())

    fun updateProfile(profile: Profile) {
        writableDatabase.update("profiles", profile.toValues(), "id = ?", arrayOf(profile.id.toString()))
    }

    /** Returns the new row id, or -1 when the unique scale_key already exists. */
    fun insertMeasurement(m: Measurement): Long =
        writableDatabase.insertWithOnConflict("measurements", null, m.toValues(), SQLiteDatabase.CONFLICT_IGNORE)

    /**
     * Applies an import plan in a single transaction: creates the profile when the plan has one,
     * then inserts the measurements (rows whose scale_key already exists are skipped).
     */
    fun applyImport(plan: ImportPlan): Pair<Profile?, List<Measurement>> =
        writableDatabase.transaction {
            val created = plan.newProfile?.let { p -> p.copy(id = insertOrThrow("profiles", null, p.toValues())) }
            val inserted = ArrayList<Measurement>(plan.measurements.size)
            for (planned in plan.measurements) {
                val m = if (created != null) planned.copy(profileId = created.id) else planned
                val id = insertWithOnConflict("measurements", null, m.toValues(), SQLiteDatabase.CONFLICT_IGNORE)
                if (id >= 0) inserted += m.copy(id = id)
            }
            created to inserted
        }

    /** Rewrites the scale-provided data of a row (impedance upgrade, late composition). */
    fun updateMeasurementScaleData(m: Measurement) {
        val values = ContentValues().apply {
            putScaleData(m)
        }
        writableDatabase.update("measurements", values, "id = ?", arrayOf(m.id.toString()))
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
        if (scaleKey == null) putNull("scale_key") else put("scale_key", scaleKey)
        put("source", source.code)
        putScaleData(this@toValues)
    }

    private fun ContentValues.putScaleData(m: Measurement) {
        if (m.impedance == null) putNull("impedance") else put("impedance", m.impedance)
        val c = m.composition
        putDouble("dev_fat_pct", c?.fatPercent)
        putDouble("dev_water_pct", c?.waterPercent)
        putDouble("dev_muscle_pct", c?.musclePercent)
        putDouble("dev_muscle_kg", c?.muscleKg)
        putDouble("dev_bone_kg", c?.boneKg)
        putDouble("dev_visceral", c?.visceralFat)
        putDouble("dev_bmr_kcal", c?.bmrKcal)
        putDouble("dev_lbm_kg", c?.lbmKg)
        putDouble("dev_protein_pct", c?.proteinPercent)
        putDouble("dev_metabolic_age", c?.metabolicAge)
        putText("composition_source", m.compositionSource?.code)
        putText("formula_lib", m.formulaLib?.id)
        putText("device_driver", m.deviceDriver)
        putText("device_address", m.deviceAddress)
    }

    private fun ContentValues.putDouble(key: String, v: Double?) {
        if (v == null) putNull(key) else put(key, v)
    }

    private fun ContentValues.putText(key: String, v: String?) {
        if (v == null) putNull(key) else put(key, v)
    }

    private fun Cursor.doubleOrNull(index: Int): Double? = if (isNull(index)) null else getDouble(index)

    private fun Cursor.toMeasurement(): Measurement {
        val composition = DeviceComposition.of(
            DeviceComposition(
                fatPercent = doubleOrNull(7),
                waterPercent = doubleOrNull(8),
                musclePercent = doubleOrNull(9),
                muscleKg = doubleOrNull(10),
                boneKg = doubleOrNull(11),
                visceralFat = doubleOrNull(12),
                bmrKcal = doubleOrNull(13),
                lbmKg = doubleOrNull(14),
                proteinPercent = doubleOrNull(15),
                metabolicAge = doubleOrNull(16),
            ),
        )
        return Measurement(
            id = getLong(0),
            profileId = if (isNull(1)) null else getLong(1),
            timestamp = getLong(2),
            weightKg = getDouble(3),
            impedance = if (isNull(4)) null else getInt(4),
            scaleKey = if (isNull(5)) null else getString(5),
            source = MeasurementSource.fromCode(getString(6)),
            composition = composition,
            compositionSource = CompositionSource.fromCode(if (isNull(17)) null else getString(17)),
            formulaLib = FormulaLib.fromId(if (isNull(18)) null else getString(18)),
            deviceDriver = if (isNull(19)) null else getString(19),
            deviceAddress = if (isNull(20)) null else getString(20),
        )
    }

    companion object {
        const val DB_NAME = "globalfitness.db"
        const val DB_VERSION = 2

        /** Driver id of the rows stored before v2 (all from the Mi Body Composition Scale 2). */
        const val LEGACY_DRIVER = "xiaomi_mi_scale_2"

        /** Columns added in v2, in cursor order after the v1 ones. */
        val V2_COLUMNS = listOf(
            "dev_fat_pct" to "REAL",
            "dev_water_pct" to "REAL",
            "dev_muscle_pct" to "REAL",
            "dev_muscle_kg" to "REAL",
            "dev_bone_kg" to "REAL",
            "dev_visceral" to "REAL",
            "dev_bmr_kcal" to "REAL",
            "dev_lbm_kg" to "REAL",
            "dev_protein_pct" to "REAL",
            "dev_metabolic_age" to "REAL",
            "composition_source" to "TEXT",
            "formula_lib" to "TEXT",
            "device_driver" to "TEXT",
            "device_address" to "TEXT",
        )

        private val MEASUREMENT_COLUMNS =
            (listOf("id", "profile_id", "timestamp", "weight_kg", "impedance", "scale_key", "source") + V2_COLUMNS.map { it.first })
                .joinToString(", ")
    }
}
