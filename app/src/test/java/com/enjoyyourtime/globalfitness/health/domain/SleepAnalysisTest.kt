package com.enjoyyourtime.globalfitness.health.domain

import com.enjoyyourtime.globalfitness.domain.Status
import com.enjoyyourtime.globalfitness.health.SleepSession
import com.enjoyyourtime.globalfitness.health.SleepStage
import com.enjoyyourtime.globalfitness.health.SleepStageInterval
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

class SleepAnalysisTest {
    private val zone = ZoneId.of("Europe/Madrid")
    private val min = 60_000L

    private fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int = 0) = LocalDateTime.of(y, mo, d, h, mi).atZone(zone).toInstant().toEpochMilli()

    private fun session(id: String, start: Long, end: Long, vararg stages: Triple<Long, Long, SleepStage>) =
        SleepSession(id, start, end, null, "com.xiaomi.wearable", stages.map { SleepStageInterval(it.first, it.second, it.third) })

    private fun night(asleep: Int, deep: Int, rem: Int, hasStages: Boolean = true): NightSleep {
        val s = session("n", 0, asleep * min)
        return NightSleep(
            date = LocalDate.of(2026, 10, 15), start = 0, end = asleep * min, inBedMinutes = asleep, asleepMinutes = asleep,
            awakeMinutes = 0, lightMinutes = asleep - deep - rem, deepMinutes = deep, remMinutes = rem, unknownMinutes = 0,
            hasStages = hasStages, efficiencyPercent = null, mainSession = s, sessionCount = 1,
        )
    }

    @Test
    fun sessionBelongsToTheDayItEnds() {
        val overnight = session("a", at(2026, 10, 14, 23, 30), at(2026, 10, 15, 7, 10))
        assertEquals(LocalDate.of(2026, 10, 15), SleepAnalysis.nightOf(overnight, zone))
        val early = session("b", at(2026, 10, 14, 21, 0), at(2026, 10, 14, 23, 50))
        assertEquals(LocalDate.of(2026, 10, 14), SleepAnalysis.nightOf(early, zone))
    }

    @Test
    fun nightsGroupSessionsByEndDate() {
        val main = session("a", at(2026, 10, 14, 23, 0), at(2026, 10, 15, 7, 0))
        val nap = session("b", at(2026, 10, 15, 15, 0), at(2026, 10, 15, 15, 30))
        val other = session("c", at(2026, 10, 15, 23, 0), at(2026, 10, 16, 6, 0))
        val nights = SleepAnalysis.nights(listOf(other, nap, main), zone)
        assertEquals(listOf(LocalDate.of(2026, 10, 15), LocalDate.of(2026, 10, 16)), nights.map { it.date })
        val first = nights.first()
        assertEquals(2, first.sessionCount)
        assertEquals("a", first.mainSession.id)
        assertEquals(8 * 60 + 30, first.asleepMinutes)
    }

    @Test
    fun stageMinutesAndEfficiency() {
        val s = at(2026, 10, 14, 23, 0)
        val sess = session(
            "a", s, s + 480 * min,
            Triple(s, s + 20 * min, SleepStage.AWAKE),
            Triple(s + 20 * min, s + 200 * min, SleepStage.LIGHT),
            Triple(s + 200 * min, s + 290 * min, SleepStage.DEEP),
            Triple(s + 290 * min, s + 380 * min, SleepStage.REM),
            Triple(s + 380 * min, s + 470 * min, SleepStage.LIGHT),
            Triple(s + 470 * min, s + 480 * min, SleepStage.AWAKE),
        )
        val n = SleepAnalysis.summarize(LocalDate.of(2026, 10, 15), listOf(sess))!!
        assertEquals(30, n.awakeMinutes)
        assertEquals(270, n.lightMinutes)
        assertEquals(90, n.deepMinutes)
        assertEquals(90, n.remMinutes)
        assertEquals(450, n.asleepMinutes)
        assertEquals(480, n.inBedMinutes)
        assertTrue(n.hasStages)
        assertEquals(450 * 100.0 / 480, n.efficiencyPercent!!, 1e-9)
    }

    @Test
    fun sessionWithoutStagesCountsAsUnspecifiedSleep() {
        val s = at(2026, 10, 14, 23, 0)
        val n = SleepAnalysis.summarize(LocalDate.of(2026, 10, 15), listOf(session("a", s, s + 420 * min)))!!
        assertEquals(420, n.asleepMinutes)
        assertEquals(420, n.unknownMinutes)
        assertFalse(n.hasStages)
        assertNull(n.efficiencyPercent)
    }

    @Test
    fun overlappingAndOutOfBoundsStagesAreClipped() {
        val s = 1_000_000L * min
        val sess = session(
            "a", s, s + 100 * min,
            Triple(s - 10 * min, s + 40 * min, SleepStage.LIGHT), // starts before the session
            Triple(s + 30 * min, s + 60 * min, SleepStage.DEEP), // overlaps the previous one
            Triple(s + 90 * min, s + 130 * min, SleepStage.REM), // ends after the session
        )
        val stages = SleepAnalysis.normalizedStages(sess)
        assertEquals(
            listOf(
                SleepStageInterval(s, s + 40 * min, SleepStage.LIGHT),
                SleepStageInterval(s + 40 * min, s + 60 * min, SleepStage.DEEP),
                SleepStageInterval(s + 90 * min, s + 100 * min, SleepStage.REM),
            ),
            stages,
        )
        assertNull(SleepAnalysis.summarize(LocalDate.of(2026, 1, 1), listOf(session("x", s, s))))
    }

    @Test
    fun qualityRuleIsDurationPlusRestorativeShare() {
        // 8 h with 40 % deep + REM: perfect.
        val perfect = SleepAnalysis.quality(night(480, deep = 96, rem = 96))
        assertEquals(100, perfect.score)
        assertEquals("Bueno", perfect.label)
        assertEquals(Status.GOOD, perfect.status)

        // 6 h (−20 points) with 20 % deep + REM (20 of 40 points): 40 + 20 = 60.
        val short = SleepAnalysis.quality(night(360, deep = 36, rem = 36))
        assertEquals(40, short.durationPoints)
        assertEquals(20, short.restorativePoints)
        assertEquals(60, short.score)
        assertEquals("Aceptable", short.label)

        // 4 h or less: no duration points.
        val veryShort = SleepAnalysis.quality(night(200, deep = 40, rem = 40))
        assertEquals(0, veryShort.durationPoints)
        assertEquals("Mejorable", veryShort.label)

        // 10 h: −20 points for the extra hour.
        assertEquals(40, SleepAnalysis.quality(night(600, deep = 240, rem = 0)).durationPoints)
    }

    @Test
    fun qualityWithoutStagesUsesDurationOnly() {
        val q = SleepAnalysis.quality(night(450, deep = 0, rem = 0, hasStages = false))
        assertNull(q.restorativePoints)
        assertNull(q.restorativeShare)
        assertEquals(100, q.score)
        assertEquals(50, SleepAnalysis.quality(night(330, deep = 0, rem = 0, hasStages = false)).score) // 5,5 h
    }
}
