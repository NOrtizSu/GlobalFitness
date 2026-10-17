package com.enjoyyourtime.globalfitness.ui.wearables

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.enjoyyourtime.globalfitness.GlobalFitnessApp
import com.enjoyyourtime.globalfitness.R
import com.enjoyyourtime.globalfitness.UiText
import com.enjoyyourtime.globalfitness.scale.MatchConfidence
import com.enjoyyourtime.globalfitness.wearable.ConfiguredWearable
import com.enjoyyourtime.globalfitness.wearable.DetectedWearable
import com.enjoyyourtime.globalfitness.wearable.WearableDetection
import com.enjoyyourtime.globalfitness.wearable.WearableDriver
import com.enjoyyourtime.globalfitness.wearable.WearableManager
import com.enjoyyourtime.globalfitness.wearable.WearableSummary
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive

/** A device seen nearby or bonded that at least one driver recognises. */
data class DetectedWearableItem(
    val address: String,
    val name: String?,
    val rssi: Int?,
    val bonded: Boolean,
    val driver: WearableDriver,
    val confidence: MatchConfidence,
    /** Other drivers that also claim it. */
    val alternatives: List<WearableDriver>,
    val alreadyConfigured: Boolean,
)

data class WearablesUiState(
    val configured: List<WearableSummary> = emptyList(),
    val detected: List<DetectedWearableItem> = emptyList(),
    val suggestion: WearableDetection.Suggest? = null,
    val catalogQuery: String = "",
    val catalog: List<WearableDriver> = emptyList(),
)

/** One-shot UI events of the section. */
sealed interface WearablesEvent {
    data class Message(val text: UiText) : WearablesEvent

    /** A device was set up: open its screen. */
    data class Open(val deviceId: String) : WearablesEvent
}

/** State and actions of Devices → Bands & watches (configured, detected, catalog, suggestion). */
class WearablesViewModel(private val manager: WearableManager) : ViewModel() {
    private val query = MutableStateFlow("")

    private val _events = Channel<WearablesEvent>(Channel.BUFFERED)
    val events: Flow<WearablesEvent> = _events.receiveAsFlow()

    val state: StateFlow<WearablesUiState> = combine(
        manager.summaries,
        manager.detected,
        manager.suggestion,
        query,
    ) { summaries, detected, suggestion, q ->
        WearablesUiState(
            configured = summaries,
            detected = detected.map { it.toItem(summaries.map { s -> s.device }) },
            suggestion = suggestion,
            catalogQuery = q,
            catalog = manager.registry.search(q),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), WearablesUiState(catalog = manager.registry.search("")))

    private fun DetectedWearable.toItem(configured: List<ConfiguredWearable>) = DetectedWearableItem(
        address = address,
        name = name,
        rssi = rssi,
        bonded = bonded,
        driver = best.driver,
        confidence = best.confidence,
        alternatives = matches.drop(1).map { it.driver },
        alreadyConfigured = configured.any { it.matchesAddress(address) },
    )

    /** Keeps the detected list fresh (bonded devices listed, stale ones dropped); run while visible. */
    suspend fun keepDetectedFresh() {
        while (currentCoroutineContext().isActive) {
            manager.refreshBonded()
            manager.refreshDetected()
            delay(3_000)
        }
    }

    fun setQuery(q: String) {
        query.value = q
    }

    fun acceptSuggestion() {
        manager.acceptSuggestion()?.let { opened(it) }
    }

    fun declineSuggestion() = manager.declineSuggestion()

    fun use(driver: WearableDriver, address: String, name: String?) {
        opened(manager.addDevice(driver, address, name ?: driver.displayName, fromSuggestion = false))
    }

    fun rename(id: String, name: String) = manager.rename(id, name)

    fun remove(summary: WearableSummary) {
        manager.removeDevice(summary.device.id)
        _events.trySend(WearablesEvent.Message(UiText.res(R.string.wearables_removed, summary.device.name)))
    }

    private fun opened(device: ConfiguredWearable) {
        _events.trySend(WearablesEvent.Message(UiText.res(R.string.wearables_added, device.name)))
        _events.trySend(WearablesEvent.Open(device.id))
    }

    companion object {
        val Factory = viewModelFactory {
            initializer {
                val app = this[APPLICATION_KEY] as GlobalFitnessApp
                WearablesViewModel(app.container.wearables)
            }
        }
    }
}
