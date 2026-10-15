package com.enjoyyourtime.globalfitness.ui.devices

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.enjoyyourtime.globalfitness.AppContainer
import com.enjoyyourtime.globalfitness.GlobalFitnessApp
import com.enjoyyourtime.globalfitness.ble.GattSessionInfo
import com.enjoyyourtime.globalfitness.scale.ConfiguredScale
import com.enjoyyourtime.globalfitness.scale.DetectedScale
import com.enjoyyourtime.globalfitness.scale.LinkKind
import com.enjoyyourtime.globalfitness.scale.MatchConfidence
import com.enjoyyourtime.globalfitness.scale.ScaleDriver
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import java.util.Locale
import java.util.UUID

/** A configured scale with everything the section shows about it. */
data class ConfiguredScaleItem(
    val scale: ConfiguredScale,
    /** Null when the driver no longer exists (shown as unknown). */
    val driver: ScaleDriver?,
    val session: GattSessionInfo?,
    val lastSeenMillis: Long?,
    /** Required settings still missing or invalid (labels). */
    val missingConfig: List<String>,
)

/** A nearby device recognised by at least one driver. */
data class DetectedScaleItem(
    val address: String,
    val name: String?,
    val rssi: Int,
    val driver: ScaleDriver,
    val confidence: MatchConfidence,
    /** Other drivers that also claim the device. */
    val alternatives: List<ScaleDriver>,
    val alreadyConfigured: Boolean,
)

data class ScaleDevicesUiState(
    val configured: List<ConfiguredScaleItem> = emptyList(),
    val detected: List<DetectedScaleItem> = emptyList(),
    val catalogQuery: String = "",
    val catalog: List<ScaleDriver> = emptyList(),
)

/** State and actions of the scales section (configured, detected, manual catalog). */
class ScaleDevicesViewModel(private val container: AppContainer) : ViewModel() {
    private val settings = container.settings
    private val registry = container.scaleRegistry
    private val monitor = container.scaleMonitor

    private val query = MutableStateFlow("")

    private val _events = Channel<String>(Channel.BUFFERED)

    /** Spanish snackbar messages. */
    val events: Flow<String> = _events.receiveAsFlow()

    private val configuredItems = combine(settings.configuredScales, monitor.sessions, monitor.lastSeen) { list, sessions, seen ->
        list.map { cs ->
            val driver = registry.find(cs.driverId)
            ConfiguredScaleItem(
                scale = cs,
                driver = driver,
                session = sessions[cs.id],
                lastSeenMillis = seen[cs.id],
                missingConfig = driver?.capabilities?.config.orEmpty()
                    .filter { it.validate(cs.config[it.key]) != null && it.required }
                    .map { it.label },
            )
        }
    }

    val state: StateFlow<ScaleDevicesUiState> = combine(
        configuredItems,
        monitor.detected,
        query,
        settings.configuredScales,
    ) { configured, detected, q, scales ->
        ScaleDevicesUiState(
            configured = configured,
            detected = detected.map { it.toItem(scales) },
            catalogQuery = q,
            catalog = registry.search(q),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ScaleDevicesUiState(catalog = registry.search("")))

    /** Keeps the detected list fresh (stale devices drop out); run it while the section is visible. */
    suspend fun keepDetectedFresh() {
        while (currentCoroutineContext().isActive) {
            monitor.refreshDetected()
            delay(2_000)
        }
    }

    private fun DetectedScale.toItem(scales: List<ConfiguredScale>): DetectedScaleItem = DetectedScaleItem(
        address = address,
        name = name,
        rssi = rssi,
        driver = best.driver,
        confidence = best.confidence,
        alternatives = matches.drop(1).map { it.driver },
        alreadyConfigured = scales.any { it.address?.equals(address, ignoreCase = true) == true },
    )

    fun setQuery(q: String) {
        query.value = q
    }

    /** "Usar esta": configures a detected device with [driver]. */
    fun useDetected(item: DetectedScaleItem, driver: ScaleDriver = item.driver) {
        add(driver, item.address)
    }

    /** Adds [driver] from the manual catalog, bound to [address] or to any compatible device. */
    fun add(driver: ScaleDriver, address: String?) {
        val existing = settings.configuredScales.value
        if (existing.any { it.driverId == driver.id && it.address.equals(address, ignoreCase = true) }) {
            _events.trySend("Esa báscula ya está configurada")
            return
        }
        val scale = ConfiguredScale(
            id = UUID.randomUUID().toString(),
            driverId = driver.id,
            address = address?.uppercase(Locale.ROOT),
            name = driver.displayName,
            addedAt = System.currentTimeMillis(),
        )
        settings.updateConfiguredScales { it + scale }
        val hint = when {
            driver.capabilities.needsConfig -> " Completa su configuración."
            driver.link == LinkKind.GATT -> " Súbete a la báscula para que la app se conecte."
            else -> ""
        }
        _events.trySend("Báscula añadida: ${driver.displayName}.$hint")
    }

    fun rename(id: String, name: String) {
        val clean = name.trim().take(MAX_NAME)
        if (clean.isEmpty()) return
        settings.updateConfiguredScales { list -> list.map { if (it.id == id) it.copy(name = clean) else it } }
    }

    fun remove(id: String) {
        val scale = settings.configuredScales.value.firstOrNull { it.id == id } ?: return
        settings.updateConfiguredScales { list -> list.filterNot { it.id == id } }
        // A removed device is never auto-configured again.
        scale.address?.let { settings.dismissAutoConfig(it) }
        _events.trySend("Báscula eliminada: ${scale.name}")
    }

    /** Saves one driver setting. Returns the validation error (Spanish) or null when saved. */
    fun setConfig(id: String, key: String, value: String): String? {
        val scale = settings.configuredScales.value.firstOrNull { it.id == id } ?: return "La báscula ya no existe"
        val field = registry.find(scale.driverId)?.capabilities?.config?.firstOrNull { it.key == key }
            ?: return "Ajuste desconocido"
        val clean = value.trim().let { if (field.kind == com.enjoyyourtime.globalfitness.scale.ConfigKind.HEX_KEY_32) it.lowercase(Locale.ROOT) else it }
        field.validate(clean)?.let { return it }
        settings.updateConfiguredScales { list ->
            list.map {
                if (it.id != id) it else it.copy(config = if (clean.isEmpty()) it.config - key else it.config + (key to clean))
            }
        }
        _events.trySend("Ajuste guardado")
        return null
    }

    companion object {
        private const val MAX_NAME = 40

        val Factory = viewModelFactory {
            initializer {
                val app = this[APPLICATION_KEY] as GlobalFitnessApp
                ScaleDevicesViewModel(app.container)
            }
        }
    }
}
