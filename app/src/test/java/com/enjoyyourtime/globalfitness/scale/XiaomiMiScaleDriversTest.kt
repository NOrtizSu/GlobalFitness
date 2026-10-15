package com.enjoyyourtime.globalfitness.scale

import com.enjoyyourtime.globalfitness.ble.MeasurementAssembler
import com.enjoyyourtime.globalfitness.ble.MiScaleParser
import com.enjoyyourtime.globalfitness.ble.TestFrames
import com.enjoyyourtime.globalfitness.scale.drivers.MiScale1Driver
import com.enjoyyourtime.globalfitness.scale.drivers.MiScale2Driver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class XiaomiMiScaleDriversTest {
    private val sd181b = Bytes.uuid16(0x181B)
    private val sd181d = Bytes.uuid16(0x181D)

    @Test
    fun miScale2MatchesOnlyValidFrames() {
        val d = MiScale2Driver()
        assertEquals(MatchConfidence.HIGH, d.match(ScaleTestSupport.adv(serviceData = mapOf(sd181b to TestFrames.bytes(TestFrames.STABLE, 14_400)))))
        assertEquals(MatchConfidence.HIGH, d.match(ScaleTestSupport.adv(name = "MIBFS")))
        assertNull(d.match(ScaleTestSupport.adv(serviceData = mapOf(sd181b to ByteArray(24)))))
        assertNull(d.match(ScaleTestSupport.adv(name = "Some Phone")))
        assertTrue(d.verified)
    }

    /** The driver must produce exactly what the verified assembler path produced. */
    @Test
    fun miScale2SessionIsIdenticalToTheAssemblerPath() {
        val clock = ScaleTestSupport.Clock()
        val driver = MiScale2Driver()
        val env = ScaleTestSupport.env(driver.id, clock)
        val session = driver.newSession(env)
        val reference = MeasurementAssembler(wallClock = { clock.now }, elapsed = { clock.now })

        val frames = listOf(
            TestFrames.bytes(TestFrames.LIVE, 10_000),
            TestFrames.bytes(TestFrames.STABLE, 14_400),
            TestFrames.bytes(TestFrames.STABLE_IMPEDANCE, 14_400, impedance = 500),
            TestFrames.bytes(TestFrames.STABLE_IMPEDANCE_LOAD_REMOVED, 14_400, impedance = 500),
        )
        val produced = ArrayList<ScaleReading>()
        val expected = ArrayList<com.enjoyyourtime.globalfitness.ble.CompletedMeasurement>()
        for (f in frames) {
            clock.advance(400)
            produced += session.parseAdvertisement(ScaleTestSupport.adv(serviceData = mapOf(sd181b to f), receivedAt = clock.now), env)
            expected += reference.onFrame(MiScaleParser.parse(f)!!)
        }
        assertEquals(1, produced.size)
        assertEquals(expected.size, produced.size)
        val r = produced.single()
        val e = expected.single()
        assertEquals(e.weightKg, r.weightKg, 1e-9)
        assertEquals(e.impedance, r.impedance)
        assertEquals(e.scaleKey, r.dedupeKey) // legacy key format kept: existing rows dedupe
        assertEquals("2026-10-15 08:30:45|14400", r.dedupeKey)
        assertEquals(e.receivedAtMillis, r.measuredAtMillis)
        assertEquals(FormulaLib.MI_SCALE, r.formulaLib)
        assertEquals(LiveState.Done, session.live)
    }

    @Test
    fun miScale2WeightOnlyCommitThenImpedanceUpgradeOnTick() {
        val clock = ScaleTestSupport.Clock()
        val driver = MiScale2Driver()
        val env = ScaleTestSupport.env(driver.id, clock)
        val session = driver.newSession(env)
        session.parseAdvertisement(ScaleTestSupport.adv(serviceData = mapOf(sd181b to TestFrames.bytes(TestFrames.STABLE, 14_400))), env)
        assertEquals(LiveState.Stabilized(72.0), session.live)
        assertTrue(session.needsTick)
        clock.advance(MeasurementAssembler.WEIGHT_ONLY_TIMEOUT_MS)
        val weightOnly = session.tick(env).single()
        assertNull(weightOnly.impedance)
        assertEquals(CompositionSource.None, weightOnly.compositionSource)
        clock.advance(5_000)
        val upgrade = session.parseAdvertisement(
            ScaleTestSupport.adv(serviceData = mapOf(sd181b to TestFrames.bytes(TestFrames.STABLE_IMPEDANCE, 14_400, impedance = 480))),
            env,
        ).single()
        assertTrue(upgrade.isImpedanceUpgrade)
        assertEquals(weightOnly.dedupeKey, upgrade.dedupeKey)
        assertEquals(480, upgrade.impedance)
    }

    private fun v1Frame(status: Int, raw: Int): ByteArray = byteArrayOf(
        status.toByte(), (raw and 0xFF).toByte(), (raw shr 8).toByte(),
        0xEA.toByte(), 0x07, 10, 15, 8, 30, 45, // 2026-10-15 08:30:45
    )

    @Test
    fun miScale1ParsesTheTenByteWeightFrame() {
        val f = MiScale1Driver.parseFrame(v1Frame(0x20, 14_400))!!
        assertEquals(72.0f, f.weightKg, 1e-4f)
        assertTrue(f.stable)
        assertEquals(ScaleTestSupport.epochMillis(2026, 10, 15, 8, 30, 45), f.scaleTimeMillis)
        val lbs = MiScale1Driver.parseFrame(v1Frame(0x21, 15_873))!!
        assertEquals(72.0f, lbs.weightKg, 0.01f)
        assertNull(MiScale1Driver.parseFrame(ByteArray(13)))
    }

    @Test
    fun miScale1PublishesStableWeightOnceWithScaleTime() {
        val clock = ScaleTestSupport.Clock(ScaleTestSupport.epochMillis(2026, 10, 15, 8, 31, 0))
        val driver = MiScale1Driver()
        val env = ScaleTestSupport.env(driver.id, clock)
        val session = driver.newSession(env)
        fun adv(status: Int, raw: Int) = ScaleTestSupport.adv(name = "MI_SCALE", serviceData = mapOf(sd181d to v1Frame(status, raw)), receivedAt = clock.now)
        assertEquals(MatchConfidence.HIGH, driver.match(adv(0x20, 14_400)))
        assertTrue(session.parseAdvertisement(adv(0x00, 12_000), env).isEmpty())
        assertEquals(LiveState.Measuring(60.0), session.live)
        val r = session.parseAdvertisement(adv(0x20, 14_400), env).single()
        assertEquals(72.0, r.weightKg, 1e-4)
        assertEquals(ScaleTestSupport.epochMillis(2026, 10, 15, 8, 30, 45), r.measuredAtMillis)
        clock.advance(1_000)
        assertTrue(session.parseAdvertisement(adv(0x20, 14_400), env).isEmpty())
        assertTrue(session.parseAdvertisement(adv(0xA0, 14_400), env).isEmpty())
    }
}
