package com.enjoyyourtime.globalfitness.health

import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.SleepSessionRecord
import com.enjoyyourtime.globalfitness.R
import java.lang.reflect.Modifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HealthMappingsTest {

    @Test
    fun sleepStagesMapToFiveCategories() {
        assertEquals(SleepStage.AWAKE, HealthMappings.sleepStage(SleepSessionRecord.STAGE_TYPE_AWAKE))
        assertEquals(SleepStage.AWAKE, HealthMappings.sleepStage(SleepSessionRecord.STAGE_TYPE_AWAKE_IN_BED))
        assertEquals(SleepStage.AWAKE, HealthMappings.sleepStage(SleepSessionRecord.STAGE_TYPE_OUT_OF_BED))
        assertEquals(SleepStage.LIGHT, HealthMappings.sleepStage(SleepSessionRecord.STAGE_TYPE_LIGHT))
        assertEquals(SleepStage.DEEP, HealthMappings.sleepStage(SleepSessionRecord.STAGE_TYPE_DEEP))
        assertEquals(SleepStage.REM, HealthMappings.sleepStage(SleepSessionRecord.STAGE_TYPE_REM))
        assertEquals(SleepStage.UNKNOWN, HealthMappings.sleepStage(SleepSessionRecord.STAGE_TYPE_SLEEPING))
        assertEquals(SleepStage.UNKNOWN, HealthMappings.sleepStage(SleepSessionRecord.STAGE_TYPE_UNKNOWN))
        assertEquals(SleepStage.UNKNOWN, HealthMappings.sleepStage(99))
    }

    @Test
    fun everyExerciseTypeOfTheLibraryHasAName() {
        val constants = ExerciseSessionRecord::class.java.fields
            .filter { Modifier.isStatic(it.modifiers) && it.name.startsWith("EXERCISE_TYPE_") && it.type == Int::class.javaPrimitiveType }
            .associate { it.name to it.getInt(null) }
        assertTrue("expected the library's exercise constants", constants.size > 50)
        constants.forEach { (name, code) ->
            assertTrue("$name ($code) has no name", code in HealthMappings.knownExerciseTypes)
            assertNotEquals(name, R.string.exercise_generic, HealthMappings.exerciseName(code))
        }
    }

    @Test
    fun exerciseNamesAreStringResources() {
        assertEquals(R.string.exercise_running, HealthMappings.exerciseName(ExerciseSessionRecord.EXERCISE_TYPE_RUNNING))
        assertEquals(R.string.exercise_walking, HealthMappings.exerciseName(ExerciseSessionRecord.EXERCISE_TYPE_WALKING))
        assertEquals(R.string.exercise_biking, HealthMappings.exerciseName(ExerciseSessionRecord.EXERCISE_TYPE_BIKING))
        assertEquals(R.string.exercise_swimming_pool, HealthMappings.exerciseName(ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_POOL))
        assertEquals(R.string.exercise_strength_training, HealthMappings.exerciseName(ExerciseSessionRecord.EXERCISE_TYPE_STRENGTH_TRAINING))
        assertEquals(R.string.exercise_generic, HealthMappings.exerciseName(12345))
    }

    @Test
    fun originNames() {
        val own = "com.enjoyyourtime.globalfitness"
        assertEquals("Mi Fitness", HealthMappings.originName("com.xiaomi.wearable", own))
        assertEquals("Zepp Life", HealthMappings.originName("com.xiaomi.hm.health", own))
        assertEquals("GlobalFitness", HealthMappings.originName(own, own))
        assertNull(HealthMappings.originName("", own))
        assertEquals("Mi Reloj", HealthMappings.originName("com.example.watch", own) { "Mi Reloj" })
        assertEquals("com.example.watch", HealthMappings.originName("com.example.watch", own) { null })
        assertEquals("com.example.watch", HealthMappings.originName("com.example.watch", own) { " " })
    }
}
