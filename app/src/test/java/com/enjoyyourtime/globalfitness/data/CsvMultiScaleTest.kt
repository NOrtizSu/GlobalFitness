package com.enjoyyourtime.globalfitness.data

import com.enjoyyourtime.globalfitness.domain.BodyMetrics
import com.enjoyyourtime.globalfitness.scale.CompositionSource
import com.enjoyyourtime.globalfitness.scale.DeviceComposition
import com.enjoyyourtime.globalfitness.scale.FormulaLib
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class CsvMultiScaleTest {
    private val zone = ZoneId.of("Europe/Madrid")
    private val profile = Profile(1, "Ana", Sex.FEMALE, LocalDate.of(1990, 5, 2), 165.5, null, 0, 0)

    @Test
    fun deviceCompositionAndProvenanceRoundTrip() {
        val m = Measurement(
            id = 1,
            profileId = 1,
            timestamp = 1_760_510_445_000,
            weightKg = 61.35,
            impedance = null,
            scaleKey = "beurer|AA:BB|1760510445|61350",
            source = MeasurementSource.SCALE,
            composition = DeviceComposition(fatPercent = 24.5, waterPercent = 55.1, musclePercent = 38.2, boneKg = 2.4, bmrKcal = 1320.0),
            compositionSource = CompositionSource.Device,
            formulaLib = null,
            deviceDriver = "beurer_sanitas",
            deviceAddress = "AA:BB:CC:DD:EE:FF",
        )
        val c = BodyMetrics.forMeasurement(profile.sex, profile.heightCm, 36, m)
        val text = CsvCodec.encode(listOf(CsvExportRow(profile, m, c)), zone)
        val row = CsvCodec.decode(text, zone).rows.single()
        assertEquals(m.composition, row.composition)
        assertEquals(CompositionSource.Device, row.compositionSource)
        assertEquals("beurer_sanitas", row.deviceDriver)
        assertEquals("AA:BB:CC:DD:EE:FF", row.deviceAddress)
        assertNull(row.formulaLib)
    }

    @Test
    fun formulaLibraryIsExportedAndImported() {
        val m = Measurement(2, 1, 1_760_510_445_000, 72.0, 480, "k", MeasurementSource.SCALE,
            compositionSource = CompositionSource.Formula(FormulaLib.MI_SCALE), formulaLib = FormulaLib.MI_SCALE, deviceDriver = "xiaomi_mi_scale_2")
        val row = CsvCodec.decode(CsvCodec.encode(listOf(CsvExportRow(profile, m, null)), zone), zone).rows.single()
        assertEquals(FormulaLib.MI_SCALE, row.formulaLib)
        assertEquals(CompositionSource.Formula(FormulaLib.MI_SCALE), row.compositionSource)
        assertNull(row.composition)
    }

    /** Files exported before multi-scale support (20 columns) still import unchanged. */
    @Test
    fun oldFormatStillImports() {
        val text = "perfil;sexo;fecha_nacimiento;altura_cm;objetivo_kg;fecha;peso_kg;impedancia_ohm;origen;clave_bascula;" +
            "imc;grasa_pct;masa_grasa_kg;musculo_kg;agua_pct;hueso_kg;grasa_visceral;metabolismo_basal_kcal;edad_metabolica;proteina_pct\n" +
            "Ana;F;1990-05-02;165,5;;2025-10-15T08:30:45+02:00;61,350;512;scale;2025-10-15 08:30:45|12270;22,40;30,1;18,5;40,2;50,1;2,3;5,0;1300;35;17,5\n"
        val parsed = CsvCodec.decode(text, zone)
        assertEquals(0, parsed.invalidLines)
        val row = parsed.rows.single()
        assertEquals(512, row.impedance)
        assertEquals("2025-10-15 08:30:45|12270", row.scaleKey)
        // Old computed columns are not mistaken for device values; composition is recomputed (Mi Fit).
        assertNull(row.composition)
        assertNull(row.compositionSource)
        assertNull(row.formulaLib)
    }

    @Test
    fun legacyRowsUseMiFitFormulasAndDeviceValuesWin() {
        val legacy = Measurement(1, 1, 0, 61.35, 512, null, MeasurementSource.SCALE)
        val viaMeasurement = BodyMetrics.forMeasurement(Sex.FEMALE, 165.5, 36, legacy)
        val direct = BodyMetrics.compute(Sex.FEMALE, 165.5, 36, 61.35, 512)
        assertEquals(direct, viaMeasurement)

        val device = legacy.copy(impedance = null, composition = DeviceComposition(fatPercent = 30.0, waterPercent = 50.0, muscleKg = 40.0))
        val c = BodyMetrics.forMeasurement(Sex.FEMALE, 165.5, 36, device)
        assertEquals(30.0, c.fatPercent!!, 1e-9)
        assertEquals(61.35 * 0.30, c.fatMassKg!!, 1e-9)
        assertEquals(40.0, c.muscleKg!!, 1e-9)
        assertEquals(50.0, c.waterPercent!!, 1e-9)
        assertNull(c.boneKg)

        val weightOnly = BodyMetrics.forMeasurement(Sex.FEMALE, 165.5, 36, legacy.copy(impedance = null))
        assertNull(weightOnly.fatPercent)
    }
}
