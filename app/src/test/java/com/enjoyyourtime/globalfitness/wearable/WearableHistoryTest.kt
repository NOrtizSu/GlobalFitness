package com.enjoyyourtime.globalfitness.wearable

import com.enjoyyourtime.globalfitness.band.HeartRateSample
import com.enjoyyourtime.globalfitness.band.RecordedHeartRate
import com.enjoyyourtime.globalfitness.wearable.xiaomi.XiaomiModels
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WearableHistoryTest {
    private val registry = AllWearables.registry()

    private fun row(ts: Long, bpm: Int, address: String?, driver: String? = null) =
        RecordedHeartRate(HeartRateSample(ts, bpm), address, driver)

    @Test
    fun groupsByDeviceNamedAfterConfiguredOnes() {
        val band = ConfiguredWearable("1", XiaomiModels.BAND_9_ID, "AA:BB:CC:DD:EE:FF", "My band", emptyMap(), 0L, false)
        val rows = listOf(
            row(300, 80, "11:22:33:44:55:66", "pinetime_infinitime"),
            row(200, 70, "aa:bb:cc:dd:ee:ff"), // original Smart Band 9 mode: no driver, lowercase
            row(100, 60, "AA:BB:CC:DD:EE:FF", XiaomiModels.BAND_9_ID),
            row(400, 90, null),
        )
        val groups = WearableHeartRate.groupByDevice(rows, listOf(band))
        assertEquals(3, groups.size)
        // Ordered by first sample; samples sorted by time.
        assertEquals("AA:BB:CC:DD:EE:FF", groups[0].deviceAddress)
        assertEquals("My band", groups[0].name)
        assertEquals(XiaomiModels.BAND_9_ID, groups[0].driverId)
        assertEquals(listOf(100L, 200L), groups[0].samples.map { it.timestampMs })
        // Not configured (removed): no name, driver from the rows.
        assertNull(groups[1].name)
        assertEquals("pinetime_infinitime", groups[1].driverId)
        // No address at all.
        assertNull(groups[2].deviceAddress)
        assertNull(groups[2].name)
        assertNull(groups[2].driverId)
        assertTrue(WearableHeartRate.groupByDevice(emptyList(), listOf(band)).isEmpty())
    }

    @Test
    fun legacyBandBecomesConfiguredXiaomiWearable() {
        val nine = LegacyBandImport.configuredFor(registry, "aa:bb:cc:dd:ee:ff", "Xiaomi Smart Band 9 3A7F", syncTimeOnConnect = true, nowMillis = 42L, id = "x")
        assertEquals(ConfiguredWearable("x", XiaomiModels.BAND_9_ID, "AA:BB:CC:DD:EE:FF", "Xiaomi Smart Band 9 3A7F", emptyMap(), 42L, false), nine)

        val pro = LegacyBandImport.configuredFor(registry, "AA:BB:CC:DD:EE:01", "Xiaomi Smart Band 9 Pro 1B2C", syncTimeOnConnect = false, nowMillis = 1L)
        assertEquals("xiaomi_smart_band_9_pro", pro.driverId)
        assertEquals(mapOf(ConfiguredWearable.CONFIG_SYNC_TIME to "0"), pro.config)

        // A renamed band (no model match) keeps working as the Smart Band 9 the original mode supported.
        val renamed = LegacyBandImport.configuredFor(registry, "AA:BB:CC:DD:EE:02", "Wrist", syncTimeOnConnect = true, nowMillis = 1L)
        assertEquals(XiaomiModels.BAND_9_ID, renamed.driverId)
    }
}
