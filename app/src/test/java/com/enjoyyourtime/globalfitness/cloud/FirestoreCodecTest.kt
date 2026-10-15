package com.enjoyyourtime.globalfitness.cloud

import com.enjoyyourtime.globalfitness.cloud.FirestoreCodec.Decoded
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FirestoreCodecTest {

    @Test
    fun measurementRoundTripsWithScaleData() {
        val record = MeasurementRecord(
            timestamp = 1_700_000_000_000L,
            weightGrams = 72_350L,
            impedance = 498,
            source = "scale",
            scaleKey = "a1b2",
            updatedAt = 0L,
            composition = mapOf(CompositionKeys.FAT_PERCENT to 18.4, CompositionKeys.MUSCLE_KG to 55.1),
            compositionSource = "FORMULA_S400",
            formulaLib = "S400",
            deviceDriver = "xiaomi_s400",
        )
        val map = FirestoreCodec.measurementToMap(record)
        assertEquals(false, map[FirestoreCodec.F_DELETED])
        assertEquals(CLOUD_SCHEMA_VERSION, map[FirestoreCodec.F_SCHEMA])
        assertEquals(72.35, map["weightKg"] as Double, 1e-9)
        assertTrue("deviceAddress" !in map.keys)

        val decoded = FirestoreCodec.measurementFromMap(record.id, map, updatedAtMillis = 5L)
        assertEquals(Decoded.Live(record.copy(updatedAt = 5L)), decoded)
    }

    @Test
    fun schemaV1DocsAreStillRead() {
        // Written by the first version: no composition / provenance fields, whole numbers as Long.
        val data = mapOf<String, Any?>(
            FirestoreCodec.F_SCHEMA to 1L, "timestamp" to 1_000L, "weightGrams" to 80_000L, "impedance" to 501L, "source" to "manual",
        )
        val decoded = FirestoreCodec.measurementFromMap("m_1000_80000", data, null) as Decoded.Live
        assertEquals(501, decoded.record.impedance)
        assertEquals("manual", decoded.record.source)
        assertTrue(decoded.record.composition.isEmpty())
        assertNull(decoded.record.deviceDriver)
    }

    @Test
    fun unknownCompositionKeysAreIgnored() {
        val data = mapOf<String, Any?>(FirestoreCodec.F_COMPOSITION to mapOf("fatPercent" to 20L, "secret" to 1.0, "boneKg" to "x"))
        val decoded = FirestoreCodec.measurementFromMap("m_1000_80000", data, null) as Decoded.Live
        assertEquals(mapOf(CompositionKeys.FAT_PERCENT to 20.0), decoded.record.composition)
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
        assertNull(FirestoreCodec.measurementFromMap("m_1000_80000", mapOf("timestamp" to 2_000L, "weightGrams" to 80_000L), null))
        assertNull(FirestoreCodec.measurementFromMap("not-an-id", mapOf("timestamp" to 1L), null))
    }

    @Test
    fun profileCarriesTheDatasetId() {
        val profile = ProfileRecord("Nico", "M", "1990-05-01", 180.5, null, editedAt = 7L)
        val map = FirestoreCodec.profileToMap(profile, "ds-1")
        assertTrue(map.keys.none { it in setOf("id", "colorIndex", "createdAt") })
        assertTrue(map.keys.all { it in FirestoreCodec.PROFILE_FIELDS })
        assertEquals(RemoteProfileDoc.Live(profile, "ds-1"), FirestoreCodec.profileDocFromMap(map, updatedAtMillis = 1L))
    }

    @Test
    fun profileDocStates() {
        assertEquals(RemoteProfileDoc.Absent, FirestoreCodec.profileDocFromMap(null, null))
        assertEquals(
            RemoteProfileDoc.Deleted(5L, "ds-1"),
            FirestoreCodec.profileDocFromMap(FirestoreCodec.profileTombstoneToMap(5L, "ds-1"), 9L),
        )
        // v1 doc (no dataset id) and a doc holding only the premium flag.
        val v1 = mapOf<String, Any?>("name" to "A", "sex" to "F", "birthDate" to "1990-01-01", "heightCm" to 160L)
        assertEquals(null, (FirestoreCodec.profileDocFromMap(v1, 3L) as RemoteProfileDoc.Live).datasetId)
        assertEquals(RemoteProfileDoc.Live(null, null), FirestoreCodec.profileDocFromMap(mapOf(FirestoreCodec.F_PREMIUM to true), 1L))
    }

    @Test
    fun deviceIdsAreTheSameOnWriteAndRead() {
        val id = FirestoreCodec.safeDocId("3f2a9c1e-uuid/with:odd chars")
        assertEquals("3f2a9c1e-uuid_with_odd_chars", id)
        assertEquals(id, FirestoreCodec.safeDocId(id))
        val decoded = FirestoreCodec.deviceFromMap(id, mapOf("driverId" to "mi_scale_2", "name" to "Baño"), 1L) as Decoded.Live
        assertEquals(id, decoded.record.id)
        assertEquals("_", FirestoreCodec.safeDocId(""))
    }
}
