package com.enjoyyourtime.globalfitness.health

import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.BasalMetabolicRateRecord
import androidx.health.connect.client.records.BodyFatRecord
import androidx.health.connect.client.records.BodyWaterMassRecord
import androidx.health.connect.client.records.BoneMassRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.HeartRateVariabilityRmssdRecord
import androidx.health.connect.client.records.LeanBodyMassRecord
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.RespiratoryRateRecord
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.records.WeightRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.reflect.KClass

class HealthPermissionsTest {

    private fun readOf(vararg types: HealthDataType) = types.mapNotNull { it.readPermission }.toSet()
    private fun writeOf(vararg types: HealthDataType) = types.mapNotNull { it.writePermission }.toSet()

    @Test
    fun permissionStringsMatchTheHealthConnectLibrary() {
        val reads: Map<HealthDataType, KClass<out Record>> = mapOf(
            HealthDataType.STEPS to StepsRecord::class,
            HealthDataType.DISTANCE to DistanceRecord::class,
            HealthDataType.ACTIVE_CALORIES to ActiveCaloriesBurnedRecord::class,
            HealthDataType.TOTAL_CALORIES to TotalCaloriesBurnedRecord::class,
            HealthDataType.HEART_RATE to HeartRateRecord::class,
            HealthDataType.RESTING_HEART_RATE to RestingHeartRateRecord::class,
            HealthDataType.HEART_RATE_VARIABILITY to HeartRateVariabilityRmssdRecord::class,
            HealthDataType.SLEEP to SleepSessionRecord::class,
            HealthDataType.OXYGEN_SATURATION to OxygenSaturationRecord::class,
            HealthDataType.EXERCISE to ExerciseSessionRecord::class,
            HealthDataType.RESPIRATORY_RATE to RespiratoryRateRecord::class,
            HealthDataType.WEIGHT to WeightRecord::class,
            HealthDataType.BODY_FAT to BodyFatRecord::class,
        )
        reads.forEach { (type, record) -> assertEquals(type.name, HealthPermission.getReadPermission(record), type.readPermission) }

        val writes: Map<HealthDataType, KClass<out Record>> = mapOf(
            HealthDataType.WEIGHT to WeightRecord::class,
            HealthDataType.BODY_FAT to BodyFatRecord::class,
            HealthDataType.BONE_MASS to BoneMassRecord::class,
            HealthDataType.LEAN_BODY_MASS to LeanBodyMassRecord::class,
            HealthDataType.BASAL_METABOLIC_RATE to BasalMetabolicRateRecord::class,
            HealthDataType.BODY_WATER_MASS to BodyWaterMassRecord::class,
        )
        writes.forEach { (type, record) -> assertEquals(type.name, HealthPermission.getWritePermission(record), type.writePermission) }
        assertEquals(HealthPermission.PERMISSION_READ_HEALTH_DATA_HISTORY, HealthPermissions.READ_HISTORY)
    }

    @Test
    fun requestedSetHas13ReadsAnd6WritesPlusOptionalHistory() {
        assertEquals(13, HealthPermissions.readPermissions.size)
        assertEquals(6, HealthPermissions.writePermissions.size)
        assertEquals(19, HealthPermissions.requested(historyAvailable = false).size)
        val withHistory = HealthPermissions.requested(historyAvailable = true)
        assertEquals(20, withHistory.size)
        assertTrue(HealthPermissions.READ_HISTORY in withHistory)
        assertFalse(HealthPermissions.READ_HISTORY in HealthPermissions.requested(historyAvailable = false))
    }

    @Test
    fun partialGrantEnablesOnlyTheGrantedTypes() {
        val granted = readOf(HealthDataType.STEPS, HealthDataType.SLEEP) + writeOf(HealthDataType.BODY_FAT)
        assertEquals(setOf(HealthDataType.STEPS, HealthDataType.SLEEP), HealthPermissions.readableTypes(granted))
        assertEquals(setOf(HealthDataType.BODY_FAT), HealthPermissions.writableTypes(granted))
        assertTrue(HealthPermissions.hasAnyWearableRead(granted))
        // Body fat alone is not enough to export: weight is required.
        assertFalse(HealthPermissions.canExport(granted))
        assertTrue(HealthPermissions.canExport(granted + writeOf(HealthDataType.WEIGHT)))
        assertTrue(HealthDataType.HEART_RATE in HealthPermissions.missingWearableTypes(granted))
        assertFalse(HealthDataType.STEPS in HealthPermissions.missingWearableTypes(granted))
    }

    @Test
    fun readingOnlyWeightDoesNotCountAsWearableData() {
        val granted = readOf(HealthDataType.WEIGHT, HealthDataType.BODY_FAT)
        assertFalse(HealthPermissions.hasAnyWearableRead(granted))
        assertEquals(HealthPermissions.wearableTypes.size, HealthPermissions.missingWearableTypes(granted).size)
    }

    @Test
    fun importWindowDependsOnHistoryPermission() {
        assertEquals(30, HealthPermissions.importWindowDays(emptySet()))
        assertEquals(365, HealthPermissions.importWindowDays(setOf(HealthPermissions.READ_HISTORY)))
    }

    @Test
    fun missingAndAllGranted() {
        val all = HealthPermissions.requested(historyAvailable = false)
        assertTrue(HealthPermissions.allGranted(all, historyAvailable = false))
        assertFalse(HealthPermissions.allGranted(all, historyAvailable = true))
        assertEquals(setOf(HealthPermissions.READ_HISTORY), HealthPermissions.missing(all, historyAvailable = true))
        // Extra unrelated grants do not matter.
        assertTrue(HealthPermissions.allGranted(all + "android.permission.health.READ_NUTRITION", historyAvailable = false))
    }

    @Test
    fun exportSwitchDefaultsOnOnlyWithPermission() {
        assertTrue(HealthSettings.exportEnabled(choice = null, canExport = true))
        assertFalse(HealthSettings.exportEnabled(choice = null, canExport = false))
        assertFalse(HealthSettings.exportEnabled(choice = false, canExport = true))
        assertFalse(HealthSettings.exportEnabled(choice = true, canExport = false))
    }
}
