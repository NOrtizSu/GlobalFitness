package com.enjoyyourtime.globalfitness.ui.health

import androidx.activity.result.contract.ActivityResultContract
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.enjoyyourtime.globalfitness.GlobalFitnessApp
import com.enjoyyourtime.globalfitness.health.HcAvailability
import com.enjoyyourtime.globalfitness.health.HealthModule
import com.enjoyyourtime.globalfitness.health.HealthPermissions
import com.enjoyyourtime.globalfitness.health.HealthSettings
import com.enjoyyourtime.globalfitness.health.WorkoutExportState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class WorkoutExportUiState(
    val availability: HcAvailability = HcAvailability.NOT_SUPPORTED,
    /** The exercise write permission is granted. */
    val canWrite: Boolean = false,
    /** The switch: the user's choice (on until touched) once the permission is granted. */
    val enabled: Boolean = false,
    val export: WorkoutExportState = WorkoutExportState(),
)

/**
 * State of the "Save workouts to Health Connect" switch ([WorkoutExportRow]). Kept apart from
 * [HealthViewModel] so the row can be placed on any screen without it (that one also loads the
 * whole Health Connect dashboard).
 */
class WorkoutExportViewModel(private val health: HealthModule) : ViewModel() {

    val uiState: StateFlow<WorkoutExportUiState> = combine(
        health.gateway.availability,
        health.gateway.granted,
        health.workoutExporter.choice,
        health.workoutExporter.state,
    ) { availability, granted, choice, export ->
        val canWrite = HealthPermissions.canExportWorkouts(granted)
        WorkoutExportUiState(availability, canWrite, HealthSettings.exportEnabled(choice, canWrite), export)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, WorkoutExportUiState())

    val permissionContract: ActivityResultContract<Set<String>, Set<String>> = health.gateway.permissionContract()

    /**
     * Only the missing permission: users who connected Health Connect before workouts were
     * exported get a short request instead of the whole list again.
     */
    val missingPermissions: Set<String> = setOf(HealthPermissions.WRITE_EXERCISE)

    fun setEnabled(enabled: Boolean) = health.workoutExporter.setEnabled(enabled)

    /** Re-reads the grants (binder calls: off the main thread). */
    fun onPermissionsResult() {
        viewModelScope.launch(Dispatchers.IO) { health.gateway.refresh() }
    }

    fun openHealthConnect(): Boolean = health.gateway.openSettings()

    companion object {
        val Factory = viewModelFactory {
            initializer { WorkoutExportViewModel((this[APPLICATION_KEY] as GlobalFitnessApp).container.health) }
        }
    }
}
