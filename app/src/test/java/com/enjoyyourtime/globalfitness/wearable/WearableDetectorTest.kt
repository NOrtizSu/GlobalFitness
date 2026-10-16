package com.enjoyyourtime.globalfitness.wearable

import com.enjoyyourtime.globalfitness.scale.Bytes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WearableDetectorTest {
    private val registry = AllWearables.registry()
    private val band9 = "Xiaomi Smart Band 9 3A7F"

    private fun adv(address: String, name: String?, rssi: Int = -55, services: List<Int> = emptyList()) =
        WearableSighting(address, name, rssi = rssi, serviceUuids = services.map { Bytes.uuid16(it) })

    private fun bonded(address: String, name: String) = WearableSighting.bonded(address, name, BtDeviceType.CLASSIC)

    @Test
    fun bondedDeviceIsSuggestedAtOnce() {
        val d = WearableDetector(registry)
        val r = d.onSighting(bonded("AA:00:00:00:00:01", band9), 0, emptySet(), emptySet())
        assertTrue(r is WearableDetection.Suggest)
        assertEquals("xiaomi_smart_band_9", (r as WearableDetection.Suggest).driver.id)
        // Only once.
        assertEquals(WearableDetection.Updated, d.onSighting(bonded("AA:00:00:00:00:01", band9), 10, emptySet(), emptySet()))
    }

    @Test
    fun advertisingDeviceNeedsToSettle() {
        val d = WearableDetector(registry, settleMs = 3_000, minAdverts = 2, rematchIntervalMs = 0)
        val a = "AA:00:00:00:00:02"
        assertEquals(WearableDetection.Updated, d.onSighting(adv(a, "Amazfit Bip 5"), 0, emptySet(), emptySet()))
        assertEquals(WearableDetection.Updated, d.onSighting(adv(a, "Amazfit Bip 5"), 1_000, emptySet(), emptySet()))
        val r = d.onSighting(adv(a, "Amazfit Bip 5"), 3_500, emptySet(), emptySet())
        assertEquals("amazfit_bip_5", (r as WearableDetection.Suggest).driver.id)
    }

    @Test
    fun farAwayOrAmbiguousOrKnownDevicesAreNotSuggested() {
        val far = WearableDetector(registry, rematchIntervalMs = 0, settleMs = 0, minAdverts = 1)
        assertEquals(WearableDetection.Updated, far.onSighting(adv("A1:00:00:00:00:01", "Amazfit Bip 5", rssi = -90), 0, emptySet(), emptySet()))

        val crowded = WearableDetector(registry, rematchIntervalMs = 0, settleMs = 0, minAdverts = 1)
        crowded.onSighting(adv("A1:00:00:00:00:02", "Amazfit GTS 4", rssi = -90), 0, emptySet(), emptySet())
        assertEquals(WearableDetection.Updated, crowded.onSighting(adv("A1:00:00:00:00:03", "Amazfit Bip 5"), 1, emptySet(), emptySet()))

        val known = WearableDetector(registry, rematchIntervalMs = 0, settleMs = 0, minAdverts = 1)
        assertEquals(WearableDetection.Updated, known.onSighting(adv("a1:00:00:00:00:04", "InfiniTime"), 0, setOf("A1:00:00:00:00:04"), emptySet()))
        assertEquals(WearableDetection.Updated, known.onSighting(adv("A1:00:00:00:00:05", "InfiniTime"), 0, emptySet(), setOf("a1:00:00:00:00:05")))
    }

    @Test
    fun lowConfidenceIsListedButNotSuggested() {
        val d = WearableDetector(registry, rematchIntervalMs = 0, settleMs = 0, minAdverts = 1)
        assertEquals(WearableDetection.Updated, d.onSighting(adv("A2:00:00:00:00:01", "Strap", services = listOf(0x180D)), 0, emptySet(), emptySet()))
        assertEquals(WearableDetection.Ignored, d.onSighting(adv("A2:00:00:00:00:02", "Headphones"), 0, emptySet(), emptySet()))
        val list = d.detected(0)
        assertEquals(listOf("A2:00:00:00:00:01"), list.map { it.address })
        assertEquals("standard_heart_rate", list.single().best.driver.id)
    }

    @Test
    fun entriesExpireAndBondedOnesLastLonger() {
        val d = WearableDetector(registry, ttlMs = 1_000, bondedTtlMs = 10_000, rematchIntervalMs = 0, settleMs = 0, minAdverts = 1)
        d.onSighting(adv("A3:00:00:00:00:01", "InfiniTime", rssi = -90), 0, emptySet(), emptySet())
        d.onSighting(bonded("A3:00:00:00:00:02", "Redmi Watch 4 1A2B"), 0, emptySet(), emptySet())
        assertEquals(2, d.detected(500).size)
        assertEquals(listOf("A3:00:00:00:00:02"), d.detected(5_000).map { it.address })
        assertTrue(d.detected(20_000).isEmpty())
    }

    @Test
    fun forgetAllowsANewSuggestion() {
        val d = WearableDetector(registry)
        d.onSighting(bonded("A4:00:00:00:00:01", band9), 0, emptySet(), emptySet())
        d.forget("a4:00:00:00:00:01")
        assertTrue(d.onSighting(bonded("A4:00:00:00:00:01", band9), 1, emptySet(), emptySet()) is WearableDetection.Suggest)
    }
}
