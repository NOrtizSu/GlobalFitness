package com.enjoyyourtime.globalfitness.wearable.huami

import com.enjoyyourtime.globalfitness.band.xiaomi.AlarmRepeat
import com.enjoyyourtime.globalfitness.band.xiaomi.AlarmSpec
import com.enjoyyourtime.globalfitness.wearable.hex
import java.util.Calendar
import java.util.TimeZone
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Classic Huami bytes as Gadgetbridge's InitOperation / HuamiSupport write them. */
class HuamiProtocolTest {
    /** FIPS-197 appendix C.1. */
    private val fipsKey = hex("000102030405060708090a0b0c0d0e0f")
    private val fipsPlain = hex("00112233445566778899aabbccddeeff")
    private val fipsCipher = "69c4e0d86a7b0430d8cdb78070b4c55a"

    @Test
    fun authenticationCommands() {
        assertEquals("0208", HuamiProtocol.requestRandom(0x08, 0x00).hex())
        assertEquals("0200", HuamiProtocol.requestRandom(0x00, 0x00).hex())
        assertEquals("8200020100", HuamiProtocol.requestRandom(0x00, 0x80).hex())
        assertEquals("8300$fipsCipher", HuamiProtocol.encryptedRandom(fipsPlain, fipsKey, 0x00, 0x80).hex())
        assertEquals("0308$fipsCipher", HuamiProtocol.encryptedRandom(fipsPlain, fipsKey, 0x08, 0x00).hex())
    }

    @Test
    fun authenticationReplies() {
        val random = HuamiProtocol.parseAuth(hex("108201") + fipsPlain) as HuamiProtocol.AuthReply.Random
        assertArrayEquals(fipsPlain, random.random)
        assertTrue(HuamiProtocol.parseAuth(hex("100201") + fipsPlain) is HuamiProtocol.AuthReply.Random)
        assertEquals(HuamiProtocol.AuthReply.Success, HuamiProtocol.parseAuth(hex("100301")))
        assertEquals(HuamiProtocol.AuthReply.Success, HuamiProtocol.parseAuth(hex("108301")))
        assertEquals(HuamiProtocol.AuthReply.WrongKey, HuamiProtocol.parseAuth(hex("108304")))
        assertEquals(HuamiProtocol.AuthReply.Failed(0x82, 0x04), HuamiProtocol.parseAuth(hex("108204")))
        assertNull(HuamiProtocol.parseAuth(hex("0102")))
        assertNull(HuamiProtocol.parseAuth(hex("1003")))
    }

    @Test
    fun batteryAndSteps() {
        // Gadgetbridge's HuamiBatteryInfo sample: level 0x30, status 0 (not charging).
        assertEquals(HuamiProtocol.Battery(48, false), HuamiProtocol.parseBattery(hex("0f3000e0070b1a12232c04e0070b1a172b3b0464")))
        assertEquals(HuamiProtocol.Battery(85, true), HuamiProtocol.parseBattery(hex("0f5501")))
        assertEquals(HuamiProtocol.Battery(85, null), HuamiProtocol.parseBattery(hex("0f55")))
        assertNull(HuamiProtocol.parseBattery(hex("0f")))
        assertEquals(1000, HuamiProtocol.parseRealtimeSteps(hex("0ce80300000000000000000000")))
        assertNull(HuamiProtocol.parseRealtimeSteps(hex("0ce803")))
    }

    @Test
    fun currentTime() {
        val tz = TimeZone.getTimeZone("Europe/Madrid")
        val t = Calendar.getInstance(tz).apply { clear(); set(2026, Calendar.MARCH, 29, 10, 20, 30) }.timeInMillis
        // ... day of week (Sunday = 7), fractions 0, 0, offset with DST in quarter hours (+2 h = 8).
        assertEquals("ea07031d0a141e07000008", HuamiProtocol.currentTime(t, tz).hex())
        // Negative offsets are signed quarter hours (-3 h = -12 = 0xf4); Monday = 1.
        val gmt3 = TimeZone.getTimeZone("GMT-03:00")
        val monday = Calendar.getInstance(gmt3).apply { clear(); set(2026, Calendar.MARCH, 30, 23, 59, 1) }.timeInMillis
        assertEquals("ea07031e173b01010000f4", HuamiProtocol.currentTime(monday, gmt3).hex())
    }

    @Test
    fun alarms() {
        assertEquals("02c2071e1f", HuamiProtocol.alarm(2, AlarmSpec(7, 30, repeat = AlarmRepeat.Weekly(31))).hex())
        assertEquals("02c0061e7f", HuamiProtocol.alarm(0, AlarmSpec(6, 30, repeat = AlarmRepeat.Daily)).hex())
        assertEquals("0202071e80", HuamiProtocol.alarm(2, AlarmSpec(7, 30, enabled = false)).hex())
        assertEquals("0281071e80", HuamiProtocol.alarm(1, AlarmSpec(7, 30), snooze = true).hex())
        assertEquals("0203000000", HuamiProtocol.alarm(3, null).hex())
    }

    @Test
    fun findAndCalls() {
        assertEquals("03", HuamiProtocol.FIND_START.hex())
        assertEquals("00", HuamiProtocol.FIND_STOP.hex())
        assertEquals("0301" + "Gadgetbridge".toByteArray().hex(), HuamiProtocol.callAlert("Gadgetbridge").hex())
        assertEquals("0300", HuamiProtocol.CALL_ALERT_STOP.hex())
        assertEquals("150200", HuamiProtocol.HR_STOP_MANUAL.hex())
        assertEquals("150101", HuamiProtocol.HR_START_CONTINUOUS.hex())
        assertEquals("150100", HuamiProtocol.HR_STOP_CONTINUOUS.hex())
        assertEquals("06140000", HuamiProtocol.FIND_PHONE_ACK.hex())
    }

    @Test
    fun notificationBody() {
        assertEquals(" \u0000 ", HuamiProtocol.notificationBody("", ""))
        assertEquals("Ana\u0000Hola\n\n", HuamiProtocol.notificationBody("Ana", "Hola"))
        assertEquals("T".repeat(32) + "\u0000b\n\n", HuamiProtocol.notificationBody("T".repeat(40), "b"))
    }

    @Test
    fun chunkedNotificationPayloads() {
        val body = "Ana\u0000Hola\n\n".toByteArray()
        assertEquals(
            "fa0000000001" + "07" + body.hex() + "00" + "WhatsApp".toByteArray().hex() + "00",
            HuamiProtocol.notificationPayload("com.whatsapp", "WhatsApp", "Ana", "Hola", extraHeader = true, maxLength = 230).hex(),
        )
        assertEquals(
            "fa01" + "0b" + body.hex() + "00" + "UNKNOWN".toByteArray().hex() + "00",
            HuamiProtocol.notificationPayload("org.example", null, "Ana", "Hola", extraHeader = false, maxLength = 230).hex(),
        )
        // SMS: own category, no icon, fake " " app name.
        assertEquals("0501" + body.hex() + "002000", HuamiProtocol.notificationPayload("com.android.mms", "Mensajes", "Ana", "Hola", false, 230).hex())
        // E-mail category for mail apps.
        assertTrue(HuamiProtocol.notificationPayload("com.google.android.gm", "Gmail", "A", "B", false, 230).hex().startsWith("0101"))
        // Cut to maxLength - prefix bytes (12 - 3 = 9).
        assertEquals("fa010b" + "Ana\u0000Hola\n".toByteArray().hex() + "00" + "App".toByteArray().hex() + "00", HuamiProtocol.notificationPayload("x", "App", "Ana", "Hola", false, 12).hex())
        // Never in the middle of a UTF-8 sequence: "ñ" is 2 bytes, 9 bytes would split the third one.
        val cut = HuamiProtocol.notificationPayload("x", "App", "Ana", "ñññ", false, 12)
        assertEquals("fa010b" + "Ana\u0000ññ".toByteArray().hex() + "00" + "App".toByteArray().hex() + "00", cut.hex())
    }

    @Test
    fun chunkedTransferFrames() {
        val data = ByteArray(40) { it.toByte() }
        val frames = HuamiProtocol.chunkedFrames(0, data, mtu = 23).map { it.hex() }
        assertEquals(
            listOf(
                "000000" + data.copyOfRange(0, 17).hex(),
                "004001" + data.copyOfRange(17, 34).hex(),
                "008002" + data.copyOfRange(34, 40).hex(),
            ),
            frames,
        )
        assertEquals(listOf("00c200aabb"), HuamiProtocol.chunkedFrames(2, hex("aabb"), 247).map { it.hex() })
    }

    @Test
    fun newAlertAndMiBand2() {
        assertEquals("fa01076869", HuamiProtocol.newAlert(HuamiProtocol.CATEGORY_CUSTOM_HUAMI, 7, "hi", 230).hex())
        assertEquals("05016869", HuamiProtocol.newAlert(HuamiProtocol.CATEGORY_SMS, 7, "hi", 230).hex())
        assertEquals("fa010768", HuamiProtocol.newAlert(HuamiProtocol.CATEGORY_CUSTOM_HUAMI, 7, "hi", 1).hex())
        assertEquals("ffc800c80003", HuamiProtocol.mi2Vibration().hex())
        assertEquals(listOf("0501" + "x".repeat(18).toByteArray().hex(), "0501" + "xx".toByteArray().hex()), HuamiProtocol.mi2TextAlerts("x".repeat(20)).map { it.hex() })
        assertEquals(HuamiProtocol.Icons.WHATSAPP, HuamiProtocol.iconFor("com.whatsapp"))
        assertEquals(HuamiProtocol.Icons.TELEGRAM, HuamiProtocol.iconFor("org.telegram.messenger"))
        assertEquals(HuamiProtocol.Icons.APP, HuamiProtocol.iconFor("org.example"))
    }

    @Test
    fun uuids() {
        assertEquals("00000009-0000-3512-2118-0009af100700", HuamiUuids.AUTH.toString())
        assertEquals("00000020-0000-3512-2118-0009af100700", HuamiUuids.CHUNKED_TRANSFER.toString())
        assertEquals("0000fee1-0000-1000-8000-00805f9b34fb", HuamiUuids.SERVICE_MIBAND2.toString())
    }
}
