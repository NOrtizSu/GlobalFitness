// Protocol ported from Gadgetbridge (AGPL-3.0), https://codeberg.org/Freeyourgadget/Gadgetbridge
package com.enjoyyourtime.globalfitness.wearable.banglejs

import com.enjoyyourtime.globalfitness.band.xiaomi.BandAlarm
import com.enjoyyourtime.globalfitness.band.xiaomi.BandNotification
import com.enjoyyourtime.globalfitness.wearable.LocalAlarms
import java.util.Base64
import java.util.TimeZone
import java.util.UUID

/**
 * The Bangle.js "Android Integration" protocol (Gadgetbridge BangleJSDeviceSupport): text lines over
 * the Nordic UART service. Phone → watch commands are `\x10GB(<json>)\n`, where the JSON-ish text
 * is evaluated by Espruino (so strings are escaped the Espruino way, see [encode]); watch → phone
 * messages are one JSON object per line with a "t" type.
 */
object BangleJs {
    val UART_SERVICE: UUID = UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e")

    /** Phone writes here. */
    val UART_TX: UUID = UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e")

    /** Phone subscribes here. */
    val UART_RX: UUID = UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9e")

    /** Gadgetbridge starts writing 20-byte packets and grows to the largest notification seen. */
    const val INITIAL_CHUNK = 20
    const val REQUESTED_MTU = 131
    const val MAX_ALARMS = 10

    /** Realtime HR interval sent with "act" (Gadgetbridge default, seconds). */
    const val REALTIME_INTERVAL_S = 10

    // ---- encoding ----

    /** `\x10GB(<json>)\n` for a command object (keys in insertion order, null values omitted). */
    fun command(fields: Map<String, Any?>): String = "\u0010GB(" + encode(fields) + ")\n"

    /** Bytes as written to the UART (ISO-8859-1, as Gadgetbridge's uartTx). */
    fun bytes(line: String): ByteArray = line.toByteArray(Charsets.ISO_8859_1)

    /** Splits [bytes] into UART writes of at most [chunk] bytes. */
    fun chunks(bytes: ByteArray, chunk: Int): List<ByteArray> {
        val size = chunk.coerceAtLeast(1)
        return (bytes.indices step size).map { bytes.copyOfRange(it, minOf(bytes.size, it + size)) }
    }

    /** Gadgetbridge jsonToStringInternal: JSON-ish text Espruino can evaluate. */
    fun encode(value: Any?): String = when (value) {
        null -> "null"
        is String -> encodeString(value)
        is Map<*, *> -> value.entries.filter { it.value != null }
            .joinToString(",", "{", "}") { (k, v) -> "\"" + k + "\":" + encode(v) }
        is Iterable<*> -> value.joinToString(",", "[", "]") { encode(it) }
        else -> value.toString()
    }

    private fun encodeString(s: String): String {
        val json = StringBuilder("\"")
        var hasUnicode = false
        for (i in s.indices) {
            val ch = s[i].code
            val nextCh = if (i + 1 < s.length) s[i + 1].code else 0
            if (ch > 255) hasUnicode = true
            when {
                // A following digit would turn "\0" into an octal escape.
                ch < 8 -> if (nextCh >= '0'.code && nextCh <= '7'.code) json.append("\\x0").append(ch) else json.append("\\").append(ch)
                ch == 8 -> json.append("\\b")
                ch == 9 -> json.append("\\t")
                ch == 10 -> json.append("\\n")
                ch == 11 -> json.append("\\v")
                ch == 12 -> json.append("\\f")
                ch == 16 -> json.append("\\20") // DLE
                ch == 34 -> json.append("\\\"")
                ch == 92 -> json.append("\\\\")
                ch < 32 || ch == 127 || ch == 173 || ch in 0xC2..0xF4 ->
                    json.append("\\x").append(Integer.toHexString((ch and 255) or 256).substring(1))
                ch > 255 -> json.append("\\u").append(Integer.toHexString((ch and 65535) or 65536).substring(1))
                else -> json.append(s[i])
            }
        }
        // Fewer characters as base64? Then send that.
        if (!hasUnicode && json.length > 5 + (s.length * 4 / 3)) {
            return "atob(\"" + Base64.getEncoder().encodeToString(s.toByteArray(Charsets.ISO_8859_1)) + "\")"
        }
        return json.append("\"").toString()
    }

    /** Gadgetbridge cropToLength: null → "", too long → cut with "...". */
    fun crop(text: String?, length: Int): String {
        val t = text ?: return ""
        return if (t.length > length) t.substring(0, length - 3) + "..." else t
    }

    /** Gadgetbridge renderUnicodeAsImage without the bitmap option: only the ellipsis is replaced. */
    fun render(text: String): String = text.replace("…", "...")

    // ---- commands ----

    /**
     * Raw (not GB(...)) time command: setTime, E.setTimeZone and the timezone stored in the
     * watch settings. The offset in hours is formatted as a Java float ("2.0", "-3.0", "5.5").
     */
    fun setTime(nowMillis: Long, timeZone: TimeZone): String {
        val tz = timeZone.getOffset(nowMillis) / (1000 * 60 * 60.0f)
        return "\u0010setTime(" + (nowMillis / 1000) + ");" +
            "E.setTimeZone(" + tz + ");" +
            "(s=>s&&(s.timezone=" + tz + ",require('Storage').write('setting.json',s)))(require('Storage').readJSON('setting.json',1))" +
            "\n"
    }

    fun notification(n: BandNotification): String = command(
        linkedMapOf(
            "t" to "notify",
            "id" to n.id,
            "src" to n.appName.ifEmpty { null },
            "title" to render(crop(n.title, 80)),
            "subject" to render(crop(null, 80)),
            "body" to render(crop(n.body, 400)),
            "sender" to render(crop(null, 40)),
        ),
    )

    fun dismissNotification(id: Int): String = command(linkedMapOf("t" to "notify-", "id" to id))

    fun find(start: Boolean): String = command(linkedMapOf("t" to "find", "n" to start))

    fun realtime(heartRate: Boolean, steps: Boolean, intervalS: Int = REALTIME_INTERVAL_S): String =
        command(linkedMapOf("t" to "act", "hrm" to heartRate, "stp" to steps, "int" to intervalS))

    /** The whole alarm list (Gadgetbridge sends every used slot each time). */
    fun alarms(alarms: List<BandAlarm>): String = command(
        linkedMapOf(
            "t" to "alarm",
            "d" to alarms.map { a ->
                linkedMapOf("h" to a.spec.hour, "m" to a.spec.minute, "rep" to LocalAlarms.dayMask(a.spec.repeat), "on" to a.spec.enabled)
            },
        ),
    )

    // ---- incoming ----

    /** Reassembles lines from UART notifications (ISO-8859-1, `\n` separated, `\r` dropped). */
    class LineBuffer(private val maxLength: Int = 16_384) {
        private val pending = StringBuilder()

        fun append(bytes: ByteArray): List<String> {
            // XON / XOFF flow control bytes are not data.
            for (b in bytes) {
                val c = (b.toInt() and 0xff).toChar()
                if (c == '\u0011' || c == '\u0013') continue
                pending.append(c)
            }
            val lines = ArrayList<String>()
            while (true) {
                val p = pending.indexOf("\n")
                if (p < 0) break
                lines += pending.substring(0, p).removeSuffix("\r")
                pending.delete(0, p + 1)
            }
            if (pending.length > maxLength) pending.setLength(0)
            return lines
        }
    }

    /** A message from the watch, or null when the line is not one ("{" ... with a "t"). */
    fun parseLine(line: String): Map<String, Any?>? {
        val trimmed = line.trim()
        if (!trimmed.startsWith("{") || !trimmed.contains("\"t\"")) return null
        val parsed = try {
            MiniJson.parse(trimmed)
        } catch (e: IllegalArgumentException) {
            return null
        }
        @Suppress("UNCHECKED_CAST")
        val value = parsed as? Map<String, Any?> ?: return null
        return if (value["t"] is String) value else null
    }
}

/**
 * Just enough JSON for the watch's messages (objects, arrays, strings with escapes, numbers,
 * booleans, null). Numbers become [Double]. Throws [IllegalArgumentException] on malformed input;
 * nesting is bounded.
 */
object MiniJson {
    private const val MAX_DEPTH = 32

    fun parse(text: String): Any? {
        val p = Parser(text)
        val value = p.value(0)
        p.skipWhitespace()
        require(p.done) { "trailing data" }
        return value
    }

    private class Parser(private val s: String) {
        var i = 0
        val done: Boolean get() = i >= s.length

        fun skipWhitespace() {
            while (i < s.length && s[i].isWhitespace()) i++
        }

        fun value(depth: Int): Any? {
            require(depth < MAX_DEPTH) { "too deep" }
            skipWhitespace()
            require(i < s.length) { "unexpected end" }
            return when (s[i]) {
                '{' -> obj(depth)
                '[' -> array(depth)
                '"' -> string()
                't' -> literal("true", true)
                'f' -> literal("false", false)
                'n' -> literal("null", null)
                else -> number()
            }
        }

        private fun literal(word: String, value: Any?): Any? {
            require(s.startsWith(word, i)) { "bad literal" }
            i += word.length
            return value
        }

        private fun obj(depth: Int): Map<String, Any?> {
            i++ // {
            val map = LinkedHashMap<String, Any?>()
            skipWhitespace()
            if (i < s.length && s[i] == '}') {
                i++
                return map
            }
            while (true) {
                skipWhitespace()
                require(i < s.length && s[i] == '"') { "key expected" }
                val key = string()
                skipWhitespace()
                require(i < s.length && s[i] == ':') { "colon expected" }
                i++
                map[key] = value(depth + 1)
                skipWhitespace()
                require(i < s.length) { "unexpected end" }
                when (s[i++]) {
                    ',' -> continue
                    '}' -> return map
                    else -> throw IllegalArgumentException("bad object")
                }
            }
        }

        private fun array(depth: Int): List<Any?> {
            i++ // [
            val list = ArrayList<Any?>()
            skipWhitespace()
            if (i < s.length && s[i] == ']') {
                i++
                return list
            }
            while (true) {
                list += value(depth + 1)
                skipWhitespace()
                require(i < s.length) { "unexpected end" }
                when (s[i++]) {
                    ',' -> continue
                    ']' -> return list
                    else -> throw IllegalArgumentException("bad array")
                }
            }
        }

        fun string(): String {
            i++ // opening quote
            val sb = StringBuilder()
            while (true) {
                require(i < s.length) { "unterminated string" }
                val c = s[i++]
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        require(i < s.length) { "bad escape" }
                        when (val e = s[i++]) {
                            '"', '\\', '/' -> sb.append(e)
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000c')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                require(i + 4 <= s.length) { "bad unicode escape" }
                                val code = s.substring(i, i + 4).toIntOrNull(16) ?: throw IllegalArgumentException("bad unicode escape")
                                sb.append(code.toChar())
                                i += 4
                            }
                            else -> throw IllegalArgumentException("bad escape")
                        }
                    }
                    else -> sb.append(c)
                }
            }
        }

        private fun number(): Double {
            val start = i
            while (i < s.length && (s[i].isDigit() || s[i] in "+-.eE")) i++
            require(i > start) { "unexpected character" }
            return s.substring(start, i).toDoubleOrNull() ?: throw IllegalArgumentException("bad number")
        }
    }
}
