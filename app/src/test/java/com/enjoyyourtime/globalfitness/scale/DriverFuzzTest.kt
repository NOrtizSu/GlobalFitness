package com.enjoyyourtime.globalfitness.scale

import com.enjoyyourtime.globalfitness.scale.drivers.AllDrivers
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * Robustness: every driver is fed thousands of random, truncated and oversized advertisements
 * and notifications (deterministic seed). Nothing may throw out of match(), a passive session,
 * or a GATT protocol callback.
 */
class DriverFuzzTest {
    private val registry = AllDrivers.registry()
    private val bindKey = "00112233445566778899aabbccddeeff"

    private val serviceShorts = listOf(
        0x181B, 0x181D, 0x181C, 0xFE95, 0x1320, 0xFFD0, 0xFFE0, 0xFFF0, 0xFFB0, 0xF0A0, 0xFC50, 0xFAA0, 0x78B2, 0x7892,
        0xA602, 0xAE00, 0x1A10, 0x00FF, 0x180F, 0xFEE7, 0x1805,
    )
    private val companyIds = listOf(
        0xFFFF, 0x06D0, 0x20CA, 0x11CA, 0xF0FF, 0x0301, 48228, 0xFF64, 0x0000, 0x1DD0, 0x12C0, 0x34C0, 0x3456, 0x004C, 0x0075, 0xE0E0, 0xD0D0,
    )
    private val names = listOf(
        null, "", "MIBFS", "MI_SCALE", "MI SCALE2", "XMTZC14HM", "MIJIA SCALE S800", "ADV", "Chipsea-BLE", "Yoda1", "EL1", "QN-Scale",
        "Health Scale", "eufy T9146", "eufy T9148", "1byone scale", "YUNMAI-ISM2", "YUNMAI-ISSE", "Keep_S3", "PICOOC-L", "PICOOC-CQ",
        "HBF-702T", "BLEsmart_0001000C0080E1A2B3C4", "CH100S", "HUAWEI AH100", "SENSSUN FAT", "Hoffen BS-8107", "openScale", "AE BS-06",
        "AFU-BH-TZ-B1", "Weight Scale", "da", "AAA002", "1BODY CONNECT", "10376BAA", "realme Smart Scale", "Dara 2.0", "swan", "robi",
        "Etekcity Smart Fitness Scale", "ES-CS20M", "ES-26BB-B", "RENPHO-SCALE-1", "Shape200", "01257B1234", "Beurer BF450", "BEURER BF700",
        "SANITAS SBF72", "Beurer BF105", "0203B1234", "A&D_UC-352BLE_1", "\u0000\u0000", "x".repeat(300),
    )

    private fun Random.bytes(max: Int): ByteArray = ByteArray(nextInt(0, max + 1)) { nextInt(256).toByte() }

    private fun Random.address(): String = (1..6).joinToString(":") { String.format("%02X", nextInt(256)) }

    private fun Random.advert(address: String): AdvertisementInfo {
        val services = serviceShorts.shuffled(this).take(nextInt(0, 4)).map { uuid16(it) }
        val serviceData = serviceShorts.shuffled(this).take(nextInt(0, 3)).associate { uuid16(it) to bytes(32) }
        val manufacturer = companyIds.shuffled(this).take(nextInt(0, 3)).associateWith { bytes(40) } +
            (if (nextInt(4) == 0) mapOf(nextInt(0x10000) to bytes(40)) else emptyMap())
        val name = if (nextInt(5) == 0) String(CharArray(nextInt(0, 30)) { nextInt(32, 0x2FF).toChar() }) else names[nextInt(names.size)]
        return AdvertisementInfo(address, name, -nextInt(30, 100), services, serviceData, manufacturer, null, 1_800_000_000_000L)
    }

    @Test
    fun matchersNeverThrow() {
        val rnd = Random(20261015)
        val failures = ArrayList<String>()
        repeat(4_000) {
            val adv = rnd.advert(rnd.address())
            for (d in registry.drivers) {
                try {
                    d.match(adv)
                } catch (e: Exception) {
                    failures += "${d.id}.match: $e on $adv"
                }
            }
        }
        assertTrue(failures.take(10).joinToString("\n"), failures.isEmpty())
    }

    @Test
    fun passiveSessionsNeverThrow() {
        val rnd = Random(1234)
        val failures = ArrayList<String>()
        for (driver in registry.drivers.filterIsInstance<PassiveScaleDriver>()) {
            val clock = ScaleTestSupport.Clock()
            val store = MapDriverStore(mapOf(ConfigField.KEY_BINDKEY to bindKey))
            val address = rnd.address()
            val env = ScaleTestSupport.env(driver.id, clock, store = store, address = address)
            val session = driver.newSession(env)
            repeat(1_500) { i ->
                clock.advance(rnd.nextLong(0, 3_000))
                val adv = rnd.advert(address)
                try {
                    session.parseAdvertisement(adv, env)
                    if (i % 7 == 0 && session.needsTick) session.tick(env)
                    session.live
                } catch (e: Exception) {
                    failures += "${driver.id}: $e on $adv"
                }
            }
        }
        assertTrue(failures.take(10).joinToString("\n"), failures.isEmpty())
    }

    @Test
    fun gattProtocolsNeverThrow() {
        val rnd = Random(777)
        val failures = ArrayList<String>()
        val common = listOf(
            0xFFF1, 0xFFF2, 0xFFF4, 0xFFB2, 0xFFB3, 0xFFE1, 0xFFE4, 0x2A9D, 0x2A9C, 0x2A9F, 0x2A19, 0x8A21, 0x8A22, 0x8A24, 0x8A82,
            0xFAA2, 0xFF01, 0x2A10, 0x0000,
        ).map { uuid16(it) }
        for (driver in registry.drivers.filterIsInstance<GattScaleDriver>()) {
            repeat(3) { round ->
                val name = names[rnd.nextInt(names.size)]
                val adv = ScaleTestSupport.adv(name = name, address = "CF:E6:03:1D:09:F7")
                val io = FakeGattIo()
                io.tree = listOf(GattCharInfo(uuid16(0xFFF0), uuid16(0xFFF1), canNotify = true, canWrite = true))
                val store = MapDriverStore(if (round == 1) mapOf(ConfigField.KEY_USER_SLOT to "2", ConfigField.KEY_PIN to "1234") else emptyMap())
                val h = try {
                    ProtocolHarness(driver, adv, io = io, store = store, address = "CF:E6:03:1D:09:F7").connect()
                } catch (e: Exception) {
                    failures += "${driver.id}.connect: $e"
                    return@repeat
                }
                val targets = (io.notified + common).distinct()
                repeat(700) { i ->
                    val chr = targets[rnd.nextInt(targets.size)]
                    val payload = when (rnd.nextInt(6)) {
                        0 -> ByteArray(0)
                        1 -> rnd.bytes(80)
                        else -> rnd.bytes(22)
                    }
                    try {
                        h.notify(chr, payload)
                        if (i % 25 == 0) io.fireTimers()
                    } catch (e: Exception) {
                        failures += "${driver.id}: $e on ${Bytes.toHex(payload)} @ $chr"
                    }
                }
                try {
                    h.protocol.onDisconnected()
                } catch (e: Exception) {
                    failures += "${driver.id}.onDisconnected: $e"
                }
            }
        }
        assertTrue(failures.take(10).joinToString("\n"), failures.isEmpty())
    }
}
