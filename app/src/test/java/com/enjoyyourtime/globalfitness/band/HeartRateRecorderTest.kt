package com.enjoyyourtime.globalfitness.band

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class HeartRateRecorderTest {
    private val recorder = HeartRateRecorder(minIntervalMs = 1_000)

    @Test
    fun storesValidReadingsTaggedAsBandLive() {
        val sample = recorder.accept(72, 10_000)
        assertEquals(HeartRateSample(10_000, 72, "band-live"), sample)
    }

    @Test
    fun skipsReadingsWhileTheBandIsStillMeasuring() {
        assertNull(recorder.accept(0, 1_000))
        assertNull(recorder.accept(10, 2_000)) // Gadgetbridge waits for a value above 10
        assertNotNull(recorder.accept(11, 3_000))
    }

    @Test
    fun skipsImplausibleValues() {
        assertNull(recorder.accept(251, 1_000))
        assertNull(recorder.accept(-5, 2_000))
        assertNotNull(recorder.accept(250, 3_000))
    }

    @Test
    fun keepsAtMostOneSamplePerInterval() {
        assertNotNull(recorder.accept(70, 10_000))
        assertNull(recorder.accept(71, 10_400))
        assertNull(recorder.accept(72, 10_999))
        assertNotNull(recorder.accept(73, 11_000))
    }

    @Test
    fun rejectedReadingsDoNotBlockTheNextValidOne() {
        assertNotNull(recorder.accept(70, 10_000))
        assertNull(recorder.accept(0, 11_000))
        assertNotNull(recorder.accept(71, 11_100))
    }

    @Test
    fun resetStartsANewSeries() {
        assertNotNull(recorder.accept(70, 10_000))
        recorder.reset()
        assertNotNull(recorder.accept(70, 10_100))
    }
}
