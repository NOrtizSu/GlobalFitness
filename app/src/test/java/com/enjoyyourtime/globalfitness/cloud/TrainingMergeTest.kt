package com.enjoyyourtime.globalfitness.cloud

import com.enjoyyourtime.globalfitness.training.model.CustomExercise
import com.enjoyyourtime.globalfitness.training.model.Equipment
import com.enjoyyourtime.globalfitness.training.model.ExerciseKind
import com.enjoyyourtime.globalfitness.training.model.Muscle
import com.enjoyyourtime.globalfitness.training.model.Routine
import com.enjoyyourtime.globalfitness.training.model.RoutineExercise
import com.enjoyyourtime.globalfitness.training.model.Workout
import com.enjoyyourtime.globalfitness.training.model.WorkoutEntry
import com.enjoyyourtime.globalfitness.training.model.WorkoutSet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.enjoyyourtime.globalfitness.training.model.Tombstone as LocalTombstone

class TrainingMergeTest {
    private val now = 2_000_000_000_000L
    private val dataset = "ds-1"
    private val h1 = TrainingCodec.photoHash(byteArrayOf(1))
    private val h2 = TrainingCodec.photoHash(byteArrayOf(2))

    private fun workout(id: String = "w1", updatedAt: Long = 100, name: String? = "Push") = Workout(
        id = id, startedAt = 1_000, endedAt = 2_000, name = name, note = null, updatedAt = updatedAt,
        entries = listOf(WorkoutEntry("e-$id", "barbell_bench_press", listOf(WorkoutSet("s-$id", weightKg = 80.0, reps = 5, done = true, doneAt = 1_500)))),
    )

    private fun custom(id: String = "u:1", updatedAt: Long = 100, name: String = "My press") =
        CustomExercise(id, name, ExerciseKind.WEIGHT_REPS, Equipment.MACHINE, setOf(Muscle.CHEST), emptySet(), false, 1, updatedAt)

    private fun note(id: String = "leg_press_machine", updatedAt: Long = 100, photo: String? = null, text: String? = "seat 4", missing: Boolean = false) =
        NoteRecord(id, text, favorite = false, photoHash = photo, updatedAt = updatedAt, photoMissing = missing)

    private fun routine(id: String = "r1", updatedAt: Long = 100, name: String = "Legs A", note: String? = null) = Routine(
        id = id, name = name, note = note, createdAt = 50, updatedAt = updatedAt,
        exercises = listOf(RoutineExercise("leg_press_machine", 3, reps = 12), RoutineExercise("u:own-machine", 2), RoutineExercise("plank", 3, durationSec = 45)),
    )

    private fun local(
        workouts: List<Workout> = emptyList(),
        custom: List<CustomExercise> = emptyList(),
        notes: List<NoteRecord> = emptyList(),
        tombstones: List<LocalTombstone> = emptyList(),
        files: Map<String, String> = mapOf(h1 to "f1.jpg", h2 to "f2.jpg"),
        routines: List<Routine> = emptyList(),
    ) = TrainingLocal(workouts, custom, notes, routines, tombstones, files.filterKeys { h -> notes.any { it.photoHash == h && !it.photoMissing } })

    private fun remote(
        workouts: List<Workout> = emptyList(),
        deadWorkouts: List<Tombstone> = emptyList(),
        custom: List<CustomExercise> = emptyList(),
        notes: List<NoteRecord> = emptyList(),
        deadNotes: List<Tombstone> = emptyList(),
        complete: Boolean = true,
        max: Long? = null,
        routines: List<Routine> = emptyList(),
        deadRoutines: List<Tombstone> = emptyList(),
    ) = TrainingCloudSnapshot(
        RemoteDocs(workouts, deadWorkouts, complete, max),
        RemoteDocs(custom, emptyList(), complete, max),
        RemoteDocs(notes, deadNotes, complete, max),
        RemoteDocs(routines, deadRoutines, complete, max),
    )

    private fun base(vararg entries: Pair<DocKey, String>, photos: Set<String> = emptySet()) =
        TrainingBase(dataset, entries = entries.toMap(), photos = photos)

    private fun agreed(w: Workout) = DocKey(TrainingKind.WORKOUT, w.id) to TrainingMerge.versionOf(w)
    private fun agreed(n: NoteRecord) = DocKey(TrainingKind.NOTE, n.exerciseId) to TrainingMerge.versionOf(n)
    private fun agreed(r: Routine) = DocKey(TrainingKind.ROUTINE, r.id) to TrainingMerge.versionOf(r)

    private fun merge(local: TrainingLocal, remote: TrainingCloudSnapshot, base: TrainingBase? = null) =
        TrainingMerge.plan(TrainingMode.MERGE, local, remote, base, now)

    private fun TrainingMerge.Plan.workoutPuts() = docWrites.filterIsInstance<TrainingWrite.PutWorkout>().map { it.workout }
    private fun TrainingMerge.Plan.notePuts() = docWrites.filterIsInstance<TrainingWrite.PutNote>().map { it.note }
    private fun TrainingMerge.Plan.routinePuts() = docWrites.filterIsInstance<TrainingWrite.PutRoutine>().map { it.routine }
    private fun TrainingMerge.Plan.deletes() = docWrites.filterIsInstance<TrainingWrite.Delete>()
    private fun TrainingMerge.Plan.deletedPhotos() = photoDeletes.map { it.hash }.toSet()
    private fun TrainingMerge.Plan.uploadedPhotos() = photoUploads.map { it.hash }.toSet()

    private fun workoutTombstone(id: String, at: Long) = LocalTombstone(LocalTombstone.WORKOUT, id, at)
    private fun noteTombstone(id: String, at: Long) = LocalTombstone(LocalTombstone.NOTE, id, at)
    private fun routineTombstone(id: String, at: Long) = LocalTombstone(LocalTombstone.ROUTINE, id, at)

    // ---- First sync ---------------------------------------------------------------------------------

    @Test
    fun phoneWithTrainingAndAnEmptyCloudUploadsEverything() {
        val n = note(photo = h1)
        val phone = local(listOf(workout()), listOf(custom()), listOf(n), routines = listOf(routine()))
        for (mode in listOf(TrainingMode.MERGE, TrainingMode.REPLACE_CLOUD, TrainingMode.ADOPT_MERGE, TrainingMode.UNION)) {
            val plan = TrainingMerge.plan(mode, phone, TrainingCloudSnapshot.EMPTY, null, now)
            assertEquals(mode.name, 4, plan.docWrites.size)
            assertEquals(mode.name, listOf(routine()), plan.routinePuts())
            assertEquals(mode.name, listOf(TrainingWrite.PutPhoto(h1, "f1.jpg")), plan.photoUploads)
            assertTrue(mode.name, plan.apply.isEmpty)
            assertTrue(mode.name, plan.photoDeletes.isEmpty())
        }
    }

    @Test
    fun newPhoneWithAnEmptyTrainingDatabaseRestoresEverything() {
        val cloud = remote(listOf(workout(), workout("w2")), custom = listOf(custom()), notes = listOf(note(photo = h1)), routines = listOf(routine()))
        for (mode in listOf(TrainingMode.MERGE, TrainingMode.ADOPT_MERGE, TrainingMode.ADOPT_REPLACE)) {
            val plan = TrainingMerge.plan(mode, local(), cloud, null, now)
            assertEquals(mode.name, setOf("w1", "w2"), plan.apply.upsertWorkouts.map { it.id }.toSet())
            assertEquals(mode.name, listOf(custom()), plan.apply.upsertCustomExercises)
            assertEquals(mode.name, h1, plan.apply.upsertNotes.single().photoHash)
            assertEquals(mode.name, listOf(routine()), plan.apply.upsertRoutines)
            assertTrue(mode.name, plan.writes.isEmpty())
        }
    }

    // ---- Last writer wins ------------------------------------------------------------------------

    @Test
    fun newerVersionWinsInBothDirections() {
        val older = workout(updatedAt = 100, name = "Push")
        val newer = workout(updatedAt = 200, name = "Push day")

        val upload = merge(local(listOf(newer)), remote(listOf(older), complete = false), base(agreed(older)))
        assertEquals(listOf(newer), upload.workoutPuts())
        assertTrue(upload.apply.isEmpty)

        val download = merge(local(listOf(older)), remote(listOf(newer), complete = false), base(agreed(older)))
        assertEquals(listOf(newer), download.apply.upsertWorkouts)
        assertTrue(download.writes.isEmpty())
    }

    @Test
    fun aLateOlderWriteInTheCloudIsHealed() {
        // The cloud holds an older copy than the one this phone agreed on (a stale queued write landed late).
        val mine = workout(updatedAt = 300, name = "Legs")
        val stale = workout(updatedAt = 200, name = "Leg")
        val plan = merge(local(listOf(mine)), remote(listOf(stale), complete = false), base(agreed(mine)))
        assertEquals(listOf(mine), plan.workoutPuts())
    }

    @Test
    fun tieGoesToTheCloudCopy() {
        val plan = merge(local(listOf(workout(name = "A"))), remote(listOf(workout(name = "B"))))
        assertEquals("B", plan.apply.upsertWorkouts.single().name)
        assertTrue(plan.writes.isEmpty())
    }

    @Test
    fun unchangedDataIsANoOpAndLocalEditsAreUploaded() {
        val w = workout()
        val n = note(photo = h1)
        val synced = base(agreed(w), agreed(n), photos = setOf(h1))
        assertTrue(merge(local(listOf(w), notes = listOf(n)), remote(complete = false), synced).isNoOp)

        val edited = w.copy(updatedAt = 150, name = "Push + core")
        assertEquals(listOf(edited), merge(local(listOf(edited), notes = listOf(n)), remote(complete = false), synced).workoutPuts())
    }

    @Test
    fun customExercisesFollowTheSameRules() {
        val synced = custom(updatedAt = 100)
        val archived = synced.copy(archived = true, updatedAt = 150)
        val plan = merge(
            local(custom = listOf(archived)),
            remote(complete = false),
            base(DocKey(TrainingKind.CUSTOM_EXERCISE, synced.id) to TrainingMerge.versionOf(synced)),
        )
        assertEquals(listOf(TrainingWrite.PutCustomExercise(archived)), plan.docWrites)
    }

    // ---- Tombstones --------------------------------------------------------------------------------

    @Test
    fun cloudTombstoneDeletesAnOlderLocalCopy() {
        val plan = merge(local(listOf(workout(updatedAt = 100))), remote(deadWorkouts = listOf(Tombstone("w1", 200))))
        assertEquals(listOf("w1"), plan.apply.removeWorkouts)
        assertTrue(plan.writes.isEmpty())
    }

    @Test
    fun localEditNewerThanTheTombstoneResurrectsIt() {
        val edited = workout(updatedAt = 300)
        val plan = merge(local(listOf(edited)), remote(deadWorkouts = listOf(Tombstone("w1", 200))))
        assertEquals(listOf(edited), plan.workoutPuts())
        assertTrue(plan.apply.isEmpty)
    }

    @Test
    fun tombstoneWinsOverTheAgreedVersionWhateverTheClocks() {
        // This phone's clock ran ahead: updatedAt > deletedAt, but it did not change the workout since the last sync.
        val w = workout(updatedAt = 900)
        val plan = merge(local(listOf(w)), remote(deadWorkouts = listOf(Tombstone("w1", 500)), complete = false), base(agreed(w)))
        assertEquals(listOf("w1"), plan.apply.removeWorkouts)
    }

    @Test
    fun localDeletionIsUploadedAndForgottenOnlyOnceConfirmed() {
        val w = workout(updatedAt = 100)
        val t = workoutTombstone("w1", 500)
        val plan = merge(local(tombstones = listOf(t)), remote(complete = false), base(agreed(w)))
        assertEquals(listOf(TrainingWrite.Delete(TrainingKind.WORKOUT, "w1", 500)), plan.deletes())
        assertTrue(plan.dropNow.isEmpty())
        assertEquals(mapOf(DocKey(TrainingKind.WORKOUT, "w1") to t), plan.dropOnConfirm)
    }

    @Test
    fun deletingTheAgreedVersionIsDatedPastIt() {
        // Deleted on a phone whose clock is behind the version it deleted.
        val w = workout(updatedAt = 1_000)
        val plan = merge(local(tombstones = listOf(workoutTombstone("w1", 400))), remote(listOf(w), complete = false), base(agreed(w)))
        assertEquals(1_001L, plan.deletes().single().deletedAt)
        assertTrue(plan.apply.isEmpty)
    }

    @Test
    fun editElsewhereAfterTheDeletionHereWins() {
        val synced = workout(updatedAt = 100)
        val editedThere = workout(updatedAt = 600, name = "Edited")
        val t = workoutTombstone("w1", 500)
        val plan = merge(local(tombstones = listOf(t)), remote(listOf(editedThere), complete = false), base(agreed(synced)))
        assertEquals(listOf(editedThere), plan.apply.upsertWorkouts)
        assertTrue(plan.deletes().isEmpty())
        assertEquals(listOf(t), plan.dropNow)
    }

    @Test
    fun deletionTheCloudShowsIsDroppedAnyOtherIsUploaded() {
        val alreadyThere = workoutTombstone("w1", 500)
        // Not in this read (e.g. never confirmed): its put may still wait in the offline queue, so the
        // tombstone is uploaded anyway and only forgotten once confirmed.
        val notShown = workoutTombstone("w2", 600)
        val plan = merge(local(tombstones = listOf(alreadyThere, notShown)), remote(deadWorkouts = listOf(Tombstone("w1", 500)), complete = false), base())
        assertEquals(listOf(alreadyThere), plan.dropNow)
        assertEquals(listOf(TrainingWrite.Delete(TrainingKind.WORKOUT, "w2", 600)), plan.writes)
        assertEquals(notShown, plan.dropOnConfirm[DocKey(TrainingKind.WORKOUT, "w2")])
    }

    // ---- Photos -------------------------------------------------------------------------------------

    @Test
    fun textEditDoesNotUploadTheUnchangedPhotoAgain() {
        val synced = note(photo = h1)
        val edited = synced.copy(notes = "seat 5", updatedAt = 200)
        val plan = merge(local(notes = listOf(edited)), remote(complete = false), base(agreed(synced), photos = setOf(h1)))
        assertEquals(listOf(edited), plan.notePuts())
        assertTrue(plan.photoUploads.isEmpty())
        assertTrue(plan.photoDeletes.isEmpty())
    }

    @Test
    fun replacedPhotoUploadsTheNewOneAndDeletesTheOld() {
        val synced = note(photo = h1)
        val replaced = synced.copy(photoHash = h2, updatedAt = 200)
        val plan = merge(local(notes = listOf(replaced)), remote(complete = false), base(agreed(synced), photos = setOf(h1)))
        assertEquals(listOf(TrainingWrite.PutPhoto(h2, "f2.jpg")), plan.photoUploads)
        assertEquals(listOf(replaced), plan.notePuts())
        assertEquals(setOf(h1), plan.deletedPhotos())
        // Order: the new photo before the note, the old one deleted after it.
        val writes = plan.writes
        assertTrue(writes.indexOf(TrainingWrite.PutPhoto(h2, "f2.jpg")) < writes.indexOf(TrainingWrite.PutNote(replaced)))
        assertTrue(writes.indexOf(TrainingWrite.PutNote(replaced)) < writes.indexOf(TrainingWrite.DeletePhoto(h1)))
    }

    @Test
    fun removedPhotoOrDeletedNoteDeletesThePhotoFromTheCloud() {
        val synced = note(photo = h1)
        val removed = synced.copy(photoHash = null, updatedAt = 200)
        val plan = merge(local(notes = listOf(removed)), remote(complete = false), base(agreed(synced), photos = setOf(h1)))
        assertNull(plan.notePuts().single().photoHash)
        assertEquals(setOf(h1), plan.deletedPhotos())

        val deleted = merge(local(tombstones = listOf(noteTombstone(synced.exerciseId, 300))), remote(complete = false), base(agreed(synced), photos = setOf(h1)))
        assertEquals(TrainingKind.NOTE, deleted.deletes().single().kind)
        assertEquals(setOf(h1), deleted.deletedPhotos())
    }

    @Test
    fun photoChangedElsewhereIsApplied() {
        val synced = note(photo = h1)
        val theirs = synced.copy(photoHash = h2, updatedAt = 200)
        val plan = merge(local(notes = listOf(synced)), remote(notes = listOf(theirs), complete = false), base(agreed(synced), photos = setOf(h1)))
        assertEquals(listOf(theirs), plan.apply.upsertNotes)
        assertTrue(plan.photoUploads.isEmpty())
        assertTrue(plan.notePuts().isEmpty())
    }

    @Test
    fun photoSharedByAnotherNoteIsKept() {
        val a = note("a", photo = h1)
        val b = note("b", photo = h1)
        val aWithoutPhoto = a.copy(photoHash = null, updatedAt = 200)
        val plan = merge(local(notes = listOf(aWithoutPhoto, b)), remote(complete = false), base(agreed(a), agreed(b), photos = setOf(h1)))
        assertTrue(plan.photoDeletes.isEmpty())
    }

    @Test
    fun noteBeatingACloudVersionWithAnotherPhotoSendsItsPhotoAgain() {
        // The other writer may have deleted our photo when it replaced it.
        val mine = note(photo = h1, updatedAt = 300)
        val theirs = note(photo = h2, updatedAt = 200)
        val plan = merge(local(notes = listOf(mine)), remote(notes = listOf(theirs), complete = false), base(photos = setOf(h1)))
        assertEquals(setOf(h1), plan.uploadedPhotos())
        assertEquals(listOf(mine), plan.notePuts())
        assertEquals(setOf(h2), plan.deletedPhotos())
    }

    // ---- Photo files missing on the phone (restored without the photo folder) --------------------

    @Test
    fun restoredNoteWithoutItsPhotoFileDownloadsTheCloudPhotoInsteadOfRemovingIt() {
        val cloud = note(photo = h1, updatedAt = 100)
        val restored = note(photo = null, updatedAt = 100, missing = true)
        val plan = merge(local(notes = listOf(restored)), remote(notes = listOf(cloud)))
        // Applied locally with the cloud's photo (the sync downloads it and stores it under a new name)...
        assertEquals(listOf(cloud), plan.apply.upsertNotes)
        // ...and nothing about the photo goes to the cloud.
        assertTrue(plan.writes.isEmpty())
    }

    @Test
    fun newerEditOfANoteWithAMissingPhotoKeepsTheCloudPhoto() {
        val cloud = note(photo = h1, updatedAt = 100, text = "seat 4")
        val edited = note(photo = null, updatedAt = 200, text = "seat 5", missing = true)
        val plan = merge(local(notes = listOf(edited)), remote(notes = listOf(cloud)), base(photos = setOf(h1)))
        val uploaded = plan.notePuts().single()
        assertEquals("seat 5", uploaded.notes)
        assertEquals(h1, uploaded.photoHash) // not a removal
        assertEquals(h1, plan.apply.upsertNotes.single().photoHash) // and downloaded here
        assertTrue(plan.photoDeletes.isEmpty())
        assertTrue(plan.photoUploads.isEmpty())
    }

    @Test
    fun missingPhotoIsLeftAloneUntilTheNotesAreReadWhole() {
        val synced = note(photo = h1)
        val restored = synced.copy(photoHash = null, photoMissing = true)
        val plan = merge(local(notes = listOf(restored)), remote(complete = false), base(agreed(synced), photos = setOf(h1)))
        assertTrue(plan.isNoOp)
        assertTrue(plan.photoDeletes.isEmpty())
    }

    @Test
    fun missingPhotoOfANoteTheCloudNeverHadIsNotUploadedAsAPhoto() {
        val restored = note(photo = null, updatedAt = 100, missing = true)
        val plan = merge(local(notes = listOf(restored)), remote())
        assertNull(plan.notePuts().single().photoHash)
        assertTrue(plan.photoUploads.isEmpty())
        assertTrue(plan.photoDeletes.isEmpty())
        assertNull(plan.apply.upsertNotes.single().photoHash) // the dangling file name is cleared here
    }

    // ---- Dataset transitions ------------------------------------------------------------------------

    @Test
    fun restoringTheCloudCopyReplacesThePhonesTraining() {
        val onlyHere = workout("here")
        val shared = workout("shared", updatedAt = 100)
        val cloudShared = workout("shared", updatedAt = 50, name = "Cloud name")
        val t = workoutTombstone("gone", 10)
        val plan = TrainingMerge.plan(
            TrainingMode.ADOPT_REPLACE,
            local(listOf(onlyHere, shared), notes = listOf(note("mine", photo = h2)), tombstones = listOf(t)),
            remote(listOf(cloudShared, workout("there")), notes = listOf(note("theirs", photo = h1))),
            null,
            now,
        )
        assertEquals(setOf("here"), plan.apply.removeWorkouts.toSet())
        assertEquals(setOf(cloudShared, workout("there")), plan.apply.upsertWorkouts.toSet()) // the cloud wins even if older
        assertEquals(listOf("mine"), plan.apply.removeNotes)
        // The cloud copy is not touched: only the old deletion is sent as a tombstone, for a doc the cloud does not have.
        assertEquals(listOf(TrainingWrite.Delete(TrainingKind.WORKOUT, "gone", 10)), plan.writes)
        assertEquals(t, plan.dropOnConfirm[DocKey(TrainingKind.WORKOUT, "gone")])
    }

    @Test
    fun automaticRestoreKeepsWhatWasLoggedBeforeSigningIn() {
        val loggedHere = workout("here")
        val myNote = note("leg_press_machine", text = "fav only", updatedAt = 9_000)
        val cloudNote = note("leg_press_machine", text = "seat 4", photo = h1, updatedAt = 100)
        val clearedHere = noteTombstone("chest_press", 9_000)
        val plan = TrainingMerge.plan(
            TrainingMode.ADOPT_MERGE,
            local(listOf(loggedHere), notes = listOf(myNote), tombstones = listOf(clearedHere)),
            remote(listOf(workout("there")), notes = listOf(cloudNote, note("chest_press", photo = h2))),
            null,
            now,
        )
        assertEquals(listOf(loggedHere), plan.workoutPuts())
        assertEquals(listOf("there"), plan.apply.upsertWorkouts.map { it.id })
        // The cloud copy wins collisions, and a deletion from before joining the dataset is not propagated.
        assertEquals(setOf(cloudNote, note("chest_press", photo = h2)), plan.apply.upsertNotes.toSet())
        assertTrue(plan.deletes().isEmpty())
        assertEquals(listOf(clearedHere), plan.dropNow)
        assertTrue(plan.photoDeletes.isEmpty())
    }

    @Test
    fun replacingTheCloudCopyUploadsThePhoneAndRemovesTheRest() {
        val mine = workout("mine")
        val theirs = workout("theirs", updatedAt = now + 5)
        val theirNote = note("theirs", photo = h1)
        val t = workoutTombstone("theirs", 10)
        val plan = TrainingMerge.plan(
            TrainingMode.REPLACE_CLOUD,
            local(listOf(mine), notes = listOf(note("mine", photo = h2)), tombstones = listOf(t)),
            remote(listOf(theirs), notes = listOf(theirNote)),
            null,
            now,
        )
        assertEquals(listOf(mine), plan.workoutPuts())
        val deletes = plan.deletes().associateBy { it.kind to it.id }
        assertEquals(setOf(TrainingKind.WORKOUT to "theirs", TrainingKind.NOTE to "theirs"), deletes.keys)
        assertEquals(now + 6, deletes.getValue(TrainingKind.WORKOUT to "theirs").deletedAt) // past the version it removes
        assertEquals(t, plan.dropOnConfirm[DocKey(TrainingKind.WORKOUT, "theirs")])
        assertEquals(setOf(h2), plan.uploadedPhotos())
        assertEquals(setOf(h1), plan.deletedPhotos())
        assertTrue(plan.apply.isEmpty)
    }

    @Test
    fun transitionsFollowTheDatasetDecision() {
        assertEquals(TrainingMode.ADOPT_REPLACE, TrainingMerge.transitionFor(SyncMode.AdoptCloud, ConflictChoice.RESTORE_CLOUD))
        assertEquals(TrainingMode.ADOPT_MERGE, TrainingMerge.transitionFor(SyncMode.AdoptCloud, null))
        assertEquals(TrainingMode.REPLACE_CLOUD, TrainingMerge.transitionFor(SyncMode.ReplaceCloud, ConflictChoice.REPLACE_CLOUD))
        // Automatic (empty cloud, or a deleted dataset there): never deletes cloud training without asking.
        assertEquals(TrainingMode.UNION, TrainingMerge.transitionFor(SyncMode.ReplaceCloud, null))
        assertNull(TrainingMerge.transitionFor(SyncMode.Merge, ConflictChoice.CONFIRM_DELETIONS))
        assertNull(TrainingMerge.transitionFor(SyncMode.RemoteWiped, null))

        // A pending transition of this dataset is finished even if the retry is a plain merge.
        val pending = TrainingBase(dataset, pending = TrainingMode.ADOPT_REPLACE)
        assertEquals(TrainingMode.ADOPT_REPLACE, TrainingMerge.modeFor(pending, dataset))
        assertEquals(TrainingMode.MERGE, TrainingMerge.modeFor(pending, "other"))
        assertEquals(TrainingMode.MERGE, TrainingMerge.modeFor(null, dataset))
    }

    // ---- Next base ------------------------------------------------------------------------------------

    @Test
    fun onlyConfirmedUploadsAreRecordedSoTheRestIsRetried() {
        val a = workout("a")
        val b = workout("b")
        val phone = local(listOf(a, b))
        val plan = merge(phone, TrainingCloudSnapshot.EMPTY)
        val result = TrainingWriteResult(setOf(DocKey(TrainingKind.WORKOUT, "a")), emptySet(), emptySet(), allConfirmed = false)
        val next = TrainingMerge.nextBase(null, dataset, TrainingCloudSnapshot.EMPTY, plan, result, emptySet(), emptyMap(), emptySet(), emptyMap())
        assertEquals(TrainingMerge.versionOf(a), next.entries[DocKey(TrainingKind.WORKOUT, "a")])
        assertNull(next.entries[DocKey(TrainingKind.WORKOUT, "b")])

        val again = merge(phone, remote(complete = false), next)
        assertEquals(listOf(b), again.workoutPuts())
    }

    @Test
    fun unconfirmedTombstoneIsUploadedAgain() {
        val w = workout()
        val synced = base(agreed(w))
        val t = workoutTombstone("w1", 500)
        val plan = merge(local(tombstones = listOf(t)), remote(complete = false), synced)
        val next = TrainingMerge.nextBase(synced, dataset, remote(complete = false), plan, TrainingWriteResult(emptySet(), emptySet(), emptySet(), false), emptySet(), emptyMap(), emptySet(), emptyMap())
        assertEquals(1, merge(local(tombstones = listOf(t)), remote(complete = false), next).deletes().size)
    }

    @Test
    fun cursorsAdvanceExceptWhereAnApplyWasSkipped() {
        val previous = TrainingBase(
            dataset,
            cursors = mapOf(TrainingKind.WORKOUT to 50L, TrainingKind.NOTE to 50L, TrainingKind.CUSTOM_EXERCISE to 50L, TrainingKind.ROUTINE to 50L),
        )
        val cloud = TrainingCloudSnapshot(
            RemoteDocs(listOf(workout()), emptyList(), complete = false, maxUpdatedAt = 70L),
            RemoteDocs(emptyList(), emptyList(), complete = false, maxUpdatedAt = null),
            RemoteDocs(emptyList(), emptyList(), complete = true, maxUpdatedAt = 40L),
            RemoteDocs(listOf(routine()), emptyList(), complete = false, maxUpdatedAt = 80L),
        )
        val plan = merge(local(), cloud, previous)
        val next = TrainingMerge.nextBase(previous, dataset, cloud, plan, TrainingWriteResult.NOTHING, emptySet(), emptyMap(), emptySet(), emptyMap())
        assertEquals(
            mapOf(TrainingKind.WORKOUT to 70L, TrainingKind.CUSTOM_EXERCISE to 50L, TrainingKind.NOTE to 40L, TrainingKind.ROUTINE to 80L),
            next.cursors,
        )

        // The user edited the workout while the sync ran: not recorded, and pulled again from the old cursor.
        val skipped = setOf(DocKey(TrainingKind.WORKOUT, "w1"))
        val kept = TrainingMerge.nextBase(previous, dataset, cloud, plan, TrainingWriteResult.NOTHING, skipped, emptyMap(), emptySet(), emptyMap())
        assertEquals(50L, kept.cursors[TrainingKind.WORKOUT])
        assertNull(kept.entries[DocKey(TrainingKind.WORKOUT, "w1")])
        assertEquals(TrainingMerge.versionOf(routine()), kept.entries[DocKey(TrainingKind.ROUTINE, "r1")])

        // The same for a routine.
        val routineSkipped = TrainingMerge.nextBase(
            previous, dataset, cloud, plan, TrainingWriteResult.NOTHING, setOf(DocKey(TrainingKind.ROUTINE, "r1")), emptyMap(), emptySet(), emptyMap(),
        )
        assertEquals(50L, routineSkipped.cursors[TrainingKind.ROUTINE])
        assertEquals(70L, routineSkipped.cursors[TrainingKind.WORKOUT])
        assertNull(routineSkipped.entries[DocKey(TrainingKind.ROUTINE, "r1")])
    }

    @Test
    fun knownPhotosAreTheOnesInUseAndInTheCloud() {
        val n = note(photo = h1)
        val plan = merge(local(notes = listOf(n)), TrainingCloudSnapshot.EMPTY)
        val notConfirmed = TrainingMerge.nextBase(null, dataset, TrainingCloudSnapshot.EMPTY, plan, TrainingWriteResult(emptySet(), emptySet(), emptySet(), false), emptySet(), emptyMap(), setOf(h1), emptyMap())
        assertTrue(notConfirmed.photos.isEmpty())
        val confirmed = TrainingMerge.nextBase(
            null, dataset, TrainingCloudSnapshot.EMPTY, plan,
            TrainingWriteResult(setOf(DocKey(TrainingKind.NOTE, n.exerciseId)), setOf(h1), emptySet(), true),
            emptySet(), emptyMap(), setOf(h1), mapOf("f1.jpg" to h1),
        )
        assertEquals(setOf(h1), confirmed.photos)
        assertEquals(mapOf("f1.jpg" to h1), confirmed.photoFiles)
        assertNull(confirmed.pending)
        assertEquals(dataset, confirmed.datasetKey)
    }

    // ---- Completing a transition never undoes what the user did since ------------------------------

    @Test
    fun retriedRestoreKeepsWhatWasLoggedAfterTheChoice() {
        val since = 1_000L
        val before = workout("before", updatedAt = 500)
        val after = workout("after", updatedAt = 2_000) // logged once the user had chosen
        val inProgress = workout("live", updatedAt = 1_500) // started before, sets logged after
        val deletedAfter = workoutTombstone("old-cloud", 1_800)
        val plan = TrainingMerge.plan(
            TrainingMode.ADOPT_REPLACE,
            local(listOf(before, after, inProgress), tombstones = listOf(deletedAfter)),
            remote(listOf(workout("there"), workout("old-cloud", updatedAt = 400))),
            null,
            now,
            pendingSince = since,
        )
        assertEquals(listOf("before"), plan.apply.removeWorkouts)
        assertEquals(setOf(after, inProgress), plan.workoutPuts().toSet())
        assertEquals(listOf("there"), plan.apply.upsertWorkouts.map { it.id })
        // A deletion made after the choice is propagated like in a merge.
        assertEquals(listOf(TrainingWrite.Delete(TrainingKind.WORKOUT, "old-cloud", 1_800)), plan.deletes())
    }

    @Test
    fun retriedAutomaticRestoreLetsNewerLocalEditsWin() {
        val cloud = workout(updatedAt = 900, name = "Cloud")
        val editedAfter = workout(updatedAt = 2_000, name = "Edited after")
        val plan = TrainingMerge.plan(TrainingMode.ADOPT_MERGE, local(listOf(editedAfter)), remote(listOf(cloud)), null, now, pendingSince = 1_000)
        assertEquals(listOf(editedAfter), plan.workoutPuts())
        assertTrue(plan.apply.isEmpty)
        // Without a transition time the cloud copy wins the collision.
        val first = TrainingMerge.plan(TrainingMode.ADOPT_MERGE, local(listOf(workout(updatedAt = 800))), remote(listOf(cloud)), null, now)
        assertEquals(listOf(cloud), first.apply.upsertWorkouts)
    }

    @Test
    fun automaticUploadToACloudWithoutOurDataNeverDeletesThere() {
        val cloudOnly = workout("theirs")
        val shared = note("leg_press_machine", updatedAt = 500, text = "cloud")
        val mine = note("leg_press_machine", updatedAt = 900, text = "mine")
        val clearedHere = noteTombstone("chest_press", 700)
        val plan = TrainingMerge.plan(
            TrainingMode.UNION,
            local(listOf(workout("mine")), notes = listOf(mine), tombstones = listOf(clearedHere)),
            remote(listOf(cloudOnly), notes = listOf(shared, note("chest_press", updatedAt = 100))),
            null,
            now,
        )
        assertTrue(plan.deletes().isEmpty())
        assertTrue(plan.photoDeletes.isEmpty())
        assertEquals(setOf("mine"), plan.workoutPuts().map { it.id }.toSet())
        assertEquals(listOf("theirs"), plan.apply.upsertWorkouts.map { it.id })
        assertEquals(listOf(mine), plan.notePuts()) // last writer wins on a collision
        assertEquals(listOf(clearedHere), plan.dropNow)
    }

    // ---- Custom exercises used by workouts ----------------------------------------------------------

    private fun workoutUsing(exerciseId: String, id: String = "w-uses") = workout(id).copy(entries = listOf(WorkoutEntry("e-$id", exerciseId)))

    @Test
    fun cloudTombstoneOfAnExerciseStillUsedKeepsItArchived() {
        val machine = custom("u:1", updatedAt = 100)
        val history = workoutUsing("u:1")
        val plan = merge(
            local(listOf(history), custom = listOf(machine)),
            TrainingCloudSnapshot(RemoteDocs.empty(false), RemoteDocs(emptyList(), listOf(Tombstone("u:1", 500)), false, null), RemoteDocs.empty(false), RemoteDocs.empty(false)),
            base(agreed(history), DocKey(TrainingKind.CUSTOM_EXERCISE, "u:1") to TrainingMerge.versionOf(machine)),
        )
        assertTrue(plan.apply.removeCustomExercises.isEmpty())
        val kept = plan.apply.upsertCustomExercises.single()
        assertTrue(kept.archived)
        assertEquals(501L, kept.updatedAt) // beats the tombstone everywhere
        assertEquals(listOf(TrainingWrite.PutCustomExercise(kept)), plan.docWrites)
    }

    @Test
    fun cloudTombstoneOfAnExerciseOnlyARoutineUsesKeepsItArchived() {
        val machine = custom("u:own-machine", updatedAt = 100)
        val legs = routine() // names u:own-machine
        val plan = merge(
            local(custom = listOf(machine), routines = listOf(legs)),
            TrainingCloudSnapshot(
                RemoteDocs.empty(false),
                RemoteDocs(emptyList(), listOf(Tombstone("u:own-machine", 500)), false, null),
                RemoteDocs.empty(false),
                RemoteDocs.empty(false),
            ),
            base(agreed(legs), DocKey(TrainingKind.CUSTOM_EXERCISE, "u:own-machine") to TrainingMerge.versionOf(machine)),
        )
        assertTrue(plan.apply.removeCustomExercises.isEmpty())
        val kept = plan.apply.upsertCustomExercises.single()
        assertTrue(kept.archived)
        assertEquals(501L, kept.updatedAt)
        assertEquals(listOf(TrainingWrite.PutCustomExercise(kept)), plan.docWrites)
    }

    @Test
    fun deletingHereAnExerciseThatOnlyARoutineFromElsewhereUsesIsUndone() {
        val machine = custom("u:own-machine", updatedAt = 100)
        val t = LocalTombstone(LocalTombstone.CUSTOM_EXERCISE, "u:own-machine", 300)
        val plan = merge(
            local(tombstones = listOf(t)),
            remote(custom = listOf(machine), routines = listOf(routine()), complete = false),
            base(DocKey(TrainingKind.CUSTOM_EXERCISE, "u:own-machine") to TrainingMerge.versionOf(machine)),
        )
        assertTrue(plan.deletes().isEmpty())
        assertTrue(plan.apply.upsertCustomExercises.single().archived)
        assertEquals(listOf(routine()), plan.apply.upsertRoutines)
    }

    @Test
    fun deletingHereAnExerciseThatWorkoutsFromElsewhereUseIsUndone() {
        val machine = custom("u:1", updatedAt = 100)
        val t = LocalTombstone(LocalTombstone.CUSTOM_EXERCISE, "u:1", 300)
        val plan = merge(
            local(tombstones = listOf(t)),
            remote(listOf(workoutUsing("u:1")), custom = listOf(machine), complete = false),
            base(DocKey(TrainingKind.CUSTOM_EXERCISE, "u:1") to TrainingMerge.versionOf(machine)),
        )
        assertTrue(plan.deletes().isEmpty())
        val kept = plan.apply.upsertCustomExercises.single()
        assertTrue(kept.archived)
        assertEquals(301L, kept.updatedAt)
        assertEquals(listOf(t), plan.dropNow)
    }

    // ---- Remembered deletions -------------------------------------------------------------------------

    @Test
    fun aLateOlderWriteDoesNotBringADeletedWorkoutBack() {
        val w = workout(updatedAt = now - 5_000)
        val deletedAt = now - 1_000
        val t = workoutTombstone("w1", deletedAt)
        val delete = merge(local(tombstones = listOf(t)), remote(complete = false), base(agreed(w)))
        val confirmed = TrainingWriteResult(setOf(DocKey(TrainingKind.WORKOUT, "w1")), emptySet(), emptySet(), true)
        val next = TrainingMerge.nextBase(base(agreed(w)), dataset, remote(complete = false), delete, confirmed, emptySet(), emptyMap(), emptySet(), emptyMap(), now = now)
        assertEquals(TrainingMerge.dead(deletedAt), next.entries[DocKey(TrainingKind.WORKOUT, "w1")])

        // An old put of the workout landed after the tombstone (merge-set made the document live again).
        val late = merge(local(), remote(listOf(w), complete = false), next)
        assertTrue(late.apply.isEmpty)
        assertEquals(listOf(TrainingWrite.Delete(TrainingKind.WORKOUT, "w1", deletedAt)), late.writes)

        // An edit made after the deletion elsewhere is a real resurrection and is applied.
        val edited = workout(updatedAt = now)
        assertEquals(listOf(edited), merge(local(), remote(listOf(edited), complete = false), next).apply.upsertWorkouts)

        // Remembered for a while only.
        val later = TrainingMerge.nextBase(next, dataset, remote(complete = false), merge(local(), remote(complete = false), next), TrainingWriteResult.NOTHING,
            emptySet(), emptyMap(), emptySet(), emptyMap(), now = deletedAt + TrainingMerge.DEAD_ENTRY_TTL_MS + 1)
        assertNull(later.entries[DocKey(TrainingKind.WORKOUT, "w1")])
    }

    @Test
    fun cloudTombstonesAreRememberedToo() {
        val w = workout(updatedAt = now - 5_000)
        val plan = merge(local(listOf(w)), remote(deadWorkouts = listOf(Tombstone("w1", now - 1_000)), complete = false), base(agreed(w)))
        val next = TrainingMerge.nextBase(base(agreed(w)), dataset, remote(complete = false), plan, TrainingWriteResult.NOTHING, emptySet(), emptyMap(), emptySet(), emptyMap(), now = now)
        assertEquals(TrainingMerge.dead(now - 1_000), next.entries[DocKey(TrainingKind.WORKOUT, "w1")])
        assertEquals(now - 1_000, TrainingMerge.deadAtOf(next.entries[DocKey(TrainingKind.WORKOUT, "w1")]))
        assertNull(TrainingMerge.deadAtOf(TrainingMerge.versionOf(w)))
    }

    // ---- Photos still in the offline queue ----------------------------------------------------------

    @Test
    fun photoWaitingInTheOfflineQueueIsNotQueuedAgainForADay() {
        val n = note(photo = h2, updatedAt = 200)
        val queuedAnHourAgo = mapOf(h2 to now - 3_600_000)
        val plan = TrainingMerge.plan(TrainingMode.MERGE, local(notes = listOf(n)), remote(complete = false), base(), now, photosInFlight = queuedAnHourAgo)
        assertTrue(plan.photoUploads.isEmpty())
        assertEquals(listOf(n), plan.notePuts()) // the note follows it in the queue

        val queuedTwoDaysAgo = mapOf(h2 to now - 2 * 86_400_000L)
        val again = TrainingMerge.plan(TrainingMode.MERGE, local(notes = listOf(n)), remote(complete = false), base(), now, photosInFlight = queuedTwoDaysAgo)
        assertEquals(setOf(h2), again.uploadedPhotos())
    }

    @Test
    fun queuedPhotoIsRememberedAndKnownOnceItsNoteIsConfirmed() {
        val n = note(photo = h2, updatedAt = 200)
        val plan = merge(local(notes = listOf(n)), remote(complete = false), base())
        val queued = TrainingWriteResult(emptySet(), emptySet(), emptySet(), allConfirmed = false, queuedPhotos = setOf(h2))
        val next = TrainingMerge.nextBase(base(), dataset, remote(complete = false), plan, queued, emptySet(), emptyMap(), setOf(h2), emptyMap(), now = now)
        assertEquals(mapOf(h2 to now), next.photosInFlight)
        assertTrue(next.photos.isEmpty())

        // Next sync: the note is sent again (alone) and confirmed, so the photo before it landed too.
        val retry = TrainingMerge.plan(TrainingMode.MERGE, local(notes = listOf(n)), remote(complete = false), next, now + 1, photosInFlight = next.photosInFlight)
        assertTrue(retry.photoUploads.isEmpty())
        val noteConfirmed = TrainingWriteResult(setOf(DocKey(TrainingKind.NOTE, n.exerciseId)), emptySet(), emptySet(), true)
        val after = TrainingMerge.nextBase(next, dataset, remote(complete = false), retry, noteConfirmed, emptySet(), emptyMap(), setOf(h2), emptyMap(),
            now = now + 1, photosInFlight = next.photosInFlight)
        assertEquals(setOf(h2), after.photos)
        assertTrue(after.photosInFlight.isEmpty())
    }

    // ---- Ids the phone's database cannot take ---------------------------------------------------------

    @Test
    fun cloudWorkoutReusingIdsOfAnotherWorkoutGetsStableNewIds() {
        val mine = workout("mine")
        val theirs = workout("theirs").copy(entries = mine.entries) // same entry and set ids
        val plan = merge(local(listOf(mine)), remote(listOf(mine, theirs)))
        val stored = plan.apply.upsertWorkouts.single()
        assertEquals("theirs", stored.id)
        assertTrue(stored.entries.single().id != mine.entries.single().id)
        assertTrue(stored.entries.single().sets.single().id != mine.entries.single().sets.single().id)
        assertEquals(mine.entries.single().sets.single().weightKg, stored.entries.single().sets.single().weightKg)
        // Deterministic, and the phone's own version is what it remembers as agreed.
        assertEquals(stored, merge(local(listOf(mine)), remote(listOf(mine, theirs))).apply.upsertWorkouts.single())
        val next = TrainingMerge.nextBase(null, dataset, remote(listOf(mine, theirs)), plan, TrainingWriteResult.NOTHING, emptySet(), emptyMap(), emptySet(), emptyMap())
        assertEquals(TrainingMerge.versionOf(stored), next.entries[DocKey(TrainingKind.WORKOUT, "theirs")])
    }

    // ---- Documents lost on the phone ------------------------------------------------------------------

    @Test
    fun documentThePhoneLostWithoutTombstoneIsReadAgain() {
        val w = workout()
        val plan = merge(local(), remote(complete = false), base(agreed(w)).copy(cursors = mapOf(TrainingKind.WORKOUT to 50L, TrainingKind.NOTE to 50L)))
        assertEquals(setOf(TrainingKind.WORKOUT), plan.refetch)
        assertTrue(plan.writes.isEmpty())
        val next = TrainingMerge.nextBase(
            base(agreed(w)).copy(cursors = mapOf(TrainingKind.WORKOUT to 50L, TrainingKind.NOTE to 50L)), dataset, remote(complete = false), plan,
            TrainingWriteResult.NOTHING, emptySet(), emptyMap(), emptySet(), emptyMap(),
        )
        assertNull(next.cursors[TrainingKind.WORKOUT]) // the next read is complete and brings it back
        assertEquals(50L, next.cursors[TrainingKind.NOTE])
        // ...where it is simply restored.
        assertEquals(listOf(w), merge(local(), remote(listOf(w)), next).apply.upsertWorkouts)
    }

    // ---- Documents written by a newer app version ----------------------------------------------------

    @Test
    fun documentsOfANewerSchemaAreNeverOverwritten() {
        val newer = custom("u:1", updatedAt = 100)
        val editedHere = newer.copy(name = "Renamed", updatedAt = 200)
        val cloud = TrainingCloudSnapshot(
            RemoteDocs.empty(false), RemoteDocs(listOf(newer), emptyList(), false, null, readOnly = setOf("u:1")), RemoteDocs.empty(false), RemoteDocs.empty(false),
        )
        val plan = merge(local(custom = listOf(editedHere)), cloud, base())
        assertTrue(plan.docWrites.isEmpty())
        assertEquals(setOf(DocKey(TrainingKind.CUSTOM_EXERCISE, "u:1")), plan.readOnly)

        // Remembered while the document is not read again...
        val next = TrainingMerge.nextBase(base(), dataset, cloud, plan, TrainingWriteResult.NOTHING, emptySet(), emptyMap(), emptySet(), emptyMap())
        assertTrue(merge(local(custom = listOf(editedHere)), remote(complete = false), next).docWrites.isEmpty())
        // ...and forgotten once a copy this version understands is read.
        val understood = merge(local(custom = listOf(editedHere)), remote(custom = listOf(newer), complete = false), next)
        assertEquals(listOf(TrainingWrite.PutCustomExercise(editedHere)), understood.docWrites)
        assertTrue(understood.readOnly.isEmpty())
    }

    // ---- Routines -------------------------------------------------------------------------------------

    @Test
    fun routinesFollowLastWriterWinsInBothDirections() {
        val older = routine(updatedAt = 100, name = "Legs A")
        val newer = routine(updatedAt = 200, name = "Legs B")

        val upload = merge(local(routines = listOf(newer)), remote(routines = listOf(older), complete = false), base(agreed(older)))
        assertEquals(listOf(TrainingWrite.PutRoutine(newer)), upload.docWrites)
        assertTrue(upload.apply.isEmpty)

        val download = merge(local(routines = listOf(older)), remote(routines = listOf(newer), complete = false), base(agreed(older)))
        assertEquals(listOf(newer), download.apply.upsertRoutines)
        assertTrue(download.writes.isEmpty())

        // A tie goes to the cloud copy; the agreed version on both sides is a no-op.
        val tie = merge(local(routines = listOf(routine(name = "A"))), remote(routines = listOf(routine(name = "B"))))
        assertEquals("B", tie.apply.upsertRoutines.single().name)
        assertTrue(merge(local(routines = listOf(older)), remote(routines = listOf(older), complete = false), base(agreed(older))).isNoOp)
        // Without reading the collection, only a routine changed since the agreed version is uploaded.
        assertTrue(merge(local(routines = listOf(older)), remote(complete = false), base(agreed(older))).isNoOp)
        assertEquals(listOf(newer), merge(local(routines = listOf(newer)), remote(complete = false), base(agreed(older))).routinePuts())
    }

    @Test
    fun routineVersionCoversEveryTarget() {
        val r = routine()
        val variants = listOf(
            r.copy(name = "Other"),
            r.copy(note = "slow eccentrics"),
            r.copy(createdAt = 51),
            r.copy(exercises = r.exercises.reversed()),
            r.copy(exercises = r.exercises.dropLast(1)),
            r.copy(exercises = r.exercises.map { it.copy(sets = it.sets + 1) }),
            r.copy(exercises = listOf(r.exercises[0].copy(reps = null)) + r.exercises.drop(1)),
            r.copy(exercises = r.exercises.dropLast(1) + r.exercises.last().copy(durationSec = 60)),
        )
        val versions = (variants + r).map(TrainingMerge::versionOf)
        assertEquals(versions.size, versions.toSet().size)
    }

    @Test
    fun routineTombstonesAgainstLiveCopies() {
        // A cloud tombstone removes an older local copy...
        val removed = merge(local(routines = listOf(routine(updatedAt = 100))), remote(deadRoutines = listOf(Tombstone("r1", 200))))
        assertEquals(listOf("r1"), removed.apply.removeRoutines)
        assertTrue(removed.writes.isEmpty())
        // ...but a local edit newer than it brings the routine back everywhere.
        val edited = routine(updatedAt = 300)
        val resurrected = merge(local(routines = listOf(edited)), remote(deadRoutines = listOf(Tombstone("r1", 200))))
        assertEquals(listOf(edited), resurrected.routinePuts())
        assertTrue(resurrected.apply.isEmpty)
        // The tombstone wins over the agreed version whatever the clocks say.
        val ahead = routine(updatedAt = 900)
        val agreedOne = merge(local(routines = listOf(ahead)), remote(deadRoutines = listOf(Tombstone("r1", 500)), complete = false), base(agreed(ahead)))
        assertEquals(listOf("r1"), agreedOne.apply.removeRoutines)
    }

    @Test
    fun routineDeletedHereIsUploadedDatedPastTheAgreedVersion() {
        val synced = routine(updatedAt = 1_000)
        val t = routineTombstone("r1", 400) // this phone's clock is behind the version it deleted
        val plan = merge(local(tombstones = listOf(t)), remote(routines = listOf(synced), complete = false), base(agreed(synced)))
        assertEquals(listOf(TrainingWrite.Delete(TrainingKind.ROUTINE, "r1", 1_001)), plan.writes)
        assertEquals(t, plan.dropOnConfirm[DocKey(TrainingKind.ROUTINE, "r1")])
        assertTrue(plan.apply.isEmpty)
        assertTrue(plan.dropNow.isEmpty())

        // Not in this read: uploaded anyway (an unconfirmed put of it may still wait in the offline queue).
        val unseen = merge(local(tombstones = listOf(routineTombstone("r1", 1_500))), remote(complete = false), base(agreed(synced)))
        assertEquals(listOf(TrainingWrite.Delete(TrainingKind.ROUTINE, "r1", 1_500)), unseen.writes)
        // The cloud already shows a tombstone: forgotten without an upload.
        val shown = merge(local(tombstones = listOf(t)), remote(deadRoutines = listOf(Tombstone("r1", 1_001)), complete = false), base(agreed(synced)))
        assertEquals(listOf(t), shown.dropNow)
        assertTrue(shown.writes.isEmpty())

        // Edited elsewhere after the deletion here: the edit wins.
        val deletedHere = routineTombstone("r1", 500)
        val editedThere = routine(updatedAt = 600, name = "Edited")
        val lost = merge(local(tombstones = listOf(deletedHere)), remote(routines = listOf(editedThere), complete = false), base(agreed(routine(updatedAt = 100))))
        assertEquals(listOf(editedThere), lost.apply.upsertRoutines)
        assertTrue(lost.deletes().isEmpty())
        assertEquals(listOf(deletedHere), lost.dropNow)
    }

    @Test
    fun aLateOlderWriteDoesNotBringADeletedRoutineBack() {
        val r = routine(updatedAt = now - 5_000)
        val deletedAt = now - 1_000
        val delete = merge(local(tombstones = listOf(routineTombstone("r1", deletedAt))), remote(complete = false), base(agreed(r)))
        val confirmed = TrainingWriteResult(setOf(DocKey(TrainingKind.ROUTINE, "r1")), emptySet(), emptySet(), true)
        val next = TrainingMerge.nextBase(base(agreed(r)), dataset, remote(complete = false), delete, confirmed, emptySet(), emptyMap(), emptySet(), emptyMap(), now = now)
        assertEquals(TrainingMerge.dead(deletedAt), next.entries[DocKey(TrainingKind.ROUTINE, "r1")])

        // An old put of the routine landed after the tombstone: the tombstone is written again.
        val late = merge(local(), remote(routines = listOf(r), complete = false), next)
        assertTrue(late.apply.isEmpty)
        assertEquals(listOf(TrainingWrite.Delete(TrainingKind.ROUTINE, "r1", deletedAt)), late.writes)

        // An edit made after the deletion elsewhere is a real resurrection.
        val edited = routine(updatedAt = now, name = "Back again")
        assertEquals(listOf(edited), merge(local(), remote(routines = listOf(edited), complete = false), next).apply.upsertRoutines)
    }

    @Test
    fun routinesFollowTheDatasetTransitions() {
        val onlyHere = routine("here")
        val shared = routine("shared", updatedAt = 100)
        val cloudShared = routine("shared", updatedAt = 50, name = "Cloud name")
        val there = routine("there")

        // "Restore the cloud copy": the phone's routines are replaced, the cloud wins even if older.
        val cloud = remote(routines = listOf(cloudShared, there))
        val restore = TrainingMerge.plan(TrainingMode.ADOPT_REPLACE, local(routines = listOf(onlyHere, shared)), cloud, null, now)
        assertEquals(listOf("here"), restore.apply.removeRoutines)
        assertEquals(setOf(cloudShared, there), restore.apply.upsertRoutines.toSet())
        assertTrue(restore.writes.isEmpty())

        // Automatic restore: what was saved here is kept and uploaded; an old deletion is not propagated.
        val oldDeletion = routineTombstone("there", 9_000)
        val automatic = TrainingMerge.plan(TrainingMode.ADOPT_MERGE, local(routines = listOf(onlyHere, shared), tombstones = listOf(oldDeletion)), cloud, null, now)
        assertEquals(listOf(onlyHere), automatic.routinePuts())
        assertEquals(setOf(cloudShared, there), automatic.apply.upsertRoutines.toSet())
        assertTrue(automatic.deletes().isEmpty())
        assertEquals(listOf(oldDeletion), automatic.dropNow)

        // "Replace the cloud copy": what is only in the cloud gets a tombstone past its version.
        val newerThere = remote(routines = listOf(routine("there", updatedAt = now + 5)))
        val replace = TrainingMerge.plan(TrainingMode.REPLACE_CLOUD, local(routines = listOf(onlyHere)), newerThere, null, now)
        assertEquals(listOf(onlyHere), replace.routinePuts())
        assertEquals(listOf(TrainingWrite.Delete(TrainingKind.ROUTINE, "there", now + 6)), replace.deletes())
        assertTrue(replace.apply.isEmpty)

        // Automatic upload to a cloud without our dataset: last writer wins, nothing is deleted there.
        val deletedHere = routineTombstone("there", 700)
        val union = TrainingMerge.plan(TrainingMode.UNION, local(routines = listOf(onlyHere, shared), tombstones = listOf(deletedHere)), cloud, null, now)
        assertTrue(union.deletes().isEmpty())
        assertEquals(setOf(onlyHere, shared), union.routinePuts().toSet())
        assertEquals(listOf(there), union.apply.upsertRoutines)
    }

    @Test
    fun aRetriedRestoreKeepsRoutinesSavedAfterTheChoice() {
        val before = routine("before", updatedAt = 500)
        val after = routine("after", updatedAt = 2_000)
        val plan = TrainingMerge.plan(
            TrainingMode.ADOPT_REPLACE, local(routines = listOf(before, after)), remote(routines = listOf(routine("there"))), null, now, pendingSince = 1_000,
        )
        assertEquals(listOf("before"), plan.apply.removeRoutines)
        assertEquals(listOf(after), plan.routinePuts())
        assertEquals(listOf("there"), plan.apply.upsertRoutines.map { it.id })
    }

    @Test
    fun routineLostOnThePhoneIsReadAgainAndANewerSchemaIsNeverOverwritten() {
        val r = routine()
        val withCursors = base(agreed(r)).copy(cursors = mapOf(TrainingKind.ROUTINE to 50L, TrainingKind.WORKOUT to 50L))
        val lost = merge(local(), remote(complete = false), withCursors)
        assertEquals(setOf(TrainingKind.ROUTINE), lost.refetch)
        assertTrue(lost.writes.isEmpty())
        val next = TrainingMerge.nextBase(withCursors, dataset, remote(complete = false), lost, TrainingWriteResult.NOTHING, emptySet(), emptyMap(), emptySet(), emptyMap())
        assertNull(next.cursors[TrainingKind.ROUTINE]) // read whole next time
        assertEquals(50L, next.cursors[TrainingKind.WORKOUT])

        val editedHere = r.copy(name = "Renamed", updatedAt = 200)
        val newer = TrainingCloudSnapshot(
            RemoteDocs.empty(false), RemoteDocs.empty(false), RemoteDocs.empty(false), RemoteDocs(listOf(r), emptyList(), false, null, readOnly = setOf(r.id)),
        )
        val plan = merge(local(routines = listOf(editedHere)), newer, base())
        assertTrue(plan.docWrites.isEmpty())
        assertEquals(setOf(DocKey(TrainingKind.ROUTINE, r.id)), plan.readOnly)
    }
}
