package com.enjoyyourtime.globalfitness.data

import com.enjoyyourtime.globalfitness.domain.BodyComposition
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

/** One exported row: measurement + owning profile (if any) + computed metrics. */
data class CsvExportRow(
    val profile: Profile?,
    val measurement: Measurement,
    val composition: BodyComposition?,
)

/** One parsed row from an imported CSV. Profile fields are null for pending measurements. */
data class CsvImportRow(
    val profileName: String?,
    val sex: Sex?,
    val birthDate: LocalDate?,
    val heightCm: Double?,
    val targetWeightKg: Double?,
    val timestamp: Long,
    val weightKg: Double,
    val impedance: Int?,
    val source: MeasurementSource,
    val scaleKey: String?,
)

data class CsvParseResult(val rows: List<CsvImportRow>, val invalidLines: Int)

/**
 * CSV format of the app. Semicolon separated with comma decimals (opens directly in a
 * Spanish-locale spreadsheet). The importer also accepts comma-separated files with dot decimals.
 */
object CsvCodec {
    const val COL_PROFILE = "perfil"
    const val COL_SEX = "sexo"
    const val COL_BIRTH = "fecha_nacimiento"
    const val COL_HEIGHT = "altura_cm"
    const val COL_TARGET = "objetivo_kg"
    const val COL_DATE = "fecha"
    const val COL_WEIGHT = "peso_kg"
    const val COL_IMPEDANCE = "impedancia_ohm"
    const val COL_SOURCE = "origen"
    const val COL_KEY = "clave_bascula"

    val HEADER = listOf(
        COL_PROFILE, COL_SEX, COL_BIRTH, COL_HEIGHT, COL_TARGET,
        COL_DATE, COL_WEIGHT, COL_IMPEDANCE, COL_SOURCE, COL_KEY,
        "imc", "grasa_pct", "masa_grasa_kg", "musculo_kg", "agua_pct", "hueso_kg",
        "grasa_visceral", "metabolismo_basal_kcal", "edad_metabolica", "proteina_pct",
    )

    private const val SEP = ';'
    private val ISO = DateTimeFormatter.ISO_OFFSET_DATE_TIME

    fun encode(rows: List<CsvExportRow>, zone: ZoneId = ZoneId.systemDefault()): String = buildString {
        appendLine(HEADER.joinToString(SEP.toString()))
        for (row in rows) {
            val p = row.profile
            val m = row.measurement
            val c = row.composition
            val date = OffsetDateTime.ofInstant(Instant.ofEpochMilli(m.timestamp), zone).truncatedTo(ChronoUnit.SECONDS)
            val fields = listOf(
                p?.name.orEmpty(),
                p?.sex?.code.orEmpty(),
                p?.birthDate?.toString().orEmpty(),
                num(p?.heightCm, 1),
                num(p?.targetWeightKg, 1),
                date.format(ISO),
                num(m.weightKg, 3),
                m.impedance?.toString().orEmpty(),
                m.source.code,
                m.scaleKey.orEmpty(),
                num(c?.bmi, 2),
                num(c?.fatPercent, 2),
                num(c?.fatMassKg, 2),
                num(c?.muscleKg, 2),
                num(c?.waterPercent, 2),
                num(c?.boneKg, 2),
                num(c?.visceralFat, 2),
                num(c?.bmr, 0),
                num(c?.metabolicAge, 0),
                num(c?.proteinPercent, 2),
            )
            appendLine(fields.joinToString(SEP.toString()) { quote(it) })
        }
    }

    fun decode(text: String, zone: ZoneId = ZoneId.systemDefault()): CsvParseResult {
        val lines = text.removePrefix(Char(0xFEFF).toString()).lineSequence().filter { it.isNotBlank() }.toList()
        if (lines.isEmpty()) return CsvParseResult(emptyList(), 0)
        val delimiter = if (lines.first().count { it == ';' } >= lines.first().count { it == ',' }) ';' else ','
        val header = splitLine(lines.first(), delimiter).map { it.trim().lowercase(Locale.ROOT) }
        val idx = header.withIndex().associate { it.value to it.index }
        if (COL_DATE !in idx || COL_WEIGHT !in idx) return CsvParseResult(emptyList(), lines.size - 1)

        val rows = ArrayList<CsvImportRow>()
        var invalid = 0
        for (line in lines.drop(1)) {
            val f = splitLine(line, delimiter)
            fun col(name: String): String? = idx[name]?.let { f.getOrNull(it) }?.trim()?.takeIf { it.isNotEmpty() }
            val timestamp = col(COL_DATE)?.let { parseDate(it, zone) }
            val weight = col(COL_WEIGHT)?.let { parseNumber(it) }
            if (timestamp == null || weight == null || weight <= 0.0) {
                invalid++
                continue
            }
            rows += CsvImportRow(
                profileName = col(COL_PROFILE),
                sex = col(COL_SEX)?.let { Sex.fromCode(it) },
                birthDate = col(COL_BIRTH)?.let { parseLocalDate(it) },
                heightCm = col(COL_HEIGHT)?.let { parseNumber(it) },
                targetWeightKg = col(COL_TARGET)?.let { parseNumber(it) },
                timestamp = timestamp,
                weightKg = weight,
                impedance = col(COL_IMPEDANCE)?.let { parseNumber(it) }?.toInt()?.takeIf { it > 0 },
                source = MeasurementSource.fromCode(col(COL_SOURCE)),
                scaleKey = col(COL_KEY),
            )
        }
        return CsvParseResult(rows, invalid)
    }

    fun parseNumber(s: String): Double? = s.trim().replace(',', '.').toDoubleOrNull()

    /** Local date-time formats besides ISO, including what Spanish Excel writes back. */
    private val DATE_TIME_FORMATS = listOf(
        DateTimeFormatter.ofPattern("yyyy-MM-dd H:mm[:ss]"),
        DateTimeFormatter.ofPattern("d/M/yyyy H:mm[:ss]"),
        DateTimeFormatter.ofPattern("d-M-yyyy H:mm[:ss]"),
    )
    private val DATE_FORMATS = listOf(
        DateTimeFormatter.ofPattern("d/M/yyyy"),
        DateTimeFormatter.ofPattern("d-M-yyyy"),
    )

    /** Measurement date: ISO with offset, ISO local, or "dd/MM/yyyy HH:mm[:ss]" (also d/M/yyyy H:mm). */
    fun parseDate(s: String, zone: ZoneId): Long? {
        val t = s.trim()
        runCatching { return OffsetDateTime.parse(t).toInstant().toEpochMilli() }
        runCatching { return LocalDateTime.parse(t).atZone(zone).toInstant().toEpochMilli() }
        for (f in DATE_TIME_FORMATS) {
            runCatching { return LocalDateTime.parse(t, f).atZone(zone).toInstant().toEpochMilli() }
        }
        return null
    }

    /** Birth date: ISO "yyyy-MM-dd" or "dd/MM/yyyy" / "d/M/yyyy" (Spanish Excel). */
    fun parseLocalDate(s: String): LocalDate? {
        val t = s.trim()
        runCatching { return LocalDate.parse(t) }
        for (f in DATE_FORMATS) {
            runCatching { return LocalDate.parse(t, f) }
        }
        // Excel sometimes keeps a time part on date cells.
        val datePart = t.substringBefore(' ')
        if (datePart != t) return parseLocalDate(datePart)
        return null
    }

    /**
     * Decodes file bytes as strict UTF-8, falling back to Windows-1252 (what Excel uses when it
     * saves a CSV on Windows).
     */
    fun decodeBytes(bytes: ByteArray): String {
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        return try {
            decoder.decode(ByteBuffer.wrap(bytes)).toString()
        } catch (e: CharacterCodingException) {
            String(bytes, Charset.forName("windows-1252"))
        }
    }

    private fun num(v: Double?, decimals: Int): String =
        if (v == null) "" else String.format(Locale.ROOT, "%.${decimals}f", v).replace('.', ',')

    private fun quote(s: String): String =
        if (s.any { it == SEP || it == '"' || it == '\n' || it == '\r' }) {
            "\"" + s.replace("\"", "\"\"") + "\""
        } else {
            s
        }

    /** Splits one CSV line honoring double-quoted fields. */
    fun splitLine(line: String, delimiter: Char): List<String> {
        val out = ArrayList<String>()
        val sb = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < line.length) {
            val ch = line[i]
            when {
                inQuotes && ch == '"' && i + 1 < line.length && line[i + 1] == '"' -> {
                    sb.append('"'); i++
                }
                ch == '"' -> inQuotes = !inQuotes
                ch == delimiter && !inQuotes -> {
                    out += sb.toString(); sb.clear()
                }
                else -> sb.append(ch)
            }
            i++
        }
        out += sb.toString()
        return out
    }
}
