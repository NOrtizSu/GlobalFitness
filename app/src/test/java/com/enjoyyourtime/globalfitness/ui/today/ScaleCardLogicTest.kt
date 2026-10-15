package com.enjoyyourtime.globalfitness.ui.today

import com.enjoyyourtime.globalfitness.ble.GattSessionInfo
import com.enjoyyourtime.globalfitness.ble.LiveScale
import com.enjoyyourtime.globalfitness.ble.ScanStatus
import com.enjoyyourtime.globalfitness.scale.SessionStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScaleCardLogicTest {
    private val now = 1_800_000_000_000L

    private fun session(id: String, status: SessionStatus, ageMs: Long) =
        GattSessionInfo(configId = id, scaleName = "Báscula $id", status = status, atMillis = now - ageMs)

    @Test
    fun hiddenWhileIdleAndScanningFine() {
        assertFalse(scaleCardVisible(ScanStatus.Scanning(locationOff = false), LiveScale.Idle, null))
        assertFalse(scaleCardVisible(ScanStatus.Starting, LiveScale.Idle, null))
    }

    @Test
    fun shownForProblemsWeighingsAndSessions() {
        assertTrue(scaleCardVisible(ScanStatus.MissingPermission, LiveScale.Idle, null))
        assertTrue(scaleCardVisible(ScanStatus.BluetoothOff, LiveScale.Idle, null))
        assertTrue(scaleCardVisible(ScanStatus.Failed(2, "x"), LiveScale.Idle, null))
        assertTrue(scaleCardVisible(ScanStatus.Scanning(locationOff = true), LiveScale.Idle, null))
        assertTrue(scaleCardVisible(ScanStatus.Scanning(locationOff = false), LiveScale.Measuring(70.0), null))
        assertTrue(scaleCardVisible(ScanStatus.Scanning(locationOff = false), LiveScale.Idle, session("a", SessionStatus.CONNECTING, 0)))
    }

    @Test
    fun busySessionWinsOverARecentFailureAndOldFailuresDisappear() {
        val busy = session("a", SessionStatus.MEASURING, 5_000)
        val failed = session("b", SessionStatus.FAILED, 1_000)
        assertEquals(busy, visibleSession(mapOf("a" to busy, "b" to failed), now))
        assertEquals(failed, visibleSession(mapOf("b" to failed), now))
        assertNull(visibleSession(mapOf("b" to session("b", SessionStatus.FAILED, 3 * 60_000)), now))
        assertNull(visibleSession(mapOf("c" to session("c", SessionStatus.DONE, 0)), now))
    }
}
