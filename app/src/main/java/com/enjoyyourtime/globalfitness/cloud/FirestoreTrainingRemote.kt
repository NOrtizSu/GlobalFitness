package com.enjoyyourtime.globalfitness.cloud

import com.enjoyyourtime.globalfitness.cloud.FirestoreCodec.Decoded
import com.enjoyyourtime.globalfitness.cloud.FirestoreCodec.F_DELETED_AT
import com.enjoyyourtime.globalfitness.cloud.FirestoreCodec.F_UPDATED_AT
import com.enjoyyourtime.globalfitness.cloud.FirestoreCodec.USERS
import com.google.android.gms.tasks.Task
import com.google.firebase.Timestamp
import com.google.firebase.firestore.Blob
import com.google.firebase.firestore.CollectionReference
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.SetOptions
import com.google.firebase.firestore.Source
import com.google.firebase.firestore.WriteBatch
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Date

/**
 * Firestore access for the training part of the backup (layout in [TrainingCodec]), on the same
 * Firestore instance as [FirestoreBackupRemote]. Reads go to the server; writes are batched and,
 * when the confirmation does not arrive in time, stay queued in the persistent offline cache
 * (Firestore keeps their order, so a note is never stored before its photo). Decoding and encoding
 * run on [compute], never on the caller's (main) thread.
 */
class FirestoreTrainingRemote internal constructor(
    private val firestore: () -> FirebaseFirestore,
    private val compute: CoroutineDispatcher = Dispatchers.Default,
) : TrainingCloud {
    private val db: FirebaseFirestore get() = firestore()

    private fun user(uid: String) = db.collection(USERS).document(uid)
    private fun collection(uid: String, kind: TrainingKind): CollectionReference = user(uid).collection(kind.collection)
    private fun docRef(uid: String, kind: TrainingKind, id: String) = collection(uid, kind).document(TrainingCodec.docId(id))
    private fun photos(uid: String): CollectionReference = user(uid).collection(TrainingCodec.PHOTOS)

    override suspend fun fetchTraining(uid: String, since: TrainingCursors): TrainingCloudSnapshot = coroutineScope {
        val workouts = async { read(uid, TrainingKind.WORKOUT, since.workouts, TrainingCodec::workoutFromMap) }
        val custom = async { read(uid, TrainingKind.CUSTOM_EXERCISE, since.customExercises, TrainingCodec::customExerciseFromMap) }
        val notes = async { read(uid, TrainingKind.NOTE, since.notes, TrainingCodec::noteFromMap) }
        val routines = async { read(uid, TrainingKind.ROUTINE, since.routines, TrainingCodec::routineFromMap) }
        TrainingCloudSnapshot(workouts.await(), custom.await(), notes.await(), routines.await())
    }

    /** The collection changed since [since] (server time, minus the usual overlap), or all of it. */
    private suspend fun <T> read(
        uid: String,
        kind: TrainingKind,
        since: Long?,
        decode: (String, Map<String, Any?>, Long?) -> Decoded<T>?,
    ): RemoteDocs<T> {
        val query: Query = if (since == null) {
            collection(uid, kind)
        } else {
            collection(uid, kind).whereGreaterThan(F_UPDATED_AT, Timestamp(Date(since - FirestoreBackupRemote.PULL_OVERLAP_MS)))
        }
        val snapshot = query.get(Source.SERVER).await()
        return withContext(compute) {
            val docs = snapshot.documents.map { TrainingCodec.RawDoc(it.id, it.data.orEmpty(), it.updatedAtMillis()) }
            TrainingCodec.collect(docs, complete = since == null, decode = decode)
        }
    }

    override suspend fun fetchPhoto(uid: String, hash: String): ByteArray? {
        if (!TrainingCodec.isPhotoHash(hash)) return null
        val doc = photos(uid).document(hash).get(Source.SERVER).await()
        if (!doc.exists()) return null
        return withContext(compute) { (doc.get(TrainingCodec.F_BYTES) as? Blob)?.toBytes() }
    }

    override suspend fun writeTraining(
        uid: String,
        writes: List<TrainingWrite>,
        photoBytes: suspend (TrainingWrite.PutPhoto) -> ByteArray?,
    ): TrainingWriteResult {
        var allConfirmed = true
        val uploaded = HashSet<String>()
        val queued = HashSet<String>()
        val deleted = HashSet<String>()
        val confirmed = HashSet<DocKey>()
        // Once one commit is not confirmed in time the server is not answering: the following commits
        // are still queued (in order) but not waited for, so a sync never blocks N times the timeout.
        var waiting = true
        suspend fun commit(task: Task<Void>): Boolean {
            if (!waiting) return false
            val ok = confirmedInTime(task)
            if (!ok) waiting = false
            return ok
        }

        // One photo per commit (up to ~1 MB each): a slow upload only delays that photo.
        for (put in writes.filterIsInstance<TrainingWrite.PutPhoto>()) {
            val bytes = photoBytes(put)
            if (bytes == null || bytes.size > TrainingCodec.MAX_PHOTO_BYTES) {
                allConfirmed = false
                continue
            }
            val batch = withContext(compute) {
                db.batch().apply {
                    set(photos(uid).document(put.hash), TrainingCodec.photoToMap(bytes.size).stamped() + (TrainingCodec.F_BYTES to Blob.fromBytes(bytes)))
                }
            }
            if (commit(batch.commit())) {
                uploaded += put.hash
            } else {
                queued += put.hash
                allConfirmed = false
            }
        }

        val docs = writes.filter { it.key != null }
        for (chunk in withContext(compute) { chunked(docs) }) {
            val batch = withContext(compute) { db.batch().apply { for (w in chunk) put(this, uid, w) } }
            if (commit(batch.commit())) chunk.mapNotNullTo(confirmed) { it.key } else allConfirmed = false
        }

        for (chunk in writes.filterIsInstance<TrainingWrite.DeletePhoto>().chunked(MAX_DOCS_PER_BATCH)) {
            val batch = db.batch()
            for (d in chunk) batch.delete(photos(uid).document(d.hash))
            if (commit(batch.commit())) chunk.mapTo(deleted) { it.hash } else allConfirmed = false
        }
        return TrainingWriteResult(confirmed, uploaded, deleted, allConfirmed, queued)
    }

    /**
     * Live documents are merged (fields a newer app version added survive) with every field written,
     * nulls included, and any old `deletedAt` removed; tombstones replace the whole document, so the
     * content of a deleted workout does not stay in the cloud.
     */
    private fun put(batch: WriteBatch, uid: String, w: TrainingWrite) {
        val live: Pair<DocKey, Map<String, Any?>>? = when (w) {
            is TrainingWrite.PutWorkout -> DocKey(TrainingKind.WORKOUT, w.workout.id) to TrainingCodec.workoutToMap(w.workout)
            is TrainingWrite.PutCustomExercise -> DocKey(TrainingKind.CUSTOM_EXERCISE, w.exercise.id) to TrainingCodec.customExerciseToMap(w.exercise)
            is TrainingWrite.PutNote -> DocKey(TrainingKind.NOTE, w.note.exerciseId) to TrainingCodec.noteToMap(w.note)
            is TrainingWrite.PutRoutine -> DocKey(TrainingKind.ROUTINE, w.routine.id) to TrainingCodec.routineToMap(w.routine)
            is TrainingWrite.Delete -> {
                batch.set(docRef(uid, w.kind, w.id), TrainingCodec.tombstoneToMap(w.deletedAt).stamped())
                null
            }
            is TrainingWrite.PutPhoto, is TrainingWrite.DeletePhoto -> null
        }
        if (live != null) {
            val (key, data) = live
            batch.set(docRef(uid, key.kind, key.id), data.stamped() + (F_DELETED_AT to FieldValue.delete()), SetOptions.merge())
        }
    }

    /** At most [MAX_DOCS_PER_BATCH] documents and about [MAX_BATCH_BYTES] per commit. */
    private fun chunked(writes: List<TrainingWrite>): List<List<TrainingWrite>> {
        val out = ArrayList<List<TrainingWrite>>()
        var current = ArrayList<TrainingWrite>()
        var bytes = 0
        for (w in writes) {
            val size = when (w) {
                is TrainingWrite.PutWorkout -> TrainingCodec.estimateBytes(TrainingCodec.workoutToMap(w.workout))
                is TrainingWrite.PutCustomExercise -> TrainingCodec.estimateBytes(TrainingCodec.customExerciseToMap(w.exercise))
                is TrainingWrite.PutNote -> TrainingCodec.estimateBytes(TrainingCodec.noteToMap(w.note))
                is TrainingWrite.PutRoutine -> TrainingCodec.estimateBytes(TrainingCodec.routineToMap(w.routine))
                else -> 100
            }
            if (current.isNotEmpty() && (current.size >= MAX_DOCS_PER_BATCH || bytes + size > MAX_BATCH_BYTES)) {
                out += current
                current = ArrayList()
                bytes = 0
            }
            current += w
            bytes += size
        }
        if (current.isNotEmpty()) out += current
        return out
    }

    /**
     * Deletes every training document (every [TrainingKind] collection, routines included) and photo
     * of [uid] (account deletion). Photos are large, so the ones the notes name are deleted by id
     * without reading them; then any leftover is paged in small pages. Throws if the server does not
     * confirm.
     */
    suspend fun deleteAll(uid: String) {
        for (kind in TrainingKind.entries) {
            while (true) {
                val page = collection(uid, kind).limit(DELETE_PAGE.toLong()).get(Source.SERVER).await()
                if (page.isEmpty) break
                val batch = db.batch()
                val photoHashes = HashSet<String>()
                for (doc in page.documents) {
                    batch.delete(doc.reference)
                    if (kind == TrainingKind.NOTE) (doc.get("photoHash") as? String)?.takeIf(TrainingCodec::isPhotoHash)?.let { photoHashes += it }
                }
                for (hash in photoHashes) batch.delete(photos(uid).document(hash))
                if (!confirmedInTime(batch.commit())) throw CloudTimeoutException()
            }
        }
        while (true) {
            val page = photos(uid).limit(PHOTO_DELETE_PAGE.toLong()).get(Source.SERVER).await()
            if (page.isEmpty) break
            val batch = db.batch()
            for (doc in page.documents) batch.delete(doc.reference)
            if (!confirmedInTime(batch.commit())) throw CloudTimeoutException()
        }
    }

    private suspend fun <T> confirmedInTime(task: Task<T>): Boolean = withTimeoutOrNull(FirestoreBackupRemote.COMMIT_TIMEOUT_MS) {
        task.await()
        true
    } ?: false

    private fun Map<String, Any?>.stamped(): Map<String, Any?> = this + (F_UPDATED_AT to FieldValue.serverTimestamp())

    private fun DocumentSnapshot.updatedAtMillis(): Long? = getTimestamp(F_UPDATED_AT)?.toDate()?.time

    private companion object {
        /** Each document write also carries a server-timestamp transform; stay far below Firestore's 500. */
        const val MAX_DOCS_PER_BATCH = 200

        /** Firestore accepts 10 MiB per request; workouts are a few KB each. */
        const val MAX_BATCH_BYTES = 2_000_000

        /** Notes page: up to 2 deletes per note (the note and its photo). */
        const val DELETE_PAGE = 200

        /** Leftover photos are read whole (~0.5 MB each). */
        const val PHOTO_DELETE_PAGE = 5
    }
}
