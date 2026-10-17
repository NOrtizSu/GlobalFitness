package com.enjoyyourtime.globalfitness.ui.wearables

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.enjoyyourtime.globalfitness.GlobalFitnessApp
import com.enjoyyourtime.globalfitness.R
import com.enjoyyourtime.globalfitness.UiText
import com.enjoyyourtime.globalfitness.band.BandLogLine
import com.enjoyyourtime.globalfitness.band.HeartRateSample
import com.enjoyyourtime.globalfitness.band.SeenApp
import com.enjoyyourtime.globalfitness.band.xiaomi.AlarmSpec
import com.enjoyyourtime.globalfitness.wearable.AuthKeyKind
import com.enjoyyourtime.globalfitness.wearable.ConfiguredWearable
import com.enjoyyourtime.globalfitness.wearable.KeyCheck
import com.enjoyyourtime.globalfitness.wearable.WearableController
import com.enjoyyourtime.globalfitness.wearable.WearableDriver
import com.enjoyyourtime.globalfitness.wearable.WearableFailure
import com.enjoyyourtime.globalfitness.wearable.WearableKeyStore
import com.enjoyyourtime.globalfitness.wearable.WearableKeys
import com.enjoyyourtime.globalfitness.wearable.WearableManager
import com.enjoyyourtime.globalfitness.wearable.WearableMessage
import com.enjoyyourtime.globalfitness.wearable.WearableState
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * UI state of one wearable's screen. The typed key is kept only in memory here (never in saved
 * instance state) and cleared as soon as it is stored.
 */
class WearableViewModel(private val manager: WearableManager, val deviceId: String) : ViewModel() {
    private val initial: ConfiguredWearable? = manager.device(deviceId)
    private val controller: WearableController? = initial?.let { manager.controller(it) }

    val driver: WearableDriver? = controller?.driver

    val device: StateFlow<ConfiguredWearable?> =
        manager.configured.map { list -> list.firstOrNull { it.id == deviceId } }
            .stateIn(viewModelScope, SharingStarted.Eagerly, initial)

    val state: StateFlow<WearableState> =
        controller?.state ?: MutableStateFlow(WearableState(failure = WearableFailure.UNKNOWN_DRIVER))
    val heartRate: StateFlow<List<HeartRateSample>> = controller?.heartRate ?: MutableStateFlow(emptyList())
    val messages: Flow<WearableMessage> = controller?.messages ?: emptyFlow()
    val log: StateFlow<List<BandLogLine>> = manager.log.lines

    val hasKey: StateFlow<Boolean> = combine(device, manager.keys.keyedAddresses) { d, keyed ->
        d != null && WearableKeyStore.normalize(d.address) in keyed
    }.stateIn(viewModelScope, SharingStarted.Eagerly, initial?.let { manager.keys.has(it.address) } ?: false)

    val syncTimeOnConnect: StateFlow<Boolean> = device.map { it?.config?.get(ConfiguredWearable.CONFIG_SYNC_TIME) != "0" }
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    val forwardingEnabled: StateFlow<Boolean> = manager.settings.forwardingEnabled
    val allowedPackages: StateFlow<Set<String>> = manager.settings.allowedPackages
    val seenApps: StateFlow<List<SeenApp>> = manager.settings.seenApps

    private val _keyInput = MutableStateFlow("")
    val keyInput: StateFlow<String> = _keyInput.asStateFlow()

    private val _keyError = MutableStateFlow<UiText?>(null)
    val keyError: StateFlow<UiText?> = _keyError.asStateFlow()

    val keyKind: AuthKeyKind get() = driver?.authKey ?: AuthKeyKind.NONE

    fun onKeyInput(text: String) {
        _keyInput.value = text
        _keyError.value = null
    }

    /** Puts the key found in [clipboard] into the field; false when the clipboard holds none. */
    fun paste(clipboard: String?): Boolean {
        val key = WearableKeys.extractFromClipboard(keyKind, clipboard)
        if (key == null) {
            _keyError.value = UiText.res(R.string.wearable_wizard_clipboard_empty)
            return false
        }
        onKeyInput(key)
        return true
    }

    /** Validates and stores the typed key; returns true when it was saved. */
    fun saveKey(): Boolean {
        val d = device.value ?: return false
        return try {
            when (val check = manager.saveKey(d, _keyInput.value)) {
                is KeyCheck.Invalid -> {
                    _keyError.value = check.problem.toUiText(keyKind, check.length)
                    false
                }
                is KeyCheck.Valid -> {
                    _keyInput.value = ""
                    _keyError.value = null
                    true
                }
            }
        } catch (e: IOException) {
            _keyError.value = UiText.res(R.string.band_key_save_failed)
            false
        }
    }

    fun clearKeyInput() {
        _keyInput.value = ""
        _keyError.value = null
    }

    fun clearKey() {
        device.value?.let { manager.clearKey(it) }
    }

    fun connect() = controller?.connect()

    fun disconnect() = controller?.disconnect()

    fun findDevice(start: Boolean) = controller?.findDevice(start)

    fun syncTime() = controller?.syncTime()

    fun refreshStatus() = controller?.refreshStatus()

    fun setLiveHeartRate(enabled: Boolean) = controller?.setLiveHeartRate(enabled)

    fun refreshAlarms() = controller?.refreshAlarms()

    fun saveAlarm(id: Int?, spec: AlarmSpec) = controller?.saveAlarm(id, spec)

    fun deleteAlarm(id: Int) = controller?.deleteAlarm(id)

    fun sendTestNotification() = controller?.sendTestNotification()

    fun setSyncTimeOnConnect(enabled: Boolean) = manager.setSyncTimeOnConnect(deviceId, enabled)

    fun setXiaomiDeviceId(value: String) = manager.setConfigValue(deviceId, ConfiguredWearable.CONFIG_XIAOMI_DEVICE_ID, value)

    fun rename(name: String) = manager.rename(deviceId, name)

    fun setForwardingEnabled(enabled: Boolean) = manager.setForwardingEnabled(enabled)

    fun setPackageAllowed(packageName: String, allowed: Boolean) = manager.setPackageAllowed(packageName, allowed)

    fun refreshBonded() = manager.refreshBonded()

    fun clearLog() = manager.log.clear()

    fun onVisible() = controller?.onUiVisible()

    fun onHidden() = controller?.onUiHidden()

    override fun onCleared() {
        _keyInput.value = ""
    }

    companion object {
        const val EXTRA_DEVICE_ID = "device_id"

        val Factory = viewModelFactory {
            initializer {
                val app = this[APPLICATION_KEY] as GlobalFitnessApp
                val id = createSavedStateHandle().get<String>(EXTRA_DEVICE_ID).orEmpty()
                WearableViewModel(app.container.wearables, id)
            }
        }
    }
}
