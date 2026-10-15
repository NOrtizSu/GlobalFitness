package com.enjoyyourtime.globalfitness.cloud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncMergeTest {
    private val now = 2_000_000_000_000L
    private val day = 86_400_000L
    private val dataset = "ds-1"
    private val rule = DuplicateRule(windowMs = 60_000, toleranceGrams = 25)

    private fun m(t: Long, grams: Long = 80_000L, impedance: Int? = null, updatedAt: Long = t, source: String = "scale") =
        MeasurementRecord(t, grams, impedance, source, null, updatedAt)

    private val profile = ProfileRecord("Nico", "M", "1990-05-01", 180.0, 75.0, editedAt = 1_000L)

    private fun local(
        measurements: List<MeasurementRecord> = emptyList(),
        p: ProfileRecord? = profile,
        s: SettingsRecord? = null,
        datasetId: String = dataset,
        devices: List<DeviceRecord> = emptyList(),
        profileDeletedAt: Long? = null,
        duplicateRule: DuplicateRule? = null,
    ) = LocalSnapshot("profile:1:1", p, s, measurements, devices, duplicateRule, datasetId, profileDeletedAt)

    private fun remote(
        measurements: List<MeasurementRecord> = emptyList(),
        tombstones: List<Tombstone> = emptyList(),
        p: ProfileRecord? = null,
        s: SettingsRecord? = null,
        complete: Boolean = true,
        maxUpdatedAt: Long? = null,
        devices: List<DeviceRecord> = emptyList(),
        profileDoc: RemoteProfileDoc = if (p == null) RemoteProfileDoc.Absent else RemoteProfileDoc.Live(p, dataset),
    ) = RemoteSnapshot(profileDoc, s, measurements, tombstones, devices, emptyList(), complete, maxUpdatedAt)

    private fun base(vararg entries: Pair<String, BaseEntry>, datasetKey: String = dataset, pull: Long? = now - day) =
        SyncBase(datasetKey, pull, now - day, entries.toMap(), emptyMap(), profileSynced = true)

    private fun live(r: MeasurementRecord) = r.id to BaseEntry.Live(SyncMerge.hashOf(r))

    private fun SyncMerge.Plan.putIds() = uploads.filterIsInstance<CloudWrite.PutMeasurement>().map { it.record.id }.toSet()
    private fun SyncMerge.Plan.tombstoneIds() = uploads.filterIsInstance<CloudWrite.DeleteMeasurement>().map { it.tombstone.id }.toSet()

    // ---- First sync of a dataset ---------------------------------------------------------------

    @Test
    fun localOnlyUploadsEverythingWithTheDatasetId() {
        val a = m(1_000)
        val b = m(2_000)
        val plan = SyncMerge.plan(local(listOf(a, b)), RemoteSnapshot.EMPTY, null, now)

        assertEquals(setOf(a.id, b.id), plan.putIds())
        assertEquals(dataset, plan.uploads.filterIsInstance<CloudWrite.PutProfile>().single().datasetId)
        assertTrue(plan.localChanges.isEmpty)
    }

    @Test
    fun sameDatasetWithoutBaseMergesAsUnion() {
        // E.g. Auto Backup restored the database (and its dataset id) on a new phone.
        val onlyLocal = m(1_000)
        val shared = m(2_000, impedance = 480)
        val onlyCloud = m(3_000)
        val plan = SyncMerge.plan(local(listOf(onlyLocal, shared)), remote(listOf(shared, onlyCloud), p = profile), null, now)

        assertEquals(setOf(onlyLocal.id), plan.putIds())
        assertEquals(listOf(onlyCloud.id), plan.localChanges.upsertMeasurements.map { it.id })
        assertTrue(plan.tombstoneIds().isEmpty())
    }

    @Test
    fun conflictKeepsTheVersionWithScaleData() {
        val weightOnlyHere = m(5_000, impedance = null)
        val withImpedanceThere = m(5_000, impedance = 512, updatedAt = 6_000)
        val plan = SyncMerge.plan(local(listOf(weightOnlyHere)), remote(listOf(withImpedanceThere), p = profile), null, now)
        assertEquals(512, plan.localChanges.upsertMeasurements.single().impedance)
        assertTrue(plan.putIds().isEmpty())

        val reverse = SyncMerge.plan(local(listOf(withImpedanceThere)), remote(listOf(weightOnlyHere), p = profile), null, now)
        assertEquals(512, (reverse.uploads.single { it is CloudWrite.PutMeasurement } as CloudWrite.PutMeasurement).record.impedance)
        assertTrue(reverse.localChanges.upsertMeasurements.isEmpty())
    }

    @Test
    fun deviceCompositionIsPartOfTheScaleData() {
        val plain = m(5_000, impedance = 500)
        val withComposition = plain.copy(
            composition = mapOf(CompositionKeys.FAT_PERCENT to 18.5),
            compositionSource = "DEVICE",
            deviceDriver = "xiaomi_s400",
            updatedAt = 7_000,
        )
        assertTrue(SyncMerge.hashOf(plain) != SyncMerge.hashOf(withComposition))
        val merged = SyncMerge.combineMeasurements(plain, withComposition)
        assertEquals(18.5, merged.composition.getValue(CompositionKeys.FAT_PERCENT), 0.0)
        assertEquals("xiaomi_s400", merged.deviceDriver)
    }

    @Test
    fun legacyHashIsUnchangedWithoutScaleProvenance() {
        assertEquals("500|scale", SyncMerge.hashOf(m(1, impedance = 500)))
    }

    // ---- Profile -------------------------------------------------------------------------------

    @Test
    fun profileLastWriterWins() {
        val newerHere = SyncMerge.plan(local(p = profile.copy(heightCm = 181.0, editedAt = 900)), remote(p = profile.copy(editedAt = 800)), null, now)
        assertEquals(181.0, newerHere.uploads.filterIsInstance<CloudWrite.PutProfile>().single().record.heightCm, 0.0)

        val cloud = profile.copy(targetWeightKg = 70.0, editedAt = 900)
        val newerThere = SyncMerge.plan(local(p = profile.copy(editedAt = 800)), remote(p = cloud), null, now)
        assertEquals(cloud, newerThere.localChanges.profile)
    }

    @Test
    fun unstampedLocalProfileLosesAgainstTheCloud() {
        // New phone / reinstall: the local profile was never edited after first sight (editedAt 0).
        val cloud = profile.copy(name = "Nicolás", editedAt = 10)
        val plan = SyncMerge.plan(local(p = profile.copy(editedAt = 0)), remote(p = cloud), null, now)
        assertEquals(cloud, plan.localChanges.profile)
        assertTrue(plan.uploads.none { it is CloudWrite.PutProfile })
    }

    @Test
    fun deletedProfileBecomesATombstoneInsteadOfComingBack() {
        val plan = SyncMerge.plan(local(p = null, profileDeletedAt = 5_000), remote(p = profile.copy(editedAt = 1_000)), base(), now)
        assertNull(plan.localChanges.profile)
        assertEquals(CloudWrite.DeleteProfile(5_000, dataset), plan.uploads.single())
    }

    @Test
    fun profileCreatedAfterTheDeletionBeatsTheOldCloudProfile() {
        val newProfile = profile.copy(name = "Ana", editedAt = 0)
        val plan = SyncMerge.plan(local(p = newProfile, profileDeletedAt = 5_000), remote(p = profile.copy(editedAt = 4_000)), base(), now)
        assertEquals("Ana", plan.uploads.filterIsInstance<CloudWrite.PutProfile>().single().record.name)
        assertNull(plan.localChanges.profile)
    }

    @Test
    fun cloudProfileTombstoneIsNotResurrectedByAnOlderLocalCopy() {
        val plan = SyncMerge.plan(
            local(p = profile.copy(editedAt = 1_000)),
            remote(profileDoc = RemoteProfileDoc.Deleted(5_000, dataset)),
            base(),
            now,
        )
        assertTrue(plan.uploads.none { it is CloudWrite.PutProfile })
    }

    // ---- Tombstones -------------------------------------------------------------------------------

    @Test
    fun cloudTombstoneDeletesOlderLocalMeasurement() {
        val a = m(1_000)
        val plan = SyncMerge.plan(local(listOf(a)), remote(tombstones = listOf(Tombstone(a.id, deletedAt = 5_000)), p = profile), null, now)
        assertEquals(setOf(a.id), plan.localChanges.deleteMeasurementIds)
    }

    @Test
    fun localVersionNewerThanTombstoneResurrects() {
        val a = m(1_000, updatedAt = 9_000)
        val plan = SyncMerge.plan(local(listOf(a)), remote(tombstones = listOf(Tombstone(a.id, deletedAt = 5_000)), p = profile), null, now)
        assertEquals(setOf(a.id), plan.putIds())
    }

    @Test
    fun tombstoneWinsOverAnUnchangedLocalCopyWhateverTheClocks() {
        // Local clock ahead: updatedAt > deletedAt, but the phone did not change it since the last sync.
        val a = m(1_000, updatedAt = 9_000)
        val plan = SyncMerge.plan(
            local(listOf(a, m(2_000))),
            remote(tombstones = listOf(Tombstone(a.id, deletedAt = 5_000)), p = profile, complete = false),
            base(live(a), live(m(2_000))),
            now,
        )
        assertEquals(setOf(a.id), plan.localChanges.deleteMeasurementIds)
        assertTrue(plan.putIds().isEmpty())
    }

    @Test
    fun deletingTheLastMeasurementIsPropagated() {
        val last = m(1_000)
        assertTrue(SyncMerge.isBaseTrusted(base(live(last)), local(emptyList())))
        val plan = SyncMerge.plan(local(emptyList()), remote(p = profile, complete = false), base(live(last)), now)
        assertEquals(setOf(last.id), plan.tombstoneIds())
        assertTrue(plan.localChanges.upsertMeasurements.isEmpty())
    }

    @Test
    fun deletionOnThisPhoneWinsOverStaleCloudCopy() {
        val deleted = m(2_000)
        val kept = m(1_000)
        val plan = SyncMerge.plan(local(listOf(kept)), remote(listOf(deleted), p = profile, complete = false), base(live(kept), live(deleted)), now)
        assertEquals(setOf(deleted.id), plan.tombstoneIds())
        assertTrue(plan.localChanges.upsertMeasurements.isEmpty())
    }

    @Test
    fun baseOfAnotherDatasetNeverDeletes() {
        val a = m(1_000)
        val b = m(2_000)
        val otherBase = base(live(a), live(b), datasetKey = "ds-old")
        assertFalse(SyncMerge.isBaseTrusted(otherBase, local(listOf(m(3_000)))))
        val plan = SyncMerge.plan(local(listOf(m(3_000))), remote(listOf(a, b), p = profile), otherBase, now)
        assertTrue(plan.tombstoneIds().isEmpty())
        assertEquals(setOf(a.id, b.id), plan.localChanges.upsertMeasurements.map { it.id }.toSet())
    }

    // ---- Same weighing captured by two phones (aliases) ---------------------------------------

    @Test
    fun sameWeighingFromAnotherPhoneIsLinkedNotInserted() {
        val mine = m(100_000, grams = 80_000)
        val theirs = m(130_000, grams = 80_010) // 30 s later, 10 g apart
        val local = local(listOf(mine), duplicateRule = rule)
        val remote = remote(listOf(theirs), p = profile)
        val plan = SyncMerge.plan(local, remote, null, now)
        assertTrue(plan.localChanges.upsertMeasurements.isEmpty())

        val next = SyncMerge.nextBase(null, local, remote, plan, local, now)
        assertEquals(BaseEntry.Alias(mine.id), next.measurements[theirs.id])
    }

    @Test
    fun deletingALinkedMeasurementDeletesBothCloudCopies() {
        val mine = m(100_000, grams = 80_000)
        val theirs = m(130_000, grams = 80_010)
        val synced = base(live(mine), theirs.id to BaseEntry.Alias(mine.id), live(m(500_000)))
        val plan = SyncMerge.plan(local(listOf(m(500_000)), duplicateRule = rule), remote(p = profile, complete = false), synced, now)
        assertEquals(setOf(mine.id, theirs.id), plan.tombstoneIds())
    }

    @Test
    fun cloudTombstoneOfTheLinkedCopyDeletesTheLocalOne() {
        val mine = m(100_000, grams = 80_000)
        val theirs = m(130_000, grams = 80_010)
        val synced = base(live(mine), theirs.id to BaseEntry.Alias(mine.id), live(m(500_000)))
        val plan = SyncMerge.plan(
            local(listOf(mine, m(500_000)), duplicateRule = rule),
            remote(tombstones = listOf(Tombstone(theirs.id, 9_000)), p = profile, complete = false),
            synced,
            now,
        )
        assertEquals(setOf(mine.id), plan.localChanges.deleteMeasurementIds)
        assertEquals(setOf(mine.id), plan.tombstoneIds())
    }

    // ---- Three-way changes ---------------------------------------------------------------------

    @Test
    fun onlyCloudChangedIsApplied() {
        val synced = m(1_000)
        val cloudEdited = synced.copy(impedance = 530, updatedAt = 50_000)
        val plan = SyncMerge.plan(local(listOf(synced)), remote(listOf(cloudEdited), p = profile, complete = false), base(live(synced)), now)
        assertEquals(530, plan.localChanges.upsertMeasurements.single().impedance)
        assertTrue(plan.putIds().isEmpty())
    }

    @Test
    fun impedanceUpgradeAfterSyncIsUploaded() {
        val synced = m(1_000)
        val upgraded = synced.copy(impedance = 505)
        val plan = SyncMerge.plan(local(listOf(upgraded)), remote(p = profile, complete = false), base(live(synced)), now)
        assertEquals(setOf(upgraded.id), plan.putIds())
    }

    @Test
    fun unchangedDataIsANoOp() {
        val a = m(1_000, impedance = 500)
        val plan = SyncMerge.plan(local(listOf(a)), remote(p = profile, complete = false), base(live(a)), now)
        assertTrue(plan.isNoOp)
    }

    // ---- Mass deletion guard ---------------------------------------------------------------------

    @Test
    fun deletingMostOfTheSyncedMeasurementsAtOnceIsFlagged() {
        val all = (1..20).map { m(it * 1_000L) }
        val synced = base(*all.map(::live).toTypedArray())
        val kept = all.take(5)
        val plan = SyncMerge.plan(local(kept), remote(p = profile, complete = false), synced, now)
        assertEquals(15, plan.measurementDeletions)
        assertTrue(SyncMerge.isMassDeletion(plan, synced))

        val few = SyncMerge.plan(local(all.drop(3)), remote(p = profile, complete = false), synced, now)
        assertFalse(SyncMerge.isMassDeletion(few, synced))
    }

    @Test
    fun undoingDeletionsRestoresThemFromTheCloud() {
        val all = (1..20).map { m(it * 1_000L) }
        val synced = base(*all.map(::live).toTypedArray())
        val plan = SyncMerge.plan(local(all.take(5)), remote(all, p = profile), SyncMerge.withoutLiveEntries(synced), now)
        assertTrue(plan.tombstoneIds().isEmpty())
        assertEquals(15, plan.localChanges.upsertMeasurements.size)
    }

    // ---- Replace plans ---------------------------------------------------------------------------

    @Test
    fun adoptCloudReplacesThePhoneData() {
        val onlyHere = m(1_000)
        val shared = m(2_000)
        val cloudProfile = profile.copy(name = "Nicolás")
        val remote = remote(listOf(shared, m(3_000)), p = cloudProfile, profileDoc = RemoteProfileDoc.Live(cloudProfile, "ds-cloud"))
        val plan = SyncMerge.planAdoptCloud(local(listOf(onlyHere, shared)), remote)

        assertEquals(setOf(onlyHere.id), plan.localChanges.deleteMeasurementIds)
        assertEquals(listOf(m(3_000).id), plan.localChanges.upsertMeasurements.map { it.id })
        assertEquals(cloudProfile, plan.localChanges.profile)
        assertTrue(plan.uploads.isEmpty())
    }

    @Test
    fun replaceCloudForceUploadsAndRemovesTheRest() {
        val here = m(1_000)
        val onlyThere = m(9_000)
        val plan = SyncMerge.planReplaceCloud(local(listOf(here)), remote(listOf(onlyThere), p = profile), now)

        val put = plan.uploads.filterIsInstance<CloudWrite.PutMeasurement>().single()
        assertEquals(here.id, put.record.id)
        assertTrue(put.force)
        assertEquals(setOf(onlyThere.id), plan.tombstoneIds())
        assertEquals(dataset, plan.uploads.filterIsInstance<CloudWrite.PutProfile>().single().datasetId)
    }

    // ---- Stale applies ---------------------------------------------------------------------------

    @Test
    fun profileEditedDuringTheSyncIsNotOverwritten() {
        val changes = RemoteChanges(profile = profile.copy(name = "Cloud"), upsertMeasurements = listOf(m(1)))
        val planned = local()
        val editedMeanwhile = local(p = profile.copy(name = "Edited"))
        val safe = SyncMerge.dropStaleSingles(changes, planned, editedMeanwhile)
        assertNull(safe.profile)
        assertEquals(1, safe.upsertMeasurements.size)
        assertEquals(changes, SyncMerge.dropStaleSingles(changes, planned, planned))
    }

    // ---- Next base -----------------------------------------------------------------------------

    @Test
    fun nextBaseRecordsAgreedStateAndCursor() {
        val a = m(1_000)
        val fromCloud = m(2_000)
        val dead = Tombstone(m(3_000).id, 4_000)
        val local = local(listOf(a))
        val remote = remote(listOf(fromCloud), listOf(dead), p = profile, complete = true, maxUpdatedAt = 77_000)
        val plan = SyncMerge.plan(local, remote, null, now)
        val after = local(listOf(a, fromCloud))

        val next = SyncMerge.nextBase(null, local, remote, plan, after, now)
        assertEquals(BaseEntry.Live(SyncMerge.hashOf(a)), next.measurements[a.id])
        assertEquals(BaseEntry.Live(SyncMerge.hashOf(fromCloud)), next.measurements[fromCloud.id])
        assertEquals(BaseEntry.Deleted(4_000), next.measurements[dead.id])
        assertEquals(77_000L, next.lastPullAt)
        assertEquals(dataset, next.datasetKey)
        assertTrue(next.profileSynced)
    }

    @Test
    fun failedInsertIsNotRecordedSoItIsNeverTombstoned() {
        val fromCloud = m(2_000)
        val local = local(listOf(m(1_000)))
        val remote = remote(listOf(fromCloud), p = profile)
        val plan = SyncMerge.plan(local, remote, null, now)
        val next = SyncMerge.nextBase(null, local, remote, plan, localAfter = local, now = now)
        assertNull(next.measurements[fromCloud.id])

        val again = SyncMerge.plan(local, remote(p = profile, complete = false), next, now + 1)
        assertTrue(again.tombstoneIds().isEmpty())
    }

    @Test
    fun changeDuringSyncIsStillUploadedNextTime() {
        val a = m(1_000)
        val local = local(listOf(a))
        val plan = SyncMerge.plan(local, RemoteSnapshot.EMPTY, null, now)
        val after = local(listOf(a.copy(impedance = 499))) // impedance arrived while uploading
        val next = SyncMerge.nextBase(null, local, RemoteSnapshot.EMPTY, plan, after, now)

        val nextPlan = SyncMerge.plan(after, remote(p = profile, complete = false), next, now + 1)
        assertEquals(setOf(a.id), nextPlan.putIds())
    }

    @Test
    fun valueThePhoneCannotStoreIsNotUploadedBackAndForth() {
        val here = m(1_000, source = "scale")
        val there = here.copy(source = "manual")
        val local = local(listOf(here))
        val remote = remote(listOf(there), p = profile, complete = false)
        val base = base(live(here))
        val plan = SyncMerge.plan(local, remote, base, now)
        assertEquals("manual", plan.localChanges.upsertMeasurements.single().source)

        val next = SyncMerge.nextBase(base, local, remote, plan, localAfter = local, now = now)
        val nextPlan = SyncMerge.plan(local, remote(p = profile, complete = false), next, now + 1)
        assertTrue(nextPlan.uploads.isEmpty())
    }

    @Test
    fun incrementalCursorNeverMovesBack() {
        val local = local(listOf(m(1_000)))
        val prev = base(live(m(1_000)), pull = 50_000)
        val remote = remote(p = profile, complete = false, maxUpdatedAt = null)
        val plan = SyncMerge.plan(local, remote, prev, now)
        assertEquals(50_000L, SyncMerge.nextBase(prev, local, remote, plan, local, now).lastPullAt)

        val newer = remote(p = profile, complete = false, maxUpdatedAt = 60_000)
        assertEquals(60_000L, SyncMerge.nextBase(prev, local, newer, SyncMerge.plan(local, newer, prev, now), local, now).lastPullAt)
    }

    // ---- Devices ------------------------------------------------------------------------------

    @Test
    fun devicesUseLastWriterWinsAndUnion() {
        val mine = DeviceRecord("s1", "mi_scale_2", "Baño", mapOf("user_slot" to "1"), updatedAt = 10)
        val cloudNewer = mine.copy(name = "Baño arriba", updatedAt = 20)
        val other = DeviceRecord("s2", "yunmai", "Gimnasio", emptyMap(), updatedAt = 5)
        val plan = SyncMerge.plan(local(devices = listOf(mine)), remote(devices = listOf(cloudNewer, other), p = profile), null, now)

        assertEquals(setOf("Baño arriba", "Gimnasio"), plan.localChanges.upsertDevices.map { it.name }.toSet())
        assertTrue(plan.uploads.none { it is CloudWrite.PutDevice })
    }
}
