package com.enjoyyourtime.globalfitness.cloud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncMergeTest {
    private val now = 2_000_000_000_000L
    private val day = 86_400_000L

    private fun m(t: Long, grams: Long = 80_000L, impedance: Int? = null, updatedAt: Long = t, source: String = "scale") =
        MeasurementRecord(t, grams, impedance, source, null, updatedAt)

    private val profile = ProfileRecord("Nico", "M", "1990-05-01", 180.0, 75.0, editedAt = 1_000L)
    private val settings = SettingsRecord(mapOf(PortableSettings.ASSIGNMENT_TOLERANCE_KG to 3.0), editedAt = 0L)

    private fun local(
        measurements: List<MeasurementRecord> = emptyList(),
        p: ProfileRecord? = profile,
        s: SettingsRecord? = settings,
        dataset: String = "profile:1:1",
        devices: List<DeviceRecord> = emptyList(),
    ) = LocalSnapshot(dataset, p, s, measurements, devices)

    private fun remote(
        measurements: List<MeasurementRecord> = emptyList(),
        tombstones: List<Tombstone> = emptyList(),
        p: ProfileRecord? = null,
        s: SettingsRecord? = null,
        complete: Boolean = true,
        maxUpdatedAt: Long? = null,
        devices: List<DeviceRecord> = emptyList(),
    ) = RemoteSnapshot(p, s, measurements, tombstones, devices, emptyList(), complete, maxUpdatedAt)

    private fun base(vararg entries: Pair<String, BaseEntry>, dataset: String = "profile:1:1", pull: Long? = now - day) =
        SyncBase(dataset, pull, now - day, entries.toMap(), emptyMap())

    private fun live(r: MeasurementRecord) = r.id to BaseEntry.Live(SyncMerge.hashOf(r))

    private fun SyncMerge.Plan.putIds() = uploads.filterIsInstance<CloudWrite.PutMeasurement>().map { it.record.id }.toSet()
    private fun SyncMerge.Plan.tombstoneIds() = uploads.filterIsInstance<CloudWrite.DeleteMeasurement>().map { it.tombstone.id }.toSet()

    // ---- First sign-in --------------------------------------------------------------------------

    @Test
    fun localOnlyUploadsEverything() {
        val a = m(1_000)
        val b = m(2_000)
        val plan = SyncMerge.plan(local(listOf(a, b)), RemoteSnapshot.EMPTY, null, now)

        assertEquals(setOf(a.id, b.id), plan.putIds())
        assertTrue(plan.uploads.any { it is CloudWrite.PutProfile })
        assertTrue(plan.uploads.any { it is CloudWrite.PutSettings })
        assertTrue(plan.localChanges.isEmpty)
    }

    @Test
    fun cloudOnlyRestoresEverything() {
        val a = m(1_000, impedance = 500)
        val b = m(2_000)
        val cloudProfile = profile.copy(editedAt = 50L)
        val plan = SyncMerge.plan(
            local(p = null, s = null, dataset = "none"),
            remote(listOf(a, b), p = cloudProfile, s = settings),
            null,
            now,
        )

        assertEquals(setOf(a.id, b.id), plan.localChanges.upsertMeasurements.map { it.id }.toSet())
        assertEquals(cloudProfile, plan.localChanges.profile)
        assertEquals(settings, plan.localChanges.settings)
        assertTrue(plan.uploads.isEmpty())
    }

    @Test
    fun bothSidesMergeAsUnionById() {
        val onlyLocal = m(1_000)
        val shared = m(2_000, impedance = 480)
        val onlyCloud = m(3_000)
        val plan = SyncMerge.plan(local(listOf(onlyLocal, shared)), remote(listOf(shared, onlyCloud)), null, now)

        assertEquals(setOf(onlyLocal.id), plan.putIds())
        assertEquals(listOf(onlyCloud.id), plan.localChanges.upsertMeasurements.map { it.id })
        assertTrue(plan.tombstoneIds().isEmpty())
        assertTrue(plan.localChanges.deleteMeasurementIds.isEmpty())
    }

    @Test
    fun conflictKeepsTheKnownImpedance() {
        val weightOnlyHere = m(5_000, impedance = null)
        val withImpedanceThere = m(5_000, impedance = 512, updatedAt = 6_000)
        val plan = SyncMerge.plan(local(listOf(weightOnlyHere)), remote(listOf(withImpedanceThere)), null, now)
        assertEquals(512, plan.localChanges.upsertMeasurements.single().impedance)
        assertTrue(plan.putIds().isEmpty())

        val reverse = SyncMerge.plan(local(listOf(withImpedanceThere)), remote(listOf(weightOnlyHere)), null, now)
        assertEquals(512, (reverse.uploads.single { it is CloudWrite.PutMeasurement } as CloudWrite.PutMeasurement).record.impedance)
        assertTrue(reverse.localChanges.upsertMeasurements.isEmpty())
    }

    @Test
    fun conflictWithTwoImpedancesTakesTheNewest() {
        val older = m(5_000, impedance = 500, updatedAt = 10)
        val newer = m(5_000, impedance = 520, updatedAt = 20)
        val plan = SyncMerge.plan(local(listOf(older)), remote(listOf(newer)), null, now)
        assertEquals(520, plan.localChanges.upsertMeasurements.single().impedance)
    }

    // ---- Profile / settings last-writer-wins -----------------------------------------------------

    @Test
    fun profileNewerLocallyIsUploaded() {
        val plan = SyncMerge.plan(
            local(p = profile.copy(heightCm = 181.0, editedAt = 900)),
            remote(p = profile.copy(editedAt = 800)),
            null,
            now,
        )
        assertEquals(181.0, (plan.uploads.single { it is CloudWrite.PutProfile } as CloudWrite.PutProfile).record.heightCm, 0.0)
        assertNull(plan.localChanges.profile)
    }

    @Test
    fun profileNewerInCloudIsDownloaded() {
        val cloud = profile.copy(targetWeightKg = 70.0, editedAt = 900)
        val plan = SyncMerge.plan(local(p = profile.copy(editedAt = 800)), remote(p = cloud), null, now)
        assertEquals(cloud, plan.localChanges.profile)
        assertFalse(plan.uploads.any { it is CloudWrite.PutProfile })
    }

    @Test
    fun profileTieGoesToTheCloudAndEqualContentDoesNothing() {
        val cloud = profile.copy(name = "Nicolás", editedAt = profile.editedAt)
        assertEquals(cloud, SyncMerge.plan(local(), remote(p = cloud), null, now).localChanges.profile)

        val same = SyncMerge.plan(local(), remote(p = profile.copy(editedAt = 5)), null, now)
        assertNull(same.localChanges.profile)
        assertFalse(same.uploads.any { it is CloudWrite.PutProfile })
    }

    @Test
    fun untouchedLocalSettingsLoseAgainstCloudSettings() {
        val cloud = SettingsRecord(mapOf(PortableSettings.ASSIGNMENT_TOLERANCE_KG to 4.5), editedAt = 10)
        val plan = SyncMerge.plan(local(s = settings.copy(editedAt = 0)), remote(s = cloud), null, now)
        assertEquals(cloud, plan.localChanges.settings)
    }

    @Test
    fun settingsCompareNumbersByValue() {
        val local = SettingsRecord(mapOf(PortableSettings.ASSIGNMENT_TOLERANCE_KG to 3), editedAt = 99)
        val cloud = SettingsRecord(mapOf(PortableSettings.ASSIGNMENT_TOLERANCE_KG to 3.0), editedAt = 1)
        assertEquals(SyncMerge.hashOf(local), SyncMerge.hashOf(cloud))
    }

    // ---- Tombstones -------------------------------------------------------------------------------

    @Test
    fun cloudTombstoneDeletesOlderLocalMeasurement() {
        val a = m(1_000)
        val plan = SyncMerge.plan(local(listOf(a)), remote(tombstones = listOf(Tombstone(a.id, deletedAt = 5_000))), null, now)
        assertEquals(setOf(a.id), plan.localChanges.deleteMeasurementIds)
        assertTrue(plan.putIds().isEmpty())
    }

    @Test
    fun localVersionNewerThanTombstoneResurrects() {
        val a = m(1_000, updatedAt = 9_000)
        val plan = SyncMerge.plan(local(listOf(a)), remote(tombstones = listOf(Tombstone(a.id, deletedAt = 5_000))), null, now)
        assertEquals(setOf(a.id), plan.putIds())
        assertTrue(plan.localChanges.deleteMeasurementIds.isEmpty())
    }

    @Test
    fun deletionOnThisPhoneBecomesTombstone() {
        val kept = m(1_000)
        val deleted = m(2_000)
        val plan = SyncMerge.plan(local(listOf(kept)), remote(complete = false), base(live(kept), live(deleted)), now)

        val tombstone = plan.uploads.filterIsInstance<CloudWrite.DeleteMeasurement>().single().tombstone
        assertEquals(deleted.id, tombstone.id)
        assertEquals(now, tombstone.deletedAt)
        assertTrue(plan.putIds().isEmpty())
    }

    @Test
    fun deletionOnThisPhoneWinsOverStaleCloudCopy() {
        val deleted = m(2_000)
        val kept = m(1_000)
        val plan = SyncMerge.plan(
            local(listOf(kept)),
            remote(listOf(deleted), complete = false),
            base(live(kept), live(deleted)),
            now,
        )
        assertEquals(setOf(deleted.id), plan.tombstoneIds())
        assertTrue(plan.localChanges.upsertMeasurements.isEmpty())
    }

    @Test
    fun knownTombstoneRemovesReimportedOldMeasurement() {
        val a = m(1_000)
        val plan = SyncMerge.plan(
            local(listOf(a, m(3_000))),
            remote(complete = false),
            base(a.id to BaseEntry.Deleted(5_000), live(m(3_000))),
            now,
        )
        assertEquals(setOf(a.id), plan.localChanges.deleteMeasurementIds)
    }

    // ---- Safety: never delete the cloud after a reinstall -------------------------------------

    @Test
    fun emptyPhoneWithOldBaseRestoresInsteadOfDeleting() {
        val a = m(1_000)
        val b = m(2_000)
        val oldBase = base(live(a), live(b))
        assertFalse(SyncMerge.isBaseTrusted(oldBase, local(emptyList())))

        val plan = SyncMerge.plan(local(emptyList()), remote(listOf(a, b)), oldBase, now)
        assertTrue(plan.tombstoneIds().isEmpty())
        assertEquals(setOf(a.id, b.id), plan.localChanges.upsertMeasurements.map { it.id }.toSet())
    }

    @Test
    fun differentDatasetIsNotTrustedForDeletions() {
        val a = m(1_000)
        val b = m(2_000)
        val oldBase = base(live(a), live(b), dataset = "profile:1:1")
        val newLocal = local(listOf(m(3_000)), dataset = "profile:7:7")
        assertFalse(SyncMerge.isBaseTrusted(oldBase, newLocal))

        val plan = SyncMerge.plan(newLocal, remote(listOf(a, b)), oldBase, now)
        assertTrue(plan.tombstoneIds().isEmpty())
        assertEquals(setOf(a.id, b.id), plan.localChanges.upsertMeasurements.map { it.id }.toSet())
    }

    // ---- Three-way changes ---------------------------------------------------------------------

    @Test
    fun onlyCloudChangedIsApplied() {
        val synced = m(1_000)
        val cloudEdited = synced.copy(impedance = 530, updatedAt = 50_000)
        val plan = SyncMerge.plan(local(listOf(synced)), remote(listOf(cloudEdited), complete = false), base(live(synced)), now)
        assertEquals(530, plan.localChanges.upsertMeasurements.single().impedance)
        assertTrue(plan.putIds().isEmpty())
    }

    @Test
    fun impedanceUpgradeAfterSyncIsUploaded() {
        val synced = m(1_000)
        val upgraded = synced.copy(impedance = 505)
        val plan = SyncMerge.plan(local(listOf(upgraded)), remote(complete = false), base(live(synced)), now)
        assertEquals(setOf(upgraded.id), plan.putIds())
    }

    @Test
    fun unchangedDataIsANoOp() {
        val a = m(1_000, impedance = 500)
        val plan = SyncMerge.plan(local(listOf(a)), remote(p = profile, s = settings, complete = false), base(live(a)), now)
        assertTrue(plan.isNoOp)
    }

    // ---- Next base -----------------------------------------------------------------------------

    @Test
    fun nextBaseRecordsAgreedStateAndCursor() {
        val a = m(1_000)
        val fromCloud = m(2_000)
        val dead = Tombstone(m(3_000).id, 4_000)
        val local = local(listOf(a))
        val remote = remote(listOf(fromCloud), listOf(dead), complete = true, maxUpdatedAt = 77_000)
        val plan = SyncMerge.plan(local, remote, null, now)
        val after = local(listOf(a, fromCloud))

        val next = SyncMerge.nextBase(null, local, remote, plan, after, now)
        assertEquals(BaseEntry.Live(SyncMerge.hashOf(a)), next.measurements[a.id])
        assertEquals(BaseEntry.Live(SyncMerge.hashOf(fromCloud)), next.measurements[fromCloud.id])
        assertEquals(BaseEntry.Deleted(4_000), next.measurements[dead.id])
        assertEquals(77_000L, next.lastPullAt)
        assertEquals(now, next.lastSyncAt)
        assertEquals("profile:1:1", next.datasetKey)
    }

    @Test
    fun failedInsertIsNotRecordedSoItIsNeverTombstoned() {
        val fromCloud = m(2_000)
        val local = local(listOf(m(1_000)))
        val remote = remote(listOf(fromCloud))
        val plan = SyncMerge.plan(local, remote, null, now)
        val next = SyncMerge.nextBase(null, local, remote, plan, localAfter = local, now = now)
        assertNull(next.measurements[fromCloud.id])

        val again = SyncMerge.plan(local, remote(complete = false), next, now + 1)
        assertTrue(again.tombstoneIds().isEmpty())
    }

    @Test
    fun changeDuringSyncIsStillUploadedNextTime() {
        val a = m(1_000)
        val local = local(listOf(a))
        val plan = SyncMerge.plan(local, RemoteSnapshot.EMPTY, null, now)
        // Impedance arrived while the sync was uploading.
        val after = local(listOf(a.copy(impedance = 499)))
        val next = SyncMerge.nextBase(null, local, RemoteSnapshot.EMPTY, plan, after, now)

        val nextPlan = SyncMerge.plan(after, remote(complete = false), next, now + 1)
        assertEquals(setOf(a.id), nextPlan.putIds())
    }

    @Test
    fun valueThePhoneCannotStoreIsNotUploadedBackAndForth() {
        val here = m(1_000, source = "scale")
        val there = here.copy(source = "manual")
        val local = local(listOf(here))
        val remote = remote(listOf(there), complete = false)
        val base = base(live(here))
        val plan = SyncMerge.plan(local, remote, base, now)
        assertEquals("manual", plan.localChanges.upsertMeasurements.single().source)

        // The adapter could not change the source: next sync must not upload "scale" again.
        val next = SyncMerge.nextBase(base, local, remote, plan, localAfter = local, now = now)
        val nextPlan = SyncMerge.plan(local, remote(p = profile, s = settings, complete = false), next, now + 1)
        assertTrue(nextPlan.uploads.isEmpty())
    }

    @Test
    fun incrementalCursorNeverMovesBack() {
        val local = local(listOf(m(1_000)))
        val prev = base(live(m(1_000)), pull = 50_000)
        val remote = remote(complete = false, maxUpdatedAt = null)
        val plan = SyncMerge.plan(local, remote, prev, now)
        assertEquals(50_000L, SyncMerge.nextBase(prev, local, remote, plan, local, now).lastPullAt)

        val newer = remote(complete = false, maxUpdatedAt = 60_000)
        assertEquals(60_000L, SyncMerge.nextBase(prev, local, newer, SyncMerge.plan(local, newer, prev, now), local, now).lastPullAt)
    }

    // ---- Devices ------------------------------------------------------------------------------

    @Test
    fun devicesUseLastWriterWinsAndUnion() {
        val mine = DeviceRecord("s1", "mi_scale_2", "Baño", mapOf("userIndex" to "1"), updatedAt = 10)
        val cloudNewer = mine.copy(name = "Baño arriba", updatedAt = 20)
        val other = DeviceRecord("s2", "yunmai", "Gimnasio", emptyMap(), updatedAt = 5)
        val plan = SyncMerge.plan(local(devices = listOf(mine)), remote(devices = listOf(cloudNewer, other)), null, now)

        assertEquals(setOf("Baño arriba", "Gimnasio"), plan.localChanges.upsertDevices.map { it.name }.toSet())
        assertTrue(plan.uploads.none { it is CloudWrite.PutDevice })
    }
}
