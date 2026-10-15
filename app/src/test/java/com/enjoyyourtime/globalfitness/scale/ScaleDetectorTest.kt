package com.enjoyyourtime.globalfitness.scale

import com.enjoyyourtime.globalfitness.ble.TestFrames
import com.enjoyyourtime.globalfitness.scale.drivers.AllDrivers
import com.enjoyyourtime.globalfitness.scale.drivers.MiScale2Driver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScaleDetectorTest {
    private val registry = AllDrivers.registry()
    private val miFrame = mapOf(Bytes.uuid16(0x181B) to TestFrames.bytes(TestFrames.STABLE, 14_400))

    private fun mi(address: String) = ScaleTestSupport.adv(serviceData = miFrame, address = address)

    @Test
    fun autoConfiguresTheOnlyUnambiguousScaleOnce() {
        val detector = ScaleDetector(registry)
        val result = detector.onAdvertisement(mi("AA:AA:AA:AA:AA:01"), 1_000, emptyList(), emptySet())
        assertTrue(result is DetectionResult.AutoConfigure)
        result as DetectionResult.AutoConfigure
        assertEquals(MiScale2Driver.ID, result.driver.id)
        assertEquals("AA:AA:AA:AA:AA:01", result.address)
        // Seen again before the app stored the configuration: no second auto-configuration.
        assertEquals(DetectionResult.Updated, detector.onAdvertisement(mi("AA:AA:AA:AA:AA:01"), 5_000, emptyList(), emptySet()))
        assertEquals(1, detector.detected(5_000).size)
    }

    @Test
    fun doesNotAutoConfigureWhenAScaleIsConfiguredOrDismissed() {
        val configured = listOf(ConfiguredScale("x", MiScale2Driver.ID, "BB:BB:BB:BB:BB:BB", "Mi"))
        assertEquals(DetectionResult.Updated, ScaleDetector(registry).onAdvertisement(mi("AA:AA:AA:AA:AA:01"), 0, configured, emptySet()))
        assertEquals(
            DetectionResult.Updated,
            ScaleDetector(registry).onAdvertisement(mi("AA:AA:AA:AA:AA:01"), 0, emptyList(), setOf("AA:AA:AA:AA:AA:01")),
        )
    }

    @Test
    fun twoScalesAroundAreAmbiguous() {
        val detector = ScaleDetector(registry)
        assertTrue(detector.onAdvertisement(mi("AA:AA:AA:AA:AA:01"), 0, emptyList(), setOf("AA:AA:AA:AA:AA:01")) is DetectionResult.Updated)
        // A second scale while the first one (dismissed, but still around) is fresh: no auto-config.
        assertEquals(DetectionResult.Updated, detector.onAdvertisement(mi("AA:AA:AA:AA:AA:02"), 1_000, emptyList(), emptySet()))
        // Once the first one has not been seen for the TTL, the second one is unambiguous.
        val later = detector.onAdvertisement(mi("AA:AA:AA:AA:AA:02"), 200_000, emptyList(), emptySet())
        assertTrue(later is DetectionResult.AutoConfigure)
    }

    @Test
    fun mediumConfidenceNeverAutoConfiguresAndUnknownDevicesAreIgnored() {
        val detector = ScaleDetector(registry)
        val generic = ScaleTestSupport.adv(name = "XMTZC99", address = "AA:AA:AA:AA:AA:09")
        assertEquals(DetectionResult.Updated, detector.onAdvertisement(generic, 0, emptyList(), emptySet()))
        assertEquals(MatchConfidence.MEDIUM, detector.detected(0).single().best.confidence)
        val phone = ScaleTestSupport.adv(name = "Pixel 9", address = "AA:AA:AA:AA:AA:10")
        assertEquals(DetectionResult.Ignored, detector.onAdvertisement(phone, 0, emptyList(), emptySet()))
    }

    @Test
    fun staleDevicesDropOut() {
        val detector = ScaleDetector(registry, ttlMs = 10_000)
        detector.onAdvertisement(mi("AA:AA:AA:AA:AA:01"), 0, emptyList(), setOf("AA:AA:AA:AA:AA:01"))
        assertEquals(1, detector.detected(5_000).size)
        assertTrue(detector.detected(20_000).isEmpty())
    }

    @Test
    fun configuredScalesSurviveEncoding() {
        val list = listOf(
            ConfiguredScale(
                id = "1",
                driverId = "xiaomi_s400",
                address = "AA:BB:CC:DD:EE:FF",
                name = "Báscula\tdel baño & más=",
                config = mapOf(ConfigField.KEY_BINDKEY to "00112233445566778899aabbccddeeff", "user_slot" to "2"),
                addedAt = 123,
                autoConfigured = true,
            ),
            ConfiguredScale(id = "2", driverId = MiScale2Driver.ID, address = null, name = "Cualquiera"),
        )
        val decoded = ConfiguredScaleCodec.decode(ConfiguredScaleCodec.encode(list))
        assertEquals(list, decoded)
        assertEquals(2, decoded[0].userSlot)
        assertTrue(ConfiguredScaleCodec.decode("garbage").isEmpty())
        assertTrue(decoded[1].matchesAddress("11:22:33:44:55:66"))
        assertTrue(decoded[0].matchesAddress("aa:bb:cc:dd:ee:ff"))
    }

    @Test
    fun catalogSearchIsAccentAndCaseInsensitive() {
        assertTrue(registry.search("xiaomi").any { it.id == MiScale2Driver.ID })
        assertEquals(registry.search("xiaomi").size, registry.search("XIAÓMI").size)
        assertEquals(registry.drivers.size, registry.search("").size)
        assertTrue(registry.search("xmtzc14hm").single().id == "xiaomi_s400")
        // Verified drivers are listed first.
        assertTrue(registry.search("")[0].verified)
    }

    @Test
    fun configFieldValidation() {
        val key = ConfigField(ConfigField.KEY_BINDKEY, "k", "", ConfigKind.HEX_KEY_32, required = true)
        assertEquals("Obligatorio", key.validate(""))
        assertEquals(null, key.validate("00112233445566778899aabbccddeeff"))
        assertTrue(key.validate("0011") != null)
        val slot = ConfigField.userSlot(maxSlot = 8)
        assertEquals(null, slot.validate("3"))
        assertTrue(slot.validate("9") != null)
        assertEquals(null, slot.validate(""))
    }
}
