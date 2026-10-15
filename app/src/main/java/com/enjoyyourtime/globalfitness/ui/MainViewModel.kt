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
    /** The profile's measurements, oldest first. */
    val entries: List<Entry> = emptyList(),
    val measurementCounts: Map<Long, Int> = emptyMap(),
)

sealed interface UiEvent {
    data class Message(val text: String, val haptic: Boolean = false) : UiEvent
}

fun Profile.compositionOf(m: Measurement): BodyComposition =
    BodyMetrics.forMeasurement(sex, heightCm, ageAt(m.timestamp), m)

class MainViewModel(private val container: AppContainer) : ViewModel() {
    private val repository = container.repository
    private val settings = container.settings

    val uiState: StateFlow<MainUiState> = combine(
        repository.loaded,
        repository.profile,
        repository.measurements,
    ) { loaded, profile, measurements ->
        // Single-profile app: every measurement belongs to the profile.
        val entries = if (profile == null) emptyList() else measurements.map { Entry(it, profile.compositionOf(it)) }
        MainUiState(
            loaded = loaded,
            profiles = listOfNotNull(profile),
            selectedProfile = profile,
            entries = entries,
            measurementCounts = if (profile == null) emptyMap() else mapOf(profile.id to measurements.size),
        )
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.Eagerly, MainUiState())

    val loadError: StateFlow<String?> = repository.loadError
    val scanStatus: StateFlow<ScanStatus> = container.scanner.status
    val live: StateFlow<LiveScale> = container.scaleMonitor.live
    val debug: StateFlow<ScanDebugInfo?> = container.scanner.debug
    val permissionRationaleSeen: StateFlow<Boolean> = settings.permissionRationaleSeen

    private val _events = Channel<UiEvent>(Channel.BUFFERED)
    val events: Flow<UiEvent> = _events.receiveAsFlow()

    init {
        viewModelScope.launch {
            container.scaleMonitor.outcomes.collect(::onOutcome)
        }
        viewModelScope.launch {
            container.scaleMonitor.errors.collect { send(it) }
        }
        viewModelScope.launch {
            container.scaleMonitor.messages.collect { send(it) }
        }
    }

    private fun onOutcome(outcome: RecordOutcome) {
        when (outcome) {
            is RecordOutcome.Saved -> send("Medición guardada", haptic = true)
            is RecordOutcome.Upgraded -> send("Impedancia añadida a la medición", haptic = true)
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

    // ---- Profile --------------------------------------------------------------------------

    fun createProfile(draft: ProfileDraft) = launchDb("No se pudo crear el perfil") {
        val created = repository.saveProfile(draft)
        settings.setSelectedProfileId(created.id)
        send("Perfil «${created.name}» creado")
    }

    fun updateProfile(@Suppress("UNUSED_PARAMETER") id: Long, draft: ProfileDraft) = launchDb("No se pudo guardar el perfil") {
        repository.saveProfile(draft)
        send("Perfil actualizado")
    }

    // ---- Measurements ---------------------------------------------------------------------

    fun deleteMeasurement(id: Long) = launchDb("No se pudo eliminar la medición") {
        repository.deleteMeasurement(id)
        send("Medición eliminada")
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
            if (r.skippedOtherProfiles > 0) add("${r.skippedOtherProfiles} de otros perfiles omitidas")
            if (r.createdProfile) add("perfil creado")
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
