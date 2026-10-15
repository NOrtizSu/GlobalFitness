package com.enjoyyourtime.globalfitness.health

import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.SleepSessionRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Modifier

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
    fun everyExerciseTypeOfTheLibraryHasASpanishName() {
        val constants = ExerciseSessionRecord::class.java.fields
            .filter { Modifier.isStatic(it.modifiers) && it.name.startsWith("EXERCISE_TYPE_") && it.type == Int::class.javaPrimitiveType }
            .associate { it.name to it.getInt(null) }
        assertTrue("expected the library's exercise constants", constants.size > 50)
        constants.forEach { (name, code) ->
            assertTrue("$name ($code) has no Spanish name", code in HealthMappings.knownExerciseTypes)
            assertNotEquals(name, "Ejercicio", HealthMappings.exerciseName(code))
        }
    }

    @Test
    fun exerciseNamesAreSpanish() {
        assertEquals("Carrera", HealthMappings.exerciseName(ExerciseSessionRecord.EXERCISE_TYPE_RUNNING))
        assertEquals("Caminata", HealthMappings.exerciseName(ExerciseSessionRecord.EXERCISE_TYPE_WALKING))
        assertEquals("Ciclismo", HealthMappings.exerciseName(ExerciseSessionRecord.EXERCISE_TYPE_BIKING))
        assertEquals("Natación en piscina", HealthMappings.exerciseName(ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_POOL))
        assertEquals("Entrenamiento de fuerza", HealthMappings.exerciseName(ExerciseSessionRecord.EXERCISE_TYPE_STRENGTH_TRAINING))
        assertEquals("Ejercicio", HealthMappings.exerciseName(12345))
    }

    @Test
    fun originNames() {
        val own = "com.enjoyyourtime.globalfitness"
        assertEquals("Mi Fitness", HealthMappings.originName("com.xiaomi.wearable", own))
        assertEquals("Zepp Life", HealthMappings.originName("com.xiaomi.hm.health", own))
        assertEquals("GlobalFitness", HealthMappings.originName(own, own))
        assertEquals("Desconocido", HealthMappings.originName("", own))
        assertEquals("Mi Reloj", HealthMappings.originName("com.example.watch", own) { "Mi Reloj" })
        assertEquals("com.example.watch", HealthMappings.originName("com.example.watch", own) { null })
        assertEquals("com.example.watch", HealthMappings.originName("com.example.watch", own) { " " })
    }
}
