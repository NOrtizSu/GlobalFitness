package com.enjoyyourtime.globalfitness.ui.training.music

import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.enjoyyourtime.globalfitness.AppContainer
import com.enjoyyourtime.globalfitness.GlobalFitnessApp
import com.enjoyyourtime.globalfitness.training.data.SpotifyCovers
import com.enjoyyourtime.globalfitness.training.data.TrainingRepository
import com.enjoyyourtime.globalfitness.training.model.Exercise
import com.enjoyyourtime.globalfitness.training.model.Workout
import com.enjoyyourtime.globalfitness.training.music.DailyPlaylists
import com.enjoyyourtime.globalfitness.training.music.PlaylistArt
import com.enjoyyourtime.globalfitness.training.music.TodayTraining
import com.enjoyyourtime.globalfitness.training.music.TrainingDay
import com.enjoyyourtime.globalfitness.training.music.WorkoutPlaylist
import com.enjoyyourtime.globalfitness.ui.training.TrainingViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * The day's playlists and the training they were chosen for. [shownOn] is the calendar day they
 * were worked out on (not always the day they are for: a workout past midnight keeps its own).
 */
data class MusicSuggestions(val shownOn: LocalDate, val day: TrainingDay, val playlists: List<WorkoutPlaylist>)

/**
 * The day's Spotify playlists for Hoy, Entreno and the workout (get it with [musicViewModel]), and
 * the one-time question about loading their covers from Spotify. The playlists are chosen again
 * only when the day or the kind of training changes, never on recomposition.
 */
class MusicViewModel(container: AppContainer) : ViewModel() {
    private val repo = container.training.repository
    private val settings = container.training.settings
    private val covers = container.training.playlistCovers

    val enabled: StateFlow<Boolean> = settings.musicSuggestions

    /** The user's answer about Spotify's covers (unknown shows as off). */
    val spotifyCovers: StateFlow<SpotifyCovers> = settings.spotifyCovers

    /** Spotify's names and covers loaded so far, by playlist id. */
    val art: StateFlow<Map<String, PlaylistArt>> = covers.art

    /** Now, then after every local midnight (and at least hourly: a change of time zone or clock). */
    private val clock = flow {
        while (true) {
            emit(System.currentTimeMillis())
            delay(TrainingViewModel.untilTomorrow())
        }
    }

    /** Null while the workouts load and when the user turned the suggestions off. */
    val suggestions: StateFlow<MusicSuggestions?> =
        suggestionsFlow(enabled, repo.loaded, repo.workouts, repo.exercises, clock, ZoneId::systemDefault)
            .flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /**
     * The question about the covers is due: the start's ad-consent step is over (never over
     * Google's form), the suggestions are on, the user never answered and is not in a workout.
     */
    val askCovers: StateFlow<Boolean> =
        combine(container.ads.startupConsentDone, enabled, settings.spotifyCovers, repo.activeWorkout) { done, on, answer, workout ->
            done && on && answer == SpotifyCovers.UNKNOWN && workout?.let { TodayTraining.isLive(it, System.currentTimeMillis()) } != true
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /** Starts loading the names and covers of [ids] that are not known yet (only once the user allowed them). */
    fun loadArt(ids: List<String>) = covers.request(ids)

    fun setEnabled(enabled: Boolean) = settings.setMusicSuggestions(enabled)

    /** The answer to the question, or the switch in the training settings. */
    fun setCoversAllowed(allowed: Boolean) = settings.setSpotifyCovers(allowed)

    companion object {
        val Factory = viewModelFactory {
            initializer {
                val app = this[APPLICATION_KEY] as GlobalFitnessApp
                MusicViewModel(app.container)
            }
        }
    }
}

/**
 * The suggestions from the workouts and the [clock] (epoch milliseconds), in the [zone] of the
 * moment: emitted again only when the day, the day they are for or the training changes.
 */
internal fun suggestionsFlow(
    enabled: Flow<Boolean>,
    loaded: Flow<Boolean>,
    workouts: Flow<List<Workout>>,
    exercises: Flow<Map<String, Exercise>>,
    clock: Flow<Long>,
    zone: () -> ZoneId,
): Flow<MusicSuggestions?> =
    combine(enabled, loaded, workouts, exercises, clock) { on, ready, list, byId, now ->
        if (!on || !ready) {
            null
        } else {
            val z = zone()
            val date = TodayTraining.musicDate(list, now, z)
            val lookup = { id: String -> byId[id] ?: TrainingRepository.unknownExercise(id) }
            Triple(Instant.ofEpochMilli(now).atZone(z).toLocalDate(), date, TodayTraining.classify(list, date, z, now, lookup))
        }
    }
        .distinctUntilChanged()
        .map { it?.let { (shownOn, date, day) -> MusicSuggestions(shownOn, day, DailyPlaylists.pick(date.toEpochDay(), day)) } }

/** The activity's music view model: Hoy, Entreno, the workout and the settings share one. */
@Composable
fun musicViewModel(): MusicViewModel {
    val owner = LocalActivity.current as? ViewModelStoreOwner ?: checkNotNull(LocalViewModelStoreOwner.current)
    return viewModel(viewModelStoreOwner = owner, factory = MusicViewModel.Factory)
}

/**
 * The suggestions to show now. After a night in the background the previous day's are still
 * cached for a moment, until the new ones are worked out: those are never shown.
 */
@Composable
fun musicSuggestions(): MusicSuggestions? {
    val suggestions by musicViewModel().suggestions.collectAsStateWithLifecycle()
    return suggestions?.takeIf { it.shownOn == LocalDate.now() }
}
