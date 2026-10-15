package com.enjoyyourtime.globalfitness.ble

import com.enjoyyourtime.globalfitness.ble.TestFrames.LIVE
import com.enjoyyourtime.globalfitness.ble.TestFrames.LOAD_REMOVED
import com.enjoyyourtime.globalfitness.ble.TestFrames.STABLE
import com.enjoyyourtime.globalfitness.ble.TestFrames.STABLE_IMPEDANCE
import com.enjoyyourtime.globalfitness.ble.TestFrames.STABLE_IMPEDANCE_LOAD_REMOVED
import com.enjoyyourtime.globalfitness.ble.TestFrames.STABLE_LOAD_REMOVED
import com.enjoyyourtime.globalfitness.ble.TestFrames.frame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MeasurementAssemblerTest {

    private var now = 1_000_000L
    private val assembler = MeasurementAssembler(wallClock = { now }, elapsed = { now })

    private fun advance(ms: Long) {
        now += ms
    }

    @Test
    fun liveThenStableThenImpedanceCommitsExactlyOnce() {
        assertEquals(AssemblerState.Idle, assembler.state)
        assertTrue(assembler.onFrame(frame(LIVE, 10_000)).isEmpty())
        assertEquals(AssemblerState.Measuring(50.0), assembler.state)
        advance(300)
        assertTrue(assembler.onFrame(frame(LIVE, 14_300)).isEmpty())
        advance(300)
        assertTrue(assembler.onFrame(frame(STABLE, 14_400)).isEmpty())
        assertEquals(AssemblerState.WaitingImpedance(72.0), assembler.state)

        advance(2_000)
        val out = assembler.onFrame(frame(STABLE_IMPEDANCE, 14_400, impedance = 500))
        assertEquals(1, out.size)
        val m = out.single()
        assertEquals(72.0, m.weightKg, 1e-9)
        assertEquals(500, m.impedance)
        assertFalse(m.isImpedanceUpgrade)
        assertEquals(now, m.receivedAtMillis)
        assertTrue(assembler.state is AssemblerState.Saved)

        // The scale keeps re-broadcasting the final frame for a minute or more.
        repeat(90) {
            advance(1_000)
            assertTrue(assembler.onFrame(frame(STABLE_IMPEDANCE, 14_400, impedance = 500)).isEmpty())
            assertTrue(assembler.onFrame(frame(STABLE_IMPEDANCE_LOAD_REMOVED, 14_400, impedance = 500)).isEmpty())
            assertTrue(assembler.tick().isEmpty())
        }
        // Load removed after the commit: still nothing new.
        assertTrue(assembler.onFrame(frame(LOAD_REMOVED, 0)).isEmpty())
        advance(60_000)
        assertTrue(assembler.tick().isEmpty())
        assertEquals(AssemblerState.Idle, assembler.state)
    }

    @Test
    fun weightOnlyCommitOnLoadRemoved() {
        assembler.onFrame(frame(LIVE, 14_000))
        advance(500)
        val stableAt = now
        assertTrue(assembler.onFrame(frame(STABLE, 14_400)).isEmpty())
        advance(4_000)
        assertTrue(assembler.onFrame(frame(STABLE, 14_400)).isEmpty())
        advance(1_000)
        val out = assembler.onFrame(frame(LOAD_REMOVED, 0))
        assertEquals(1, out.size)
        assertNull(out.single().impedance)
        assertEquals(72.0, out.single().weightKg, 1e-9)
        assertEquals(stableAt, out.single().receivedAtMillis)

        // Re-broadcasts of the weight-only final frame do not duplicate.
        repeat(30) {
            advance(1_000)
            assertTrue(assembler.onFrame(frame(STABLE_LOAD_REMOVED, 14_400)).isEmpty())
            assertTrue(assembler.onFrame(frame(STABLE, 14_400)).isEmpty())
            assertTrue(assembler.tick().isEmpty())
        }
    }

    @Test
    fun weightOnlyCommitAfterTenSecondsWithoutFrames() {
        val stableAt = now
        assertTrue(assembler.onFrame(frame(STABLE, 14_400)).isEmpty())
        advance(9_999)
        assertTrue(assembler.tick().isEmpty())
        advance(1)
        val out = assembler.tick()
        assertEquals(1, out.size)
        assertNull(out.single().impedance)
        assertEquals(stableAt, out.single().receivedAtMillis)
        advance(5_000)
        assertTrue(assembler.tick().isEmpty())
        assertTrue(assembler.onFrame(frame(STABLE, 14_400)).isEmpty())
    }

    @Test
    fun impedanceAfterWeightOnlyCommitUpgradesInsteadOfCreating() {
        assembler.onFrame(frame(STABLE, 14_400))
        advance(1_000)
        val first = assembler.onFrame(frame(LOAD_REMOVED, 0)).single()
        assertNull(first.impedance)

        advance(2_000)
        val upgrade = assembler.onFrame(frame(STABLE_IMPEDANCE, 14_400, impedance = 480))
        assertEquals(1, upgrade.size)
        assertTrue(upgrade.single().isImpedanceUpgrade)
        assertEquals(first.scaleKey, upgrade.single().scaleKey)
        assertEquals(480, upgrade.single().impedance)
        assertEquals(first.receivedAtMillis, upgrade.single().receivedAtMillis)

        // Only one upgrade.
        repeat(10) {
            advance(1_000)
            assertTrue(assembler.onFrame(frame(STABLE_IMPEDANCE, 14_400, impedance = 480)).isEmpty())
        }
    }

    @Test
    fun upgradeMatchesSameWeightWhenScaleClockTicked() {
        assembler.onFrame(frame(STABLE, 14_400, second = 10))
        advance(10_000)
        val first = assembler.tick().single()
        advance(5_000)
        val upgrade = assembler.onFrame(frame(STABLE_IMPEDANCE, 14_400, impedance = 510, second = 25)).single()
        assertTrue(upgrade.isImpedanceUpgrade)
        assertEquals(first.scaleKey, upgrade.scaleKey)
        assertTrue(assembler.onFrame(frame(STABLE_IMPEDANCE, 14_400, impedance = 510, second = 25)).isEmpty())
    }

    @Test
    fun distinctWeighingsProduceDistinctCommits() {
        val a = assembler.onFrame(frame(STABLE_IMPEDANCE, 14_400, impedance = 500, second = 1))
        advance(120_000)
        assembler.onFrame(frame(LOAD_REMOVED, 0))
        advance(60_000)
        val b = assembler.onFrame(frame(STABLE_IMPEDANCE, 11_000, impedance = 520, second = 59))
        assertEquals(1, a.size)
        assertEquals(1, b.size)
        assertTrue(a.single().scaleKey != b.single().scaleKey)
        assertEquals(55.0, b.single().weightKg, 1e-9)
    }

    @Test
    fun rebroadcastWithTickedScaleClockIsIgnored() {
        val first = assembler.onFrame(frame(STABLE_IMPEDANCE, 14_400, impedance = 500, second = 10))
        assertEquals(1, first.size)
        // Same raw weight and impedance, new scale time: still the same reading.
        repeat(5) { i ->
            advance(60_000)
            assertTrue(assembler.onFrame(frame(STABLE_IMPEDANCE, 14_400, impedance = 500, second = 11 + i)).isEmpty())
        }
        // A different impedance with the same weight is a new weighing.
        advance(1_000)
        assertEquals(1, assembler.onFrame(frame(STABLE_IMPEDANCE, 14_400, impedance = 530, second = 30)).size)
    }

    @Test
    fun sameReadingAfterTenMinutesIsANewMeasurement() {
        assertEquals(1, assembler.onFrame(frame(STABLE_IMPEDANCE, 14_400, impedance = 500, second = 1)).size)
        advance(MeasurementAssembler.REBROADCAST_WINDOW_MS + 1)
        assertEquals(1, assembler.onFrame(frame(STABLE_IMPEDANCE, 14_400, impedance = 500, second = 2)).size)
    }

    @Test
    fun pendingWeightOnlyIsCommittedWhenANewWeighingStarts() {
        val stableAt = now
        assertTrue(assembler.onFrame(frame(STABLE, 14_400)).isEmpty())
        advance(2_000)
        // Small fluctuation (< 1 kg): keep waiting for impedance.
        assertTrue(assembler.onFrame(frame(LIVE, 14_300)).isEmpty())
        advance(2_000)
        // Someone else steps on within the 10 s timeout.
        val out = assembler.onFrame(frame(LIVE, 11_000))
        assertEquals(1, out.size)
        assertNull(out.single().impedance)
        assertEquals(72.0, out.single().weightKg, 1e-9)
        assertEquals(stableAt, out.single().receivedAtMillis)
        assertEquals(AssemblerState.Measuring(55.0), assembler.state)
    }

    @Test
    fun timeoutsUseTheMonotonicClockNotTheWallClock() {
        var wall = 5_000_000L
        var mono = 0L
        val a = MeasurementAssembler(wallClock = { wall }, elapsed = { mono })
        a.onFrame(frame(STABLE, 14_400))
        // Wall clock jumps back an hour (time zone / NTP change): no effect on the timeout.
        wall -= 3_600_000L
        mono += 9_000
        assertTrue(a.tick().isEmpty())
        mono += 1_000
        val out = a.tick().single()
        assertEquals(5_000_000L, out.receivedAtMillis)
    }

    @Test
    fun liveFramesWithoutStabilizationNeverCommit() {
        repeat(20) {
            advance(500)
            assertTrue(assembler.onFrame(frame(LIVE, 14_000 + it)).isEmpty())
        }
        assertTrue(assembler.onFrame(frame(LOAD_REMOVED, 0)).isEmpty())
        advance(30_000)
        assertTrue(assembler.tick().isEmpty())
        assertEquals(AssemblerState.Idle, assembler.state)
    }
}
