package com.enjoyyourtime.globalfitness.wearable.banglejs

import com.enjoyyourtime.globalfitness.band.xiaomi.AlarmRepeat
import com.enjoyyourtime.globalfitness.band.xiaomi.AlarmSpec
import com.enjoyyourtime.globalfitness.band.xiaomi.BandAlarm
import com.enjoyyourtime.globalfitness.band.xiaomi.BandNotification
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Bangle.js command text, as Gadgetbridge's BangleJSDeviceSupport encodes it, and message parsing. */
class BangleJsTest {
    private val bs = "\\"

    @Test
    fun stringEscaping() {
        assertEquals("\"plain text\"", BangleJs.encode("plain text"))
        assertEquals("\"a${bs}\"b$bs${bs}c\"", BangleJs.encode("a\"b\\c"))
        assertEquals("\"l1${bs}nl2${bs}tx${bs}b${bs}v${bs}f\"", BangleJs.encode("l1\nl2\tx\b\u000B\u000C"))
        // \0 followed by a digit would be octal: \x00 instead.
        assertEquals("\"${bs}0a${bs}x005\"", BangleJs.encode("\u0000a\u00005"))
        assertEquals("\"${bs}20\"", BangleJs.encode("\u0010"))
        assertEquals("\"line${bs}x0dend${bs}x7f and soft${bs}xadhyphen\"", BangleJs.encode("line\rend\u007F and soft­hyphen"))
        // 0xC2..0xF4 look like UTF-8 lead bytes on the watch: escaped.
        assertEquals("\"Lleg${bs}xf3 el caf${bs}xe9 de Mar${bs}xeda\"", BangleJs.encode("Llegó el café de María"))
        // ...unless base64 is shorter: E9 74 E9.
        assertEquals("atob(\"6XTp\")", BangleJs.encode("été"))
        assertEquals("\"${bs}u20ac\"", BangleJs.encode("€"))
    }

    @Test
    fun base64WhenShorter() {
        val s = "\u0001\u0002\u0003\u0004\u0005\u0006\u0007\u0001\u0002\u0003\u0004\u0005"
        // 12 control characters escape to 12 x 2 chars + quote = 25 > 5 + 16: base64 is used.
        assertEquals("atob(\"AQIDBAUGBwECAwQF\")", BangleJs.encode(s))
        // Unicode never goes base64.
        assertTrue(BangleJs.encode("€\u0001\u0002\u0003\u0004\u0005\u0006\u0007").startsWith("\""))
    }

    @Test
    fun objectsArraysAndNumbers() {
        val text = BangleJs.encode(linkedMapOf("t" to "x", "n" to true, "i" to 10, "skip" to null, "d" to listOf(1, "a")))
        assertEquals("{\"t\":\"x\",\"n\":true,\"i\":10,\"d\":[1,\"a\"]}", text)
    }

    @Test
    fun commandFraming() {
        assertEquals("\u0010GB({\"t\":\"find\",\"n\":true})\n", BangleJs.find(true))
        assertEquals("\u0010GB({\"t\":\"notify-\",\"id\":42})\n", BangleJs.dismissNotification(42))
        assertEquals("\u0010GB({\"t\":\"act\",\"hrm\":true,\"stp\":true,\"int\":10})\n", BangleJs.realtime(heartRate = true, steps = true))
    }

    @Test
    fun notificationLikeGadgetbridge() {
        val n = BandNotification(7, "com.whatsapp", "WhatsApp", "Ana", "Hola…", 0)
        assertEquals(
            "\u0010GB({\"t\":\"notify\",\"id\":7,\"src\":\"WhatsApp\",\"title\":\"Ana\",\"subject\":\"\",\"body\":\"Hola...\",\"sender\":\"\"})\n",
            BangleJs.notification(n),
        )
        val long = BangleJs.notification(n.copy(title = "t".repeat(100), appName = ""))
        assertTrue(long.contains("\"title\":\"" + "t".repeat(77) + "...\""))
        assertTrue(!long.contains("\"src\""))
    }

    @Test
    fun alarms() {
        val list = listOf(
            BandAlarm(1, AlarmSpec(7, 30, repeat = AlarmRepeat.Weekly(31))),
            BandAlarm(2, AlarmSpec(9, 5, enabled = false)),
        )
        assertEquals(
            "\u0010GB({\"t\":\"alarm\",\"d\":[{\"h\":7,\"m\":30,\"rep\":31,\"on\":true},{\"h\":9,\"m\":5,\"rep\":0,\"on\":false}]})\n",
            BangleJs.alarms(list),
        )
    }

    @Test
    fun setTime() {
        assertEquals(
            "\u0010setTime(1800000000);E.setTimeZone(2.0);" +
                "(s=>s&&(s.timezone=2.0,require('Storage').write('setting.json',s)))(require('Storage').readJSON('setting.json',1))\n",
            BangleJs.setTime(1_800_000_000_123L, TimeZone.getTimeZone("GMT+02:00")),
        )
        assertTrue(BangleJs.setTime(0, TimeZone.getTimeZone("GMT+05:30")).contains("E.setTimeZone(5.5);"))
        assertTrue(BangleJs.setTime(0, TimeZone.getTimeZone("GMT-03:00")).contains("E.setTimeZone(-3.0);"))
    }

    @Test
    fun chunksAndBytes() {
        val bytes = BangleJs.bytes("é€")
        assertEquals(listOf(0xE9, 0x3F), bytes.map { it.toInt() and 0xff }) // ISO-8859-1 as Gadgetbridge
        assertEquals(listOf(20, 20, 5), BangleJs.chunks(ByteArray(45), 20).map { it.size })
    }

    @Test
    fun lineBufferAndMessages() {
        val lines = BangleJs.LineBuffer()
        assertTrue(lines.append("{\"t\":\"status\",\"bat\":8".toByteArray()).isEmpty())
        val got = lines.append("5,\"chg\":1}\r\n\u0011{\"t\":\"ver\",\"fw\":\"2v20\"}\n".toByteArray(Charsets.ISO_8859_1))
        assertEquals(listOf("{\"t\":\"status\",\"bat\":85,\"chg\":1}", "{\"t\":\"ver\",\"fw\":\"2v20\"}"), got)
        val status = BangleJs.parseLine(got[0])!!
        assertEquals("status", status["t"])
        assertEquals(85.0, status["bat"])
        assertNull(BangleJs.parseLine(">Uncaught ReferenceError: \"GB\" is not defined"))
        assertNull(BangleJs.parseLine("{\"x\":1}"))
        assertNull(BangleJs.parseLine("{\"t\":"))
        assertNull(BangleJs.parseLine("{\"t\":5}"))
    }

    @Test
    fun miniJson() {
        val v = MiniJson.parse(" {\"a\":[1,-2.5e1,true,false,null],\"b\":\"x${bs}u00e9${bs}n$bs\"\",\"c\":{}} ")
        assertEquals(mapOf("a" to listOf(1.0, -25.0, true, false, null), "b" to "xé\n\"", "c" to emptyMap<String, Any?>()), v)
        for (bad in listOf("", "{", "[1,]", "{\"a\" 1}", "\"open", "tru", "{}x", "[".repeat(100) + "]".repeat(100))) {
            try {
                MiniJson.parse(bad)
                fail("accepted $bad")
            } catch (e: IllegalArgumentException) {
                // expected
            }
        }
    }
}
