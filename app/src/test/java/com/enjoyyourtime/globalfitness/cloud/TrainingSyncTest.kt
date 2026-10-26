package com.enjoyyourtime.globalfitness.cloud

import com.enjoyyourtime.globalfitness.cloud.FirestoreCodec.Decoded
import com.enjoyyourtime.globalfitness.training.FakeTrainingStore
import com.enjoyyourtime.globalfitness.training.data.TrainingBatch
import com.enjoyyourtime.globalfitness.training.data.TrainingRepository
import com.enjoyyourtime.globalfitness.training.data.TrainingStore
import com.enjoyyourtime.globalfitness.training.model.Equipment
import com.enjoyyourtime.globalfitness.training.model.ExerciseKind
import com.enjoyyourtime.globalfitness.training.model.Muscle
import com.enjoyyourtime.globalfitness.training.model.RoutineExercise
import com.enjoyyourtime.globalfitness.training.model.Workout
import com.enjoyyourtime.globalfitness.training.model.WorkoutEntry
import com.enjoyyourtime.globalfitness.training.model.WorkoutSet
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import com.enjoyyourtime.globalfitness.training.model.Tombstone as LocalTombstone

/**
 * The whole training sync (plan, photo download, apply, upload, tombstones, base) between phones that
 * share one fake cloud. The cloud stores what [TrainingCodec] writes and decodes it back like the
 * Firestore layer does, with merge semantics for live documents.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TrainingSyncTest {
    private val uid = "user-1"
    private val dataset = "ds-1"
    private var now = 1_000_000L
    private var ids = 0
    private val scopes = mutableListOf<CoroutineScope>()

    @After
    fun cancelScopes() = scopes.forEach { it.cancel() }

    private val photoA = byteArrayOf(10, 20, 30)
    private val photoB = byteArrayOf(40, 50, 60, 70)

    // ---- Fakes ---------------------------------------------------------------------------------------

    private class FakeCloud : TrainingCloud {
        var serverTime = 1_700_000_000_000L
        val docs = HashMap<TrainingKind, LinkedHashMap<String, Pair<Map<String, Any?>, Long>>>()
        val photos = HashMap<String, ByteArray>()
        val log = ArrayList<TrainingWrite>()
        var confirm = true
        var beforeFetch: (suspend () -> Unit)? = null
        var fullNoteReads = 0

        override suspend fun fetchTraining(uid: String, since: TrainingCursors): TrainingCloudSnapshot {
            beforeFetch?.invoke()
            if (since.notes == null) fullNoteReads++
            return TrainingCloudSnapshot(
                read(TrainingKind.WORKOUT, since.workouts, TrainingCodec::workoutFromMap),
                read(TrainingKind.CUSTOM_EXERCISE, since.customExercises, TrainingCodec::customExerciseFromMap),
                read(TrainingKind.NOTE, since.notes, TrainingCodec::noteFromMap),
                read(TrainingKind.ROUTINE, since.routines, TrainingCodec::routineFromMap),
            )
        }

        private fun <T> read(kind: TrainingKind, since: Long?, decode: (String, Map<String, Any?>, Long?) -> Decoded<T>?): RemoteDocs<T> {
            val docs = docs[kind].orEmpty()
                .filter { (_, entry) -> since == null || entry.second > since - FirestoreBackupRemote.PULL_OVERLAP_MS }
                .map { (docId, entry) -> TrainingCodec.RawDoc(docId, entry.first, entry.second) }
            return TrainingCodec.collect(docs, complete = since == null, decode = decode)
        }

        override suspend fun fetchPhoto(uid: String, hash: String): ByteArray? = photos[hash]

        /** Writes sent while the server does not confirm: they wait here, in order, until [flush]. */
        private val held = ArrayList<Pair<TrainingWrite, ByteArray?>>()

        override suspend fun writeTraining(
            uid: String,
            writes: List<TrainingWrite>,
            photoBytes: suspend (TrainingWrite.PutPhoto) -> ByteArray?,
        ): TrainingWriteResult {
            val uploaded = HashSet<String>()
            val queued = HashSet<String>()
            val deleted = HashSet<String>()
            val confirmed = HashSet<DocKey>()
            for (w in writes) {
                log += w
                val bytes = (w as? TrainingWrite.PutPhoto)?.let { photoBytes(it) }
                if (!confirm) {
                    held += w to bytes
                    if (w is TrainingWrite.PutPhoto && bytes != null) queued += w.hash
                    continue
                }
                land(w, bytes)
                when (w) {
                    is TrainingWrite.PutPhoto -> if (bytes != null) uploaded += w.hash
                    is TrainingWrite.DeletePhoto -> deleted += w.hash
                    else -> confirmed += w.key!!
                }
            }
            return TrainingWriteResult(confirmed, uploaded, deleted, allConfirmed = confirm, queuedPhotos = queued)
        }

        /** The offline queue reaches the server. */
        fun flush() {
            held.forEach { (w, bytes) -> land(w, bytes) }
            held.clear()
        }

        fun land(w: TrainingWrite, bytes: ByteArray? = null) {
            serverTime += 3_600_000L // one hour per write: cursors and the pull overlap behave as on the server
            when (w) {
                is TrainingWrite.PutPhoto -> bytes?.let { photos[w.hash] = it }
                is TrainingWrite.DeletePhoto -> photos.remove(w.hash)
                is TrainingWrite.PutWorkout -> putLive(TrainingKind.WORKOUT, w.workout.id, TrainingCodec.workoutToMap(w.workout))
                is TrainingWrite.PutCustomExercise -> putLive(TrainingKind.CUSTOM_EXERCISE, w.exercise.id, TrainingCodec.customExerciseToMap(w.exercise))
                is TrainingWrite.PutNote -> putLive(TrainingKind.NOTE, w.note.exerciseId, TrainingCodec.noteToMap(w.note))
                is TrainingWrite.PutRoutine -> putLive(TrainingKind.ROUTINE, w.routine.id, TrainingCodec.routineToMap(w.routine))
                is TrainingWrite.Delete ->
                    docs.getOrPut(w.kind) { LinkedHashMap() }[TrainingCodec.docId(w.id)] = TrainingCodec.tombstoneToMap(w.deletedAt) to serverTime
            }
        }

        /** A document written by someone else (another phone, another app version). */
        fun putRaw(kind: TrainingKind, id: String, data: Map<String, Any?>) {
            serverTime += 3_600_000L
            docs.getOrPut(kind) { LinkedHashMap() }[TrainingCodec.docId(id)] = data to serverTime
        }

        /** set(..., SetOptions.merge()) with deletedAt removed, as the Firestore layer writes live docs. */
        private fun putLive(kind: TrainingKind, id: String, data: Map<String, Any?>) {
            val collection = docs.getOrPut(kind) { LinkedHashMap() }
            val key = TrainingCodec.docId(id)
            collection[key] = ((collection[key]?.first.orEmpty() + data) - FirestoreCodec.F_DELETED_AT) to serverTime
        }

        fun live(kind: TrainingKind): Map<String, Map<String, Any?>> =
            docs[kind].orEmpty().filterValues { it.first[FirestoreCodec.F_DELETED] != true }.entries
                .associate { (k, v) -> TrainingCodec.rawId(k)!! to v.first }

        fun dead(kind: TrainingKind): Set<String> =
            docs[kind].orEmpty().filterValues { it.first[FirestoreCodec.F_DELETED] == true }.keys.mapTo(HashSet()) { TrainingCodec.rawId(it)!! }
    }

    private class FakePhotos : TrainingPhotoFiles {
        val files = LinkedHashMap<String, ByteArray>()
        private var next = 0

        override fun exists(name: String) = name in files

        override fun bytes(name: String) = files[name]

        override fun store(exerciseId: String, bytes: ByteArray): String {
            val name = "${exerciseId.replace(':', '_')}_${next++}.jpg"
            files[name] = bytes
            return name
        }

        override fun delete(name: String) {
            files.remove(name)
        }

        override fun deleteUnused(used: Set<String>, graceMs: Long) {
            files.keys.retainAll(used)
        }
    }

    /** Stores the encoded text, so the base codec is part of every round trip. */
    private class FakeBases : TrainingBaseStore {
        val texts = HashMap<String, String>()

        override suspend fun loadTrainingBase(uid: String): TrainingBase? = texts[uid]?.let(TrainingBaseCodec::decode)

        override suspend fun saveTrainingBase(uid: String, base: TrainingBase) {
            texts[uid] = TrainingBaseCodec.encode(base)
        }
    }

    private class Phone(
        val store: FakeTrainingStore,
        val repo: TrainingRepository,
        val photos: FakePhotos,
        val bases: FakeBases,
        val sync: TrainingSync,
    )

    private fun TestScope.phone(
        cloud: FakeCloud,
        store: FakeTrainingStore = FakeTrainingStore(),
        photos: FakePhotos = FakePhotos(),
        database: (FakeTrainingStore) -> TrainingStore = { it },
    ): Phone {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = CoroutineScope(SupervisorJob() + dispatcher).also { scopes += it }
        val repo = TrainingRepository(database(store), scope, io = dispatcher, compute = dispatcher, clock = { now }, newId = { "id${ids++}" })
        val bases = FakeBases()
        val sync = TrainingSync(repo, photos, cloud, bases, clock = { now }, io = dispatcher, compute = dispatcher)
        return Phone(store, repo, photos, bases, sync)
    }

    private suspend fun Phone.syncNow(): Boolean = sync.sync(uid, dataset)

    private suspend fun Phone.logWorkout(name: String): Workout {
        val w = repo.startWorkout(at = now, name = name)
        return repo.updateWorkout(w.id) {
            it.copy(
                endedAt = now + 3_600_000,
                entries = listOf(
                    WorkoutEntry(
                        "e-${w.id}", "barbell_bench_press",
                        listOf(WorkoutSet("s-${w.id}", weightKg = 80.0, reps = 5, done = true, doneAt = now + 60_000), WorkoutSet("p-${w.id}")),
                    ),
                ),
            )
        }!!
    }

    private suspend fun Phone.attachPhoto(exerciseId: String, bytes: ByteArray, text: String? = null) {
        val name = photos.store(exerciseId, bytes)
        val old = repo.notes.value[exerciseId]?.photo
        repo.updateNote(exerciseId) { it.copy(photo = name, notes = text ?: it.notes) }
        old?.let { photos.delete(it) } // what the screen does when the photo is replaced
    }

    private fun Phone.photoOf(exerciseId: String): ByteArray? = repo.notes.value[exerciseId]?.photo?.let { photos.files[it] }

    // ---- Tests -----------------------------------------------------------------------------------------

    @Test
    fun aNewPhoneRestoresWorkoutsMachinesNotesAndPhotos() = runTest {
        val cloud = FakeCloud()
        val a = phone(cloud)
        advanceUntilIdle()
        val machine = a.repo.createCustomExercise("Hack squat", ExerciseKind.WEIGHT_REPS, Equipment.MACHINE, setOf(Muscle.QUADS), setOf(Muscle.GLUTES))
        a.attachPhoto(machine.id, photoA, text = "seat 4")
        a.repo.setFavorite("leg_press_machine", true)
        val workout = a.logWorkout("Legs")
        assertFalse(a.syncNow())
        assertEquals(setOf(TrainingCodec.photoHash(photoA)), cloud.photos.keys)
        assertEquals(setOf(machine.id, "leg_press_machine"), cloud.live(TrainingKind.NOTE).keys)

        now += 86_400_000
        val b = phone(cloud)
        advanceUntilIdle()
        assertFalse(b.syncNow())
        assertEquals(listOf(workout), b.repo.workouts.value)
        assertEquals(listOf(machine), b.repo.customExercises.value)
        assertEquals("seat 4", b.repo.notes.value.getValue(machine.id).notes)
        assertArrayEquals(photoA, b.photoOf(machine.id))
        assertTrue(b.repo.notes.value.getValue("leg_press_machine").favorite)

        // Nothing changed: the next syncs of both phones write nothing.
        val writes = cloud.log.size
        assertFalse(b.syncNow())
        assertFalse(a.syncNow())
        assertEquals(writes, cloud.log.size)
    }

    @Test
    fun editsAndDeletionsTravelBetweenPhones() = runTest {
        val cloud = FakeCloud()
        val a = phone(cloud)
        advanceUntilIdle()
        val keep = a.logWorkout("Push")
        val drop = a.logWorkout("Pull")
        a.repo.updateNote("leg_press_machine") { it.copy(notes = "seat 4") }
        a.syncNow()
        val b = phone(cloud)
        advanceUntilIdle()
        b.syncNow()

        now += 60_000
        b.repo.updateNote("leg_press_machine") { it.copy(notes = "seat 5") }
        b.repo.deleteWorkout(drop.id)
        b.syncNow()
        assertTrue(b.store.tombstones.isEmpty()) // confirmed, so forgotten
        assertEquals(setOf(drop.id), cloud.dead(TrainingKind.WORKOUT))

        a.syncNow()
        assertEquals(listOf(keep.id), a.repo.workouts.value.map { it.id })
        assertEquals("seat 5", a.repo.notes.value.getValue("leg_press_machine").notes)
        assertTrue(a.store.tombstones.isEmpty()) // a deletion from the cloud leaves no local tombstone
    }

    @Test
    fun localDeletionIsForgottenOnlyAfterTheServerConfirmsIt() = runTest {
        val cloud = FakeCloud()
        val a = phone(cloud)
        advanceUntilIdle()
        val w = a.logWorkout("Push")
        a.syncNow()
        a.repo.deleteWorkout(w.id)

        cloud.confirm = false
        assertTrue(a.syncNow()) // still queued
        assertEquals(1, a.store.tombstones.size)

        cloud.confirm = true
        assertFalse(a.syncNow())
        assertTrue(a.store.tombstones.isEmpty())
        assertEquals(setOf(w.id), cloud.dead(TrainingKind.WORKOUT))
    }

    @Test
    fun phoneRestoredWithoutItsPhotoFilesDownloadsThemAndNeverRemovesTheCloudPhoto() = runTest {
        val cloud = FakeCloud()
        val a = phone(cloud)
        advanceUntilIdle()
        a.attachPhoto("leg_press_machine", photoA, text = "seat 4")
        a.attachPhoto("chest_press_machine", photoB, text = "seat 2")
        a.syncNow()
        val hashA = TrainingCodec.photoHash(photoA)
        val hashB = TrainingCodec.photoHash(photoB)

        // Auto Backup restored training.db (with the photo file names) but not the photo folder.
        val restored = FakeTrainingStore(notes = a.repo.notes.value.values.toList())
        now += 86_400_000
        val b = phone(cloud, restored)
        advanceUntilIdle()
        // Before the first sync the user edits one of the notes (its photo file is still missing).
        b.repo.updateNote("chest_press_machine") { it.copy(notes = "seat 3") }
        val writesBefore = cloud.log.size

        assertFalse(b.syncNow())
        val written = cloud.log.drop(writesBefore)
        assertTrue(written.none { it is TrainingWrite.DeletePhoto || it is TrainingWrite.PutPhoto })
        assertEquals(setOf(hashA, hashB), cloud.photos.keys)
        // The edit is uploaded, with the cloud's photo kept.
        val uploaded = written.filterIsInstance<TrainingWrite.PutNote>().single().note
        assertEquals("seat 3", uploaded.notes)
        assertEquals(hashB, uploaded.photoHash)
        assertEquals(hashB, cloud.live(TrainingKind.NOTE).getValue("chest_press_machine")["photoHash"])
        assertEquals(hashA, cloud.live(TrainingKind.NOTE).getValue("leg_press_machine")["photoHash"])
        // Both photos were downloaded and the notes point at the new local files.
        assertArrayEquals(photoA, b.photoOf("leg_press_machine"))
        assertArrayEquals(photoB, b.photoOf("chest_press_machine"))
        assertEquals("seat 4", b.repo.notes.value.getValue("leg_press_machine").notes)

        // Once the files are back, the notes are read incrementally again and nothing more is written.
        val fullReads = cloud.fullNoteReads
        val writes = cloud.log.size
        assertFalse(b.syncNow())
        assertEquals(fullReads, cloud.fullNoteReads)
        assertEquals(writes, cloud.log.size)
    }

    @Test
    fun replacedPhotoReplacesItEverywhereAndTheOldOneIsDeleted() = runTest {
        val cloud = FakeCloud()
        val a = phone(cloud)
        advanceUntilIdle()
        a.attachPhoto("leg_press_machine", photoA)
        a.syncNow()
        val b = phone(cloud)
        advanceUntilIdle()
        b.syncNow()
        val oldFileOnB = b.repo.notes.value.getValue("leg_press_machine").photo!!

        now += 60_000
        a.attachPhoto("leg_press_machine", photoB)
        a.syncNow()
        assertEquals(setOf(TrainingCodec.photoHash(photoB)), cloud.photos.keys)

        b.syncNow()
        assertArrayEquals(photoB, b.photoOf("leg_press_machine"))
        assertFalse(oldFileOnB in b.photos.files)
        assertEquals(1, b.photos.files.size)

        // Removing the photo removes it from the cloud too.
        now += 60_000
        b.repo.updateNote("leg_press_machine") { it.copy(photo = null, notes = "no photo") }
        b.syncNow()
        assertTrue(cloud.photos.isEmpty())
        a.syncNow()
        assertNull(a.repo.notes.value.getValue("leg_press_machine").photo)
    }

    @Test
    fun anEditMadeWhileTheSyncRunsIsNeverOverwritten() = runTest {
        val cloud = FakeCloud()
        val a = phone(cloud)
        advanceUntilIdle()
        val w = a.logWorkout("Push")
        a.syncNow()
        val b = phone(cloud)
        advanceUntilIdle()
        b.syncNow()

        now += 60_000
        a.repo.updateWorkout(w.id) { it.copy(name = "Edited on A") }
        a.syncNow()

        // B's user renames the workout after B read its data but before the cloud copy is applied.
        now += 60_000
        cloud.beforeFetch = { b.repo.updateWorkout(w.id) { it.copy(name = "Edited on B") } }
        b.syncNow()
        cloud.beforeFetch = null
        assertEquals("Edited on B", b.repo.workout(w.id)!!.name)

        // The newer edit (B's) wins on the next syncs, on both phones.
        b.syncNow()
        a.syncNow()
        assertEquals("Edited on B", a.repo.workout(w.id)!!.name)
        assertEquals("Edited on B", b.repo.workout(w.id)!!.name)
    }

    @Test
    fun restoringTheCloudCopyReplacesThisPhonesTraining() = runTest {
        val cloud = FakeCloud()
        val a = phone(cloud)
        advanceUntilIdle()
        val theirs = a.logWorkout("From the cloud")
        a.syncNow()

        val b = phone(cloud)
        advanceUntilIdle()
        val mine = b.logWorkout("Only on this phone")
        b.repo.updateNote("leg_press_machine") { it.copy(notes = "x") }
        b.repo.updateNote("leg_press_machine") { it.copy(notes = null) } // deleted: a tombstone
        assertEquals(1, b.store.tombstones.size)
        val deletedNoteAt = b.store.tombstones.values.single().deletedAt
        val writes = cloud.log.size

        now += 1_000 // the user chooses after logging
        b.sync.prepareTransition(uid, TrainingMode.ADOPT_REPLACE, dataset)
        b.syncNow()
        assertEquals(listOf(theirs), b.repo.workouts.value)
        assertNull(b.store.workouts[mine.id])
        assertTrue(b.store.tombstones.isEmpty())
        // The cloud's data is not touched: only the old local deletion goes up, as a tombstone of a doc it does not have.
        assertEquals(listOf(TrainingWrite.Delete(TrainingKind.NOTE, "leg_press_machine", deletedNoteAt)), cloud.log.drop(writes))
        assertEquals(setOf(theirs.id), cloud.live(TrainingKind.WORKOUT).keys)
        assertNull(b.bases.loadTrainingBase(uid)!!.pending)
    }

    @Test
    fun replacingTheCloudCopyRemovesWhatIsOnlyInTheCloud() = runTest {
        val cloud = FakeCloud()
        val a = phone(cloud)
        advanceUntilIdle()
        val theirs = a.logWorkout("Old cloud data")
        a.attachPhoto("leg_press_machine", photoA)
        a.syncNow()

        val b = phone(cloud)
        advanceUntilIdle()
        val mine = b.logWorkout("This phone")
        now += 1_000 // the user chooses after logging
        b.sync.prepareTransition(uid, TrainingMode.REPLACE_CLOUD, dataset)
        b.syncNow()
        assertEquals(setOf(mine.id), cloud.live(TrainingKind.WORKOUT).keys)
        assertEquals(setOf(theirs.id), cloud.dead(TrainingKind.WORKOUT))
        assertEquals(setOf("leg_press_machine"), cloud.dead(TrainingKind.NOTE))
        assertTrue(cloud.photos.isEmpty())
        assertEquals(listOf(mine), b.repo.workouts.value)
    }

    @Test
    fun aTransitionInterruptedByAnErrorIsFinishedByTheRetry() = runTest {
        val cloud = FakeCloud()
        val a = phone(cloud)
        advanceUntilIdle()
        val theirs = a.logWorkout("From the cloud")
        a.syncNow()
        val b = phone(cloud)
        advanceUntilIdle()
        b.logWorkout("Only on this phone")

        now += 1_000 // the user chooses after logging
        b.sync.prepareTransition(uid, TrainingMode.ADOPT_REPLACE, dataset)
        cloud.beforeFetch = { throw IOException("offline") }
        try {
            b.syncNow()
            fail("expected the connection error")
        } catch (e: IOException) {
            // retried by the engine
        }
        cloud.beforeFetch = null
        assertEquals(TrainingMode.ADOPT_REPLACE, b.bases.loadTrainingBase(uid)!!.pending)

        b.syncNow() // the retry is planned as the same restore, not as a merge
        assertEquals(listOf(theirs), b.repo.workouts.value)
    }

    @Test
    fun aFreshPhoneTakingOverTheCloudKeepsWhatItLogged() = runTest {
        val cloud = FakeCloud()
        val a = phone(cloud)
        advanceUntilIdle()
        val theirs = a.logWorkout("From the cloud")
        a.syncNow()
        val b = phone(cloud)
        advanceUntilIdle()
        val mine = b.logWorkout("Logged before signing in")

        b.sync.prepareTransition(uid, TrainingMode.ADOPT_MERGE, dataset)
        b.syncNow()
        assertEquals(setOf(theirs.id, mine.id), b.repo.workouts.value.map { it.id }.toSet())
        assertNotNull(cloud.live(TrainingKind.WORKOUT)[mine.id])
    }

    // ---- Review fixes -----------------------------------------------------------------------------------

    @Test
    fun aRetriedRestoreNeverRemovesAWorkoutLoggedAfterTheChoice() = runTest {
        val cloud = FakeCloud()
        val a = phone(cloud)
        advanceUntilIdle()
        val theirs = a.logWorkout("From the cloud")
        a.syncNow()
        val b = phone(cloud)
        advanceUntilIdle()
        val before = b.logWorkout("Before choosing")

        now += 1_000
        b.sync.prepareTransition(uid, TrainingMode.ADOPT_REPLACE, dataset)
        cloud.beforeFetch = { throw IOException("offline") }
        try {
            b.syncNow()
            fail("expected the connection error")
        } catch (e: IOException) {
            // retried by the engine, maybe much later
        }
        now += 60_000
        val after = b.logWorkout("After choosing")
        now += 1_000
        // The retry decides the same transition again: its original time is kept.
        b.sync.prepareTransition(uid, TrainingMode.ADOPT_REPLACE, dataset)
        cloud.beforeFetch = null
        b.syncNow()

        assertEquals(setOf(theirs.id, after.id), b.repo.workouts.value.map { it.id }.toSet())
        assertNotNull(cloud.live(TrainingKind.WORKOUT)[after.id])
        assertNull(cloud.live(TrainingKind.WORKOUT)[before.id])
    }

    @Test
    fun aMachineDeletedElsewhereButUsedHereStaysArchived() = runTest {
        val cloud = FakeCloud()
        val a = phone(cloud)
        advanceUntilIdle()
        val machine = a.repo.createCustomExercise("Hack squat", ExerciseKind.WEIGHT_REPS, Equipment.MACHINE, setOf(Muscle.QUADS), emptySet())
        val w = a.repo.startWorkout(at = now)
        a.repo.updateWorkout(w.id) { it.copy(entries = listOf(WorkoutEntry("e1", machine.id))) }
        a.syncNow()

        // A phone that never had these workouts deleted the machine.
        now += 60_000
        cloud.putRaw(TrainingKind.CUSTOM_EXERCISE, machine.id, TrainingCodec.tombstoneToMap(now))
        a.syncNow()
        val kept = a.repo.customExercises.value.single()
        assertTrue(kept.archived)
        assertTrue(kept.updatedAt > now)
        assertEquals(true, cloud.live(TrainingKind.CUSTOM_EXERCISE).getValue(machine.id)["archived"])
    }

    @Test
    fun aLateOlderWriteDoesNotResurrectADeletedWorkout() = runTest {
        val cloud = FakeCloud()
        val a = phone(cloud)
        advanceUntilIdle()
        val w = a.logWorkout("Push")
        a.syncNow()
        val b = phone(cloud)
        advanceUntilIdle()
        b.syncNow()
        val stale = cloud.live(TrainingKind.WORKOUT).getValue(w.id) // what an old queued put of it carries

        now += 60_000
        b.repo.deleteWorkout(w.id)
        b.syncNow()
        assertEquals(setOf(w.id), cloud.dead(TrainingKind.WORKOUT))

        cloud.putRaw(TrainingKind.WORKOUT, w.id, stale) // the old put lands: merge-set makes the document live again
        b.syncNow()
        assertEquals(setOf(w.id), cloud.dead(TrainingKind.WORKOUT)) // the tombstone is written again
        assertTrue(b.repo.workouts.value.isEmpty())
        a.syncNow()
        assertTrue(a.repo.workouts.value.isEmpty())
    }

    /** A database that refuses one workout, like SQLite with a colliding entry id. */
    private class RefusingStore(private val inner: FakeTrainingStore, private val refused: String) : TrainingStore by inner {
        override fun applyBatch(batch: TrainingBatch) {
            if (batch.upsertWorkouts.any { it.id == refused }) throw IllegalStateException("UNIQUE constraint failed: workout_entries.id")
            inner.applyBatch(batch)
        }
    }

    @Test
    fun aDocumentTheDatabaseRefusesDoesNotStopTheTrainingSync() = runTest {
        val cloud = FakeCloud()
        val a = phone(cloud)
        advanceUntilIdle()
        val good = a.logWorkout("Good")
        val bad = a.logWorkout("Bad")
        a.attachPhoto("leg_press_machine", photoA)
        a.syncNow()

        val b = phone(cloud, database = { RefusingStore(it, bad.id) })
        advanceUntilIdle()
        assertFalse(b.syncNow()) // no error: the rest is restored
        assertEquals(listOf(good.id), b.repo.workouts.value.map { it.id })
        assertArrayEquals(photoA, b.photoOf("leg_press_machine"))
        assertFalse(b.syncNow())
        assertTrue(cloud.live(TrainingKind.WORKOUT).containsKey(bad.id)) // never deleted from the cloud
    }

    @Test
    fun anUnconfirmedPhotoUploadIsNotSentAgainWhileItWaitsInTheQueue() = runTest {
        val cloud = FakeCloud()
        val a = phone(cloud)
        advanceUntilIdle()
        cloud.confirm = false
        a.attachPhoto("leg_press_machine", photoA)
        assertTrue(a.syncNow())
        assertTrue(a.syncNow())
        assertEquals(1, cloud.log.count { it is TrainingWrite.PutPhoto })
        assertEquals(2, cloud.log.count { it is TrainingWrite.PutNote }) // the small note is sent again

        cloud.confirm = true
        cloud.flush()
        assertFalse(a.syncNow())
        assertEquals(1, cloud.log.count { it is TrainingWrite.PutPhoto })
        assertEquals(setOf(TrainingCodec.photoHash(photoA)), cloud.photos.keys)
    }

    // ---- Routines ---------------------------------------------------------------------------------------

    @Test
    fun routinesTravelBetweenPhonesWithTheirEditsAndDeletions() = runTest {
        val cloud = FakeCloud()
        val a = phone(cloud)
        advanceUntilIdle()
        val legs = a.repo.createRoutine(
            "Legs A",
            listOf(RoutineExercise("leg_press_machine", 3, reps = 12), RoutineExercise("u:machine-only-on-a", 2)),
            note = "Rest 2 min",
        )
        val push = a.repo.createRoutine("Push", listOf(RoutineExercise("barbell_bench_press", 4, reps = 6), RoutineExercise("plank", 3, durationSec = 45)))
        assertFalse(a.syncNow())
        assertEquals(setOf(legs.id, push.id), cloud.live(TrainingKind.ROUTINE).keys)

        // A new phone restores them as they are, with an exercise it does not know (shown as unknown).
        now += 86_400_000
        val b = phone(cloud)
        advanceUntilIdle()
        assertFalse(b.syncNow())
        assertEquals(setOf(legs, push), b.repo.routines.value.toSet())
        assertTrue(b.store.tombstones.isEmpty())

        // Edited on B, deleted on A: each change reaches the other phone.
        now += 60_000
        b.repo.updateRoutine(legs.id) { it.copy(name = "Legs B", exercises = it.exercises + RoutineExercise("leg_extension_machine", 4, reps = 15)) }
        assertFalse(b.syncNow())
        a.repo.deleteRoutine(push.id)
        assertFalse(a.syncNow())
        assertTrue(a.store.tombstones.isEmpty()) // confirmed, so forgotten
        assertEquals(setOf(push.id), cloud.dead(TrainingKind.ROUTINE))
        assertEquals(b.repo.routine(legs.id), a.repo.routine(legs.id))
        assertEquals("Legs B", a.repo.routine(legs.id)!!.name)

        assertFalse(b.syncNow())
        assertEquals(listOf(legs.id), b.repo.routines.value.map { it.id })
        assertTrue(b.store.tombstones.isEmpty()) // a deletion from the cloud leaves no local tombstone

        // Nothing changed: the next syncs of both phones write nothing.
        val writes = cloud.log.size
        assertFalse(a.syncNow())
        assertFalse(b.syncNow())
        assertEquals(writes, cloud.log.size)
    }

    @Test
    fun aRoutineDeletionIsForgottenOnlyAfterTheServerConfirmsIt() = runTest {
        val cloud = FakeCloud()
        val a = phone(cloud)
        advanceUntilIdle()
        val r = a.repo.createRoutine("Core", listOf(RoutineExercise("plank", 3, durationSec = 60)))
        a.syncNow()
        a.repo.deleteRoutine(r.id)

        cloud.confirm = false
        assertTrue(a.syncNow()) // still queued
        assertEquals(setOf(LocalTombstone.ROUTINE to r.id), a.store.tombstones.keys)

        cloud.confirm = true
        assertFalse(a.syncNow())
        assertTrue(a.store.tombstones.isEmpty())
        assertEquals(setOf(r.id), cloud.dead(TrainingKind.ROUTINE))
    }

    @Test
    fun aRoutineEditedWhileTheSyncRunsIsNeverOverwritten() = runTest {
        val cloud = FakeCloud()
        val a = phone(cloud)
        advanceUntilIdle()
        val r = a.repo.createRoutine("Pull", listOf(RoutineExercise("lat_pulldown_machine", 3, reps = 10)))
        a.syncNow()
        val b = phone(cloud)
        advanceUntilIdle()
        b.syncNow()

        now += 60_000
        a.repo.updateRoutine(r.id) { it.copy(name = "Edited on A") }
        a.syncNow()

        // B's user renames the routine after B read its data but before the cloud copy is applied.
        now += 60_000
        cloud.beforeFetch = { b.repo.updateRoutine(r.id) { it.copy(name = "Edited on B") } }
        b.syncNow()
        cloud.beforeFetch = null
        assertEquals("Edited on B", b.repo.routine(r.id)!!.name)

        // The newer edit (B's) wins on the next syncs, on both phones.
        b.syncNow()
        a.syncNow()
        assertEquals("Edited on B", a.repo.routine(r.id)!!.name)
        assertEquals("Edited on B", b.repo.routine(r.id)!!.name)
    }

    @Test
    fun restoringOrReplacingTheCloudCopyTakesTheRoutinesAlong() = runTest {
        val cloud = FakeCloud()
        val a = phone(cloud)
        advanceUntilIdle()
        val theirs = a.repo.createRoutine("From the cloud", listOf(RoutineExercise("plank", 3, durationSec = 30)))
        a.syncNow()

        // "Restore the cloud copy": the routines saved only on this phone are replaced.
        val b = phone(cloud)
        advanceUntilIdle()
        val mine = b.repo.createRoutine("Only on this phone", listOf(RoutineExercise("leg_press_machine", 3, reps = 10)))
        now += 1_000 // the user chooses after saving it
        b.sync.prepareTransition(uid, TrainingMode.ADOPT_REPLACE, dataset)
        b.syncNow()
        assertEquals(listOf(theirs), b.repo.routines.value)
        assertNull(b.store.routines[mine.id])
        assertEquals(setOf(theirs.id), cloud.live(TrainingKind.ROUTINE).keys)

        // "Replace the cloud copy" on a third phone: the cloud's routines give way to its own.
        val c = phone(cloud)
        advanceUntilIdle()
        val fresh = c.repo.createRoutine("Phone C", listOf(RoutineExercise("leg_press_machine", 4, reps = 8)))
        now += 1_000
        c.sync.prepareTransition(uid, TrainingMode.REPLACE_CLOUD, dataset)
        c.syncNow()
        assertEquals(setOf(fresh.id), cloud.live(TrainingKind.ROUTINE).keys)
        assertEquals(setOf(theirs.id), cloud.dead(TrainingKind.ROUTINE))

        // The other phones follow with a plain merge.
        a.syncNow()
        assertEquals(listOf(fresh), a.repo.routines.value)
    }

    @Test
    fun aLateOlderWriteDoesNotResurrectADeletedRoutine() = runTest {
        val cloud = FakeCloud()
        val a = phone(cloud)
        advanceUntilIdle()
        val r = a.repo.createRoutine("Push", listOf(RoutineExercise("barbell_bench_press", 3, reps = 8)))
        a.syncNow()
        val b = phone(cloud)
        advanceUntilIdle()
        b.syncNow()
        val stale = cloud.live(TrainingKind.ROUTINE).getValue(r.id) // what an old queued put of it carries

        now += 60_000
        b.repo.deleteRoutine(r.id)
        b.syncNow()
        assertEquals(setOf(r.id), cloud.dead(TrainingKind.ROUTINE))

        cloud.putRaw(TrainingKind.ROUTINE, r.id, stale) // the old put lands: merge-set makes the document live again
        b.syncNow()
        assertEquals(setOf(r.id), cloud.dead(TrainingKind.ROUTINE)) // the tombstone is written again
        assertTrue(b.repo.routines.value.isEmpty())
        a.syncNow()
        assertTrue(a.repo.routines.value.isEmpty())
    }
}
