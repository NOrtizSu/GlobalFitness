package com.enjoyyourtime.globalfitness.cloud

import com.enjoyyourtime.globalfitness.training.model.CustomExercise
import com.enjoyyourtime.globalfitness.training.model.Routine
import com.enjoyyourtime.globalfitness.training.model.Workout
import com.enjoyyourtime.globalfitness.training.model.Tombstone as LocalTombstone

/**
 * The training documents of the backup, one Firestore collection each under `users/{uid}/`.
 * [tag] is the short code used in the sync bookkeeping file: never change it.
 */
enum class TrainingKind(val collection: String, val tombstoneKind: String, internal val tag: String) {
    WORKOUT("workouts", LocalTombstone.WORKOUT, "w"),
    CUSTOM_EXERCISE("customExercises", LocalTombstone.CUSTOM_EXERCISE, "c"),
    NOTE("exerciseNotes", LocalTombstone.NOTE, "n"),

    /** The user's own routines (the built-in ones are part of the app and never synced). */
    ROUTINE("routines", LocalTombstone.ROUTINE, "r"),
    ;

    companion object {
        fun ofTombstone(kind: String): TrainingKind? = entries.firstOrNull { it.tombstoneKind == kind }

        internal fun ofTag(tag: String): TrainingKind? = entries.firstOrNull { it.tag == tag }
    }
}

/** One training document: its kind and the app's own id (not the encoded Firestore id). */
data class DocKey(val kind: TrainingKind, val id: String)

/**
 * How the training data is synced, derived from the dataset decision of the measurements
 * (see [TrainingMerge.transitionFor]):
 * - [MERGE]: same dataset, last writer wins per document.
 * - [ADOPT_MERGE]: a fresh phone took over the cloud dataset automatically: the cloud copy wins every
 *   collision, but what was logged on this phone before signing in is kept and uploaded.
 * - [ADOPT_REPLACE]: the user chose "restore the cloud copy": the phone's training is replaced.
 * - [REPLACE_CLOUD]: the user chose "replace the cloud copy": the cloud's training is replaced.
 * - [UNION]: the phone's dataset goes to a cloud that holds none of ours (empty, or a deleted
 *   dataset) without the user being asked: last writer wins, nothing is deleted in the cloud.
 *
 * In every mode but [MERGE], documents changed after the transition was decided
 * ([TrainingBase.pendingSince]) are merged normally, so retrying a transition never undoes what the
 * user did since.
 *
 * [code] is persisted (pending transitions): never change it.
 */
enum class TrainingMode(val code: String) {
    MERGE("merge"),
    ADOPT_MERGE("adopt_merge"),
    ADOPT_REPLACE("adopt_replace"),
    REPLACE_CLOUD("replace_cloud"),
    UNION("union"),
    ;

    companion object {
        fun fromCode(code: String?): TrainingMode? = entries.firstOrNull { it.code == code }
    }
}

/**
 * The portable form of an [com.enjoyyourtime.globalfitness.training.model.ExerciseNote]: the photo is
 * identified by the SHA-256 of its JPEG bytes instead of a local file name, so two phones agree on it.
 */
data class NoteRecord(
    val exerciseId: String,
    val notes: String?,
    val favorite: Boolean,
    /** Lowercase hex SHA-256 of the photo bytes, null when there is no photo. */
    val photoHash: String?,
    /** Client time of the last change (the note's updatedAt). */
    val updatedAt: Long,
    /**
     * Local only: the note names a photo file that is not on this phone (the photo folder is not part
     * of Android Auto Backup, so a restored phone has the names but not the files). Such a photo is
     * "not downloaded yet", never a removal: [photoHash] is null and the cloud's photo is kept.
     */
    val photoMissing: Boolean = false,
)

/** What the phone has, as the planner sees it. */
data class TrainingLocal(
    val workouts: List<Workout>,
    val customExercises: List<CustomExercise>,
    val notes: List<NoteRecord>,
    val routines: List<Routine>,
    /** Deletions not yet propagated (see [com.enjoyyourtime.globalfitness.training.data.TrainingRepository.tombstones]). */
    val tombstones: List<LocalTombstone>,
    /** Photo hash -> a local file with those bytes (only files that exist). */
    val photoFiles: Map<String, String> = emptyMap(),
)

/** One training collection as read from the cloud: all of it, or only what changed since the cursor. */
data class RemoteDocs<T>(
    val live: List<T>,
    val dead: List<Tombstone>,
    /** True when this is the whole collection. */
    val complete: Boolean,
    /** Highest server updatedAt (epoch ms) among the docs read, for the next incremental pull. */
    val maxUpdatedAt: Long?,
    /**
     * Ids of live documents written by a newer app version (higher schema): this version may apply
     * them but never overwrites them, so nothing it does not understand is lost.
     */
    val readOnly: Set<String> = emptySet(),
) {
    companion object {
        fun <T> empty(complete: Boolean = true) = RemoteDocs<T>(emptyList(), emptyList(), complete, null)
    }
}

data class TrainingCloudSnapshot(
    val workouts: RemoteDocs<Workout>,
    val customExercises: RemoteDocs<CustomExercise>,
    val notes: RemoteDocs<NoteRecord>,
    val routines: RemoteDocs<Routine>,
) {
    companion object {
        val EMPTY = TrainingCloudSnapshot(RemoteDocs.empty(), RemoteDocs.empty(), RemoteDocs.empty(), RemoteDocs.empty())
    }
}

/** Server-time cursors for incremental pulls; a null field reads that whole collection. */
data class TrainingCursors(
    val workouts: Long? = null,
    val customExercises: Long? = null,
    val notes: Long? = null,
    val routines: Long? = null,
) {
    companion object {
        val FULL = TrainingCursors()
    }
}

/** One cloud write of the training sync. */
sealed interface TrainingWrite {
    data class PutWorkout(val workout: Workout) : TrainingWrite
    data class PutCustomExercise(val exercise: CustomExercise) : TrainingWrite
    data class PutNote(val note: NoteRecord) : TrainingWrite
    data class PutRoutine(val routine: Routine) : TrainingWrite

    /** A tombstone: the document's content is wiped and only `{deleted, deletedAt}` remains. */
    data class Delete(val kind: TrainingKind, val id: String, val deletedAt: Long) : TrainingWrite

    /** Uploads the photo [hash], read from the local [file]. */
    data class PutPhoto(val hash: String, val file: String) : TrainingWrite
    data class DeletePhoto(val hash: String) : TrainingWrite

    /** The document a Put / Delete writes, null for photos. */
    val key: DocKey?
        get() = when (this) {
            is PutWorkout -> DocKey(TrainingKind.WORKOUT, workout.id)
            is PutCustomExercise -> DocKey(TrainingKind.CUSTOM_EXERCISE, exercise.id)
            is PutNote -> DocKey(TrainingKind.NOTE, note.exerciseId)
            is PutRoutine -> DocKey(TrainingKind.ROUTINE, routine.id)
            is Delete -> DocKey(kind, id)
            is PutPhoto, is DeletePhoto -> null
        }
}

/** What the server confirmed of a [TrainingCloud.writeTraining] call (the rest stays queued offline or failed). */
data class TrainingWriteResult(
    val confirmedDocs: Set<DocKey>,
    val uploadedPhotos: Set<String>,
    val deletedPhotos: Set<String>,
    /** False when some write is still waiting in the offline queue (or a photo could not be read). */
    val allConfirmed: Boolean,
    /** Photo uploads handed to the offline queue without confirmation: not queued again for a while. */
    val queuedPhotos: Set<String> = emptySet(),
) {
    companion object {
        val NOTHING = TrainingWriteResult(emptySet(), emptySet(), emptySet(), allConfirmed = true)
    }
}

/** Cloud access of the training backup (Firestore on the phone, a fake in tests). */
interface TrainingCloud {
    /** Reads the training document collections (photos are never listed, only fetched by hash). */
    suspend fun fetchTraining(uid: String, since: TrainingCursors): TrainingCloudSnapshot

    /** The bytes of photo [hash], or null when the cloud does not have it. Throws on connection errors. */
    suspend fun fetchPhoto(uid: String, hash: String): ByteArray?

    /**
     * Writes in order: photo uploads, then documents and tombstones, then photo deletions (a note is
     * never visible before its photo, and an old photo is removed only after the note stopped using it).
     * [photoBytes] reads the bytes of an upload; null skips it (left unconfirmed).
     */
    suspend fun writeTraining(
        uid: String,
        writes: List<TrainingWrite>,
        photoBytes: suspend (TrainingWrite.PutPhoto) -> ByteArray?,
    ): TrainingWriteResult
}

/** The machine photo files on the phone, as the training sync needs them. All methods are blocking. */
interface TrainingPhotoFiles {
    fun exists(name: String): Boolean

    fun bytes(name: String): ByteArray?

    /** Stores [bytes] as a new file for [exerciseId]; returns its name. */
    fun store(exerciseId: String, bytes: ByteArray): String

    fun delete(name: String)

    /** Deletes files no note uses, except files written in the last [graceMs] (a photo being attached now). */
    fun deleteUnused(used: Set<String>, graceMs: Long)
}

/** Where the training sync keeps its bookkeeping ([SyncStateStore] on the phone). */
interface TrainingBaseStore {
    suspend fun loadTrainingBase(uid: String): TrainingBase?

    suspend fun saveTrainingBase(uid: String, base: TrainingBase)
}
