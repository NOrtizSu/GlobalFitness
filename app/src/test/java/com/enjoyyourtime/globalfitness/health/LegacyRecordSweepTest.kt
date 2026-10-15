package com.enjoyyourtime.globalfitness.health

import com.enjoyyourtime.globalfitness.data.Measurement
import com.enjoyyourtime.globalfitness.data.MeasurementSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LegacyRecordSweepTest {
    private val own = "com.enjoyyourtime.globalfitness"
    private val t0 = 1_800_000_000_000L

    private fun record(id: String, clientId: String?, time: Long? = t0, origin: String = own) = StoredRecord(id, clientId, origin, time)

    private fun measurement(id: Long, timestamp: Long) = Measurement(id, 1, timestamp, 70.0, null, null, MeasurementSource.MANUAL)

    @Test
    fun legacyIdsAreRecognised() {
        assertTrue(ClientRecordIds.isLegacy(ClientRecordIds.legacy(7, ExportRecordType.WEIGHT)))
        assertTrue(ClientRecordIds.isLegacy("gf-12-bodyfat"))
        assertFalse(ClientRecordIds.isLegacy(ClientRecordIds.of("m_${t0}_70000", ExportRecordType.WEIGHT)))
        assertFalse(ClientRecordIds.isLegacy(null))
        assertFalse(ClientRecordIds.isLegacy(""))
    }

    @Test
    fun deletesOnlyOwnUntrackedLegacyRecords() {
        val records = listOf(
            record("a", "gf-1-weight"),
            record("b", "gf-1-bodyfat"),
            record("c", "m_${t0}_70000-weight"),
            record("d", null),
            record("e", "gf-2-weight", origin = "com.other.app"),
        )
        assertEquals(listOf("a", "b"), LegacyRecordSweep.select(records, own, ledger = emptyList(), measurements = emptyList()))
    }

    @Test
    fun recordsTrackedByLegacyLedgerEntriesAreLeftToTheMigration() {
        val tracked = LedgerEntry(5, "sig", 1, setOf(ExportRecordType.WEIGHT), recordKey = null)
        val records = listOf(
            record("tracked", "gf-5-weight"),
            // Same measurement id but a type the ledger does not list: written by another installation.
            record("orphan-type", "gf-5-bodyfat"),
            record("orphan", "gf-6-weight"),
        )
        assertEquals(listOf("orphan-type", "orphan"), LegacyRecordSweep.select(records, own, listOf(tracked), emptyList()))
    }

    @Test
    fun theOnlyCopyOfAMeasurementNotExportedAgainIsKept() {
        val restored = measurement(1, t0)
        val records = listOf(record("old", "gf-9-weight", time = t0), record("gone", "gf-10-weight", time = t0 + 60_000))
        // Restored measurement not exported yet: its old record is its only copy in Health Connect.
        assertEquals(listOf("gone"), LegacyRecordSweep.select(records, own, emptyList(), listOf(restored)))
        // Exported again under its natural id: the old record is a duplicate.
        val exported = LedgerEntry(1, "sig", 1, setOf(ExportRecordType.WEIGHT), recordKey = "m_${t0}_70000")
        assertEquals(listOf("old", "gone"), LegacyRecordSweep.select(records, own, listOf(exported), listOf(restored)))
    }

    @Test
    fun recordsWithoutATimeAreKept() {
        assertEquals(emptyList<String>(), LegacyRecordSweep.select(listOf(record("x", "gf-1-weight", time = null)), own, emptyList(), emptyList()))
    }

    @Test
    fun pendingTypesFollowPermissionsAndPreviousSweeps() {
        val writable = setOf(ExportRecordType.WEIGHT, ExportRecordType.BODY_FAT)
        // Partial grant: only writable types, the ones already swept are skipped...
        assertEquals(
            listOf(ExportRecordType.BODY_FAT),
            LegacyRecordSweep.pendingTypes(writable, swept = setOf(ExportRecordType.WEIGHT), force = false),
        )
        // ...unless a history export forces a new sweep.
        assertEquals(
            listOf(ExportRecordType.WEIGHT, ExportRecordType.BODY_FAT),
            LegacyRecordSweep.pendingTypes(writable, swept = setOf(ExportRecordType.WEIGHT), force = true),
        )
        assertEquals(emptyList<ExportRecordType>(), LegacyRecordSweep.pendingTypes(emptySet(), emptySet(), force = true))
        // A type granted later is swept then.
        assertEquals(
            listOf(ExportRecordType.BONE_MASS),
            LegacyRecordSweep.pendingTypes(writable + ExportRecordType.BONE_MASS, swept = writable, force = false),
        )
    }
}
