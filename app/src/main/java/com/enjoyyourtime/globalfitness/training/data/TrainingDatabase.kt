package com.enjoyyourtime.globalfitness.training.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import androidx.core.database.sqlite.transaction
import com.enjoyyourtime.globalfitness.training.model.CustomExercise
import com.enjoyyourtime.globalfitness.training.model.Equipment
import com.enjoyyourtime.globalfitness.training.model.ExerciseKind
import com.enjoyyourtime.globalfitness.training.model.ExerciseNote
import com.enjoyyourtime.globalfitness.training.model.Muscle
import com.enjoyyourtime.globalfitness.training.model.Routine
import com.enjoyyourtime.globalfitness.training.model.RoutineExercise
import com.enjoyyourtime.globalfitness.training.model.Tombstone
import com.enjoyyourtime.globalfitness.training.model.Workout
import com.enjoyyourtime.globalfitness.training.model.WorkoutEntry
import com.enjoyyourtime.globalfitness.training.model.WorkoutSet

/**
 * Training data in its own SQLite file (training.db), separate from the scale measurements. Plain
 * SQLite like [com.enjoyyourtime.globalfitness.data.AppDatabase]; all methods are blocking.
 */
class TrainingDatabase(context: Context) : SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION), TrainingStore {

    override fun onConfigure(db: SQLiteDatabase) {
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE workouts (
                id TEXT PRIMARY KEY,
                started_at INTEGER NOT NULL,
                ended_at INTEGER,
                name TEXT,
                note TEXT,
                updated_at INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX idx_workouts_started ON workouts(started_at)")
        db.execSQL(
            """
            CREATE TABLE workout_entries (
                id TEXT PRIMARY KEY,
                workout_id TEXT NOT NULL REFERENCES workouts(id) ON DELETE CASCADE,
                position INTEGER NOT NULL,
                exercise_id TEXT NOT NULL,
                note TEXT
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX idx_entries_workout ON workout_entries(workout_id, position)")
        db.execSQL(
            """
            CREATE TABLE workout_sets (
                id TEXT PRIMARY KEY,
                entry_id TEXT NOT NULL REFERENCES workout_entries(id) ON DELETE CASCADE,
                position INTEGER NOT NULL,
                weight_kg REAL,
                reps INTEGER,
                duration_s INTEGER,
                distance_m REAL,
                level INTEGER,
                kcal INTEGER,
                warmup INTEGER NOT NULL DEFAULT 0,
                done INTEGER NOT NULL DEFAULT 0,
                done_at INTEGER
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX idx_sets_entry ON workout_sets(entry_id, position)")
        db.execSQL(
            """
            CREATE TABLE custom_exercises (
                id TEXT PRIMARY KEY,
                name TEXT NOT NULL,
                kind TEXT NOT NULL,
                equipment TEXT NOT NULL,
                primary_muscles TEXT NOT NULL,
                secondary_muscles TEXT NOT NULL,
                archived INTEGER NOT NULL DEFAULT 0,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE exercise_notes (
                exercise_id TEXT PRIMARY KEY,
                notes TEXT,
                photo TEXT,
                favorite INTEGER NOT NULL DEFAULT 0,
                updated_at INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE tombstones (
                kind TEXT NOT NULL,
                id TEXT NOT NULL,
                deleted_at INTEGER NOT NULL,
                PRIMARY KEY (kind, id)
            )
            """.trimIndent(),
        )
        createRoutineTables(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) createRoutineTables(db)
    }

    /** v2: the user's routines (reusable sets of exercises with their target sets and reps). */
    private fun createRoutineTables(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS routines (
                id TEXT PRIMARY KEY,
                name TEXT NOT NULL,
                note TEXT,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS routine_exercises (
                routine_id TEXT NOT NULL REFERENCES routines(id) ON DELETE CASCADE,
                position INTEGER NOT NULL,
                exercise_id TEXT NOT NULL,
                sets INTEGER NOT NULL,
                reps INTEGER,
                duration_s INTEGER,
                PRIMARY KEY (routine_id, position)
            )
            """.trimIndent(),
        )
    }

    // ---- reads ------------------------------------------------------------------------------

    override fun loadWorkouts(): List<Workout> {
        val db = readableDatabase
        val sets = HashMap<String, MutableList<WorkoutSet>>()
        db.rawQuery(
            "SELECT entry_id, id, weight_kg, reps, duration_s, distance_m, level, kcal, warmup, done, done_at " +
                "FROM workout_sets ORDER BY entry_id, position",
            null,
        ).use { c ->
            while (c.moveToNext()) {
                sets.getOrPut(c.getString(0)) { ArrayList() } += WorkoutSet(
                    id = c.getString(1),
                    weightKg = c.doubleOrNull(2),
                    reps = c.intOrNull(3),
                    durationSec = c.intOrNull(4),
                    distanceM = c.doubleOrNull(5),
                    level = c.intOrNull(6),
                    kcal = c.intOrNull(7),
                    warmup = c.getInt(8) != 0,
                    done = c.getInt(9) != 0,
                    doneAt = c.longOrNull(10),
                )
            }
        }
        val entries = HashMap<String, MutableList<WorkoutEntry>>()
        db.rawQuery("SELECT workout_id, id, exercise_id, note FROM workout_entries ORDER BY workout_id, position", null).use { c ->
            while (c.moveToNext()) {
                val id = c.getString(1)
                entries.getOrPut(c.getString(0)) { ArrayList() } += WorkoutEntry(
                    id = id,
                    exerciseId = c.getString(2),
                    sets = sets[id].orEmpty(),
                    note = c.stringOrNull(3),
                )
            }
        }
        return db.rawQuery("SELECT id, started_at, ended_at, name, note, updated_at FROM workouts ORDER BY started_at, id", null).use { c ->
            buildList {
                while (c.moveToNext()) {
                    val id = c.getString(0)
                    add(
                        Workout(
                            id = id,
                            startedAt = c.getLong(1),
                            endedAt = c.longOrNull(2),
                            name = c.stringOrNull(3),
                            note = c.stringOrNull(4),
                            updatedAt = c.getLong(5),
                            entries = entries[id].orEmpty(),
                        ),
                    )
                }
            }
        }
    }

    override fun loadCustomExercises(): List<CustomExercise> =
        readableDatabase.rawQuery(
            "SELECT id, name, kind, equipment, primary_muscles, secondary_muscles, archived, created_at, updated_at FROM custom_exercises ORDER BY created_at, id",
            null,
        ).use { c ->
            buildList {
                while (c.moveToNext()) {
                    add(
                        CustomExercise(
                            id = c.getString(0),
                            name = c.getString(1),
                            kind = ExerciseKind.fromCode(c.getString(2)),
                            equipment = Equipment.fromCode(c.getString(3)),
                            primary = Muscle.decode(c.getString(4)),
                            secondary = Muscle.decode(c.getString(5)),
                            archived = c.getInt(6) != 0,
                            createdAt = c.getLong(7),
                            updatedAt = c.getLong(8),
                        ),
                    )
                }
            }
        }

    override fun loadNotes(): List<ExerciseNote> =
        readableDatabase.rawQuery("SELECT exercise_id, notes, photo, favorite, updated_at FROM exercise_notes", null).use { c ->
            buildList {
                while (c.moveToNext()) {
                    add(ExerciseNote(c.getString(0), c.stringOrNull(1), c.stringOrNull(2), c.getInt(3) != 0, c.getLong(4)))
                }
            }
        }

    override fun loadRoutines(): List<Routine> {
        val db = readableDatabase
        val exercises = HashMap<String, MutableList<RoutineExercise>>()
        db.rawQuery("SELECT routine_id, exercise_id, sets, reps, duration_s FROM routine_exercises ORDER BY routine_id, position", null).use { c ->
            while (c.moveToNext()) {
                exercises.getOrPut(c.getString(0)) { ArrayList() } +=
                    RoutineExercise(c.getString(1), c.getInt(2), c.intOrNull(3), c.intOrNull(4))
            }
        }
        return db.rawQuery("SELECT id, name, note, created_at, updated_at FROM routines ORDER BY created_at, id", null).use { c ->
            buildList {
                while (c.moveToNext()) {
                    val id = c.getString(0)
                    add(Routine(id, c.getString(1), exercises[id].orEmpty(), c.stringOrNull(2), c.getLong(3), c.getLong(4)))
                }
            }
        }
    }

    override fun loadTombstones(): List<Tombstone> =
        readableDatabase.rawQuery("SELECT kind, id, deleted_at FROM tombstones", null).use { c ->
            buildList { while (c.moveToNext()) add(Tombstone(c.getString(0), c.getString(1), c.getLong(2))) }
        }

    // ---- writes -----------------------------------------------------------------------------

    override fun upsertWorkout(workout: Workout) {
        writableDatabase.transaction { writeWorkout(workout) }
    }

    override fun deleteWorkout(id: String, deletedAt: Long) {
        writableDatabase.transaction {
            delete("workouts", "id = ?", arrayOf(id))
            tombstone(Tombstone.WORKOUT, id, deletedAt)
        }
    }

    override fun upsertCustomExercise(exercise: CustomExercise) {
        writableDatabase.transaction { writeCustomExercise(exercise) }
    }

    override fun deleteCustomExercise(id: String, deletedAt: Long) {
        writableDatabase.transaction {
            delete("custom_exercises", "id = ?", arrayOf(id))
            tombstone(Tombstone.CUSTOM_EXERCISE, id, deletedAt)
        }
    }

    override fun upsertNote(note: ExerciseNote) {
        writableDatabase.transaction { writeNote(note) }
    }

    override fun deleteNote(exerciseId: String, deletedAt: Long) {
        writableDatabase.transaction {
            delete("exercise_notes", "exercise_id = ?", arrayOf(exerciseId))
            tombstone(Tombstone.NOTE, exerciseId, deletedAt)
        }
    }

    override fun upsertRoutine(routine: Routine) {
        writableDatabase.transaction { writeRoutine(routine) }
    }

    override fun deleteRoutine(id: String, deletedAt: Long) {
        writableDatabase.transaction {
            delete("routines", "id = ?", arrayOf(id))
            tombstone(Tombstone.ROUTINE, id, deletedAt)
        }
    }

    override fun dropTombstones(tombstones: Collection<Tombstone>) {
        if (tombstones.isEmpty()) return
        writableDatabase.transaction {
            for (t in tombstones) delete("tombstones", "kind = ? AND id = ? AND deleted_at <= ?", arrayOf(t.kind, t.id, t.deletedAt.toString()))
        }
    }

    override fun applyBatch(batch: TrainingBatch) {
        if (batch.isEmpty) return
        writableDatabase.transaction {
            batch.upsertCustomExercises.forEach { writeCustomExercise(it) }
            batch.upsertNotes.forEach { writeNote(it) }
            batch.upsertWorkouts.forEach { writeWorkout(it) }
            batch.removeWorkouts.forEach { delete("workouts", "id = ?", arrayOf(it)) }
            batch.removeCustomExercises.forEach { delete("custom_exercises", "id = ?", arrayOf(it)) }
            batch.removeNotes.forEach { delete("exercise_notes", "exercise_id = ?", arrayOf(it)) }
            batch.upsertRoutines.forEach { writeRoutine(it) }
            batch.removeRoutines.forEach { delete("routines", "id = ?", arrayOf(it)) }
        }
    }

    private fun SQLiteDatabase.writeWorkout(w: Workout) {
        // Deleting the workout row cascades to its entries and sets.
        delete("workouts", "id = ?", arrayOf(w.id))
        insertOrThrow(
            "workouts",
            null,
            ContentValues().apply {
                put("id", w.id)
                put("started_at", w.startedAt)
                putLong("ended_at", w.endedAt)
                putText("name", w.name)
                putText("note", w.note)
                put("updated_at", w.updatedAt)
            },
        )
        w.entries.forEachIndexed { entryIndex, entry ->
            insertOrThrow(
                "workout_entries",
                null,
                ContentValues().apply {
                    put("id", entry.id)
                    put("workout_id", w.id)
                    put("position", entryIndex)
                    put("exercise_id", entry.exerciseId)
                    putText("note", entry.note)
                },
            )
            entry.sets.forEachIndexed { setIndex, s ->
                insertOrThrow(
                    "workout_sets",
                    null,
                    ContentValues().apply {
                        put("id", s.id)
                        put("entry_id", entry.id)
                        put("position", setIndex)
                        putDouble("weight_kg", s.weightKg)
                        putInt("reps", s.reps)
                        putInt("duration_s", s.durationSec)
                        putDouble("distance_m", s.distanceM)
                        putInt("level", s.level)
                        putInt("kcal", s.kcal)
                        put("warmup", if (s.warmup) 1 else 0)
                        put("done", if (s.done) 1 else 0)
                        putLong("done_at", s.doneAt)
                    },
                )
            }
        }
        // A workout restored or recreated after a deletion is live again.
        delete("tombstones", "kind = ? AND id = ?", arrayOf(Tombstone.WORKOUT, w.id))
    }

    private fun SQLiteDatabase.writeCustomExercise(e: CustomExercise) {
        insertWithOnConflict(
            "custom_exercises",
            null,
            ContentValues().apply {
                put("id", e.id)
                put("name", e.name)
                put("kind", e.kind.code)
                put("equipment", e.equipment.code)
                put("primary_muscles", Muscle.encode(e.primary))
                put("secondary_muscles", Muscle.encode(e.secondary))
                put("archived", if (e.archived) 1 else 0)
                put("created_at", e.createdAt)
                put("updated_at", e.updatedAt)
            },
            SQLiteDatabase.CONFLICT_REPLACE,
        )
        delete("tombstones", "kind = ? AND id = ?", arrayOf(Tombstone.CUSTOM_EXERCISE, e.id))
    }

    private fun SQLiteDatabase.writeNote(n: ExerciseNote) {
        insertWithOnConflict(
            "exercise_notes",
            null,
            ContentValues().apply {
                put("exercise_id", n.exerciseId)
                putText("notes", n.notes)
                putText("photo", n.photo)
                put("favorite", if (n.favorite) 1 else 0)
                put("updated_at", n.updatedAt)
            },
            SQLiteDatabase.CONFLICT_REPLACE,
        )
        delete("tombstones", "kind = ? AND id = ?", arrayOf(Tombstone.NOTE, n.exerciseId))
    }

    private fun SQLiteDatabase.writeRoutine(r: Routine) {
        // Deleting the routine row cascades to its exercises.
        delete("routines", "id = ?", arrayOf(r.id))
        insertOrThrow(
            "routines",
            null,
            ContentValues().apply {
                put("id", r.id)
                put("name", r.name)
                putText("note", r.note)
                put("created_at", r.createdAt)
                put("updated_at", r.updatedAt)
            },
        )
        r.exercises.forEachIndexed { i, e ->
            insertOrThrow(
                "routine_exercises",
                null,
                ContentValues().apply {
                    put("routine_id", r.id)
                    put("position", i)
                    put("exercise_id", e.exerciseId)
                    put("sets", e.sets)
                    putInt("reps", e.reps)
                    putInt("duration_s", e.durationSec)
                },
            )
        }
        delete("tombstones", "kind = ? AND id = ?", arrayOf(Tombstone.ROUTINE, r.id))
    }

    private fun SQLiteDatabase.tombstone(kind: String, id: String, at: Long) {
        insertWithOnConflict(
            "tombstones",
            null,
            ContentValues().apply {
                put("kind", kind)
                put("id", id)
                put("deleted_at", at)
            },
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    private fun ContentValues.putDouble(key: String, v: Double?) = if (v == null) putNull(key) else put(key, v)

    private fun ContentValues.putInt(key: String, v: Int?) = if (v == null) putNull(key) else put(key, v)

    private fun ContentValues.putLong(key: String, v: Long?) = if (v == null) putNull(key) else put(key, v)

    private fun ContentValues.putText(key: String, v: String?) = if (v == null) putNull(key) else put(key, v)

    private fun Cursor.doubleOrNull(i: Int): Double? = if (isNull(i)) null else getDouble(i)

    private fun Cursor.intOrNull(i: Int): Int? = if (isNull(i)) null else getInt(i)

    private fun Cursor.longOrNull(i: Int): Long? = if (isNull(i)) null else getLong(i)

    private fun Cursor.stringOrNull(i: Int): String? = if (isNull(i)) null else getString(i)

    companion object {
        const val DB_NAME = "training.db"
        const val DB_VERSION = 2
    }
}
