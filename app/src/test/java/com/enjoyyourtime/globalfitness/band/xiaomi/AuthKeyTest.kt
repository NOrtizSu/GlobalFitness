package com.enjoyyourtime.globalfitness.band.xiaomi

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthKeyTest {
    private val hex = "00112233445566778899aabbccddeeff"
    private val bytes = byteArrayOf(
        0x00, 0x11, 0x22, 0x33, 0x44, 0x55, 0x66, 0x77,
        0x88.toByte(), 0x99.toByte(), 0xaa.toByte(), 0xbb.toByte(),
        0xcc.toByte(), 0xdd.toByte(), 0xee.toByte(), 0xff.toByte(),
    )

    private fun valid(input: String): AuthKey {
        val result = AuthKey.parse(input)
        assertTrue("expected valid: $input -> $result", result is AuthKeyResult.Valid)
        return (result as AuthKeyResult.Valid).key
    }

    private fun invalid(input: String): AuthKeyResult.Invalid {
        val result = AuthKey.parse(input)
        assertTrue("expected invalid: $input", result is AuthKeyResult.Invalid)
        return result as AuthKeyResult.Invalid
    }

    @Test
    fun parsesPlainHex() {
        assertArrayEquals(bytes, valid(hex).bytes())
    }

    @Test
    fun acceptsOptionalPrefixAndUpperCase() {
        assertArrayEquals(bytes, valid("0x$hex").bytes())
        assertArrayEquals(bytes, valid("0X${hex.uppercase()}").bytes())
    }

    @Test
    fun ignoresWhitespaceFromCopyAndPaste() {
        assertArrayEquals(bytes, valid("  0x0011 2233 4455 6677\n8899aabbccddeeff \n").bytes())
    }

    @Test
    fun rejectsWrongLength() {
        assertEquals(AuthKeyResult.Invalid(AuthKeyError.WRONG_LENGTH, 30), invalid(hex.dropLast(2)))
        assertEquals(AuthKeyResult.Invalid(AuthKeyError.WRONG_LENGTH, 34), invalid("0x${hex}00"))
    }

    @Test
    fun rejectsNonHexCharacters() {
        assertEquals(AuthKeyError.NOT_HEX, invalid(hex.replaceFirst('a', 'g')).error)
    }

    @Test
    fun rejectsEmptyInput() {
        assertEquals(AuthKeyError.EMPTY, invalid("   ").error)
        assertEquals(AuthKeyError.EMPTY, invalid("0x").error)
    }

    @Test
    fun roundTripsThroughHexWithoutRevealingTheKeyInToString() {
        val key = valid("0x$hex")
        assertEquals(hex, key.toHex())
        assertEquals(key, valid(key.toHex()))
        assertFalse(key.toString().contains(hex))
        assertFalse(key.toString().contains("0011"))
    }

    @Test
    fun bytesAreACopy() {
        val key = valid(hex)
        key.bytes()[0] = 0x7f
        assertArrayEquals(bytes, key.bytes())
    }
}
