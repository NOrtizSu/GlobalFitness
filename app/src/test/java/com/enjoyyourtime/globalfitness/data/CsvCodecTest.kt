package com.enjoyyourtime.globalfitness.data

import com.enjoyyourtime.globalfitness.domain.BodyMetrics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

class CsvCodecTest {
    private val zone = ZoneId.of("Europe/Madrid")

    @Test
    fun roundTripKeepsMeasurementsAndProfiles() {
        val profile = Profile(1, "Ana; \"la jefa\"", Sex.FEMALE, LocalDate.of(1990, 5, 2), 165.5, 60.0, 0, 0)
        val m1 = Measurement(1, 1, 1_760_510_445_000, 61.35, 512, "2026-10-15 08:30:45|12270", MeasurementSource.SCALE)
        val m2 = Measurement(2, null, 1_760_600_000_000, 80.0, null, null, MeasurementSource.MANUAL)
        val text = CsvCodec.encode(
            listOf(
                CsvExportRow(profile, m1, BodyMetrics.compute(profile.sex, profile.heightCm, 36, m1.weightKg, m1.impedance)),
                CsvExportRow(null, m2, null),
            ),
            zone,
        )
        assertTrue(text.lines().first().startsWith("perfil;sexo;fecha_nacimiento"))
        assertTrue(text.contains("61,350"))

        val parsed = CsvCodec.decode(text, zone)
        assertEquals(0, parsed.invalidLines)
        assertEquals(2, parsed.rows.size)
        val a = parsed.rows[0]
        assertEquals(profile.name, a.profileName)
        assertEquals(Sex.FEMALE, a.sex)
        assertEquals(profile.birthDate, a.birthDate)
        assertEquals(165.5, a.heightCm!!, 1e-9)
        assertEquals(60.0, a.targetWeightKg!!, 1e-9)
        assertEquals(m1.timestamp, a.timestamp)
        assertEquals(61.35, a.weightKg, 1e-9)
        assertEquals(512, a.impedance)
        assertEquals(m1.scaleKey, a.scaleKey)
        assertEquals(MeasurementSource.SCALE, a.source)

        val b = parsed.rows[1]
        assertNull(b.profileName)
        assertNull(b.impedance)
        assertEquals(MeasurementSource.MANUAL, b.source)
        assertEquals(80.0, b.weightKg, 1e-9)
    }

    @Test
    fun acceptsSpanishExcelRoundTripInWindows1252() {
        val text = "perfil;sexo;fecha_nacimiento;altura_cm;objetivo_kg;fecha;peso_kg;impedancia_ohm;origen\r\n" +
            "\u00CD\u00F1igo;M;12/05/1990;178;75;19/06/2026 5:41;81,97;481;scale\r\n" +
            "\u00CD\u00F1igo;M;12/05/1990;178;75;2/7/2026 07:05:30;81,5;;manual\r\n"
        val bytes = text.toByteArray(Charsets.ISO_8859_1) // same bytes as Windows-1252 for these characters
        val decoded = CsvCodec.decodeBytes(bytes)
        assertTrue(decoded.contains("\u00CD\u00F1igo"))
        val parsed = CsvCodec.decode(decoded, zone)
        assertEquals(0, parsed.invalidLines)
        assertEquals(2, parsed.rows.size)
        val a = parsed.rows[0]
        assertEquals("\u00CD\u00F1igo", a.profileName)
        assertEquals(LocalDate.of(1990, 5, 12), a.birthDate)
        assertEquals(LocalDateTime.of(2026, 6, 19, 5, 41).atZone(zone).toInstant().toEpochMilli(), a.timestamp)
        assertEquals(81.97, a.weightKg, 1e-9)
        val b = parsed.rows[1]
        assertEquals(LocalDateTime.of(2026, 7, 2, 7, 5, 30).atZone(zone).toInstant().toEpochMilli(), b.timestamp)
        assertNull(b.impedance)
        assertEquals(MeasurementSource.MANUAL, b.source)
    }

    @Test
    fun decodesUtf8StrictlyAndParsesDateVariants() {
        assertEquals("\u00CD\u00F1igo", CsvCodec.decodeBytes("\u00CD\u00F1igo".toByteArray(Charsets.UTF_8)))
        assertEquals(LocalDate.of(1990, 5, 2), CsvCodec.parseLocalDate("2/5/1990"))
        assertEquals(LocalDate.of(1990, 5, 2), CsvCodec.parseLocalDate("02/05/1990"))
        assertEquals(LocalDate.of(1990, 5, 2), CsvCodec.parseLocalDate("1990-05-02"))
        assertEquals(LocalDate.of(1990, 5, 2), CsvCodec.parseLocalDate("02/05/1990 0:00"))
        assertNull(CsvCodec.parseLocalDate("mayo"))
        assertEquals(
            LocalDateTime.of(2026, 10, 15, 8, 30, 45).atZone(zone).toInstant().toEpochMilli(),
            CsvCodec.parseDate("15/10/2026 08:30:45", zone),
        )
        assertNull(CsvCodec.parseDate("15/10/2026", zone))
    }

    @Test
    fun acceptsCommaSeparatedWithDotDecimals() {
        val text = "fecha,peso_kg,impedancia_ohm,perfil\n2026-10-15T08:30:00+02:00,72.5,500,Luis\nmal,xx,,\n"
        val parsed = CsvCodec.decode(text, zone)
        assertEquals(1, parsed.rows.size)
        assertEquals(1, parsed.invalidLines)
        assertEquals(72.5, parsed.rows[0].weightKg, 1e-9)
        assertEquals("Luis", parsed.rows[0].profileName)
    }
}
