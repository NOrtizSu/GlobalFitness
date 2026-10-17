package com.enjoyyourtime.globalfitness.wearable

import com.enjoyyourtime.globalfitness.band.BandDbSchema
import com.enjoyyourtime.globalfitness.band.xiaomi.AlarmRepeat
import com.enjoyyourtime.globalfitness.band.xiaomi.AlarmSpec
import com.enjoyyourtime.globalfitness.scale.MapDriverStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WearableStorageTest {
    @Test
    fun configuredWearablesRoundTrip() {
        val list = listOf(
            ConfiguredWearable("id-1", "xiaomi_smart_band_9", "AA:BB:CC:DD:EE:FF", "Xiaomi Smart Band 9 3A7F", mapOf("a" to "b=c&d", "x" to "ñ"), 123L, true),
            ConfiguredWearable("id\t2", "pinetime_infinitime", "11:22:33:44:55:66", "InfiniTime", emptyMap(), 0L, false),
        )
        assertEquals(list, ConfiguredWearableCodec.decode(ConfiguredWearableCodec.encode(list)))
    }

    @Test
    fun codecSkipsBadInput() {
        assertTrue(ConfiguredWearableCodec.decode(null).isEmpty())
        assertTrue(ConfiguredWearableCodec.decode("v1\nwhatever").isEmpty())
        assertTrue(ConfiguredWearableCodec.decode("w1\nonly\tthree\tfields").isEmpty())
        assertEquals(1, ConfiguredWearableCodec.decode("w1\nid\tdrv\tAA\tname\t\t5\t0\n\tdrv\tAA\tname\t\t5\t0").size)
    }

    @Test
    fun driverStoreMapRoundTrip() {
        val map = mapOf("local_alarms" to "1,7,30,1,0,31;2,8,0,0,1,0", "k=1&" to "v;é")
        assertEquals(map, WearableConfigStore.decodeMap(WearableConfigStore.encodeMap(map)))
        assertTrue(WearableConfigStore.decodeMap("broken").isEmpty())
    }

    @Test
    fun localAlarmsAssignSlotsAndPersist() {
        val store = MapDriverStore()
        val alarms = LocalAlarms(store, maxAlarms = 2)
        val first = alarms.put(null, AlarmSpec(7, 30, repeat = AlarmRepeat.Weekly(31)))!!
        val second = alarms.put(null, AlarmSpec(6, 0, smart = true))!!
        assertEquals(1, first.id)
        assertEquals(2, second.id)
        assertNull(alarms.put(null, AlarmSpec(9, 0)))
        // Sorted by time; reloaded from the store.
        assertEquals(listOf(2, 1), LocalAlarms(store, 2).list().map { it.id })
        assertEquals(AlarmRepeat.Weekly(31), LocalAlarms(store, 2).get(1)!!.spec.repeat)
        alarms.put(1, AlarmSpec(7, 45, enabled = false, repeat = AlarmRepeat.Daily))
        assertEquals(AlarmSpec(7, 45, enabled = false, repeat = AlarmRepeat.Daily), alarms.get(1)!!.spec)
        alarms.remove(2)
        assertEquals(listOf(1), alarms.list().map { it.id })
        assertTrue(LocalAlarms.decode("garbage;1,25,0,1,0,0;1,2,3").isEmpty())
    }

    @Test
    fun dayMasks() {
        assertEquals(0, LocalAlarms.dayMask(AlarmRepeat.Once))
        assertEquals(127, LocalAlarms.dayMask(AlarmRepeat.Daily))
        assertEquals(65, LocalAlarms.dayMask(AlarmRepeat.Weekly(65)))
        assertEquals(3, LocalAlarms.dayMask(AlarmRepeat.Other(7, 3)))
        assertEquals(0, LocalAlarms.dayMask(AlarmRepeat.Other(7, null)))
    }

    @Test
    fun bandDbMigrationIsAdditive() {
        assertEquals(2, BandDbSchema.VERSION)
        assertEquals(listOf("ALTER TABLE hr_samples ADD COLUMN driver TEXT"), BandDbSchema.migrations(1, 2))
        assertTrue(BandDbSchema.migrations(2, 2).isEmpty())
        assertTrue(BandDbSchema.CREATE.first().contains("driver TEXT"))
        assertTrue(BandDbSchema.CREATE.first().contains("UNIQUE (timestamp, source)"))
    }
}
