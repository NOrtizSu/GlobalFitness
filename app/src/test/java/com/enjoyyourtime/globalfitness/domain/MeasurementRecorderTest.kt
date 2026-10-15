package com.enjoyyourtime.globalfitness.domain

import com.enjoyyourtime.globalfitness.data.Measurement
import com.enjoyyourtime.globalfitness.data.MeasurementSource
import com.enjoyyourtime.globalfitness.data.MeasurementStore
import com.enjoyyourtime.globalfitness.scale.CompositionSource
import com.enjoyyourtime.globalfitness.scale.FormulaLib
import com.enjoyyourtime.globalfitness.scale.ScaleReading
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MeasurementRecorderTest {
    private class FakeStore : MeasurementStore {
        override val measurements = MutableStateFlow<List<Measurement>>(emptyList())
        val deleted = HashSet<String>()
        private var nextId = 1L

        override suspend fun awaitLoaded() = Unit

        override suspend fun insertMeasurement(m: Measurement): Measurement? {
            if (m.scaleKey != null && measurements.value.any { it.scaleKey == m.scaleKey }) return null
            val stored = m.copy(id = nextId++)
            measurements.value = measurements.value + stored
            return stored
        }

        override suspend fun updateScaleData(updated: Measurement): Measurement? {
            if (measurements.value.none { it.id == updated.id }) return null
            measurements.value = measurements.value.map { if (it.id == updated.id) updated else it }
            return updated
        }

        override fun isDeletedScaleKey(key: String): Boolean = key in deleted
    }

    private val t = 1_800_000_000_000L

    private fun reading(key: String, kg: Double, impedance: Int? = null, impedanceLow: Int? = null, upgrade: Boolean = false) = ScaleReading(
        weightKg = kg,
        impedance = impedance,
        impedanceLow = impedanceLow,
        measuredAtMillis = t,
        receivedAtMillis = t,
        dedupeKey = key,
        compositionSource = CompositionSource.None,
        formulaLib = FormulaLib.S400,
        driverId = "xiaomi_s400",
        deviceAddress = "AA:BB:CC:DD:EE:FF",
        isImpedanceUpgrade = upgrade,
    )

    @Test
    fun aWeighingTheUserDeletedIsNeverStoredAgain() = runBlocking {
        val store = FakeStore().apply { deleted += "k1" }
        val recorder = MeasurementRecorder(store)
        assertNull(recorder.record(reading("k1", 72.0)))
        assertNull(recorder.record(reading("k1", 72.0, impedance = 500, upgrade = true)))
        assertTrue(store.measurements.value.isEmpty())
    }

    @Test
    fun anUpgradeForAMissingRowDoesNotResurrectIt() = runBlocking {
        val store = FakeStore()
        assertNull(MeasurementRecorder(store).record(reading("k2", 72.0, impedance = 500, upgrade = true)))
        assertTrue(store.measurements.value.isEmpty())
    }

    @Test
    fun weightOnlyRowGetsBothImpedancesAndRebroadcastsAreDuplicates() = runBlocking {
        val store = FakeStore()
        val recorder = MeasurementRecorder(store)
        assertTrue(recorder.record(reading("k3", 72.0)) is RecordOutcome.Saved)
        val upgraded = recorder.record(reading("k3", 72.0, impedance = 500, impedanceLow = 560, upgrade = true))
        assertTrue(upgraded is RecordOutcome.Upgraded)
        val m = store.measurements.value.single()
        assertEquals(500, m.impedance)
        assertEquals(560, m.impedanceLow)
        assertEquals(MeasurementSource.SCALE, m.source)
        assertTrue(recorder.record(reading("k3", 72.0, impedance = 500, impedanceLow = 560)) is RecordOutcome.Duplicate)
        assertEquals(1, store.measurements.value.size)
        assertFalse(recorder.hasLegacyMiScaleRows())
    }
}
