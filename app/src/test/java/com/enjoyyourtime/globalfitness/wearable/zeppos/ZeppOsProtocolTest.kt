package com.enjoyyourtime.globalfitness.wearable.zeppos

import com.enjoyyourtime.globalfitness.band.xiaomi.AlarmRepeat
import com.enjoyyourtime.globalfitness.band.xiaomi.AlarmSpec
import com.enjoyyourtime.globalfitness.band.xiaomi.BandAlarm
import com.enjoyyourtime.globalfitness.wearable.WearableDeviceInfo
import com.enjoyyourtime.globalfitness.wearable.hex
import java.util.Calendar
import java.util.TimeZone
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Zepp OS service payloads, byte for byte as Gadgetbridge's ZeppOs*Service classes build them. */
class ZeppOsProtocolTest {
    private val fipsKey = hex("000102030405060708090a0b0c0d0e0f")
    private val fipsPlain = hex("00112233445566778899aabbccddeeff")

    @Test
    fun authentication() {
        val pub = ByteArray(48) { it.toByte() }
        assertEquals("04020002" + pub.hex(), ZeppOsProtocol.authPublicKey(pub).hex())
        val challenge = ZeppOsProtocol.parseAuthChallenge(hex("100401") + fipsPlain + pub)!!
        assertArrayEquals(fipsPlain, challenge.random)
        assertArrayEquals(pub, challenge.publicKey)
        assertNull(ZeppOsProtocol.parseAuthChallenge(hex("100402") + fipsPlain + pub))
        assertNull(ZeppOsProtocol.parseAuthChallenge(hex("100401") + fipsPlain))

        val shared = ByteArray(48) { (0x80 + it).toByte() }
        val session = ZeppOsProtocol.deriveSession(shared, fipsKey)
        assertEquals(0x83828180.toInt(), session.sequence)
        assertEquals(ByteArray(16) { ((0x88 + it) xor it).toByte() }.hex(), session.key.hex())

        // 05 | AES-ECB(authKey, random) | AES-ECB(sessionKey, random)
        val answer = ZeppOsProtocol.authAnswer(fipsPlain, fipsKey, fipsKey)
        assertEquals("05" + "69c4e0d86a7b0430d8cdb78070b4c55a".repeat(2), answer.hex())
        assertEquals(ZeppOsProtocol.AuthResult.SUCCESS, ZeppOsProtocol.parseAuthResult(hex("100501")))
        assertEquals(ZeppOsProtocol.AuthResult.WRONG_KEY, ZeppOsProtocol.parseAuthResult(hex("100525")))
        assertEquals(ZeppOsProtocol.AuthResult.FAILED, ZeppOsProtocol.parseAuthResult(hex("100502")))
        assertNull(ZeppOsProtocol.parseAuthResult(hex("100401")))
    }

    @Test
    fun servicesBatteryAndDeviceInfo() {
        assertEquals(mapOf(0x29 to true, 0x47 to false), ZeppOsProtocol.parseServices(hex("04 0200 2900 01 4700 00")))
        assertNull(ZeppOsProtocol.parseServices(hex("04 0300 2900 01")))
        assertEquals(85 to true, ZeppOsProtocol.parseBattery(hex("04 0f 55 01") + ByteArray(17)))
        assertEquals(85 to null, ZeppOsProtocol.parseBattery(hex("04 0f 55")))
        assertNull(ZeppOsProtocol.parseBattery(hex("05 0f 55")))
        val info = hex("02 01 0e00000000000000") + "SN1\u0000HW2\u00003.1.4\u0000".toByteArray()
        assertEquals(WearableDeviceInfo(serial = "SN1", hardware = "HW2", firmware = "3.1.4"), ZeppOsProtocol.parseDeviceInfo(info))
        val withSkip = hex("02 01 0900000000000000 02 aabb") + "9.9\u0000".toByteArray()
        assertEquals(WearableDeviceInfo(firmware = "9.9"), ZeppOsProtocol.parseDeviceInfo(withSkip))
        assertNull(ZeppOsProtocol.parseDeviceInfo(hex("0201")))
    }

    @Test
    fun time() {
        val tz = TimeZone.getTimeZone("Europe/Madrid")
        val t = Calendar.getInstance(tz).apply { clear(); set(2026, Calendar.MARCH, 29, 10, 20, 30); set(Calendar.MILLISECOND, 500) }.timeInMillis
        // Sunday = 0, fractions 0x80, DST flag 0x08, +2 h = 8 quarter hours.
        assertEquals("ea07031d0a141e00800808", ZeppOsProtocol.currentTimeBytes(t, tz).hex())
        assertEquals("05ea07031d0a141e00800808", ZeppOsProtocol.setTime(t, tz).hex())
        val winter = Calendar.getInstance(tz).apply { clear(); set(2026, Calendar.JANUARY, 5, 8, 0, 0) }.timeInMillis
        assertEquals("ea0701050800000100" + "0004", ZeppOsProtocol.currentTimeBytes(winter, tz).hex())
    }

    @Test
    fun notifications() {
        val v5 = ZeppOsProtocol.parseNotificationCapabilities(hex("02 05 6400 01 01 0500 0001020304 01 01 05"))!!
        assertEquals(5, v5.version)
        assertEquals(true, v5.supportsPictures)
        assertEquals(true, v5.supportsKey)
        val active2 = ZeppOsProtocol.parseNotificationCapabilities(hex("02 05 6400 01 00 01 01 01"))!!
        assertEquals(true, active2.supportsPictures)
        val v4 = ZeppOsProtocol.parseNotificationCapabilities(hex("02 04 6400 01 00"))!!
        assertEquals(4, v4.version)
        assertEquals(false, v4.supportsPictures)

        val expected = "03" + "2a000000" + "fa" + "00" +
            "com.whatsapp".toByteArray().hex() + "00" + "Ana".toByteArray().hex() + "00" + "Hola".toByteArray().hex() + "00" +
            "WhatsApp".toByteArray().hex() + "00" + "00"
        assertEquals(expected, ZeppOsProtocol.notification(42, "com.whatsapp", "WhatsApp", "Ana", "Hola", false, v4).hex())
        // v5 adds "not silent", then the picture flag and an empty key when supported.
        assertEquals(expected + "00" + "00" + "00", ZeppOsProtocol.notification(42, "com.whatsapp", "WhatsApp", "Ana", "Hola", false, v5).hex())
        assertEquals("05", ZeppOsProtocol.notification(1, "p", null, null, "b", true, v4).hex().substring(10, 12))
        assertEquals("032a000000fa020000000000", ZeppOsProtocol.deleteNotification(42).hex())
    }

    @Test
    fun alarms() {
        assertEquals("030105020715" + "1f" + "0000000000", ZeppOsProtocol.saveAlarm(2, AlarmSpec(7, 21, smart = true, repeat = AlarmRepeat.Weekly(31))).hex())
        assertEquals("030100000600" + "00" + "0000000000", ZeppOsProtocol.saveAlarm(0, AlarmSpec(6, 0, enabled = false)).hex())
        assertEquals("050103", ZeppOsProtocol.deleteAlarm(3).hex())
        val list = ZeppOsProtocol.parseAlarms(hex("0a02" + "0401071e7f0000000001" + "0100061e000000000000"))!!
        assertEquals(
            listOf(
                BandAlarm(1, AlarmSpec(6, 30, enabled = false, smart = true, repeat = AlarmRepeat.Once)),
                BandAlarm(2, AlarmSpec(7, 30, enabled = true, smart = false, repeat = AlarmRepeat.Daily)),
            ),
            list,
        )
        assertNull(ZeppOsProtocol.parseAlarms(hex("0a02" + "0401071e7f")))
    }

    @Test
    fun liveDataAndFind() {
        assertEquals(1000, ZeppOsProtocol.parseRealtimeSteps(hex("07 00 e803") + ByteArray(11)))
        assertNull(ZeppOsProtocol.parseRealtimeSteps(hex("06 00 01")))
        assertEquals("0401", ZeppOsProtocol.HR_START.hex())
        assertEquals("0402", ZeppOsProtocol.HR_CONTINUE.hex())
        assertEquals("0400", ZeppOsProtocol.HR_STOP.hex())
        assertEquals("0501", ZeppOsProtocol.STEPS_ENABLE.hex())
        assertEquals("03", ZeppOsProtocol.FIND_START.hex())
        assertEquals("06", ZeppOsProtocol.FIND_STOP.hex())
        assertEquals("1201", ZeppOsProtocol.FIND_PHONE_ACK.hex())
    }
}
