package com.enjoyyourtime.globalfitness.ui.progress

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.enjoyyourtime.globalfitness.GlobalFitnessApp
import com.enjoyyourtime.globalfitness.data.Measurement
import com.enjoyyourtime.globalfitness.data.Profile
import com.enjoyyourtime.globalfitness.training.data.MuscleMapMode
import com.enjoyyourtime.globalfitness.training.data.TrainingRepository
import com.enjoyyourtime.globalfitness.training.data.TrainingSettings
import com.enjoyyourtime.globalfitness.training.domain.BodyReadings
import com.enjoyyourtime.globalfitness.training.domain.BodyShapeEstimate
import com.enjoyyourtime.globalfitness.training.domain.BodyShapeModel
import com.enjoyyourtime.globalfitness.training.domain.BodyweightTimeline
import com.enjoyyourtime.globalfitness.training.domain.ExerciseHistory
import com.enjoyyourtime.globalfitness.training.domain.LoadModel
import com.enjoyyourtime.globalfitness.training.domain.MuscleStats
import com.enjoyyourtime.globalfitness.training.model.Exercise
import com.enjoyyourtime.globalfitness.training.model.LoadUnit
import com.enjoyyourtime.globalfitness.training.model.Muscle
import com.enjoyyourtime.globalfitness.training.model.Workout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

data class MusclesUiState(
    val loaded: Boolean = false,
    val loadError: String? = null,
    /** Any workout at all (in progress included): without one the page shows how to start. */
    val hasWorkouts: Boolean = false,
    val now: Long = 0L,
    val period: StrengthPeriod = StrengthPeriod.M3,
    val snapshots: Map<Muscle, MuscleSnapshot> = emptyMap(),
    /** The body figure's shape now (see [BodyShapeModel]). */
    val shape: BodyShapeEstimate = BodyShapeEstimate.NONE,
    /** The shape at [pastAt] to compare with; null when the data does not go back far enough. */
    val pastShape: BodyShapeEstimate? = null,
    val pastAt: Long? = null,
)

/**
 * Body map statistics for Progreso → Músculos and Hoy's weekly card (activity scoped, shared by
 * both). Recomputed when the workouts or the weighings change and every minute while observed, since
 * weekly sets and recovery move with the clock. The weighings ([measurements]) give the body weight
 * that bodyweight exercises and assisted machines really move (see [LoadModel]) and, with the
 * [profile], the body composition that shapes the figure (see [BodyShapeModel]).
 */
class MusclesViewModel(
    private val repository: TrainingRepository,
    private val settings: TrainingSettings,
    measurements: StateFlow<List<Measurement>>,
    profile: StateFlow<Profile?>,
    private val clock: () -> Long = System::currentTimeMillis,
) : ViewModel() {
    /** Last mode chosen on the body map (persisted). */
    val mode: StateFlow<MuscleMapMode> = settings.mapMode

    /** Unit the loads of the recent exercises are shown in. */
    val loadUnit: StateFlow<LoadUnit> = settings.loadUnit

    private val period = MutableStateFlow(StrengthPeriod.M3)

    private val ticks = flow {
        while (true) {
            emit(clock())
            delay(TICK_MS)
        }
    }

    private val body = combine(measurements, profile) { list, p ->
        Body(BodyShapeModel.readings(list, p), LoadModel(BodyweightTimeline.fromMeasurements(list)))
    }

    private val inputs = combine(
        repository.loaded,
        repository.loadError,
        repository.workouts,
        repository.exercises,
        body,
    ) { loaded, error, workouts, exercises, b -> Inputs(loaded, error, workouts, exercises, b) }

    val state: StateFlow<MusclesUiState> = combine(inputs, period, ticks) { input, p, now -> compute(input, p, now) }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MusclesUiState())

    fun setMode(mode: MuscleMapMode) = settings.setMapMode(mode)

    fun setPeriod(p: StrengthPeriod) {
        period.value = p
    }

    /**
     * What the weighings give: their body composition (read with the profile once per change, not
     * every tick) and the loads of bodyweight exercises.
     */
    private class Body(val readings: BodyReadings, val load: LoadModel)

    private data class Inputs(
        val loaded: Boolean,
        val error: String?,
        val workouts: List<Workout>,
        val exercises: Map<String, Exercise>,
        val body: Body,
    ) {
        val load: LoadModel get() = body.load
    }

    companion object {
        private val TICK_MS = Duration.ofMinutes(1).toMillis()
        private val DAY_MS = Duration.ofDays(1).toMillis()

        /** How far back the figure is compared: three months, or since the data starts if it is newer. */
        const val COMPARE_MONTHS = 3
        private const val COMPARE_DAYS = 30L * COMPARE_MONTHS

        /** Less history than this is no evolution worth showing. */
        private val MIN_COMPARE_MS = Duration.ofDays(14).toMillis()

        /**
         * Where the full comparison starts: the local midnight [COMPARE_DAYS] days before [now]'s
         * day. Anchored to the day, the past figure stays put while the page is open instead of
         * moving with the clock (and crossing a shape step, cross-fading by itself).
         */
        fun compareFrom(now: Long, zone: ZoneId = ZoneId.systemDefault()): Long =
            Instant.ofEpochMilli(now).atZone(zone).toLocalDate().minusDays(COMPARE_DAYS).atStartOfDay(zone).toInstant().toEpochMilli()

        /**
         * The moment the figure is compared with: [compareFrom], or [firstDataAt] when the data starts
         * later; null without data or with less than [MIN_COMPARE_MS] of it.
         */
        internal fun pastAt(firstDataAt: Long?, now: Long, zone: ZoneId = ZoneId.systemDefault()): Long? =
            firstDataAt?.let { first -> maxOf(compareFrom(now, zone), first) }?.takeIf { it <= now - MIN_COMPARE_MS }

        private fun compute(input: Inputs, period: StrengthPeriod, now: Long): MusclesUiState {
            if (!input.loaded) return MusclesUiState(loadError = input.error, period = period, now = now)
            val workouts = input.workouts
            val lookup: (String) -> Exercise = { id -> input.exercises[id] ?: TrainingRepository.unknownExercise(id) }
            val weekly = MuscleStats.weekly(workouts, lookup, now)
            val strength = MuscleStats.strength(workouts, lookup, from = now - period.days * DAY_MS, to = now + 1, load = input.load)
            val recovery = MuscleStats.recovery(workouts, lookup, now)
            val sessions = ExerciseHistory.allSessions(workouts, exercises = lookup, load = input.load)
            val snapshots = Muscle.entries.associateWith { m ->
                MuscleSnapshot(
                    muscle = m,
                    volume = weekly.getValue(m),
                    strength = strength.getValue(m),
                    recovery = recovery.getValue(m),
                    exercises = MuscleMapLogic.recentExercises(m, sessions, lookup),
                )
            }
            val readings = input.body.readings
            val shape = BodyShapeModel.at(now, readings, workouts, lookup, input.load)
            val pastAt = pastAt(BodyShapeModel.firstDataAt(readings, workouts), now)
            return MusclesUiState(
                loaded = true,
                hasWorkouts = workouts.isNotEmpty(),
                now = now,
                period = period,
                snapshots = snapshots,
                shape = shape,
                pastShape = pastAt?.let { BodyShapeModel.at(it, readings, workouts, lookup, input.load) },
                pastAt = pastAt,
            )
        }

        val Factory = viewModelFactory {
            initializer {
                val app = this[APPLICATION_KEY] as GlobalFitnessApp
                MusclesViewModel(
                    app.container.training.repository,
                    app.container.training.settings,
                    app.container.repository.measurements,
                    app.container.repository.profile,
                )
            }
        }
    }
}
