package com.enjoyyourtime.globalfitness.cloud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class DatasetIdentityTest {
    private var counter = 0
    private val newId: () -> String = { "ds-${++counter}" }

    @Test
    fun firstRunCreatesADataset() {
        assertEquals(DatasetIdentity("ds-1", null, null), DatasetIdentity.observe(null, null, 10, newId))
        assertEquals(DatasetIdentity("ds-2", "profile:1:1", null), DatasetIdentity.observe(null, "profile:1:1", 10, newId))
    }

    @Test
    fun sameOwnerKeepsItsDataset() {
        val identity = DatasetIdentity("ds-1", "profile:1:1", null)
        assertSame(identity, DatasetIdentity.observe(identity, "profile:1:1", 99, newId))
    }

    @Test
    fun firstProfileJoinsTheDatasetCreatedBeforeOnboarding() {
        val beforeOnboarding = DatasetIdentity("ds-1", null, null)
        assertEquals(DatasetIdentity("ds-1", "profile:1:1", null), DatasetIdentity.observe(beforeOnboarding, "profile:1:1", 50, newId))
    }

    @Test
    fun deletingTheProfileIsJournaledAndAProfileCreatedLaterStaysInTheDataset() {
        val identity = DatasetIdentity("ds-1", "profile:1:1", null)
        val deleted = DatasetIdentity.observe(identity, null, 500, newId)
        assertEquals(DatasetIdentity("ds-1", null, 500), deleted)
        // The deletion time is kept (the new profile must win over the old cloud one).
        assertEquals(DatasetIdentity("ds-1", "profile:2:600", 500), DatasetIdentity.observe(deleted, "profile:2:600", 700, newId))
    }

    @Test
    fun anotherOwnerReplacingTheProfileStartsANewDataset() {
        val identity = DatasetIdentity("existing", "profile:1:1", 300)
        assertEquals(DatasetIdentity("ds-1", "profile:7:7", null), DatasetIdentity.observe(identity, "profile:7:7", 500, newId))
    }

    @Test
    fun adoptingTheCloudDatasetClearsTheJournal() {
        val adopted = DatasetIdentity("ds-1", "profile:1:1", 500).adopt("ds-cloud")
        assertEquals("ds-cloud", adopted.datasetId)
        assertNull(adopted.profileDeletedAt)
    }
}
