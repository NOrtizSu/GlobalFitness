package com.enjoyyourtime.globalfitness.band.xiaomi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class XiaomiBand9Test {
    @Test
    fun matchesTheGadgetbridgeNamePattern() {
        assertTrue(XiaomiBand9.isBand9("Xiaomi Smart Band 9 3A7F"))
        assertTrue(XiaomiBand9.isBand9(" Xiaomi Smart Band 9 0000 "))
        assertFalse(XiaomiBand9.isBand9("Xiaomi Smart Band 9 Pro 3A7F"))
        assertFalse(XiaomiBand9.isBand9("Xiaomi Smart Band 9 Active 3A7F"))
        assertFalse(XiaomiBand9.isBand9("Xiaomi Smart Band 9 3a7f"))
        assertFalse(XiaomiBand9.isBand9("Xiaomi Smart Band 8 3A7F"))
        assertFalse(XiaomiBand9.isBand9(null))
    }

    @Test
    fun listsOtherXiaomiBandsAsCandidates() {
        assertTrue(XiaomiBand9.isXiaomiBand("Xiaomi Smart Band 9 Pro 1234"))
        assertTrue(XiaomiBand9.isXiaomiBand("Redmi Smart Band 2 1234"))
        assertFalse(XiaomiBand9.isXiaomiBand("MIBFS"))
    }

    @Test
    fun usesTheSerialPortProfileUuid() {
        assertEquals("00001101-0000-1000-8000-00805f9b34fb", XiaomiBand9.SPP_UUID.toString())
    }
}
