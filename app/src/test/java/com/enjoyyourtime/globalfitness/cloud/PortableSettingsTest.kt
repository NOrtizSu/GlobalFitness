package com.enjoyyourtime.globalfitness.cloud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Nothing secret or device-specific may leave the phone. */
class PortableSettingsTest {

    private val localPrefs: Map<String, Any?> = mapOf(
        "units" to "kg",
        "selected_profile_id" to 3L,
        "ble_permission_rationale_seen" to true,
        "dismissed_auto_config" to "AA:BB:CC:DD:EE:FF",
        "bandAuthKey" to "0123456789abcdef",
        "band_prefs" to "x",
        "healthConnectToken" to "t",
    )

    @Test
    fun nothingIsPortableToday() {
        assertTrue(PortableSettings.filterSettings(localPrefs).isEmpty())
    }

    @Test
    fun allowlistKeepsOnlySimpleSafeValues() {
        val allowed = setOf("units", "bandAuthKey", "nested")
        val values = localPrefs + ("nested" to listOf(1, 2))
        // "bandAuthKey" is allowlisted by mistake here, but the secret filter still drops it.
        assertEquals(mapOf("units" to "kg"), PortableSettings.filterSettings(values, allowed))
    }

    @Test
    fun settingsDocumentNeverContainsSecrets() {
        val doc = FirestoreCodec.settingsToMap(SettingsRecord(localPrefs.filterValues { it != null }.mapValues { it.value!! }, 10L))
        @Suppress("UNCHECKED_CAST")
        val values = doc["values"] as Map<String, Any>
        assertTrue(values.isEmpty())
        val flat = doc.toString()
        assertFalse(flat.contains("AA:BB"))
        assertFalse(flat.contains("0123456789abcdef"))
    }

    @Test
    fun downloadedSettingsAreFilteredToo() {
        val remote = mapOf(
            "values" to mapOf("pinned_scale_mac" to "11:22", "bandAuthKey" to "k"),
            FirestoreCodec.F_EDITED_AT to 5L,
        )
        val record = FirestoreCodec.settingsFromMap(remote, null)!!
        assertTrue(record.values.isEmpty())
        assertEquals(5L, record.editedAt)
    }

    @Test
    fun sensitiveKeyDetection() {
        listOf("authKey", "AUTH_KEY", "token", "macAddress", "bleAddress", "pinnedMac", "band_secret", "healthData", "password", "bindkey", "pin")
            .forEach { assertTrue(it, PortableSettings.looksSensitive(it)) }
        listOf(PortableSettings.DEVICE_USER_SLOT, "unit", "name")
            .forEach { assertFalse(it, PortableSettings.looksSensitive(it)) }
    }

    @Test
    fun scaleConfigKeepsOnlyTheUserSlot() {
        val device = DeviceRecord(
            id = "3f2a-uuid",
            driverId = "xiaomi_s400",
            name = "Báscula baño",
            config = mapOf(
                "user_slot" to "2",
                "bindkey" to "00112233445566778899aabbccddeeff",
                "pin" to "1234",
                "omron_pairing_key" to "abcd",
                "bodyconnect_password" to "1234",
                "epoch_mode" to "unix",
            ),
            updatedAt = 1L,
        )
        @Suppress("UNCHECKED_CAST")
        val config = FirestoreCodec.deviceToMap(device)["config"] as Map<String, String>
        assertEquals(mapOf("user_slot" to "2"), config)
        assertFalse(FirestoreCodec.deviceToMap(device).toString().contains("00112233445566778899aabbccddeeff"))
    }
}
