package com.enjoyyourtime.globalfitness.scale

import com.enjoyyourtime.globalfitness.scale.ScaleTestSupport.adv
import com.enjoyyourtime.globalfitness.scale.ScaleTestSupport.hex
import com.enjoyyourtime.globalfitness.scale.drivers.EtekcityESF551Driver
import com.enjoyyourtime.globalfitness.scale.drivers.EufyC20Driver
import com.enjoyyourtime.globalfitness.scale.drivers.MedisanaBs44xDriver
import com.enjoyyourtime.globalfitness.scale.drivers.OkOkDriver
import com.enjoyyourtime.globalfitness.scale.drivers.SinocareDriver
import com.enjoyyourtime.globalfitness.scale.drivers.StandardWeightProfileDriver
import com.enjoyyourtime.globalfitness.scale.libs.S400Aggregator
import com.enjoyyourtime.globalfitness.scale.libs.S400Packet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Commit, re-arm and filtering rules of individual drivers. */
class DriverBehaviourTest {

    // ---- OKOK V11: live values committed once stable ------------------------------------------

    private fun v11(raw: Int): ByteArray {
        val d = ByteArray(23)
        d[3] = (raw shr 8).toByte()
        d[4] = raw.toByte()
        d[9] = 0x00 // kg, divider 10
        var checksum = 0xCA xor 0x11
        for (i in 0 until 16) checksum = checksum xor Bytes.u8(d, i)
        d[16] = checksum.toByte()
        return d
    }

    private fun okokAdv(raw: Int) = adv("ADV", manufacturer = mapOf(OkOkDriver.MANUF_V11 to v11(raw)))

    @Test
    fun okokV11CommitsAfterFiveRepeatsOnceAndAZeroReArms() {
        val driver = OkOkDriver()
        assertEquals(MatchConfidence.HIGH, driver.match(okokAdv(725)))
        val clock = ScaleTestSupport.Clock()
        val env = ScaleTestSupport.env(driver.id, clock)
        val session = driver.newSession(env)
        val out = ArrayList<ScaleReading>()
        repeat(4) {
            clock.advance(100)
            out += session.parseAdvertisement(okokAdv(725), env)
        }
        assertTrue(out.isEmpty())
        assertEquals(LiveState.Measuring(72.5), session.live)
        clock.advance(100)
        out += session.parseAdvertisement(okokAdv(725), env)
        assertEquals(72.5, out.single().weightKg, 1e-3)
        repeat(6) { out += session.parseAdvertisement(okokAdv(725), env) } // still standing on it
        assertEquals(1, out.size)
        session.parseAdvertisement(okokAdv(0), env) // stepped off
        repeat(5) {
            clock.advance(100)
            out += session.parseAdvertisement(okokAdv(725), env)
        }
        assertEquals(2, out.size)
    }

    @Test
    fun okokV11CommitsAValueThatStaysUnchangedOnTick() {
        val driver = OkOkDriver()
        val clock = ScaleTestSupport.Clock()
        val env = ScaleTestSupport.env(driver.id, clock)
        val session = driver.newSession(env)
        session.parseAdvertisement(okokAdv(803), env)
        clock.advance(200)
        session.parseAdvertisement(okokAdv(803), env)
        assertTrue(session.needsTick)
        assertTrue(session.tick(env).isEmpty())
        clock.advance(OkOkDriver.STABLE_MS)
        assertEquals(80.3, session.tick(env).single().weightKg, 1e-3)
        assertFalse(session.needsTick)
    }

    // ---- Sinocare: nine identical frames, reset on empty scale or silence ---------------------

    private fun sinocare(raw: Int): ByteArray {
        val d = ByteArray(17)
        d[6] = 0xCE.toByte()
        d[9] = raw.toByte()
        d[10] = (raw shr 8).toByte()
        d[15] = 0x01
        var x = 0
        for (i in 6 until 16) x = x xor Bytes.u8(d, i)
        d[16] = x.toByte()
        return d
    }

    @Test
    fun sinocareCountRestartsAfterSilenceAndAnEmptyScaleReArms() {
        assertEquals(7430, SinocareDriver.weightRaw(sinocare(7430)))
        val driver = SinocareDriver()
        val clock = ScaleTestSupport.Clock()
        val env = ScaleTestSupport.env(driver.id, clock)
        val session = driver.newSession(env)
        val out = ArrayList<ScaleReading>()
        fun feed(n: Int, raw: Int) = repeat(n) {
            clock.advance(200)
            out += session.parseAdvertisement(adv("Weight Scale", manufacturer = mapOf(SinocareDriver.MANUFACTURER_ID to sinocare(raw))), env)
        }
        feed(5, 7430)
        clock.advance(6_000) // silence: the next frames start a new count
        feed(4, 7430)
        assertTrue(out.isEmpty())
        feed(5, 7430)
        assertEquals(74.30, out.single().weightKg, 1e-3)
        feed(1, 0) // empty scale
        feed(9, 7430) // same weight again: a new weighing
        assertEquals(2, out.size)
    }

    // ---- Eufy C20: weight confirmed twice --------------------------------------------------------

    private fun c20(grams10: Int, heartRate: Int = 0, impedance10: Int = 0): ByteArray {
        val d = ByteArray(19)
        var flags = 0x01
        d[12] = grams10.toByte()
        d[13] = (grams10 shr 8).toByte()
        if (heartRate > 0) {
            flags = flags or 0x80
            d[15] = heartRate.toByte()
        }
        if (impedance10 > 0) {
            flags = flags or 0x40
            d[17] = impedance10.toByte()
            d[18] = (impedance10 shr 8).toByte()
        }
        d[10] = flags.toByte()
        return d
    }

    @Test
    fun eufyC20NeedsTheWeightTwiceAndCommitsAConfirmedWeightAfterSilence() {
        val driver = EufyC20Driver()
        val clock = ScaleTestSupport.Clock()
        val env = ScaleTestSupport.env(driver.id, clock)
        val session = driver.newSession(env)
        fun frame(payload: ByteArray): List<ScaleReading> {
            clock.advance(100)
            return session.parseAdvertisement(adv(manufacturer = mapOf(EufyC20Driver.MANUFACTURER_ID to payload)), env)
        }
        assertEquals(MatchConfidence.HIGH, driver.match(adv(manufacturer = mapOf(EufyC20Driver.MANUFACTURER_ID to c20(7230)))))
        assertTrue(frame(c20(7230, 70, 5000)).isEmpty()) // first frame may still be moving
        val full = frame(c20(7230, 70, 5000)).single()
        assertEquals(72.3, full.weightKg, 1e-3)
        assertEquals(500, full.impedance)

        clock.advance(70_000)
        assertTrue(frame(c20(8000)).isEmpty())
        clock.advance(9_000)
        assertTrue("a weight seen once is not committed", session.tick(env).isEmpty())
        frame(c20(8000))
        frame(c20(8000))
        clock.advance(9_000)
        val weightOnly = session.tick(env).single()
        assertEquals(80.0, weightOnly.weightKg, 1e-3)
        assertNull(weightOnly.impedance)
    }

    // ---- Xiaomi S400 aggregation over time ------------------------------------------------------

    @Test
    fun s400AggregatorFlushesPacketAAloneAndDropsStaleHalves() {
        val agg = S400Aggregator()
        assertEquals(S400Aggregator.Outcome.Pending, agg.ingest("m", S400Packet(70f, 500f, null, 80), 0))
        assertTrue(agg.hasPending)
        assertTrue(agg.flush(5_000).isEmpty())
        val flushed = agg.flush(10_000).single().second
        assertEquals(70f, flushed.weightKg)
        assertTrue(flushed.timedOut)
        assertFalse(agg.hasPending)

        // A packet B arriving long after an unfinished packet A never completes it.
        agg.ingest("n", S400Packet(72f, 510f, null, 70), 100_000)
        assertEquals(S400Aggregator.Outcome.Pending, agg.ingest("n", S400Packet(0f, null, 560f, null), 115_000))
        assertTrue(agg.flush(130_000).isEmpty())
        assertFalse(agg.hasPending)
    }

    // ---- Bluetooth SIG weight scale: 0xFFFF = not measured --------------------------------------

    @Test
    fun sigUnsuccessfulValuesAreNeverStored() {
        val weight = uuid16(0x2A9D)
        val bodyComposition = uuid16(0x2A9C)
        val store = MapDriverStore(mapOf(ConfigField.KEY_USER_SLOT to "3", ConfigField.KEY_PIN to "1234"))
        val h = ProtocolHarness(StandardWeightProfileDriver(), store = store).connect()
        h.notify(weight, "06 FF FF EA 07 08 16 08 2A 24 03") // measurement unsuccessful
        h.io.fireTimers()
        assertTrue(h.readings.isEmpty())

        h.notify(bodyComposition, "00 02 FF FF 88 13") // fat not measured, impedance 500 Ω
        h.notify(weight, "06 54 33 EA 07 08 16 08 2A 24 03")
        h.io.fireTimers()
        val r = h.readings.single()
        assertEquals(65.7, r.weightKg, 1e-3)
        assertEquals(500, r.impedance)
        assertNull(r.composition?.fatPercent)
    }

    // ---- Medisana BS44x: only the configured person -----------------------------------------------

    private fun medisanaWeight(person: Int, kg100: Int, unixSec: Long): ByteArray {
        val d = ByteArray(14)
        d[1] = kg100.toByte()
        d[2] = (kg100 shr 8).toByte()
        for (i in 0 until 4) d[5 + i] = (unixSec shr (8 * i)).toByte()
        d[13] = person.toByte()
        return d
    }

    private fun medisanaFeature(person: Int, fat10: Int): ByteArray {
        val d = ByteArray(16)
        d[5] = person.toByte()
        d[8] = fat10.toByte()
        d[9] = (fat10 shr 8).toByte()
        d[10] = 0x28 // water 55.2 %
        d[11] = 0x02
        d[12] = 0x91.toByte() // muscle 40.1 %
        d[13] = 0x01
        d[14] = 31 // bone 3.1 kg
        return d
    }

    @Test
    fun medisanaImportsOnlyTheConfiguredPersonAndFlagsStoredRecords() {
        val chrWeight = uuid16(0x8A21)
        val chrFeature = uuid16(0x8A22)
        val h = ProtocolHarness(MedisanaBs44xDriver(), ctx = ScaleTestSupport.ctx(userSlot = 2)).connect()
        val nowSec = h.clock.now / 1000

        h.notify(chrWeight, medisanaWeight(person = 1, kg100 = 8150, unixSec = nowSec - 60))
        h.notify(chrFeature, medisanaFeature(person = 1, fat10 = 280))
        assertTrue("another person's weighing", h.readings.isEmpty())

        h.notify(chrWeight, medisanaWeight(person = 2, kg100 = 7230, unixSec = nowSec - 60))
        h.notify(chrFeature, medisanaFeature(person = 2, fat10 = 235))
        val live = h.readings.single()
        assertEquals(72.3, live.weightKg, 1e-3)
        assertEquals(23.5, live.composition!!.fatPercent!!, 1e-3)
        assertFalse(live.fromHistory)

        h.notify(chrWeight, medisanaWeight(person = 2, kg100 = 7180, unixSec = nowSec - 86_400))
        h.notify(chrFeature, medisanaFeature(person = 2, fat10 = 236))
        assertTrue("record from the scale memory", h.readings.last().fromHistory)
        assertEquals(2, h.readings.size)
    }

    // ---- Etekcity ESF-551: weight only when no impedance follows ------------------------------------

    @Test
    fun etekcityEsf551PublishesTheLockedWeightWhenNoImpedanceFollows() {
        val notify = uuid16(0xFFF1)
        val lockedNoImpedance = hex("A5 02 00 10 00 00 01 61 A1 00 80 38 01 00 00 00 00 00 00 00 01 00")
        val final = hex("A5 02 00 10 00 00 01 61 A1 00 80 38 01 F4 01 00 00 00 00 00 01 00")

        val h = ProtocolHarness(EtekcityESF551Driver()).connect()
        h.notify(notify, lockedNoImpedance)
        assertTrue(h.readings.isEmpty())
        h.io.fireTimers()
        val r = h.readings.single()
        assertEquals(80.0, r.weightKg, 1e-3)
        assertNull(r.impedance)
        assertTrue(h.io.disconnected)
        h.notify(notify, final) // too late: the weighing is closed
        assertEquals(1, h.readings.size)

        // The impedance arriving in time wins and the weight-only timer is cancelled.
        val h2 = ProtocolHarness(EtekcityESF551Driver()).connect()
        h2.notify(notify, lockedNoImpedance)
        h2.notify(notify, final)
        h2.io.fireTimers()
        assertEquals(500, h2.readings.single().impedance)
    }
}
