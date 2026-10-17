package com.enjoyyourtime.globalfitness.ui.band

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.enjoyyourtime.globalfitness.GlobalFitnessApp
import com.enjoyyourtime.globalfitness.R
import com.enjoyyourtime.globalfitness.UiText
import com.enjoyyourtime.globalfitness.band.BandCandidate
import com.enjoyyourtime.globalfitness.band.BandLogLine
import com.enjoyyourtime.globalfitness.band.BandManager
import com.enjoyyourtime.globalfitness.band.BandMessage
import com.enjoyyourtime.globalfitness.band.BandState
import com.enjoyyourtime.globalfitness.band.HeartRateSample
import com.enjoyyourtime.globalfitness.band.SeenApp
import com.enjoyyourtime.globalfitness.band.SelectedBand
import com.enjoyyourtime.globalfitness.band.xiaomi.AlarmSpec
import com.enjoyyourtime.globalfitness.band.xiaomi.AuthKey
import com.enjoyyourtime.globalfitness.band.xiaomi.AuthKeyResult
import java.io.IOException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * UI state for the direct band screen. The typed auth key is kept only in memory here (never in saved
 * instance state) and cleared as soon as it is stored.
 */
class BandViewModel(private val manager: BandManager) : ViewModel() {
    val state: StateFlow<BandState> = manager.state
    val heartRate: StateFlow<List<HeartRateSample>> = manager.heartRate
    val log: StateFlow<List<BandLogLine>> = manager.log.lines
    val messages: SharedFlow<BandMessage> = manager.messages

    val hasKey: StateFlow<Boolean> = manager.store.hasAuthKey
    val selected: StateFlow<SelectedBand?> = manager.store.selected
    val syncTimeOnConnect: StateFlow<Boolean> = manager.store.syncTimeOnConnect
    val forwardingEnabled: StateFlow<Boolean> = manager.store.forwardingEnabled
    val allowedPackages: StateFlow<Set<String>> = manager.store.allowedPackages
    val seenApps: StateFlow<List<SeenApp>> = manager.store.seenApps

    val candidates: StateFlow<List<BandCandidate>> = manager.scanner.candidates
    val scanning: StateFlow<Boolean> = manager.scanner.scanning

    private val _keyInput = MutableStateFlow("")
    val keyInput: StateFlow<String> = _keyInput.asStateFlow()

    private val _keyError = MutableStateFlow<UiText?>(null)
    val keyError: StateFlow<UiText?> = _keyError.asStateFlow()

    fun onKeyInput(text: String) {
        _keyInput.value = text
        _keyError.value = null
    }

    /** Validates and stores the typed key; returns true when it was saved. */
    fun saveKey(): Boolean = when (val result = AuthKey.parse(_keyInput.value)) {
        is AuthKeyResult.Invalid -> {
            _keyError.value = result.error.toUiText(result.length)
            false
        }
        is AuthKeyResult.Valid -> try {
            manager.saveAuthKey(result.key)
            _keyInput.value = ""
            _keyError.value = null
            true
        } catch (e: IOException) {
            _keyError.value = UiText.res(R.string.band_key_save_failed)
            false
        }
    }

    fun clearKey() = manager.clearAuthKey()

    fun refreshBonded() = manager.scanner.refreshBonded()

    fun startScan() = manager.scanner.startScan()

    fun stopScan() = manager.scanner.stopScan()

    fun select(candidate: BandCandidate) = manager.selectBand(candidate)

    fun connect() = manager.connect()

    fun disconnect() = manager.disconnect()

    fun findDevice(start: Boolean) = manager.findDevice(start)

    fun syncTime() = manager.syncTime()

    fun refreshStatus() = manager.refreshStatus()

    fun setLiveHeartRate(enabled: Boolean) = manager.setLiveHeartRate(enabled)

    fun refreshAlarms() = manager.refreshAlarms()

    fun saveAlarm(id: Int?, spec: AlarmSpec) = manager.saveAlarm(id, spec)

    fun deleteAlarm(id: Int) = manager.deleteAlarm(id)

    fun sendTestNotification() = manager.sendTestNotification()

    fun setSyncTimeOnConnect(enabled: Boolean) = manager.setSyncTimeOnConnect(enabled)

    fun setForwardingEnabled(enabled: Boolean) = manager.setForwardingEnabled(enabled)

    fun setPackageAllowed(packageName: String, allowed: Boolean) = manager.setPackageAllowed(packageName, allowed)

    fun clearLog() = manager.log.clear()

    fun onVisible() = manager.onUiVisible()

    fun onHidden() = manager.onUiHidden()

    override fun onCleared() {
        _keyInput.value = ""
    }

    companion object {
        val Factory = viewModelFactory {
            initializer {
                val app = this[APPLICATION_KEY] as GlobalFitnessApp
                BandViewModel(app.container.band)
            }
        }
    }
}
