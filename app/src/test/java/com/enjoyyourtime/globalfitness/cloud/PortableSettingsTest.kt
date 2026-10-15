package com.enjoyyourtime.globalfitness.cloud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Nothing secret or device-specific may leave the phone. */
class PortableSettingsTest {

    private val localPrefs: Map<String, Any?> = mapOf(
        PortableSettings.ASSIGNMENT_TOLERANCE_KG to 2.5,
        "pinned_scale_mac" to "AA:BB:CC:DD:EE:FF",
        "selected_profile_id" to 3L,
        "ble_permission_rationale_seen" to true,
        "bandAuthKey" to "0123456789abcdef",
        "band_prefs" to "x",
        "healthConnectToken" to "t",
        "unknownFutureSetting" to "v",
    )

    @Test
    fun onlyAllowlistedSettingsAreKept() {
        assertEquals(mapOf(PortableSettings.ASSIGNMENT_TOLERANCE_KG to 2.5), PortableSettings.filterSettings(localPrefs))
    }

    @Test
    fun settingsDocumentNeverContainsSecrets() {
        val doc = FirestoreCodec.settingsToMap(SettingsRecord(localPrefs.filterValues { it != null }.mapValues { it.value!! }, 10L))
        @Suppress("UNCHECKED_CAST")
        val values = doc["values"] as Map<String, Any>
        assertEquals(setOf(PortableSettings.ASSIGNMENT_TOLERANCE_KG), values.keys)
        val flat = doc.toString()
        assertFalse(flat.contains("AA:BB"))
        assertFalse(flat.contains("0123456789abcdef"))
    }

    @Test
    fun downloadedSettingsAreFilteredToo() {
        val remote = mapOf(
            "values" to mapOf(PortableSettings.ASSIGNMENT_TOLERANCE_KG to 4.0, "pinned_scale_mac" to "11:22"),
            FirestoreCodec.F_EDITED_AT to 5L,
        )
        val record = FirestoreCodec.settingsFromMap(remote, null)!!
        assertEquals(mapOf(PortableSettings.ASSIGNMENT_TOLERANCE_KG to 4.0), record.values)
        assertEquals(5L, record.editedAt)
    }

    @Test
    fun sensitiveKeyDetection() {
        listOf("authKey", "AUTH_KEY", "token", "macAddress", "bleAddress", "pinnedMac", "band_secret", "healthData", "password")
            .forEach { assertTrue(it, PortableSettings.looksSensitive(it)) }
        listOf(PortableSettings.ASSIGNMENT_TOLERANCE_KG, "userIndex", "unit", "name")
            .forEach { assertFalse(it, PortableSettings.looksSensitive(it)) }
    }

    @Test
    fun deviceConfigDropsSecretsAndAddresses() {
        val device = DeviceRecord(
            id = "mi_scale_2_1",
            driverId = "mi_scale_2",
            name = "Báscula baño",
            config = mapOf("userIndex" to "2", "authKey" to "secret", "macAddress" to "AA:BB", "unit" to "kg"),
            updatedAt = 1L,
        )
        @Suppress("UNCHECKED_CAST")
        val config = FirestoreCodec.deviceToMap(device)["config"] as Map<String, String>
        assertEquals(mapOf("userIndex" to "2", "unit" to "kg"), config)
    }
}
