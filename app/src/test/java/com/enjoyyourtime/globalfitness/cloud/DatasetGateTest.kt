package com.enjoyyourtime.globalfitness.cloud

import org.junit.Assert.assertEquals
import org.junit.Test

class DatasetGateTest {
    private val profile = ProfileRecord("Nico", "M", "1990-05-01", 180.0, null, editedAt = 0)
    private val measurement = MeasurementRecord(1_000, 80_000, null, "scale", null, 1_000)

    private fun local(
        datasetId: String = "mine",
        p: ProfileRecord? = profile,
        measurements: List<MeasurementRecord> = listOf(measurement),
        profileDeletedAt: Long? = null,
    ) = LocalSnapshot("profile:1:1", p, null, measurements, emptyList(), datasetId = datasetId, profileDeletedAt = profileDeletedAt)

    private fun remote(doc: RemoteProfileDoc, anyMeasurementDoc: Boolean? = null) =
        RemoteSnapshot(doc, null, emptyList(), emptyList(), emptyList(), emptyList(), true, null, anyMeasurementDoc)

    private fun base(datasetKey: String = "mine", profileSynced: Boolean = true) =
        SyncBase(datasetKey, null, 1L, emptyMap(), emptyMap(), profileSynced)

    private fun liveCloud(datasetId: String?) = RemoteProfileDoc.Live(profile, datasetId)

    @Test
    fun sameDatasetMerges() {
        assertEquals(SyncMode.Merge, DatasetGate.decide(local(), remote(liveCloud("mine")), null, null))
    }

    @Test
    fun emptyCloudGetsThisPhonesData() {
        assertEquals(SyncMode.ReplaceCloud, DatasetGate.decide(local(), remote(RemoteProfileDoc.Absent, false), null, null))
        // A cloud dataset that was deleted (profile tombstone of another dataset) counts as empty too.
        assertEquals(SyncMode.ReplaceCloud, DatasetGate.decide(local(), remote(RemoteProfileDoc.Deleted(5, "other")), null, null))
    }

    @Test
    fun differentDataOnBothSidesAsksTheUser() {
        assertEquals(
            SyncMode.NeedsChoice(ConflictKind.DIFFERENT_DATA),
            DatasetGate.decide(local(), remote(liveCloud("other")), base(), null),
        )
        // A cloud copy from before dataset ids existed is treated as different, never merged blindly.
        assertEquals(
            SyncMode.NeedsChoice(ConflictKind.DIFFERENT_DATA),
            DatasetGate.decide(local(), remote(liveCloud(null)), null, null),
        )
    }

    @Test
    fun theUsersChoiceDecides() {
        assertEquals(SyncMode.AdoptCloud, DatasetGate.decide(local(), remote(liveCloud("other")), null, ConflictChoice.RESTORE_CLOUD))
        assertEquals(SyncMode.ReplaceCloud, DatasetGate.decide(local(), remote(liveCloud("other")), null, ConflictChoice.REPLACE_CLOUD))
    }

    @Test
    fun freshProfileOnANewPhoneRestoresAutomatically() {
        assertEquals(SyncMode.AdoptCloud, DatasetGate.decide(local(measurements = emptyList()), remote(liveCloud("other")), null, null))
    }

    @Test
    fun cloudWipedAfterSyncingStopsInsteadOfReuploading() {
        assertEquals(SyncMode.RemoteWiped, DatasetGate.decide(local(), remote(RemoteProfileDoc.Absent, anyMeasurementDoc = false), base(), null))
        // Measurements still there: not a wipe, the profile is simply uploaded again.
        assertEquals(SyncMode.Merge, DatasetGate.decide(local(), remote(RemoteProfileDoc.Absent, anyMeasurementDoc = true), base(), null))
        // Never synced this dataset: just an empty cloud.
        assertEquals(SyncMode.ReplaceCloud, DatasetGate.decide(local(), remote(RemoteProfileDoc.Absent, false), base("other"), null))
    }

    @Test
    fun noLocalProfileNeverResurrectsTheCloudOne() {
        assertEquals(SyncMode.Idle, DatasetGate.decide(local(p = null, measurements = emptyList()), remote(liveCloud("mine")), base(), null))
        // ...but a profile deleted here is propagated by a merge of the same dataset.
        assertEquals(
            SyncMode.Merge,
            DatasetGate.decide(local(p = null, measurements = emptyList(), profileDeletedAt = 9), remote(liveCloud("mine")), base(), null),
        )
        assertEquals(
            SyncMode.Idle,
            DatasetGate.decide(local(p = null, measurements = emptyList(), profileDeletedAt = 9), remote(liveCloud("other")), base(), null),
        )
    }

    @Test
    fun profileDocWithOnlyThePremiumFlagHoldsNoDataset() {
        assertEquals(SyncMode.ReplaceCloud, DatasetGate.decide(local(), remote(RemoteProfileDoc.Live(null, null)), null, null))
    }

    @Test
    fun sameDatasetWithProfileTombstoneMerges() {
        assertEquals(SyncMode.Merge, DatasetGate.decide(local(), remote(RemoteProfileDoc.Deleted(5, "mine")), base(), null))
    }
}
