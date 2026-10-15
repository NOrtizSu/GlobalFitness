package com.enjoyyourtime.globalfitness.domain

import com.enjoyyourtime.globalfitness.data.Measurement
import com.enjoyyourtime.globalfitness.data.MeasurementSource
import com.enjoyyourtime.globalfitness.data.Profile
import com.enjoyyourtime.globalfitness.data.Sex
import com.enjoyyourtime.globalfitness.scale.CompositionSource
import com.enjoyyourtime.globalfitness.scale.DeviceComposition
import com.enjoyyourtime.globalfitness.scale.FormulaLib
import com.enjoyyourtime.globalfitness.scale.libs.FormulaLibs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

class BodyMetricsTest {

    private val tol = 0.05

    @Test
    fun maleReferenceVector() {
        val c = BodyMetrics.compute(Sex.MALE, heightCm = 175.0, age = 30, weightKg = 75.0, impedance = 500)
        assertEquals(24.49, c.bmi, tol)
        assertEquals(22.48, c.fatPercent!!, tol)
        assertEquals(53.18, c.waterPercent!!, tol)
        assertEquals(2.96, c.boneKg!!, tol)
        assertEquals(55.18, c.muscleKg!!, tol)
        assertEquals(1600.2, c.bmr, tol)
        assertEquals(12.16, c.visceralFat, tol)
        assertEquals(30.59, c.metabolicAge!!, tol)
        assertEquals(20.39, c.proteinPercent!!, tol)
        assertEquals(75.0 * 22.48 / 100, c.fatMassKg!!, tol)
        assertEquals((175 - 80) * 0.7, c.idealWeightKg, 1e-9)
        assertEquals(18.5 * 1.75 * 1.75, c.healthyWeightMinKg, 1e-9)
        assertEquals(24.9 * 1.75 * 1.75, c.healthyWeightMaxKg, 1e-9)
    }

    @Test
    fun fatMuscleAndBoneSumToWeight() {
        val c = BodyMetrics.compute(Sex.MALE, 175.0, 30, 75.0, 500)
        assertEquals(75.0, c.fatMassKg!! + c.muscleKg!! + c.boneKg!!, 1e-6)
    }

    @Test
    fun femaleSanityWithinClamps() {
        for ((h, w, age, imp) in listOf(
            Quad(165.0, 60.0, 35, 520),
            Quad(158.0, 48.0, 22, 610),
            Quad(170.0, 82.0, 55, 450),
            Quad(150.0, 95.0, 70, 380),
        )) {
            val c = BodyMetrics.compute(Sex.FEMALE, h, age, w, imp)
            assertTrue(c.fatPercent!! in 5.0..75.0)
            assertTrue(c.waterPercent!! in 35.0..75.0)
            assertTrue(c.boneKg!! in 0.5..8.0)
            assertTrue(c.muscleKg!! in 10.0..120.0)
            assertTrue(c.proteinPercent!! in 5.0..32.0)
            assertTrue(c.visceralFat in 1.0..50.0)
            assertTrue(c.bmr in 500.0..10000.0)
            assertTrue(c.metabolicAge!! in 15.0..80.0)
            assertEquals((h - 70) * 0.6, c.idealWeightKg, 1e-9)
        }
        val c = BodyMetrics.compute(Sex.FEMALE, 165.0, 35, 60.0, 520)
        assertEquals(30.99, c.fatPercent!!, tol)
        assertEquals(49.27, c.waterPercent!!, tol)
    }

    @Test
    fun weightOnlyHasNoImpedanceMetrics() {
        val c = BodyMetrics.compute(Sex.MALE, 175.0, 30, 75.0, null)
        assertNull(c.fatPercent)
        assertNull(c.fatMassKg)
        assertNull(c.waterPercent)
        assertNull(c.boneKg)
        assertNull(c.muscleKg)
        assertNull(c.metabolicAge)
        assertNull(c.proteinPercent)
        assertNull(Classifier.bodyType(c))
        assertEquals(24.49, c.bmi, tol)
        assertEquals(1600.2, c.bmr, tol)
        assertEquals(12.16, c.visceralFat, tol)
    }

    @Test
    fun classificationsForReferenceVector() {
        val c = BodyMetrics.compute(Sex.MALE, 175.0, 30, 75.0, 500)
        assertEquals(Classification("Normal", Status.GOOD), Metric.BMI.classify(c))
        assertEquals(Classification("Alto", Status.WARNING), Metric.FAT_PERCENT.classify(c))
        assertEquals(Classification("Normal", Status.GOOD), Metric.MUSCLE.classify(c))
        assertEquals(Classification("Bajo", Status.WARNING), Metric.WATER.classify(c))
        assertEquals(Classification("Normal", Status.GOOD), Metric.BONE.classify(c))
        assertEquals(Classification("Alto", Status.WARNING), Metric.VISCERAL.classify(c))
        // 1600.2 kcal < 75 kg * 21.6 (male, 29–49 years)
        assertEquals(Classification("Bajo", Status.WARNING), Metric.BMR.classify(c))
        assertEquals(Classification("Excelente", Status.GOOD), Metric.PROTEIN.classify(c))
        assertEquals(Classification("Mayor que tu edad", Status.WARNING), Metric.METABOLIC_AGE.classify(c))
        val type = Classifier.bodyType(c)
        assertNotNull(type)
        assertEquals(1, type!!.index)
        assertEquals("Sobrepeso", type.name)
    }

    @Test
    fun classifierBands() {
        assertEquals("Bajo", Classifier.bmi(18.4).label)
        assertEquals("Obesidad", Classifier.bmi(30.0).label)
        assertEquals("Muy bajo", Classifier.fat(10.0, Sex.MALE, 30).label)
        assertEquals("Muy alto", Classifier.fat(42.0, Sex.FEMALE, 65).label)
        assertEquals("Normal", Classifier.fat(30.0, Sex.FEMALE, 16).label) // 16–18 row: [20,28,37,41]
        assertEquals("Excelente", Classifier.muscle(60.0, Sex.MALE, 180.0).label)
        assertEquals("Bajo", Classifier.muscle(30.0, Sex.FEMALE, 155.0).label)
        assertEquals("Alto", Classifier.water(62.0, Sex.FEMALE).label)
        assertEquals("Muy alto", Classifier.visceral(15.0).label)
        assertEquals("Bajo", Classifier.bmr(1200.0, 70.0, Sex.MALE, 25).label)
        assertEquals("Excelente", Classifier.protein(21.0).label)
        assertEquals("Más joven", Classifier.metabolicAge(30.0, 30).label)
        assertEquals("Delgado musculoso", Classifier.bodyTypeByIndex(8).name)
    }

    @Test
    fun fatThresholdsForMinors() {
        assertEquals(listOf(12.0, 21.0, 30.0, 34.0), Classifier.fatThresholds(Sex.FEMALE, 10).toList())
        assertEquals(listOf(15.0, 24.0, 33.0, 37.0), Classifier.fatThresholds(Sex.FEMALE, 12).toList())
        assertEquals(listOf(18.0, 27.0, 36.0, 40.0), Classifier.fatThresholds(Sex.FEMALE, 15).toList())
        assertEquals(listOf(20.0, 28.0, 37.0, 41.0), Classifier.fatThresholds(Sex.FEMALE, 17).toList())
        for (age in listOf(8, 13, 15, 17)) {
            assertEquals(listOf(7.0, 16.0, 25.0, 30.0), Classifier.fatThresholds(Sex.MALE, age).toList())
        }
        assertEquals(listOf(21.0, 28.0, 35.0, 40.0), Classifier.fatThresholds(Sex.FEMALE, 18).toList())
        assertEquals(listOf(11.0, 17.0, 22.0, 27.0), Classifier.fatThresholds(Sex.MALE, 18).toList())
        assertEquals("Muy bajo", Classifier.fat(6.0, Sex.MALE, 10).label)
        assertEquals("Alto", Classifier.fat(26.0, Sex.MALE, 15).label)
    }

    @Test
    fun boneBandIsReferencePlusMinusOneKiloWithInclusiveWeightBoundaries() {
        assertEquals(3.2, Classifier.boneReference(Sex.MALE, 75.0), 1e-9)
        assertEquals(2.9, Classifier.boneReference(Sex.MALE, 74.9), 1e-9)
        assertEquals(2.9, Classifier.boneReference(Sex.MALE, 60.0), 1e-9)
        assertEquals(2.5, Classifier.boneReference(Sex.MALE, 59.9), 1e-9)
        assertEquals(2.5, Classifier.boneReference(Sex.FEMALE, 60.0), 1e-9)
        assertEquals(2.2, Classifier.boneReference(Sex.FEMALE, 45.0), 1e-9)
        assertEquals(1.8, Classifier.boneReference(Sex.FEMALE, 44.9), 1e-9)
        val r = Classifier.boneRange(Sex.MALE, 80.0)
        assertEquals(2.2, r.start, 1e-9)
        assertEquals(4.2, r.endInclusive, 1e-9)
        assertEquals("Bajo", Classifier.bone(2.1, Sex.MALE, 80.0).label)
        assertEquals("Normal", Classifier.bone(2.2, Sex.MALE, 80.0).label)
        assertEquals("Normal", Classifier.bone(4.2, Sex.MALE, 80.0).label)
        assertEquals("Alto", Classifier.bone(4.3, Sex.MALE, 80.0).label)
    }

    @Test
    fun ageIsFullYearsAtMeasurementDate() {
        val p = Profile(1, "A", Sex.MALE, LocalDate.of(1996, 10, 16), 175.0, null, 0, 0)
        val dayBefore = LocalDate.of(2026, 10, 15).atTime(12, 0).toInstant(ZoneOffset.UTC).toEpochMilli()
        val birthday = LocalDate.of(2026, 10, 16).atTime(12, 0).toInstant(ZoneOffset.UTC).toEpochMilli()
        assertEquals(29, p.ageAt(dayBefore, ZoneOffset.UTC))
        assertEquals(30, p.ageAt(birthday, ZoneOffset.UTC))
    }

    /** Skeletal muscle is its own metric: never taken as total muscle, never used for protein. */
    @Test
    fun skeletalMuscleIsReportedSeparatelyFromTotalMuscle() {
        val c = BodyMetrics.compute(Sex.MALE, 180.0, 30, 80.0, 500, FormulaLib.ONE_BYONE_NEW, null)
        assertEquals(40.57, c.skeletalMusclePercent!!, 0.1)
        assertEquals(15.96, c.proteinPercent!!, 0.1) // the library's own protein estimate
        val skeletalKg = 80.0 * c.skeletalMusclePercent!! / 100.0
        assertTrue("total ${c.muscleKg} vs skeletal $skeletalKg", c.muscleKg!! > skeletalKg + 10.0)

        val mi = BodyMetrics.compute(Sex.MALE, 180.0, 30, 80.0, 500)
        assertNull(mi.skeletalMusclePercent)

        // Its own metric: % of body weight against the skeletal muscle bands (M 33.3–39.3).
        assertEquals(c.skeletalMusclePercent!!, Metric.SKELETAL_MUSCLE.extract(c)!!, 0.0)
        assertEquals(Classification("Alto", Status.GOOD), Metric.SKELETAL_MUSCLE.classify(c))
        assertEquals(33.3..39.3, Metric.SKELETAL_MUSCLE.normalRange(c))
        assertNull(Metric.SKELETAL_MUSCLE.classify(mi))
    }

    /** A status chip never shows for a value that is missing (e.g. protein or muscle a library does not give). */
    @Test
    fun noStatusWithoutAValue() {
        for (lib in FormulaLib.entries + null) {
            for (impedance in listOf(null, 500)) {
                val c = BodyMetrics.compute(Sex.FEMALE, 165.0, 40, 62.0, impedance, lib, null)
                for (metric in Metric.entries) {
                    if (metric.extract(c) == null) assertNull("$lib $impedance $metric", metric.classify(c))
                }
            }
        }
    }

    @Test
    fun s400UsesBothImpedancesWhenTheLowOneIsStored() {
        val dual = BodyMetrics.compute(Sex.MALE, 172.0, 27, 76.5, 365, FormulaLib.S400, null, impedanceLow = 402)
        assertEquals(14.1, dual.fatPercent!!, 0.2)
        // Rows without the low-frequency value still get a composition (standard model).
        val single = BodyMetrics.compute(Sex.MALE, 172.0, 27, 76.5, 365, FormulaLib.S400, null)
        assertTrue("fat ${single.fatPercent}", single.fatPercent!! in 3.0..60.0)
    }

    @Test
    fun implausibleImpedanceGivesNoLibraryComposition() {
        for (imp in listOf(100, 2_000)) {
            val c = BodyMetrics.compute(Sex.MALE, 180.0, 35, 80.0, imp, FormulaLib.STANDARD_IMPEDANCE, null)
            assertNull("impedance $imp", c.fatPercent)
            assertEquals(imp, c.impedance)
        }
        assertNotNull(BodyMetrics.compute(Sex.MALE, 180.0, 35, 80.0, 500, FormulaLib.STANDARD_IMPEDANCE, null).fatPercent)
    }

    @Test
    fun recomputableLibraryValuesFollowTheCurrentProfile() {
        val stored = DeviceComposition(fatPercent = 30.0, waterPercent = 50.0)
        val m = Measurement(
            1, 1, 0L, 80.0, 500, "k", MeasurementSource.SCALE,
            composition = stored,
            compositionSource = CompositionSource.Formula(FormulaLib.STANDARD_IMPEDANCE),
            formulaLib = FormulaLib.STANDARD_IMPEDANCE,
            deviceDriver = "okok_broadcast",
        )
        val expected = FormulaLibs.compute(FormulaLib.STANDARD_IMPEDANCE, true, 35, 180.0, 80.0, 500)!!.fatPercent!!
        assertEquals(expected, BodyMetrics.forMeasurement(Sex.MALE, 180.0, 35, m).fatPercent!!, 1e-9)
        // Values measured by the scale itself always win.
        val device = m.copy(compositionSource = CompositionSource.Device)
        assertEquals(30.0, BodyMetrics.forMeasurement(Sex.MALE, 180.0, 35, device).fatPercent!!, 1e-9)
    }

    private data class Quad(val h: Double, val w: Double, val age: Int, val imp: Int)
}
