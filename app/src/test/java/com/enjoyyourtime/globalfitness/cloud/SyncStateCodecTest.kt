package com.enjoyyourtime.globalfitness.cloud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SyncStateCodecTest {

    @Test
    fun baseRoundTrips() {
        val base = SyncBase(
            datasetKey = "profile:3:1700000000000",
            lastPullAt = 1_700_000_123_456L,
            lastSyncAt = 1_700_000_200_000L,
            measurements = mapOf(
                "m_1_80000" to BaseEntry.Live("-|scale"),
                "m_2_80100" to BaseEntry.Live("512|manual"),
                "m_3_80200" to BaseEntry.Deleted(1_700_000_000_999L),
            ),
            devices = mapOf("mi_scale_2_1" to BaseEntry.Live("abc123")),
        )
        assertEquals(base, SyncStateCodec.decodeBase(SyncStateCodec.encodeBase(base)))
    }

    @Test
    fun baseWithoutCursorRoundTrips() {
        val base = SyncBase("none", null, null, emptyMap(), emptyMap())
        assertEquals(base, SyncStateCodec.decodeBase(SyncStateCodec.encodeBase(base)))
    }

    @Test
    fun unknownVersionOrGarbageIsIgnored() {
        assertNull(SyncStateCodec.decodeBase("v\t99\ndataset\tx\n"))
        assertNull(SyncStateCodec.decodeBase("garbage"))
        assertNull(SyncStateCodec.decodeBase(""))
    }

    @Test
    fun stampsRoundTrip() {
        val stamps = EditStamps(EditStamp("p-hash", 10L), EditStamp("s-hash", 0L))
        assertEquals(stamps, SyncStateCodec.decodeStamps(SyncStateCodec.encodeStamps(stamps)))
        assertEquals(EditStamps(null, null), SyncStateCodec.decodeStamps(""))
    }

    @Test
    fun editStampKeepsTimeUntilContentChanges() {
        val first = EditStamp.observe(previous = null, hash = "a", now = 500, fallback = 100)
        assertEquals(EditStamp("a", 100), first) // first sighting: the data layer's best known time

        assertEquals(first, EditStamp.observe(first, "a", now = 900, fallback = 100))
        assertEquals(EditStamp("b", 900), EditStamp.observe(first, "b", now = 900, fallback = 100))
    }
}
