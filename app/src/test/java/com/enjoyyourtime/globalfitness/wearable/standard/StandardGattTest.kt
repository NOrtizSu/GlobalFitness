package com.enjoyyourtime.globalfitness.wearable.standard

import com.enjoyyourtime.globalfitness.wearable.hex
import java.util.Calendar
import java.util.TimeZone
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Heart Rate Measurement (0x2A37) parsing and the standard time / battery formats. */
class StandardGattTest {
    @Test
    fun uint8HeartRateWithoutExtras() {
        val m = StandardGatt.parseHeartRate(hex("00 48"))!!
        assertEquals(72, m.bpm)
        assertNull(m.sensorContact)
        assertNull(m.energyExpendedKj)
        assertTrue(m.rrIntervalsMs.isEmpty())
        assertTrue(m.isValid)
    }

    @Test
    fun uint16HeartRate() {
        assertEquals(0x0123, StandardGatt.parseHeartRate(hex("01 23 01"))!!.bpm)
    }

    @Test
    fun sensorContactFlags() {
        // bit 2 = supported, bit 1 = detected.
        val noContact = StandardGatt.parseHeartRate(hex("04 50"))!!
        assertEquals(false, noContact.sensorContact)
        assertFalse(noContact.isValid)
        assertEquals(true, StandardGatt.parseHeartRate(hex("06 50"))!!.sensorContact)
        // bit 1 without bit 2 = not supported.
        assertNull(StandardGatt.parseHeartRate(hex("02 50"))!!.sensorContact)
    }

    @Test
    fun energyExpendedAndRrIntervals() {
        // flags: uint16 HR | contact | energy | RR; HR 130; energy 0x0102 kJ; RR 1024 → 1000 ms, 512 → 500 ms.
        val m = StandardGatt.parseHeartRate(hex("1F 82 00 02 01 00 04 00 02"))!!
        assertEquals(130, m.bpm)
        assertEquals(true, m.sensorContact)
        assertEquals(0x0102, m.energyExpendedKj)
        assertEquals(listOf(1000, 500), m.rrIntervalsMs)
    }

    @Test
    fun rrIntervalsWithoutEnergyAndOddTrailingByte() {
        // RR 0x0333 = 819 → 799 ms (Gadgetbridge integer math); the dangling byte is ignored.
        val m = StandardGatt.parseHeartRate(hex("10 3C 33 03 FF"))!!
        assertEquals(60, m.bpm)
        assertEquals(listOf(819 * 1000 / 1024), m.rrIntervalsMs)
    }

    @Test
    fun truncatedMeasurementsAreRejected() {
        assertNull(StandardGatt.parseHeartRate(ByteArray(0)))
        assertNull(StandardGatt.parseHeartRate(hex("00")))
        assertNull(StandardGatt.parseHeartRate(hex("01 40")))
        assertNull(StandardGatt.parseHeartRate(hex("08 40 01")))
    }

    @Test
    fun bodySensorLocationAndBattery() {
        assertEquals(BodySensorLocation.WRIST, BodySensorLocation.fromRaw(2))
        assertNull(BodySensorLocation.fromRaw(9))
        assertEquals(87, StandardGatt.parseBatteryLevel(hex("57")))
        assertEquals(100, StandardGatt.parseBatteryLevel(hex("FF")))
        assertNull(StandardGatt.parseBatteryLevel(ByteArray(0)))
        assertEquals("1.14.0", StandardGatt.parseString("1.14.0\u0000\u0000 ".toByteArray()))
        assertNull(StandardGatt.parseString(hex("0000")))
    }

    @Test
    fun currentTimeAndLocalTimeInformation() {
        val tz = TimeZone.getTimeZone("Europe/Madrid")
        // Sunday 2026-03-29 10:20:30.500 local time (the day DST starts in Spain).
        val t = Calendar.getInstance(tz).apply { clear(); set(2026, Calendar.MARCH, 29, 10, 20, 30); set(Calendar.MILLISECOND, 500) }.timeInMillis
        assertArrayEquals(hex("EA07 03 1D 0A 14 1E 07 80 00"), StandardGatt.currentTime(t, tz))
        // UTC+1 standard (4 quarter hours) + 1 h DST (4).
        assertArrayEquals(hex("04 04"), StandardGatt.localTimeInformation(t, tz))
        assertArrayEquals(hex("F8 00"), StandardGatt.localTimeInformation(t, TimeZone.getTimeZone("GMT-02:00")))
    }
}
