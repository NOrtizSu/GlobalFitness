package com.enjoyyourtime.globalfitness.band

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class BandLogTest {
    private val key = "00112233445566778899aabbccddeeff"

    @Test
    fun redactsRegisteredSecretsInAnyCase() {
        val log = BandLog(clock = { 5L })
        log.registerSecret(key)
        log.add("clave=$key / ${key.uppercase()}")
        val line = log.lines.value.single()
        assertEquals(5L, line.timeMs)
        assertFalse(line.text.contains(key, ignoreCase = true))
        assertEquals("clave=[clave oculta] / [clave oculta]", line.text)
    }

    @Test
    fun keepsOnlyTheLastLines() {
        val log = BandLog(capacity = 3)
        (1..5).forEach { log.add("línea $it") }
        assertEquals(listOf("línea 3", "línea 4", "línea 5"), log.lines.value.map { it.text })
        log.clear()
        assertEquals(0, log.lines.value.size)
    }
}
