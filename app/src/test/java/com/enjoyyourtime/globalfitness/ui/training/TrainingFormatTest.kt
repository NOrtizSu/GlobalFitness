package com.enjoyyourtime.globalfitness.ui.training

import com.enjoyyourtime.globalfitness.training.model.ExerciseKind
import com.enjoyyourtime.globalfitness.training.model.LoadUnit
import com.enjoyyourtime.globalfitness.training.model.WorkoutSet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class TrainingFormatTest {
    private val es = Locale.forLanguageTag("es-ES")
    private val en = Locale.US
    private val de = Locale.GERMANY

    private fun value(input: NumberInput): Double = (input as NumberInput.Value).value

    @Test
    fun loadsParseWithEitherDecimalSeparatorAndLocaleDigits() {
        assertEquals(82.5, value(TrainingFormat.parseLoad("82,5", LoadUnit.KG)), 1e-9)
        assertEquals(82.5, value(TrainingFormat.parseLoad("82.5", LoadUnit.KG)), 1e-9)
        assertEquals(82.5, value(TrainingFormat.parseLoad(" 82,5 ", LoadUnit.KG)), 1e-9)
        // Arabic-Indic digits with the Arabic decimal separator.
        assertEquals(82.5, value(TrainingFormat.parseLoad("٨٢٫٥", LoadUnit.KG)), 1e-9)
        assertEquals(NumberInput.Empty, TrainingFormat.parseLoad("", LoadUnit.KG))
        assertEquals(NumberInput.Invalid, TrainingFormat.parseLoad("8,2,5", LoadUnit.KG))
        assertEquals(NumberInput.Invalid, TrainingFormat.parseLoad(",", LoadUnit.KG))
        assertEquals(NumberInput.Invalid, TrainingFormat.parseLoad("1200", LoadUnit.KG))
    }

    @Test
    fun poundsAreStoredAsKilograms() {
        val kg = value(TrainingFormat.parseLoad("185", LoadUnit.LB))
        assertEquals(83.9146, kg, 1e-4)
        // Shown back exactly as typed, not as 184,99.
        assertEquals("185", TrainingFormat.load(kg, LoadUnit.LB, en))
        assertEquals("83.91", TrainingFormat.load(kg, LoadUnit.KG, en))
        assertTrue(TrainingFormat.sameLoad("185", kg, LoadUnit.LB))
        assertFalse(TrainingFormat.sameLoad("186", kg, LoadUnit.LB))
    }

    @Test
    fun loadsFormatWithTheLocaleSeparatorAndNoTrailingZeros() {
        assertEquals("82,5", TrainingFormat.load(82.5, LoadUnit.KG, es))
        assertEquals("82.5", TrainingFormat.load(82.5, LoadUnit.KG, en))
        assertEquals("80", TrainingFormat.load(80.0, LoadUnit.KG, de))
        assertEquals("1,25", TrainingFormat.load(1.25, LoadUnit.KG, de))
        // No grouping: a 1000 lb leg press is not "1.000".
        assertEquals("1000", TrainingFormat.load(LoadUnit.LB.toKg(1000.0), LoadUnit.LB, de))
    }

    @Test
    fun typingKeepsTheStoredValueWhileItStillMeansIt() {
        // "80," while typing "80,5" still means 80 kg: the cell must not be reset to "80".
        assertTrue(TrainingFormat.sameLoad("80,", 80.0, LoadUnit.KG))
        assertTrue(TrainingFormat.sameLoad("", null, LoadUnit.KG))
        assertFalse(TrainingFormat.sameLoad("", 80.0, LoadUnit.KG))
    }

    @Test
    fun countsAreWholeAndBounded() {
        assertEquals(12.0, value(TrainingFormat.parseCount("12", TrainingFormat.MAX_REPS)), 0.0)
        assertEquals(NumberInput.Invalid, TrainingFormat.parseCount("12,5", TrainingFormat.MAX_REPS))
        assertEquals(NumberInput.Invalid, TrainingFormat.parseCount("1000", TrainingFormat.MAX_REPS))
        assertEquals(5_250.0, value(TrainingFormat.parseDistanceKm("5,25")), 1e-9)
    }

    @Test
    fun durationDigitsReadFromTheRight() {
        assertNull(TrainingFormat.digitsToSeconds(""))
        assertEquals(45, TrainingFormat.digitsToSeconds("45"))
        assertEquals(90, TrainingFormat.digitsToSeconds("130"))
        assertEquals(5_400, TrainingFormat.digitsToSeconds("13000"))
        assertEquals(90, TrainingFormat.digitsToSeconds("0:01:30"))
        // A group above 59 while typing still counts its seconds.
        assertEquals(75, TrainingFormat.digitsToSeconds("75"))
        // Six digits hold at most 99:59:59, and what is stored is what the cell shows back.
        assertEquals(TrainingFormat.MAX_DURATION_SEC, TrainingFormat.digitsToSeconds("999999"))
        assertEquals("995959", TrainingFormat.secondsToDigits(TrainingFormat.digitsToSeconds("999999")))
        assertEquals("130", TrainingFormat.secondsToDigits(90))
        assertEquals("13000", TrainingFormat.secondsToDigits(5_400))
        assertEquals("", TrainingFormat.secondsToDigits(null))
    }

    @Test
    fun durationMaskShowsMinutesAndSeconds() {
        assertEquals("", TrainingFormat.maskDuration("", en))
        assertEquals("0:05", TrainingFormat.maskDuration("5", en))
        assertEquals("0:45", TrainingFormat.maskDuration("45", en))
        assertEquals("1:30", TrainingFormat.maskDuration("130", en))
        assertEquals("13:05", TrainingFormat.maskDuration("1305", en))
        assertEquals("1:30:00", TrainingFormat.maskDuration("13000", en))
        // Typing after a full mask: the digits shift left.
        assertEquals("13:05", TrainingFormat.maskDuration(TrainingFormat.digitsOnly("1:305"), en))
        // Deleting the last digit of "0:05" empties the cell.
        assertEquals("", TrainingFormat.maskDuration(TrainingFormat.digitsOnly("0:0"), en))
        assertEquals("1:02:03", TrainingFormat.duration(3_723, en))
        assertEquals("0:45", TrainingFormat.duration(45, es))
    }

    @Test
    fun previousTextDependsOnTheKind() {
        val unit = LoadUnit.KG
        assertEquals("80 × 8", TrainingFormat.previous(WorkoutSet("a", weightKg = 80.0, reps = 8), ExerciseKind.WEIGHT_REPS, unit, en))
        assertEquals("82,5 × 8", TrainingFormat.previous(WorkoutSet("a", weightKg = 82.5, reps = 8), ExerciseKind.WEIGHT_REPS, unit, es))
        assertEquals("12", TrainingFormat.previous(WorkoutSet("a", reps = 12), ExerciseKind.BODYWEIGHT_REPS, unit, en))
        assertEquals("+10 × 8", TrainingFormat.previous(WorkoutSet("a", weightKg = 10.0, reps = 8), ExerciseKind.BODYWEIGHT_REPS, unit, en))
        // Assisted: the assistance, as typed in its column.
        assertEquals("30 × 8", TrainingFormat.previous(WorkoutSet("a", weightKg = 30.0, reps = 8), ExerciseKind.ASSISTED_REPS, unit, en))
        assertEquals("1:30", TrainingFormat.previous(WorkoutSet("a", durationSec = 90), ExerciseKind.DURATION, unit, en))
        assertEquals("20 × 1:30", TrainingFormat.previous(WorkoutSet("a", weightKg = 20.0, durationSec = 90), ExerciseKind.DURATION, unit, en))
        assertEquals("30:00 · 5", TrainingFormat.previous(WorkoutSet("a", durationSec = 1_800, distanceM = 5_000.0), ExerciseKind.CARDIO, unit, en))
        assertNull(TrainingFormat.previous(WorkoutSet("a"), ExerciseKind.WEIGHT_REPS, unit, en))
        // In pounds.
        assertEquals("185 × 5", TrainingFormat.previous(WorkoutSet("a", weightKg = LoadUnit.LB.toKg(185.0), reps = 5), ExerciseKind.WEIGHT_REPS, LoadUnit.LB, en))
    }

    @Test
    fun setsSummaryGroupsConsecutiveSetsWithTheSameLoad() {
        val kg = { s: String -> "$s kg" }
        val km = { s: String -> "$s km" }
        fun summary(kind: ExerciseKind, vararg sets: WorkoutSet) =
            TrainingFormat.setsSummary(sets.toList(), kind, LoadUnit.KG, en, kg, km)
        assertEquals(
            "80 kg × 8, 8, 7 · 85 kg × 5",
            summary(
                ExerciseKind.WEIGHT_REPS,
                WorkoutSet("1", weightKg = 80.0, reps = 8),
                WorkoutSet("2", weightKg = 80.0, reps = 8),
                WorkoutSet("3", weightKg = 80.0, reps = 7),
                WorkoutSet("4", weightKg = 85.0, reps = 5),
            ),
        )
        assertEquals("12, 10", summary(ExerciseKind.BODYWEIGHT_REPS, WorkoutSet("1", reps = 12), WorkoutSet("2", reps = 10)))
        assertEquals("+10 kg × 8", summary(ExerciseKind.BODYWEIGHT_REPS, WorkoutSet("1", weightKg = 10.0, reps = 8)))
        assertEquals("−30 kg × 8, 7", summary(ExerciseKind.ASSISTED_REPS, WorkoutSet("1", weightKg = 30.0, reps = 8), WorkoutSet("2", weightKg = 30.0, reps = 7)))
        assertEquals("1:00, 0:45", summary(ExerciseKind.DURATION, WorkoutSet("1", durationSec = 60), WorkoutSet("2", durationSec = 45)))
        assertEquals("30:00 · 5 km", summary(ExerciseKind.CARDIO, WorkoutSet("1", durationSec = 1_800, distanceM = 5_000.0)))
        assertEquals("", summary(ExerciseKind.WEIGHT_REPS))
    }

    @Test
    fun searchIgnoresAccentsAndCase() {
        val haystack = TrainingFormat.normalize("Jalón al pecho Polea Dorsales Bíceps")
        assertTrue(TrainingFormat.matches(TrainingFormat.searchTokens("jalon"), haystack))
        assertTrue(TrainingFormat.matches(TrainingFormat.searchTokens("BICEPS polea"), haystack))
        assertTrue(TrainingFormat.matches(TrainingFormat.searchTokens("  "), haystack))
        assertFalse(TrainingFormat.matches(TrainingFormat.searchTokens("jalon remo"), haystack))
        assertEquals(listOf("press", "banca"), TrainingFormat.searchTokens("Press, banca"))
        assertEquals("cuadriceps", TrainingFormat.normalize("Cuádriceps"))
    }
}
