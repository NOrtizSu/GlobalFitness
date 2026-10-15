package com.enjoyyourtime.globalfitness.cloud

import com.enjoyyourtime.globalfitness.cloud.FirestoreCodec.Decoded
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FirestoreCodecTest {

    @Test
    fun measurementRoundTrips() {
        val record = MeasurementRecord(1_700_000_000_000L, 72_350L, 498, "scale", "a1b2", updatedAt = 0L)
        val map = FirestoreCodec.measurementToMap(record)
        assertEquals(false, map[FirestoreCodec.F_DELETED])
        assertEquals(CLOUD_SCHEMA_VERSION, map[FirestoreCodec.F_SCHEMA])
        assertEquals(72.35, map["weightKg"] as Double, 1e-9)

        val decoded = FirestoreCodec.measurementFromMap(record.id, map, updatedAtMillis = 5L)
        assertEquals(Decoded.Live(record.copy(updatedAt = 5L)), decoded)
    }

    @Test
    fun firestoreLongsAndDoublesAreAccepted() {
        // Firestore returns whole numbers as Long and decimals as Double.
        val data = mapOf<String, Any?>("timestamp" to 1_000L, "weightGrams" to 80_000L, "impedance" to 501L, "source" to "manual")
        val decoded = FirestoreCodec.measurementFromMap("m_1000_80000", data, null) as Decoded.Live
        assertEquals(501, decoded.record.impedance)
        assertEquals("manual", decoded.record.source)
        assertEquals(1_000L, decoded.record.updatedAt)
    }

    @Test
    fun tombstoneRoundTrips() {
        val tombstone = Tombstone("m_1000_80000", deletedAt = 42L)
        val map = FirestoreCodec.tombstoneToMap(tombstone)
        assertEquals(setOf(FirestoreCodec.F_SCHEMA, FirestoreCodec.F_DELETED, FirestoreCodec.F_DELETED_AT), map.keys)
        assertEquals(Decoded.Dead(tombstone), FirestoreCodec.measurementFromMap(tombstone.id, map, 99L))
    }

    @Test
    fun docWhoseFieldsContradictItsIdIsIgnored() {
        val data = mapOf<String, Any?>("timestamp" to 2_000L, "weightGrams" to 80_000L)
        assertNull(FirestoreCodec.measurementFromMap("m_1000_80000", data, null))
        assertNull(FirestoreCodec.measurementFromMap("not-an-id", mapOf("timestamp" to 1L), null))
    }

    @Test
    fun profileRoundTripsWithoutLocalOnlyFields() {
        val profile = ProfileRecord("Nico", "M", "1990-05-01", 180.5, null, editedAt = 7L)
        val map = FirestoreCodec.profileToMap(profile)
        assertTrue(map.keys.none { it in setOf("id", "colorIndex", "createdAt") })
        assertEquals(profile, FirestoreCodec.profileFromMap(map, updatedAtMillis = 1L))
    }

    @Test
    fun incompleteProfileIsIgnored() {
        // e.g. a doc that only holds the informational premium flag.
        assertNull(FirestoreCodec.profileFromMap(mapOf(FirestoreCodec.F_PREMIUM to true), 1L))
    }

    @Test
    fun deviceDocIdsAreSanitized() {
        assertEquals("mi_scale_2_AA_BB", FirestoreCodec.safeDocId("mi_scale_2/AA:BB"))
        assertEquals("_", FirestoreCodec.safeDocId(""))
    }
}
