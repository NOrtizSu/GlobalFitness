package com.enjoyyourtime.globalfitness.wearable.pinetime

import com.enjoyyourtime.globalfitness.wearable.hex
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** InfiniTime message bytes, as Gadgetbridge's PineTimeJFSupport writes them. */
class InfiniTimeTest {
    private fun alert(text: String): ByteArray = hex("FA 01 FF") + text.toByteArray(Charsets.UTF_8)

    @Test
    fun notificationWithSourceTitleAndBody() {
        assertArrayEquals(alert("WhatsApp: Ana\u0000Hola"), InfiniTime.notification("WhatsApp", "Ana", "Hola"))
    }

    @Test
    fun notificationWithoutSourceOrTitle() {
        assertArrayEquals(alert("Ana\u0000Hola"), InfiniTime.notification(null, "Ana", "Hola"))
        assertArrayEquals(alert("WhatsApp\u0000Hola"), InfiniTime.notification("WhatsApp", "", "Hola"))
        // No body: source NUL title.
        assertArrayEquals(alert("WhatsApp\u0000Ana"), InfiniTime.notification("WhatsApp", "Ana", ""))
        assertArrayEquals(alert("\u0000Ana"), InfiniTime.notification(null, "Ana", null))
    }

    @Test
    fun oldFirmwareSendsOnlyOneText() {
        assertArrayEquals(alert("Hola"), InfiniTime.notification("WhatsApp", "Ana", "Hola", firmware015 = false))
        assertArrayEquals(alert("Ana"), InfiniTime.notification("WhatsApp", "Ana", "", firmware015 = false))
    }

    @Test
    fun longTitleIsCutWithMarkerAndTotalTruncatedTo100() {
        val body = "b".repeat(60)
        val title = "Title with trailing spaces at the cut point    and more words after it"
        // cut = max(25, 100 - 3 - 60 - (3 + 2)) = 32; trailing spaces before the cut are dropped.
        val expectedTitle = title.substring(0, 32).trimEnd() + ">"
        assertArrayEquals(alert("App: $expectedTitle\u0000$body"), InfiniTime.notification("App", title, body))
        val huge = InfiniTime.notification("App", "T", "x".repeat(500))
        assertEquals(3 + 100, huge.size)
    }

    @Test
    fun callAndFindDevice() {
        assertArrayEquals(hex("03 01 31") + "GlobalFitness".toByteArray(), InfiniTime.call("GlobalFitness"))
        assertArrayEquals(hex("03 01 31") + "Phone\u0000Ana".toByteArray(), InfiniTime.call("Ana", source = "Phone"))
        assertArrayEquals(hex("03 01 31") + "Ana".toByteArray(), InfiniTime.call("Ana", source = "Phone", firmware015 = false))
    }

    @Test
    fun firmwareVersions() {
        assertTrue(InfiniTime.isAtLeast015("1.14.0"))
        assertTrue(InfiniTime.isAtLeast015("0.15.0"))
        assertFalse(InfiniTime.isAtLeast015("0.14.1"))
        assertTrue(InfiniTime.isAtLeast015(null))
        assertTrue(InfiniTime.isAtLeast015("1.14"))
    }

    @Test
    fun stepCount() {
        assertEquals(12345, InfiniTime.parseSteps(hex("39 30 00 00")))
        assertNull(InfiniTime.parseSteps(hex("39 30")))
    }
}
