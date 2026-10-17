// Protocol ported from Gadgetbridge (AGPL-3.0), https://codeberg.org/Freeyourgadget/Gadgetbridge
package com.enjoyyourtime.globalfitness.wearable.standard

import com.enjoyyourtime.globalfitness.scale.Bytes
import java.util.Calendar
import java.util.TimeZone
import java.util.UUID

/** Bluetooth SIG services and characteristics used by the wearable drivers. */
object StandardUuids {
    val HEART_RATE_SERVICE: UUID = Bytes.uuid16(0x180D)
    val HEART_RATE_MEASUREMENT: UUID = Bytes.uuid16(0x2A37)
    val BODY_SENSOR_LOCATION: UUID = Bytes.uuid16(0x2A38)
    val HEART_RATE_CONTROL_POINT: UUID = Bytes.uuid16(0x2A39)

    val BATTERY_SERVICE: UUID = Bytes.uuid16(0x180F)
    val BATTERY_LEVEL: UUID = Bytes.uuid16(0x2A19)

    val DEVICE_INFORMATION: UUID = Bytes.uuid16(0x180A)
    val MANUFACTURER_NAME: UUID = Bytes.uuid16(0x2A29)
    val MODEL_NUMBER: UUID = Bytes.uuid16(0x2A24)
    val SERIAL_NUMBER: UUID = Bytes.uuid16(0x2A25)
    val HARDWARE_REVISION: UUID = Bytes.uuid16(0x2A27)
    val FIRMWARE_REVISION: UUID = Bytes.uuid16(0x2A26)
    val SOFTWARE_REVISION: UUID = Bytes.uuid16(0x2A28)

    val CURRENT_TIME_SERVICE: UUID = Bytes.uuid16(0x1805)
    val CURRENT_TIME: UUID = Bytes.uuid16(0x2A2B)
    val LOCAL_TIME_INFORMATION: UUID = Bytes.uuid16(0x2A0F)

    val IMMEDIATE_ALERT_SERVICE: UUID = Bytes.uuid16(0x1802)
    val ALERT_LEVEL: UUID = Bytes.uuid16(0x2A06)

    val ALERT_NOTIFICATION_SERVICE: UUID = Bytes.uuid16(0x1811)
    val NEW_ALERT: UUID = Bytes.uuid16(0x2A46)
}

/** Body sensor location (0x2A38). */
enum class BodySensorLocation(val raw: Int) {
    OTHER(0), CHEST(1), WRIST(2), FINGER(3), HAND(4), EAR_LOBE(5), FOOT(6);

    companion object {
        fun fromRaw(raw: Int): BodySensorLocation? = entries.firstOrNull { it.raw == raw }
    }
}

/**
 * One Heart Rate Measurement (0x2A37). [sensorContact] is null when the sensor does not report
 * contact; [energyExpendedKj] null when absent; [rrIntervalsMs] in milliseconds.
 */
data class HeartRateMeasurement(
    val bpm: Int,
    val sensorContact: Boolean?,
    val energyExpendedKj: Int?,
    val rrIntervalsMs: List<Int>,
) {
    /** Gadgetbridge HeartRate.isValid: no reported loss of contact and a non-zero rate. */
    val isValid: Boolean get() = sensorContact != false && bpm > 0
}

/** Parsers and builders of standard characteristics (Gadgetbridge HeartRateProfile, BLETypeConversions). */
object StandardGatt {
    /**
     * Heart Rate Measurement, as Gadgetbridge's HeartRateProfile: flags bit 0 = 16-bit rate, bit 2 =
     * contact supported with bit 1 = contact detected, bit 3 = energy expended (kJ) present,
     * bit 4 = RR intervals (1/1024 s) follow. Null when truncated.
     */
    fun parseHeartRate(value: ByteArray): HeartRateMeasurement? {
        if (value.isEmpty()) return null
        val flags = value[0].toInt() and 0xff
        var i = 1
        val bpm: Int
        if (flags and 0x01 != 0) {
            if (value.size < i + 2) return null
            bpm = Bytes.fromUnsignedInt16Le(value, i)
            i += 2
        } else {
            if (value.size < i + 1) return null
            bpm = value[i].toInt() and 0xff
            i += 1
        }
        val contact = if (flags and 0x04 != 0) flags and 0x02 != 0 else null
        var energy: Int? = null
        if (flags and 0x08 != 0) {
            if (value.size < i + 2) return null
            energy = Bytes.fromUnsignedInt16Le(value, i)
            i += 2
        }
        val rr = ArrayList<Int>()
        if (flags and 0x10 != 0) {
            while (i + 1 < value.size) {
                rr += Bytes.fromUnsignedInt16Le(value, i) * 1000 / 1024
                i += 2
            }
        }
        return HeartRateMeasurement(bpm, contact, energy, rr)
    }

    /** Battery Level (0x2A19): uint8 percent clamped to 0..100 (Gadgetbridge ValueDecoder.decodePercent). */
    fun parseBatteryLevel(value: ByteArray): Int? = value.firstOrNull()?.let { (it.toInt() and 0xff).coerceIn(0, 100) }

    /** Device Information strings: UTF-8, trimmed, null when empty (and NUL padding removed). */
    fun parseString(value: ByteArray?): String? =
        value?.toString(Charsets.UTF_8)?.trim { it.isWhitespace() || it == '\u0000' }?.takeIf { it.isNotEmpty() }

    /** ISO day of week as BLE wants it: Monday = 1 ... Sunday = 7. */
    fun dayOfWeek(calendar: Calendar): Int = when (val d = calendar.get(Calendar.DAY_OF_WEEK)) {
        Calendar.SUNDAY -> 7
        else -> d - 1
    }

    /**
     * Current Time (0x2A2B), 10 bytes (BLETypeConversions.calendarToCurrentTime): year LE, month,
     * day, hour, minute, second, day of week, fractions of 1/256 s, adjust reason.
     */
    fun currentTime(nowMillis: Long, timeZone: TimeZone, adjustReason: Int = 0): ByteArray {
        val c = Calendar.getInstance(timeZone).apply { timeInMillis = nowMillis }
        val year = c.get(Calendar.YEAR)
        return byteArrayOf(
            (year and 0xff).toByte(),
            ((year shr 8) and 0xff).toByte(),
            (c.get(Calendar.MONTH) + 1).toByte(),
            c.get(Calendar.DATE).toByte(),
            c.get(Calendar.HOUR_OF_DAY).toByte(),
            c.get(Calendar.MINUTE).toByte(),
            c.get(Calendar.SECOND).toByte(),
            dayOfWeek(c).toByte(),
            (c.get(Calendar.MILLISECOND) / 1000.0 * 256).toInt().toByte(),
            adjustReason.toByte(),
        )
    }

    /**
     * Local Time Information (0x2A0F), 2 bytes (BLETypeConversions.calendarToLocalTime): standard
     * offset and DST offset, both in quarter hours.
     */
    fun localTimeInformation(nowMillis: Long, timeZone: TimeZone): ByteArray {
        val raw = timeZone.rawOffset
        val dst = timeZone.getOffset(nowMillis) - raw
        return byteArrayOf((raw / QUARTER_HOUR_MS).toByte(), (dst / QUARTER_HOUR_MS).toByte())
    }

    const val QUARTER_HOUR_MS = 15 * 60 * 1000
}
