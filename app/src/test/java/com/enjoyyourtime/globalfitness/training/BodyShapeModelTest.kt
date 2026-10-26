package com.enjoyyourtime.globalfitness.training

import com.enjoyyourtime.globalfitness.data.Measurement
import com.enjoyyourtime.globalfitness.data.MeasurementSource
import com.enjoyyourtime.globalfitness.data.Profile
import com.enjoyyourtime.globalfitness.data.Sex
import com.enjoyyourtime.globalfitness.scale.DeviceComposition
import com.enjoyyourtime.globalfitness.training.catalog.ExerciseCatalog
import com.enjoyyourtime.globalfitness.training.data.TrainingRepository
import com.enjoyyourtime.globalfitness.training.domain.BodyShape
import com.enjoyyourtime.globalfitness.training.domain.BodyShapeModel
import com.enjoyyourtime.globalfitness.training.domain.LoadModel
import com.enjoyyourtime.globalfitness.training.domain.MuscleStats
import com.enjoyyourtime.globalfitness.training.model.Exercise
import com.enjoyyourtime.globalfitness.training.model.Workout
import com.enjoyyourtime.globalfitness.training.model.WorkoutEntry
import com.enjoyyourtime.globalfitness.training.model.WorkoutSet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.LocalDate

class BodyShapeModelTest {
    private val day = Duration.ofDays(1).toMillis()
    private val hour = Duration.ofHours(1).toMillis()
    private val now = 1_000 * day
    private val exercises = TrainingRepository.buildExercises(ExerciseCatalog.all, emptyList(), emptyMap())
    private val lookup: (String) -> Exercise = { exercises[it] ?: TrainingRepository.unknownExercise(it) }
    private var ids = 0

    private fun profile(sex: Sex = Sex.MALE, heightCm: Double = 178.0) =
        Profile(1, "Test", sex, LocalDate.of(1990, 1, 1), heightCm, null, 0, 0)

    /** A weighing whose scale reported [fat] % (and [muscleKg] of total muscle when given). */
    private fun weighing(at: Long, fat: Double?, weightKg: Double = 80.0, muscleKg: Double? = null) = Measurement(
        id = (ids++).toLong(),
        profileId = 1,
        timestamp = at,
        weightKg = weightKg,
        impedance = null,
        scaleKey = "k${ids++}",
        source = MeasurementSource.SCALE,
        composition = if (fat == null && muscleKg == null) null else DeviceComposition(fatPercent = fat, muscleKg = muscleKg),
    )

    /** The shape at [time] from these weighings (read with [profile]) and workouts. */
    private fun shapeAt(time: Long, measurements: List<Measurement>, profile: Profile?, workouts: List<Workout>) =
        BodyShapeModel.at(time, BodyShapeModel.readings(measurements, profile), workouts, lookup)

    /** Four done sets of [exerciseId] at [weightKg] × 8, ticked at [at]. */
    private fun entry(exerciseId: String, weightKg: Double, at: Long) =
        WorkoutEntry("e${ids++}", exerciseId, List(4) { WorkoutSet("s${ids++}", weightKg = weightKg, reps = 8, done = true, doneAt = at) })

    /** Push / pull / legs every second day from [from] to [to], with loads going up [kgPerWeek] a week. */
    private fun routine(from: Long, to: Long, kgPerWeek: Double = 1.0): List<Workout> {
        val days = listOf(
            listOf("barbell_bench_press", "machine_shoulder_press", "pec_deck", "rope_triceps_pushdown"),
            listOf("lat_pulldown_machine", "seated_cable_row", "barbell_curl", "face_pull"),
            listOf("barbell_back_squat", "leg_press_machine", "lying_leg_curl_machine", "standing_calf_raise_machine"),
        )
        val result = ArrayList<Workout>()
        var t = from
        var i = 0
        while (t <= to) {
            val weeks = (t - from) / (7.0 * day)
            val entries = days[i % 3].map { id ->
                WorkoutEntry("e${ids++}", id, List(4) { WorkoutSet("s${ids++}", weightKg = 50.0 + kgPerWeek * weeks, reps = 8, done = true, doneAt = t) })
            }
            result += Workout("w${ids++}", t, t + hour, null, null, t, entries)
            t += 2 * day
            i++
        }
        return result
    }

    @Test
    fun fatnessGrowsWithBodyFat() {
        for (sex in Sex.entries) {
            var previous = -1.0
            for (fat in 4..55) {
                val f = BodyShapeModel.fatness(sex, fat.toDouble())
                assertTrue("$sex $fat %", f >= previous && f in 0.0..1.0)
                previous = f
            }
        }
        assertTrue(BodyShapeModel.fatness(Sex.MALE, 25.0) > BodyShapeModel.fatness(Sex.MALE, 20.0))
    }

    @Test
    fun fatnessRangesDependOnSex() {
        assertEquals(0.0, BodyShapeModel.fatness(Sex.MALE, 8.0), 1e-9)
        assertEquals(0.5, BodyShapeModel.fatness(Sex.MALE, 18.0), 1e-9)
        assertEquals(1.0, BodyShapeModel.fatness(Sex.MALE, 30.0), 1e-9)
        assertEquals(0.0, BodyShapeModel.fatness(Sex.FEMALE, 16.0), 1e-9)
        assertEquals(0.5, BodyShapeModel.fatness(Sex.FEMALE, 27.0), 1e-9)
        assertEquals(1.0, BodyShapeModel.fatness(Sex.FEMALE, 40.0), 1e-9)
        // The same body fat is leaner for a woman than for a man.
        assertTrue(BodyShapeModel.fatness(Sex.FEMALE, 24.0) < BodyShapeModel.fatness(Sex.MALE, 24.0))
    }

    @Test
    fun noDataGivesTheNeutralFigure() {
        val none = shapeAt(now, emptyList(), null, emptyList())
        assertEquals(BodyShape.NEUTRAL, none.shape)
        assertFalse(none.fromScale)
        assertFalse(none.fromTraining)
        // Weighings without body fat (manual weights) do not shape the figure either.
        val manual = shapeAt(now, listOf(weighing(now - day, fat = null)), profile(), emptyList())
        assertEquals(BodyShape.NEUTRAL, manual.shape)
        assertFalse(manual.fromScale)
    }

    @Test
    fun bodyFatShapesTheFigureWithoutTouchingMuscularityWhenThereIsNoMuscleData() {
        val shape = shapeAt(now, listOf(weighing(now - day, fat = 25.0)), profile(), emptyList())
        assertTrue(shape.fromScale)
        assertEquals(BodyShapeModel.fatness(Sex.MALE, 25.0).toFloat(), shape.shape.fatness, 1e-6f)
        assertEquals(0.5f, shape.shape.muscularity, 1e-6f)
    }

    @Test
    fun onlyDataUpToTheTimeCounts() {
        val profile = profile()
        val early = listOf(weighing(now - 60 * day, fat = 28.0), weighing(now - 58 * day, fat = 28.0))
        val late = listOf(weighing(now - 2 * day, fat = 15.0), weighing(now - day, fat = 15.0))
        val then = now - 50 * day
        val workouts = routine(now - 120 * day, now)
        val past = shapeAt(then, early + late, profile, workouts)
        val onlyEarly = shapeAt(then, early, profile, workouts.filter { it.startedAt <= then })
        assertEquals(onlyEarly, past)
        assertEquals(BodyShapeModel.fatness(Sex.MALE, 28.0).toFloat(), past.shape.fatness, 1e-6f)
        // Before the first workout nothing is known about training.
        assertNull(BodyShapeModel.trainingScore(now - 200 * day, workouts, lookup, LoadModel.WITHOUT_BODYWEIGHT))
    }

    @Test
    fun oneOddReadingDoesNotMoveTheFigure() {
        val readings = listOf(20.0, 20.5, 35.0, 19.5, 20.0).mapIndexed { i, fat -> weighing(now - (5 - i) * day, fat) }
        val shape = shapeAt(now, readings, profile(), emptyList())
        assertEquals(BodyShapeModel.fatness(Sex.MALE, 20.0).toFloat(), shape.shape.fatness, 1e-6f)
        // Readings older than the smoothing window before the latest one are left out.
        val stale = listOf(weighing(now - 200 * day, 35.0), weighing(now - 199 * day, 35.0), weighing(now - day, 20.0))
        assertEquals(
            BodyShapeModel.fatness(Sex.MALE, 20.0).toFloat(),
            shapeAt(now, stale, profile(), emptyList()).shape.fatness,
            1e-6f,
        )
    }

    @Test
    fun readingsOlderThanSixMonthsBeforeTheMomentStopShapingTheFigure() {
        val profile = profile()
        val longAgo = now - 730 * day
        val once = listOf(weighing(longAgo, fat = 30.0, muscleKg = 64.0))
        // Within six months of the weighing, its body fat and muscle mass still shape the figure.
        val soon = shapeAt(longAgo + 170 * day, once, profile, emptyList())
        assertTrue(soon.fromScale)
        assertEquals(BodyShapeModel.fatness(Sex.MALE, 30.0).toFloat(), soon.shape.fatness, 1e-6f)
        assertTrue(soon.shape.muscularity > 0.55f)
        // Two years on it says nothing: drawn as without a scale, and captioned so.
        val later = shapeAt(now, once, profile, emptyList())
        assertEquals(BodyShape.NEUTRAL, later.shape)
        assertFalse(later.fromScale)
        // With training only, the figure shows the training alone.
        val workouts = routine(now - 60 * day, now)
        assertEquals(shapeAt(now, emptyList(), profile, workouts), shapeAt(now, once, profile, workouts))
        // A newer weighing counts again, without the stale one in its median.
        val again = shapeAt(now, once + weighing(now - 10 * day, fat = 20.0), profile, emptyList())
        assertEquals(BodyShapeModel.fatness(Sex.MALE, 20.0).toFloat(), again.shape.fatness, 1e-6f)
        assertEquals(0.5f, again.shape.muscularity, 1e-6f)
    }

    @Test
    fun strengthGainCountsAnExerciseOnceHoweverManyMusclesItTrains() {
        // Weekly for ten weeks: the squat (quads and glutes) gets stronger, the curl (biceps) does not.
        val load = LoadModel.WITHOUT_BODYWEIGHT
        val workouts = (0 until 10).map { i ->
            val t = now - (70 - 7 * i) * day
            Workout("w${ids++}", t, t + hour, null, null, t, listOf(entry("barbell_back_squat", 100.0 + 5 * i, t), entry("barbell_curl", 30.0, t)))
        }
        val trends = MuscleStats.exerciseTrends(workouts, lookup, from = now - BodyShapeModel.STRENGTH_WEEKS * 7 * day, to = now + 1, load = load)
        val squat = trends.single { it.exercise.id == "barbell_back_squat" }
        val curl = trends.single { it.exercise.id == "barbell_curl" }
        assertEquals(2, squat.exercise.primary.size)
        assertEquals(squat.sessions, curl.sessions)
        assertEquals(0.0, curl.change, 1e-9)
        assertTrue(squat.change > 0.2)
        // The mean of the two exercises: the squat is not counted once per muscle (that would be 2/3 of its gain).
        val expected = squat.change / 2 * 100 / BodyShapeModel.FULL_GAIN_PERCENT
        assertTrue(expected < 1.0)
        assertEquals(expected, BodyShapeModel.strengthGain(now, workouts, lookup, load), 1e-9)
    }

    @Test
    fun trainingBuildsUpMuscularityGraduallyAndFadesWhenStopped() {
        val start = now - 300 * day
        val workouts = routine(start, now - 100 * day)
        fun muscularity(at: Long) = shapeAt(at, emptyList(), null, workouts).shape.muscularity
        val week2 = muscularity(start + 14 * day)
        val week8 = muscularity(start + 56 * day)
        val week20 = muscularity(start + 140 * day)
        assertTrue("$week2 < $week8 < $week20", 0.5f < week2 && week2 < week8 && week8 < week20 && week20 <= 1f)
        // After stopping, the figure goes back towards average slowly, never below it.
        val stopped4 = muscularity(now - 72 * day)
        val stopped16 = muscularity(now - 2 * day)
        assertTrue("$stopped16 < $stopped4 < ${muscularity(now - 100 * day)}", stopped16 < stopped4 && stopped16 >= 0.5f)
    }

    @Test
    fun strengthGainsAddToConsistency() {
        val from = now - 70 * day
        val flat = routine(from, now, kgPerWeek = 0.0)
        val progressing = routine(from, now, kgPerWeek = 2.5)
        val load = LoadModel.WITHOUT_BODYWEIGHT
        assertTrue(BodyShapeModel.trainingScore(now, progressing, lookup, load)!! > BodyShapeModel.trainingScore(now, flat, lookup, load)!!)
    }

    @Test
    fun scaleMuscleAndTrainingBlend() {
        assertEquals(0.5, BodyShapeModel.muscularity(null, null), 1e-9)
        assertEquals(0.3, BodyShapeModel.muscularity(0.3, null), 1e-9)
        assertEquals(0.75, BodyShapeModel.muscularity(null, 0.5), 1e-9)
        assertEquals(0.5 * 0.2 + 0.5 * 1.0, BodyShapeModel.muscularity(0.2, 1.0), 1e-9)
    }

    @Test
    fun scaleMuscleIsReadForTheUsersHeight() {
        // 80 kg, 1.78 m, 15 % fat: 64 kg of muscle is a lot for the height; 52 kg is little.
        val strong = shapeAt(now, listOf(weighing(now - day, 15.0, muscleKg = 64.0)), profile(), emptyList())
        val weak = shapeAt(now, listOf(weighing(now - day, 15.0, muscleKg = 52.0)), profile(), emptyList())
        assertTrue(strong.shape.muscularity > 0.75f)
        assertTrue(weak.shape.muscularity < 0.4f)
        // The same muscle on a shorter person reads as more muscular.
        val short = shapeAt(now, listOf(weighing(now - day, 15.0, muscleKg = 52.0)), profile(heightCm = 160.0), emptyList())
        assertTrue(short.shape.muscularity > weak.shape.muscularity)
        // An implausible value (a skeletal muscle figure reported as total muscle, say) is ignored.
        val odd = shapeAt(now, listOf(weighing(now - day, 15.0, muscleKg = 15.0)), profile(), emptyList())
        assertEquals(0.5f, odd.shape.muscularity, 1e-6f)
    }

    @Test
    fun comparisonStartsWithTheFirstBodyFatReadingElseTheFirstWorkout() {
        val workouts = routine(now - 40 * day, now)
        assertEquals(workouts.first().startedAt, BodyShapeModel.firstDataAt(BodyShapeModel.readings(emptyList(), profile()), workouts))
        val readings = listOf(weighing(now - 80 * day, fat = null), weighing(now - 30 * day, fat = 22.0))
        assertEquals(now - 30 * day, BodyShapeModel.firstDataAt(BodyShapeModel.readings(readings, profile()), workouts))
        assertNull(BodyShapeModel.firstDataAt(BodyShapeModel.readings(emptyList(), null), emptyList()))
    }

    @Test
    fun quantisedShapesShareFigures() {
        assertEquals(BodyShape(0.45f, 0.75f), BodyShape(0.43f, 0.77f).quantised())
        assertEquals(BodyShape(0f, 1f), BodyShape(0.01f, 0.99f).quantised())
    }
}
