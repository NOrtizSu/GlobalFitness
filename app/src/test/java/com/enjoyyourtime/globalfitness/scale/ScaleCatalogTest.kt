package com.enjoyyourtime.globalfitness.scale

import com.enjoyyourtime.globalfitness.scale.ScaleTestSupport.adv
import com.enjoyyourtime.globalfitness.scale.ScaleTestSupport.hex
import com.enjoyyourtime.globalfitness.scale.drivers.AllDrivers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every supported family is detected from its advertisement, and the specific driver wins over
 * generic ones (fixtures adapted from openScale's ScaleCatalog).
 */
class ScaleCatalogTest {
    private val registry = AllDrivers.registry()
    private val ffb0 = uuid16(0xFFB0)

    /** Advertisement -> expected winning driver id. */
    private val fixtures: List<Pair<AdvertisementInfo, String>> = listOf(
        adv("A&D_UC-352BLE_123456", listOf(uuid16(0x181D))) to "and_uc352ble",
        adv("HEALTHKEEP 280") to "healthkeep_280",
        adv("AE BS-06") to "active_era_bf06",
        adv("AFU-BH-TZ-B1", listOf(uuid16(0xFC50))) to "afu_b1",
        adv("Keep_S3") to "keep_s3",
        adv("PICOOC-CQ") to "picooc_gatt",
        adv("Latin-S") to "picooc_gatt",
        adv("PICOOC-L") to "picooc_mini_lite",
        adv("Beurer BF450") to "beurer_bf450",
        adv("BIA SCALE", listOf(ffb0)) to "taylor_bia",
        adv("RYFIT") to "ryfit",
        adv("CULT Smart Scale Pro") to "cult_smart_scale_pro",
        adv("realme Smart Scale") to "realme_smart_scale",
        adv("YUNMAI-ISSE-1234") to "yunmai_se",
        adv("YUNMAI-SIGNAL-1234") to "yunmai_mini",
        adv("01257B1234") to "trisa_body_analyze",
        adv("SANITAS SBF72") to "sanitas_sbf72",
        adv("SANITAS SBF73") to "sanitas_sbf72",
        adv("Beurer BF915") to "sanitas_sbf72",
        adv("Beurer BF105") to "beurer_bf_standard",
        adv("Beurer BF1000") to "beurer_bf_standard",
        adv("Beurer BF500") to "beurer_bf_standard",
        adv("Beurer BF600") to "beurer_bf_standard",
        adv("Beurer BF950") to "beurer_bf_standard",
        adv("Shape200") to "soehnle_shape",
        adv("Weight Scale") to "sinocare",
        adv("SENSSUN FAT") to "senssun_fat",
        adv("RENPHO-SCALE-1234") to "renpho_es_wbe28",
        adv("QN-Scale", listOf(uuid16(0xFFE0))) to "qn_scale",
        adv("Health Scale") to "onebyone_classic",
        adv("eufy T9146") to "onebyone_classic",
        adv("eufy T9147") to "onebyone_classic",
        adv("eufy T9120") to "onebyone_classic",
        adv("1byone scale") to "onebyone_new",
        adv("XMTZC14HM") to "xiaomi_s400",
        adv("MIJIA SCALE S800") to "xiaomi_s800",
        adv("MIBFS") to "xiaomi_mi_scale_2",
        adv("MI_SCALE", serviceData = mapOf(uuid16(0x181D) to ByteArray(10))) to "xiaomi_mi_scale_1",
        adv("runstar-r6") to "runstar_r6",
        adv("RUNSTAR-R5") to "runstar_r5",
        adv("relaxmedic") to "relaxmedic",
        adv("robi") to "robi_s9",
        adv("Vitafit VT701") to "vitafit_vt701",
        adv("EEBBL") to "eebbl_p1",
        adv("FITTRACK Dara") to "fittrack_dara",
        adv("SSW532", listOf(ffb0)) to "drtrust_ssw532",
        adv("MY_SCALE", listOf(ffb0)) to "taylor_bia",
        adv("swan", listOf(ffb0)) to "mgb_swan_icomon",
        adv("0203B1234") to "medisana_bs44x",
        adv("0131971234") to "medisana_bs44x",
        adv("000fatscale01") to "inlife",
        adv("HUAWEI AH100") to "huawei_ah100",
        adv("HUAWEI CH100") to "huawei_ah100",
        adv("CH100S") to "honor_ch100s",
        adv("Hoffen BS-8107") to "hoffen_bbs8107",
        adv("PC-PW 3008 BT") to "hoffen_bbs8107",
        adv("yunchen") to "hesley_yunchen",
        adv("vscale") to "exingtech_y1",
        adv("Body Fat-B2") to "ebelter_body_fat_b2",
        adv("Electronic Scale") to "excelvan_cf36x",
        adv("Dara 2.0") to "hume_dara2",
        adv("Etekcity Smart Fitness Scale") to "etekcity_esf551",
        adv("EUFY C20") to "eufy_c20",
        adv("eufy T9148") to "eufy_p2",
        adv("ES-CS20M") to "es_cs20m",
        adv("ES-26BB-B") to "renpho_es26bb",
        adv("R-A012") to "renpho_es26bb",
        adv("R-A020") to "renpho_es26bb",
        adv("Mengii") to "digoo_dgso38h",
        adv("openScale") to "custom_openscale",
        adv("BEURER BF700") to "beurer_sanitas_ffe0",
        adv("BEURER BF710") to "beurer_sanitas_ffe0",
        adv("SANITAS SBF70") to "beurer_sanitas_ffe0",
        adv("AAA002") to "aaa_broadcast",
        adv("1BODY CONNECT") to "body_connect",
        adv("1X-LINE") to "body_connect",
        adv("10376BAA") to "weight_gurus_0376",
        adv("HBF-702T") to "omron_wlp",
        adv("KRD-703T") to "omron_wlp",
        adv("HBF-227T") to "omron_wlp",
        adv("HBF-228T") to "omron_wlp",
        adv("HBF-230T") to "omron_wlp",
        adv("HBF-222T") to "omron_wlp",
        adv("BCM-500") to "omron_wlp",
        adv("VIVA") to "omron_wlp",
        adv("BLEsmart_0001000C0080E1A2B3C4") to "omron_wlp",
        adv(services = listOf(uuid16(0x1320))) to "yunmai_x",
        adv(manufacturer = mapOf(0xFFFF to hex("aabb00005e00530172000000ffffff1500761b510e032300"))) to "qn_broadcast",
        adv("ADV", manufacturer = mapOf(0x20CA to ByteArray(16))) to "okok_broadcast",
        adv("ADV", manufacturer = mapOf(0x11CA to ByteArray(16))) to "okok_broadcast",
        adv("ADV", manufacturer = mapOf(0xF0FF to ByteArray(16))) to "okok_broadcast",
        adv(services = listOf(uuid16(0xFFD0)), manufacturer = mapOf(0x06D0 to ByteArray(20))) to "etekcity_fit8s",
        adv(manufacturer = mapOf(0x1DD0 to ByteArray(9))) to "scaleup",
        adv("EL1", listOf(uuid16(0xF0A0)), manufacturer = mapOf(0x0301 to hex("015F4E3D2C1B0A8F10E69CF7CB452CCCFFFF"))) to "ailink_broadcast",
        // Generic Bluetooth SIG weight scale (no vendor fingerprint).
        adv("Generic", listOf(uuid16(0x181D))) to "sig_weight_scale",
    )

    @Test
    fun everyFixtureIsClaimedByItsOwnDriver() {
        val wrong = fixtures.mapNotNull { (adv, expected) ->
            val got = registry.best(adv)?.driver?.id
            if (got == expected) null else "${adv.name ?: adv.manufacturerData.keys}: expected $expected, got $got"
        }
        assertTrue(wrong.joinToString("\n"), wrong.isEmpty())
    }

    @Test
    fun everyRegisteredDriverHasAFixture() {
        val covered = fixtures.map { it.second }.toSet()
        val missing = registry.drivers.map { it.id }.filter { it !in covered }
        assertTrue("Drivers without a catalog fixture: $missing", missing.isEmpty())
    }

    @Test
    fun driverIdsAreUnique() {
        val ids = registry.drivers.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun unrelatedDevicesAreIgnored() {
        assertNull(registry.best(adv("MI Band 5", listOf(uuid16(0xFEE7)))))
        assertNull(registry.best(adv("PC-PW 3007 BT")))
        assertNull(registry.best(adv("Galaxy Buds2")))
        assertNull(registry.best(adv(null)))
    }

    @Test
    fun onlyTheMiScale2IsVerified() {
        assertEquals(listOf("xiaomi_mi_scale_2"), registry.drivers.filter { it.verified }.map { it.id })
    }

    @Test
    fun catalogSearchFindsByBrandAndModel() {
        assertTrue(registry.search("omron").any { it.id == "omron_wlp" })
        assertEquals("xiaomi_mi_scale_2", registry.search("xiaomi").first().id)
        assertTrue(registry.search("t9148").any { it.id == "eufy_p2" })
        assertTrue(registry.search("OMRON hbf").any { it.id == "omron_wlp" })
    }
}
