package com.enjoyyourtime.globalfitness.wearable

import com.enjoyyourtime.globalfitness.scale.Bytes
import com.enjoyyourtime.globalfitness.scale.MatchConfidence
import com.enjoyyourtime.globalfitness.wearable.standard.StandardHeartRateDriver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The name-regex table of every driver (from Gadgetbridge's coordinators) and catalog invariants. */
class WearableCatalogTest {
    private val registry = AllWearables.registry()

    private fun named(name: String, bonded: Boolean = false) =
        WearableSighting("AA:BB:CC:DD:EE:FF", name, rssi = if (bonded) null else -50, bonded = bonded)

    /** At least one real advertised / bonded name per driver, with the driver that must win. */
    private val positives: Map<String, List<String>> = mapOf(
        // Xiaomi / Redmi protobuf
        "xiaomi_smart_band_10_pro" to listOf("Xiaomi Smart Band 10 Pro 1A2B"),
        "xiaomi_smart_band_10" to listOf("Xiaomi Smart Band 10 1A2B"),
        "xiaomi_smart_band_11" to listOf("Xiaomi Smart Band 11 FFFF"),
        "xiaomi_smart_band_9_pro" to listOf("Xiaomi Smart Band 9 Pro 0A1B"),
        "xiaomi_smart_band_9_active" to listOf("Xiaomi Smart Band 9 Active 1234", "Xiaomi Band 9 Active ABCD"),
        "xiaomi_smart_band_9" to listOf("Xiaomi Smart Band 9 3A7F"),
        "xiaomi_smart_band_8_pro" to listOf("Xiaomi Smart Band 8 Pro 9F00"),
        "xiaomi_smart_band_8_active" to listOf("Xiaomi Smart Band 8 Active Z9X8", "Xiaomi Band 8 Active 0000"),
        "xiaomi_smart_band_8" to listOf("Xiaomi Smart Band 8 Z1Y2"),
        "xiaomi_smart_band_7_pro" to listOf("Xiaomi Smart Band 7 Pro", "Xiaomi Smart Band 7 Pro A1B2"),
        "xiaomi_mi_band_4c" to listOf("Mi Smart Band 4C_1A2B"),
        "xiaomi_mi_watch_color_sport" to listOf("Mi ColorS 1Z2Y"),
        "xiaomi_mi_watch_lite" to listOf("Mi Watch Lite_A1B2"),
        "redmi_smart_band_2" to listOf("Redmi Smart Band 2 A1B2"),
        "redmi_smart_band_3" to listOf("Redmi Smart Band 3 A1B2"),
        "redmi_smart_band_pro" to listOf("Redmi Band Pro A1B2"),
        "redmi_watch_2_lite" to listOf("Redmi Watch 2 Lite 1A2B"),
        "redmi_watch_2" to listOf("Redmi Watch 2 1A2B"),
        "redmi_watch_3_active" to listOf("Redmi Watch 3 Active 1A2B"),
        "redmi_watch_3" to listOf("Redmi Watch 3 1A2B"),
        "redmi_watch_4" to listOf("Redmi Watch 4 1A2B"),
        "redmi_watch_5_active" to listOf("Redmi Watch 5 Active 1A2B"),
        "redmi_watch_5_lite" to listOf("Redmi Watch 5 Lite 1A2B"),
        "redmi_watch_5" to listOf("Redmi Watch 5 1A2B"),
        "redmi_watch_6" to listOf("Redmi Watch 6 1A2B", "Redmi Watch 6 NFC 1a2b"),
        "redmi_watch_move" to listOf("Move"),
        "xiaomi_watch_5" to listOf("Xiaomi Watch 5", "Xiaomi Watch 5 eSIM"),
        "xiaomi_watch_s1_active" to listOf("XiaomiWatchS1Active 1Z2Y"),
        "xiaomi_watch_s1_pro" to listOf("Xiaomi Watch S1 Pro 1A2B", "L61 1A2B"),
        "xiaomi_watch_s1" to listOf("Xiaomi Watch S1 1A2B"),
        "xiaomi_watch_s3" to listOf("Xiaomi Watch S3 1A2B", "Xiaomi Watch S3 eSIM 1A2B"),
        "xiaomi_watch_s4" to listOf("Xiaomi Watch S4 1A2B"),
        // Zepp OS
        "xiaomi_smart_band_7" to listOf("Xiaomi Smart Band 7", "Xiaomi Smart Band 7 A1B2"),
        "amazfit_band_7" to listOf("Amazfit Band 7", "Amazfit Band 7-1A2B"),
        "amazfit_gtr_3" to listOf("Amazfit GTR 3"),
        "amazfit_gtr_3_pro" to listOf("Amazfit GTR 3 Pro"),
        "amazfit_gtr_4" to listOf("Amazfit GTR 4", "Amazfit GTR 4 LE"),
        "amazfit_gtr_mini" to listOf("Amazfit GTR Mini"),
        "amazfit_gts_3" to listOf("Amazfit GTS 3"),
        "amazfit_gts_4" to listOf("Amazfit GTS 4"),
        "amazfit_gts_4_mini" to listOf("Amazfit GTS 4 Mini"),
        "amazfit_gts_4_mini_new" to listOf("Amazfit GTS 4 mini New"),
        "amazfit_bip_5" to listOf("Amazfit Bip 5"),
        "amazfit_bip_5_unity" to listOf("Amazfit Bip 5 Unity"),
        "amazfit_bip_6" to listOf("Amazfit Bip 6"),
        "amazfit_bip_max" to listOf("Amazfit Bip Max", "Amazfit BIP Max"),
        "amazfit_balance" to listOf("Amazfit Balance"),
        "amazfit_balance_2" to listOf("Amazfit Balance 2"),
        "amazfit_balance_2_xt" to listOf("Amazfit Balance 2 XT"),
        "amazfit_balance_3" to listOf("Amazfit Balance 3", "Balance 3"),
        "amazfit_balance_ultra" to listOf("Amazfit Balance Ultra"),
        "amazfit_active" to listOf("Amazfit Active"),
        "amazfit_active_edge" to listOf("Amazfit Active Edge"),
        "amazfit_active_max" to listOf("Amazfit Active Max", "Active Max"),
        "amazfit_active_2_round" to listOf("Active 2 (Round)"),
        "amazfit_active_2_nfc" to listOf("Active 2 NFC (Round)"),
        "amazfit_active_2_square" to listOf("Active 2 (Square)", "Active 2 NFC Square"),
        "amazfit_active_3_premium" to listOf("Amazfit Active 3 Premium"),
        "amazfit_cheetah_pro" to listOf("Amazfit Cheetah Pro"),
        "amazfit_cheetah_round" to listOf("Amazfit Cheetah R"),
        "amazfit_cheetah_square" to listOf("Amazfit Cheetah S"),
        "amazfit_cheetah_2_pro" to listOf("Amazfit Cheetah 2 Pro", "Cheetah 2 Pro"),
        "amazfit_cheetah_2_ultra" to listOf("Cheetah 2 Ultra"),
        "amazfit_falcon" to listOf("Amazfit Falcon"),
        "amazfit_trex_2" to listOf("Amazfit T-Rex 2"),
        "amazfit_trex_ultra" to listOf("Amazfit T-Rex Ultra"),
        "amazfit_trex_ultra_2" to listOf("T-Rex Ultra 2"),
        "amazfit_trex_3" to listOf("Amazfit T-Rex 3"),
        "amazfit_trex_3_pro_44" to listOf("T-Rex 3 Pro (44mm)"),
        "amazfit_trex_3_pro_48" to listOf("T-Rex 3 Pro (48mm)"),
        "amazfit_helio_ring" to listOf("Amazfit Helio Ring"),
        "amazfit_helio_strap" to listOf("Amazfit Helio Strap"),
        // Classic Huami
        "xiaomi_mi_band_2" to listOf("MI Band 2", "Mi Band 2"),
        "xiaomi_mi_band_2_hrx" to listOf("Mi Band HRX", "Mi Band 2i"),
        "xiaomi_mi_band_3" to listOf("Mi Band 3", "Xiaomi Band 3"),
        "xiaomi_mi_band_4" to listOf("Mi Smart Band 4"),
        "xiaomi_mi_band_5" to listOf("Mi Smart Band 5"),
        "xiaomi_mi_band_6" to listOf("Mi Smart Band 6"),
        "amazfit_band_5" to listOf("Amazfit Band 5"),
        "amazfit_x" to listOf("Amazfit X"),
        "amazfit_neo" to listOf("Amazfit Neo"),
        "amazfit_bip" to listOf("Amazfit Bip Watch"),
        "amazfit_bip_lite" to listOf("Amazfit Bip Lite"),
        "amazfit_bip_s" to listOf("Amazfit Bip S"),
        "amazfit_bip_s_lite" to listOf("Bip S Lite"),
        "amazfit_bip_u" to listOf("Amazfit Bip U"),
        "amazfit_bip_u_pro" to listOf("Amazfit Bip U Pro"),
        "amazfit_pop" to listOf("Amazfit Pop"),
        "amazfit_pop_pro" to listOf("Amazfit Pop Pro"),
        "amazfit_bip_3" to listOf("Amazfit Bip 3"),
        "amazfit_bip_3_pro" to listOf("Amazfit Bip 3 Pro"),
        "amazfit_cor" to listOf("Amazfit Cor", "Amazfit Band"),
        "amazfit_cor_2" to listOf("Amazfit Cor 2", "Amazfit Band 2"),
        "amazfit_gts" to listOf("Amazfit GTS"),
        "amazfit_gtr" to listOf("Amazfit GTR"),
        "amazfit_gtr_lite" to listOf("Amazfit GTR L"),
        "amazfit_gts_2" to listOf("Amazfit GTS 2"),
        "amazfit_gts_2e" to listOf("Amazfit GTS 2e"),
        "amazfit_gts_2_mini" to listOf("Amazfit GTS2 mini"),
        "amazfit_gtr_2" to listOf("Amazfit GTR 2"),
        "amazfit_gtr_2e" to listOf("Amazfit GTR 2e"),
        "amazfit_trex" to listOf("Amazfit T-Rex"),
        "amazfit_trex_pro" to listOf("Amazfit T-Rex Pro"),
        "amazfit_verge_lite" to listOf("Amazfit Verge L"),
        "zepp_e" to listOf("Zepp E"),
        // Others
        "pinetime_infinitime" to listOf("InfiniTime", "Pinetime-JF"),
        "banglejs" to listOf("Bangle.js 1a2b", "Espruino ABCD", "Puck.js 1234"),
        "polar_h10" to listOf("Polar H10 12345678"),
        "polar_h9" to listOf("Polar H9 ABCDEF12"),
    )

    /** Names that must not be claimed by any driver of the family. */
    private val negatives: Map<WearableFamily, List<String>> = mapOf(
        WearableFamily.XIAOMI to listOf("Xiaomi Smart Band 9", "Xiaomi Smart Band 9 3a7f", "Xiaomi Smart Band 9 3A7F0", "Redmi Watch 4", "Mi Band 3", "Xiaomi Watch 5 Pro", "Xiaomi Watch 5 Lite 1A2B", "Moved"),
        WearableFamily.ZEPP_OS to listOf("Amazfit Band 5", "Amazfit GTR", "Amazfit GTR 3 Pro Max", "Amazfit Bip 5 1A2B3", "amazfit band 7"),
        WearableFamily.HUAMI to listOf("Mi Smart Band 7", "Amazfit Bip 5", "Amazfit GTR 3", "amazfit gts", "Mi Band 2 HRX"),
        WearableFamily.PINETIME to listOf("PineTime", "P8"),
        WearableFamily.BANGLEJS to listOf("Bangle", "bangle.js"),
        WearableFamily.STANDARD_BLE to listOf("Polar H7 1234", "Polar Verity"),
    )

    @Test
    fun everyDriverHasAPositiveName() {
        val missing = registry.drivers.map { it.id }.filter { it != StandardHeartRateDriver.GENERIC_ID && it !in positives }
        assertTrue("drivers without a test name: $missing", missing.isEmpty())
    }

    @Test
    fun positiveNamesPickTheirDriver() {
        for ((id, names) in positives) {
            val expected = registry.find(id)
            assertNotNull("unknown driver $id", expected)
            for (name in names) {
                for (bonded in listOf(false, true)) {
                    val best = registry.best(named(name, bonded))
                    assertEquals("$name → ${registry.detect(named(name)).map { it.driver.id to it.confidence }}", id, best?.driver?.id)
                    val confidence = if (id == "redmi_watch_move") MatchConfidence.MEDIUM else MatchConfidence.HIGH
                    assertEquals(name, confidence, best!!.confidence)
                    // Exactly one HIGH match: detection can suggest it.
                    if (confidence == MatchConfidence.HIGH) assertEquals(name, 1, registry.detect(named(name)).count { it.confidence == MatchConfidence.HIGH })
                }
            }
        }
    }

    @Test
    fun negativeNamesAreNotClaimedByTheFamily() {
        for ((family, names) in negatives) {
            for (name in names) {
                val claimed = registry.detect(named(name)).filter { it.driver.family == family }
                assertTrue("$name claimed by ${claimed.map { it.driver.id }}", claimed.isEmpty())
            }
        }
    }

    @Test
    fun genericHeartRateNeedsTheService() {
        val hrs = WearableSighting("AA:BB:CC:DD:EE:FF", "Coospo H6", rssi = -60, serviceUuids = listOf(Bytes.uuid16(0x180D)))
        val best = registry.best(hrs)!!
        assertEquals(StandardHeartRateDriver.GENERIC_ID, best.driver.id)
        assertEquals(MatchConfidence.LOW, best.confidence)
        assertNull(registry.best(WearableSighting("AA:BB:CC:DD:EE:FF", "Coospo H6", rssi = -60)))
        // A watch that also advertises 0x180D is still taken by its own driver.
        assertEquals("pinetime_infinitime", registry.best(WearableSighting("AA", "InfiniTime", rssi = -60, serviceUuids = listOf(Bytes.uuid16(0x180D))))!!.driver.id)
    }

    @Test
    fun catalogInvariants() {
        assertEquals(registry.drivers.size, registry.drivers.map { it.id }.toSet().size)
        for (d in registry.drivers) {
            assertTrue(d.id, d.displayName.isNotBlank() && d.brand.isNotBlank())
            assertTrue(d.id, !d.verified)
            assertEquals(d.id, d.needsAuthKey, d.keyHelp != null)
            if (d.needsAuthKey) assertEquals(d.id, "https://gadgetbridge.org/basics/pairing/huami-xiaomi-server/", d.keyHelp!!.serverDocUrl)
            assertTrue(d.id, d.docUrl?.startsWith("https://gadgetbridge.org/") == true)
            assertTrue(d.id, d.features.isNotEmpty())
        }
        val families = registry.drivers.groupBy { it.family }.mapValues { it.value.size }
        assertEquals(32, families[WearableFamily.XIAOMI])
        assertTrue(families.getValue(WearableFamily.HUAMI) >= 30)
        assertTrue(families.getValue(WearableFamily.ZEPP_OS) >= 38)
        // docs/store-listing/*.md state this number ("Direct support for 110 models"): update them with it.
        assertEquals(110, registry.drivers.size)
        assertEquals(WearableTransport.CLASSIC_SPP, registry.find("xiaomi_smart_band_9")!!.transport)
        assertEquals(WearableTransport.BLE_GATT, registry.find("xiaomi_smart_band_8")!!.transport)
        assertEquals(WearableTransport.BLE_UART, registry.find("banglejs")!!.transport)
        assertEquals(AuthKeyKind.NUMERIC_USER_ID, registry.find("xiaomi_mi_watch_lite")!!.authKey)
        assertEquals(AuthKeyKind.NONE, registry.find("pinetime_infinitime")!!.authKey)
    }

    @Test
    fun catalogSearch() {
        assertTrue(registry.search("band 9").any { it.id == "xiaomi_smart_band_9" })
        assertTrue(registry.search("pulsómetro").any { it.id == StandardHeartRateDriver.GENERIC_ID })
        assertTrue(registry.search("zepp os").all { it.family == WearableFamily.ZEPP_OS || "zepp" in it.keywords.joinToString() })
        assertEquals(registry.drivers.size, registry.search("").size)
    }
}
