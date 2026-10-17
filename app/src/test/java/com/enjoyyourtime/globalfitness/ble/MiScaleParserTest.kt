package com.enjoyyourtime.globalfitness.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MiScaleParserTest {

    @Test
    fun parsesKgVectorWithImpedance() {
        val frame = MiScaleParser.parse(MiScaleParser.fromHex("0226ea070a0f081e2df4014038"))!!
        assertEquals(WeightUnit.KG, frame.unit)
        assertTrue(frame.stabilized)
        assertTrue(frame.hasImpedance)
        assertFalse(frame.loadRemoved)
        assertEquals(500, frame.impedance)
        assertEquals(14400, frame.rawWeight)
        assertEquals(72.00, frame.weightKg, 1e-9)
        assertEquals("2026-10-15 08:30:45", frame.scaleTimeKey)
        assertEquals("0226ea070a0f081e2df4014038", frame.rawHex)
    }

    @Test
    fun parsesLbsVector() {
        val bytes = MiScaleParser.fromHex("0326ea070a0f081e2df401013e")
        val frame = MiScaleParser.parse(bytes)!!
        assertEquals(WeightUnit.LBS, frame.unit)
        assertEquals(15873, frame.rawWeight)
        assertEquals(158.73 * 0.45359237, frame.weightKg, 1e-9)
        assertEquals(71.998, frame.weightKg, 0.001)
    }

    @Test
    fun parsesJinUnit() {
        val frame = MiScaleParser.parse(TestFrames.bytes(ctrl1 = 0x60, raw = 14400))!!
        assertEquals(WeightUnit.JIN, frame.unit)
        assertEquals(72.0, frame.weightKg, 1e-9)
        assertTrue(frame.stabilized)
    }

    @Test
    fun rejectsInvalidLengths() {
        assertNull(MiScaleParser.parse(null))
        assertNull(MiScaleParser.parse(ByteArray(0)))
        assertNull(MiScaleParser.parse(ByteArray(12)))
        assertNull(MiScaleParser.parse(ByteArray(14)))
        assertNull(MiScaleParser.parse(MiScaleParser.fromHex("0226ea070a0f081e2df40140")))
    }

    @Test
    fun impedanceIsNullWithoutFlagOrOutOfRange() {
        // Impedance bytes present but has-impedance bit clear.
        val noFlag = MiScaleParser.parse(TestFrames.bytes(ctrl1 = 0x24, raw = 14400, impedance = 500))!!
        assertFalse(noFlag.hasImpedance)
        assertNull(noFlag.impedance)

        val zero = MiScaleParser.parse(TestFrames.bytes(ctrl1 = 0x26, raw = 14400, impedance = 0))!!
        assertTrue(zero.hasImpedance)
        assertNull(zero.impedance)

        val tooHigh = MiScaleParser.parse(TestFrames.bytes(ctrl1 = 0x26, raw = 14400, impedance = 3001))!!
        assertNull(tooHigh.impedance)

        val max = MiScaleParser.parse(TestFrames.bytes(ctrl1 = 0x26, raw = 14400, impedance = 3000))!!
        assertEquals(3000, max.impedance)

        val removed = MiScaleParser.parse(TestFrames.bytes(ctrl1 = 0x80, raw = 0))!!
        assertTrue(removed.loadRemoved)
        assertFalse(removed.stabilized)
    }

    @Test
    fun dedupeKeyCombinesTimeAndRawWeight() {
        val a = TestFrames.frame(TestFrames.STABLE, 14400)
        val b = TestFrames.frame(TestFrames.STABLE_IMPEDANCE, 14400, impedance = 500)
        val c = TestFrames.frame(TestFrames.STABLE, 14410)
        assertEquals(a.dedupeKey, b.dedupeKey)
        assertTrue(a.dedupeKey != c.dedupeKey)
    }
}
