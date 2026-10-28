package com.enjoyyourtime.globalfitness.ui.training.music

import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.enjoyyourtime.globalfitness.AppContainer
import com.enjoyyourtime.globalfitness.GlobalFitnessApp
import com.enjoyyourtime.globalfitness.training.player.PlayerCommand
import com.enjoyyourtime.globalfitness.training.player.PlayerState
import com.enjoyyourtime.globalfitness.training.player.playerStates
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update

/**
 * The music controls for Entreno, the workout and the training settings (get it with
 * [playerViewModel]). The sessions are only followed while a screen shows the player, and access
 * is checked again whenever one comes back to the front ([recheckAccess]).
 */
class PlayerViewModel(container: AppContainer) : ViewModel() {
    private val settings = container.training.settings
    private val player = container.training.player
    private val checks = MutableStateFlow(0)

    val controlsEnabled: StateFlow<Boolean> = settings.musicControls

    /**
     * Followed off the main thread. Started once when shown (again only when the access changes,
     * or to retry after a refused read); anything unexpected shows as nothing playing, never a crash
     * ([playerStates]).
     */
    val state: StateFlow<PlayerState> = playerStates(settings.musicControls, checks, player::accessGranted, player::needsRetry, player::states)
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), initialState())

    /** Whether Fortia's listener has notification access, as of the last check. */
    val accessGranted: StateFlow<Boolean> = checks.map { player.accessGranted() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), player.accessGranted())

    /** Reads the access again (back from Android's settings, where it may have changed). */
    fun recheckAccess() = checks.update { it + 1 }

    fun send(command: PlayerCommand) = player.send(command)

    fun setControls(enabled: Boolean) = settings.setMusicControls(enabled)

    /** Until the sessions are read: no card that appears late, nor buttons that become a song. */
    private fun initialState(): PlayerState = when {
        !settings.musicControls.value -> PlayerState.Off
        player.accessGranted() -> PlayerState.Idle
        else -> PlayerState.Keys
    }

    companion object {
        val Factory = viewModelFactory {
            initializer {
                val app = this[APPLICATION_KEY] as GlobalFitnessApp
                PlayerViewModel(app.container)
            }
        }
    }
}

/** The activity's player view model: Entreno, the workout and the settings share one. */
@Composable
fun playerViewModel(): PlayerViewModel {
    val owner = LocalActivity.current as? ViewModelStoreOwner ?: checkNotNull(LocalViewModelStoreOwner.current)
    return viewModel(viewModelStoreOwner = owner, factory = PlayerViewModel.Factory)
}

/** What the player shows now, following the sessions while on screen; access is read again on every resume. */
@Composable
fun playerState(): PlayerState {
    val vm = playerViewModel()
    LifecycleResumeEffect(vm) {
        vm.recheckAccess()
        onPauseOrDispose { }
    }
    val state by vm.state.collectAsStateWithLifecycle()
    return state
}
