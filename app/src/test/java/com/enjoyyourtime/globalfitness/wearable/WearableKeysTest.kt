package com.enjoyyourtime.globalfitness.wearable

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WearableKeysTest {
    private val key = "00112233445566778899aabbccddeeff"

    @Test
    fun hexKeysAcceptPrefixCaseAndWhitespace() {
        for (input in listOf(key, "0x$key", "0X$key", key.uppercase(), " $key\n", "0011 2233 4455 6677 8899 aabb ccdd eeff")) {
            assertEquals(input, KeyCheck.Valid(key), WearableKeys.validate(AuthKeyKind.HEX_32, input))
        }
    }

    @Test
    fun hexKeyProblems() {
        assertEquals(KeyCheck.Invalid(KeyProblem.EMPTY), WearableKeys.validate(AuthKeyKind.HEX_32, "  "))
        assertEquals(KeyCheck.Invalid(KeyProblem.EMPTY), WearableKeys.validate(AuthKeyKind.HEX_32, "0x"))
        assertEquals(KeyCheck.Invalid(KeyProblem.WRONG_LENGTH, 30), WearableKeys.validate(AuthKeyKind.HEX_32, key.dropLast(2)))
        assertEquals(KeyCheck.Invalid(KeyProblem.WRONG_LENGTH, 34), WearableKeys.validate(AuthKeyKind.HEX_32, key + "00"))
        assertEquals(KeyCheck.Invalid(KeyProblem.NOT_HEX, 32), WearableKeys.validate(AuthKeyKind.HEX_32, key.dropLast(1) + "g"))
    }

    @Test
    fun numericUserIds() {
        assertEquals(KeyCheck.Valid("1234567890"), WearableKeys.validate(AuthKeyKind.NUMERIC_USER_ID, " 1234567890\n"))
        assertEquals(KeyCheck.Invalid(KeyProblem.NOT_NUMERIC, 4), WearableKeys.validate(AuthKeyKind.NUMERIC_USER_ID, "12a4"))
        assertEquals(KeyCheck.Invalid(KeyProblem.TOO_LONG, 21), WearableKeys.validate(AuthKeyKind.NUMERIC_USER_ID, "1".repeat(21)))
        assertEquals(KeyCheck.Invalid(KeyProblem.EMPTY), WearableKeys.validate(AuthKeyKind.NUMERIC_USER_ID, ""))
    }

    @Test
    fun clipboardExtraction() {
        assertEquals(key, WearableKeys.extractFromClipboard(AuthKeyKind.HEX_32, "authKey: 0x$key (Mi Band)"))
        assertEquals(key, WearableKeys.extractFromClipboard(AuthKeyKind.HEX_32, "  0x${key.uppercase()}  "))
        assertEquals(key, WearableKeys.extractFromClipboard(AuthKeyKind.HEX_32, "Device: AA:BB\nkey=$key\n"))
        // 34 hex characters are not a key (a token must be exactly 32).
        assertNull(WearableKeys.extractFromClipboard(AuthKeyKind.HEX_32, key + "00"))
        assertNull(WearableKeys.extractFromClipboard(AuthKeyKind.HEX_32, "no key here"))
        assertNull(WearableKeys.extractFromClipboard(AuthKeyKind.HEX_32, null))
        assertEquals("1234567890", WearableKeys.extractFromClipboard(AuthKeyKind.NUMERIC_USER_ID, "user id 1234567890"))
    }

    @Test
    fun hexToBytesAndHiddenToString() {
        assertArrayEquals(hex(key), WearableKeys.hexToBytes(key))
        assertFalse(KeyCheck.Valid(key).toString().contains(key))
        assertTrue(WearableKeyStore.fileName("aa:bb:cc:dd:ee:ff") == "key_AABBCCDDEEFF")
        assertNull(WearableKeyStore.fileName("../../etc/passwd"))
        assertNull(WearableKeyStore.fileName("AA:BB:CC:DD:EE"))
    }
}
