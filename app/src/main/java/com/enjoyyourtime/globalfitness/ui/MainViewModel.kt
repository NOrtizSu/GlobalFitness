package com.enjoyyourtime.globalfitness.ui

import android.content.ContentResolver
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.enjoyyourtime.globalfitness.AppContainer
import com.enjoyyourtime.globalfitness.GlobalFitnessApp
import com.enjoyyourtime.globalfitness.ble.LiveScale
import com.enjoyyourtime.globalfitness.ble.ScanDebugInfo
import com.enjoyyourtime.globalfitness.ble.ScanStatus
import com.enjoyyourtime.globalfitness.data.CsvCodec
import com.enjoyyourtime.globalfitness.data.CsvExportRow
import com.enjoyyourtime.globalfitness.data.Measurement
import com.enjoyyourtime.globalfitness.data.MeasurementSource
import com.enjoyyourtime.globalfitness.data.Profile
import com.enjoyyourtime.globalfitness.data.ProfileDraft
import com.enjoyyourtime.globalfitness.domain.BodyComposition
import com.enjoyyourtime.globalfitness.domain.BodyMetrics
import com.enjoyyourtime.globalfitness.domain.RecordOutcome
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A measurement with its composition computed from the owning profile. */
data class Entry(val measurement: Measurement, val composition: BodyComposition)

data class MainUiState(
    val loaded: Boolean = false,
    val profiles: List<Profile> = emptyList(),
    val selectedProfile: Profile? = null,
    /** Selected profile's measurements, oldest first. */
    val entries: List<Entry> = emptyList(),
    /** Measurements without profile, newest first. */
    val pending: List<Measurement> = emptyList(),
    val measurementCounts: Map<Long, Int> = emptyMap(),
    val toleranceKg: Double = 3.0,
    val pinnedMac: String? = null,
)

sealed interface UiEvent {
    data class Message(val text: String, val haptic: Boolean = false) : UiEvent
}

fun Profile.compositionOf(m: Measurement): BodyComposition =
    BodyMetrics.compute(sex, heightCm, ageAt(m.timestamp), m.weightKg, m.impedance)

class MainViewModel(private val container: AppContainer) : ViewModel() {
    private val repository = container.repository
    private val settings = container.settings

    private val settingsFlow = combine(settings.selectedProfileId, settings.toleranceKg, settings.pinnedMac) { sel, tol, mac ->
        Triple(sel, tol, mac)
    }

    val uiState: StateFlow<MainUiState> = combine(
        repository.loaded,
        repository.profiles,
        repository.measurements,
        settingsFlow,
    ) { loaded, profiles, measurements, (selectedId, tolerance, mac) ->
        val selected = profiles.firstOrNull { it.id == selectedId } ?: profiles.firstOrNull()
        val entries = if (selected == null) {
            emptyList()
        } else {
            measurements.filter { it.profileId == selected.id }.map { Entry(it, selected.compositionOf(it)) }
        }
        MainUiState(
            loaded = loaded,
            profiles = profiles,
            selectedProfile = selected,
            entries = entries,
            pending = measurements.filter { it.profileId == null }.sortedByDescending { it.timestamp },
            measurementCounts = measurements.mapNotNull { it.profileId }.groupingBy { it }.eachCount(),
            toleranceKg = tolerance,
            pinnedMac = mac,
        )
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.Eagerly, MainUiState())

    val loadError: StateFlow<String?> = repository.loadError
    val scanStatus: StateFlow<ScanStatus> = container.scanner.status
    val live: StateFlow<LiveScale> = container.scaleMonitor.live
    val debug: StateFlow<ScanDebugInfo?> = container.scanner.debug
    val lastScaleAddress: StateFlow<String?> = container.scanner.lastScaleAddress
    val permissionRationaleSeen: StateFlow<Boolean> = settings.permissionRationaleSeen

    private val _events = Channel<UiEvent>(Channel.BUFFERED)
    val events: Flow<UiEvent> = _events.receiveAsFlow()

    private val pendingPromptId = MutableStateFlow<Long?>(null)

    /** Measurement waiting for "¿De quién es esta medición?". */
    val pendingPrompt: StateFlow<Measurement?> = combine(pendingPromptId, repository.measurements) { id, all ->
        id?.let { wanted -> all.firstOrNull { it.id == wanted && it.profileId == null } }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    init {
        viewModelScope.launch {
            container.scaleMonitor.outcomes.collect(::onOutcome)
        }
        viewModelScope.launch {
            container.scaleMonitor.errors.collect { send(it) }
        }
    }

    private fun onOutcome(outcome: RecordOutcome) {
        when (outcome) {
            is RecordOutcome.Saved -> {
                val profile = outcome.profile
                if (profile != null) {
                    settings.setSelectedProfileId(profile.id)
                    send("Guardado para ${profile.name}", haptic = true)
                } else {
                    pendingPromptId.value = outcome.measurement.id
                    send("Medición guardada, falta asignarla", haptic = true)
                }
            }
            is RecordOutcome.Upgraded -> {
                val name = outcome.profile?.name
                send(if (name != null) "Impedancia añadida a la medición de $name" else "Impedancia añadida a la medición", haptic = true)
            }
            is RecordOutcome.Duplicate -> Unit
        }
    }

    private fun send(text: String, haptic: Boolean = false) {
        _events.trySend(UiEvent.Message(text, haptic))
    }

    /** Runs a database operation, reporting failures as a Spanish snackbar instead of crashing. */
    private fun launchDb(errorPrefix: String, block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                send("$errorPrefix: ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    // ---- Profiles -------------------------------------------------------------------------

    fun selectProfile(id: Long) = settings.setSelectedProfileId(id)

    fun createProfile(draft: ProfileDraft) = launchDb("No se pudo crear el perfil") {
        val created = repository.createProfile(draft)
        settings.setSelectedProfileId(created.id)
        send("Perfil «${created.name}» creado")
    }

    fun updateProfile(id: Long, draft: ProfileDraft) = launchDb("No se pudo guardar el perfil") {
        repository.updateProfile(id, draft)
        send("Perfil actualizado")
    }

    fun deleteProfile(profile: Profile, deleteMeasurements: Boolean) = launchDb("No se pudo eliminar el perfil") {
        repository.deleteProfile(profile.id, deleteMeasurements)
        if (settings.selectedProfileId.value == profile.id) {
            settings.setSelectedProfileId(repository.profiles.value.firstOrNull()?.id)
        }
        send(
            if (deleteMeasurements) {
                "Perfil «${profile.name}» eliminado con sus mediciones"
            } else {
                "Perfil «${profile.name}» eliminado; sus mediciones quedan pendientes"
            },
        )
    }

    // ---- Measurements ---------------------------------------------------------------------

    fun assign(measurementId: Long, profileId: Long) = launchDb("No se pudo asignar la medición") {
        repository.assignMeasurement(measurementId, profileId)
        if (pendingPromptId.value == measurementId) pendingPromptId.value = null
        val name = repository.profiles.value.firstOrNull { it.id == profileId }?.name
        if (name != null) send("Asignada a $name")
    }

    fun deleteMeasurement(id: Long) = launchDb("No se pudo eliminar la medición") {
        repository.deleteMeasurement(id)
        if (pendingPromptId.value == id) pendingPromptId.value = null
        send("Medición eliminada")
    }

    fun dismissPrompt() {
        pendingPromptId.value = null
    }

    fun addManual(weightKg: Double, timestamp: Long) {
        val profile = uiState.value.selectedProfile ?: return
        launchDb("No se pudo guardar la medición") {
            repository.insertMeasurement(
                Measurement(
                    id = 0,
                    profileId = profile.id,
                    timestamp = timestamp,
                    weightKg = weightKg,
                    impedance = null,
                    scaleKey = null,
                    source = MeasurementSource.MANUAL,
                ),
            )
            send("Medición manual añadida a ${profile.name}")
        }
    }

    // ---- Settings -------------------------------------------------------------------------

    fun setTolerance(kg: Double) = settings.setToleranceKg(kg)

    fun pinScale(address: String) {
        settings.setPinnedMac(address)
        send("Báscula vinculada: $address")
    }

    fun forgetScale() {
        settings.setPinnedMac(null)
        send("Báscula olvidada; se aceptará cualquier Mi Scale")
    }

    fun markPermissionRationaleSeen() = settings.markPermissionRationaleSeen()

    fun refreshScanner() = container.scanner.refresh()

    // ---- CSV ------------------------------------------------------------------------------

    fun exportCsv(resolver: ContentResolver, uri: Uri) {
        viewModelScope.launch {
            val profiles = repository.profiles.value.associateBy { it.id }
            val measurements = repository.measurements.value
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val rows = measurements.map { m ->
                        val p = m.profileId?.let { profiles[it] }
                        CsvExportRow(p, m, p?.compositionOf(m))
                    }
                    val text = CsvCodec.encode(rows)
                    resolver.openOutputStream(uri, "wt")?.use { out ->
                        out.write(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())) // UTF-8 BOM for Excel
                        out.write(text.toByteArray(Charsets.UTF_8))
                    } ?: error("No se pudo abrir el archivo")
                    rows.size
                }
            }
            send(
                result.fold(
                    onSuccess = { "Exportadas $it mediciones" },
                    onFailure = { "Error al exportar: ${it.message ?: it.javaClass.simpleName}" },
                ),
            )
        }
    }

    fun importCsv(resolver: ContentResolver, uri: Uri) = launchDb("Error al importar") {
        val parsed = withContext(Dispatchers.IO) {
            val bytes = resolver.openInputStream(uri)?.use { it.readBytes() } ?: error("No se pudo abrir el archivo")
            CsvCodec.decode(CsvCodec.decodeBytes(bytes))
        }
        if (parsed.rows.isEmpty()) {
            send("No se encontraron mediciones válidas en el CSV")
            return@launchDb
        }
        val r = repository.importRows(parsed.rows, parsed.invalidLines)
        val parts = buildList {
            add("Importadas ${r.imported}")
            if (r.duplicates > 0) add("${r.duplicates} duplicadas omitidas")
            if (r.invalid > 0) add("${r.invalid} filas no válidas")
            if (r.createdProfiles > 0) add("${r.createdProfiles} perfiles creados")
        }
        send(parts.joinToString(" · "))
    }

    companion object {
        val Factory = viewModelFactory {
            initializer {
                val app = this[APPLICATION_KEY] as GlobalFitnessApp
                MainViewModel(app.container)
            }
        }
    }
}
