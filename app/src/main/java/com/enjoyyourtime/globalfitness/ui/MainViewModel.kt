package com.enjoyyourtime.globalfitness.ui

import android.app.Activity
import android.content.ContentResolver
import android.net.Uri
import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.enjoyyourtime.globalfitness.AppContainer
import com.enjoyyourtime.globalfitness.GlobalFitnessApp
import com.enjoyyourtime.globalfitness.R
import com.enjoyyourtime.globalfitness.UiText
import com.enjoyyourtime.globalfitness.account.AccountUser
import com.enjoyyourtime.globalfitness.ble.GattSessionInfo
import com.enjoyyourtime.globalfitness.ble.LiveScale
import com.enjoyyourtime.globalfitness.ble.ScanDebugInfo
import com.enjoyyourtime.globalfitness.ble.ScaleSuggestion
import com.enjoyyourtime.globalfitness.ble.ScanStatus
import com.enjoyyourtime.globalfitness.cloud.CloudSyncStatus
import com.enjoyyourtime.globalfitness.data.CsvCodec
import com.enjoyyourtime.globalfitness.data.CsvExportRow
import com.enjoyyourtime.globalfitness.data.Measurement
import com.enjoyyourtime.globalfitness.data.MeasurementSource
import com.enjoyyourtime.globalfitness.data.Profile
import com.enjoyyourtime.globalfitness.data.ProfileDraft
import com.enjoyyourtime.globalfitness.domain.BodyComposition
import com.enjoyyourtime.globalfitness.domain.BodyMetrics
import com.enjoyyourtime.globalfitness.domain.RecordOutcome
import com.enjoyyourtime.globalfitness.ui.devices.toUiText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import com.enjoyyourtime.globalfitness.wearable.WearableConnection
import com.enjoyyourtime.globalfitness.wearable.WearableSummary
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A measurement with its body composition (scale-reported values win over the formulas). */
data class Entry(val measurement: Measurement, val composition: BodyComposition)

data class MainUiState(
    val loaded: Boolean = false,
    val profile: Profile? = null,
    /** The profile's measurements, oldest first. */
    val entries: List<Entry> = emptyList(),
)

/** The experimental direct band mode, once a band has been chosen. */
sealed interface UiEvent {
    data class Message(val text: UiText, val haptic: Boolean = false) : UiEvent
}

fun Profile.compositionOf(m: Measurement): BodyComposition =
    BodyMetrics.forMeasurement(sex, heightCm, ageAt(m.timestamp), m)

/** App-wide state shared by the five sections (activity scoped). */
class MainViewModel(private val container: AppContainer) : ViewModel() {
    private val repository = container.repository
    private val settings = container.settings

    val uiState: StateFlow<MainUiState> = combine(
        repository.loaded,
        repository.profile,
        repository.measurements,
    ) { loaded, profile, measurements ->
        MainUiState(
            loaded = loaded,
            profile = profile,
            entries = if (profile == null) emptyList() else measurements.map { Entry(it, profile.compositionOf(it)) },
        )
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.Eagerly, MainUiState())

    /** The profile right now (uiState is derived on a background dispatcher and can lag a moment). */
    val currentProfile: Profile? get() = repository.profile.value

    val loadError: StateFlow<String?> = repository.loadError
    val scanStatus: StateFlow<ScanStatus> = container.scanner.status
    val live: StateFlow<LiveScale> = container.scaleMonitor.live
    val sessions: StateFlow<Map<String, GattSessionInfo>> = container.scaleMonitor.sessions
    val scaleSuggestion: StateFlow<ScaleSuggestion?> = container.scaleMonitor.suggestion
    val debug: StateFlow<ScanDebugInfo?> = container.scanner.debug
    val permissionRationaleSeen: StateFlow<Boolean> = settings.permissionRationaleSeen

    val accountUser: StateFlow<AccountUser?> = container.account.user
    val cloudStatus: StateFlow<CloudSyncStatus> = container.cloudSync.status
    val premium: StateFlow<Boolean> = container.billing.isPremium
    val adPrivacyOptionsRequired: StateFlow<Boolean> = container.ads.privacyOptionsRequired

    /** The wearable shown on Today: the connected one, otherwise the first set up. */
    val wearable: StateFlow<WearableSummary?> = container.wearables.summaries
        .map { list -> list.firstOrNull { it.state.connection == WearableConnection.READY } ?: list.firstOrNull() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _events = Channel<UiEvent>(Channel.BUFFERED)
    val events: Flow<UiEvent> = _events.receiveAsFlow()

    init {
        viewModelScope.launch {
            container.scaleMonitor.outcomes.collect(::onOutcome)
        }
        viewModelScope.launch {
            container.scaleMonitor.errors.collect { send(it.toUiText()) }
        }
        viewModelScope.launch {
            container.scaleMonitor.messages.collect { send(it.toUiText()) }
        }
    }

    private fun onOutcome(outcome: RecordOutcome) {
        when (outcome) {
            is RecordOutcome.Saved -> send(UiText.res(R.string.msg_measurement_saved), haptic = true)
            is RecordOutcome.Upgraded -> send(UiText.res(R.string.msg_impedance_added), haptic = true)
            is RecordOutcome.Duplicate -> Unit
        }
    }

    private fun send(text: UiText, haptic: Boolean = false) {
        _events.trySend(UiEvent.Message(text, haptic))
    }

    private fun send(@StringRes id: Int) = send(UiText.res(id))

    /** Runs a database operation, reporting failures as a snackbar instead of crashing. */
    private fun launchDb(@StringRes errorPrefix: Int, block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                send(UiText.res(R.string.common_error_detail, UiText.res(errorPrefix), e.message ?: e.javaClass.simpleName))
            }
        }
    }

    // ---- Profile --------------------------------------------------------------------------

    fun createProfile(draft: ProfileDraft) = launchDb(R.string.msg_profile_create_failed) {
        val created = repository.saveProfile(draft)
        settings.setSelectedProfileId(created.id)
    }

    fun updateProfile(draft: ProfileDraft) = launchDb(R.string.msg_profile_save_failed) {
        repository.saveProfile(draft)
        send(R.string.msg_profile_updated)
    }

    // ---- Measurements ---------------------------------------------------------------------

    fun deleteMeasurement(id: Long) = launchDb(R.string.msg_measurement_delete_failed) {
        repository.deleteMeasurement(id)
        send(R.string.msg_measurement_deleted)
    }

    fun addManual(weightKg: Double, timestamp: Long) {
        val profile = uiState.value.profile ?: return
        launchDb(R.string.msg_measurement_save_failed) {
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
            send(R.string.msg_weight_added)
        }
    }

    // ---- Scanner and ads -------------------------------------------------------------------

    fun markPermissionRationaleSeen() = settings.markPermissionRationaleSeen()

    /** The main sections are on screen: ad consent and ads may start (see [com.enjoyyourtime.globalfitness.ads.AdsManager]). */
    fun markOnboardingCompleted() = container.onboarding.markCompleted()

    fun refreshScanner() = container.scanner.refresh()

    /** "Yes, it's mine" on the "Is this your scale?" card. */
    fun acceptScaleSuggestion() = container.scaleMonitor.acceptSuggestion()

    /** "No" on the "Is this your scale?" card: that scale is not suggested again. */
    fun declineScaleSuggestion() = container.scaleMonitor.declineSuggestion()

    /** A top-level section was opened: the interstitial policy decides whether to show one. */
    fun onSectionChanged(activity: Activity) {
        container.ads.interstitials.onSectionChanged(activity)
    }

    fun showAdPrivacyOptions(activity: Activity) {
        container.ads.showPrivacyOptions(activity) { error -> if (error != null) send(error) }
    }

    // ---- CSV ------------------------------------------------------------------------------

    fun exportCsv(resolver: ContentResolver, uri: Uri) {
        viewModelScope.launch {
            val profile = repository.profile.value
            val measurements = repository.measurements.value
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val rows = measurements.map { m -> CsvExportRow(profile, m, profile?.compositionOf(m)) }
                    val text = CsvCodec.encode(rows)
                    resolver.openOutputStream(uri, "wt")?.use { out ->
                        out.write(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())) // UTF-8 BOM for Excel
                        out.write(text.toByteArray(Charsets.UTF_8))
                    } ?: throw FileNotOpened()
                    rows.size
                }
            }
            send(
                result.fold(
                    onSuccess = { UiText.plural(R.plurals.msg_csv_exported, it) },
                    onFailure = { UiText.res(R.string.common_error_detail, UiText.res(R.string.msg_csv_export_failed), errorDetail(it)) },
                ),
            )
        }
    }

    fun importCsv(resolver: ContentResolver, uri: Uri) {
        viewModelScope.launch {
            try {
                importCsvRows(resolver, uri)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                send(UiText.res(R.string.common_error_detail, UiText.res(R.string.msg_csv_import_failed), errorDetail(e)))
            }
        }
    }

    private suspend fun importCsvRows(resolver: ContentResolver, uri: Uri) {
        val parsed = withContext(Dispatchers.IO) {
            val bytes = resolver.openInputStream(uri)?.use { it.readBytes() } ?: throw FileNotOpened()
            CsvCodec.decode(CsvCodec.decodeBytes(bytes))
        }
        if (parsed.rows.isEmpty()) {
            send(R.string.msg_csv_no_rows)
            return
        }
        val r = repository.importRows(parsed.rows, parsed.invalidLines)
        val parts = buildList {
            add(UiText.plural(R.plurals.msg_csv_imported, r.imported))
            if (r.duplicates > 0) add(UiText.plural(R.plurals.msg_csv_duplicates, r.duplicates))
            if (r.invalid > 0) add(UiText.plural(R.plurals.msg_csv_invalid_rows, r.invalid))
            if (r.skippedOtherProfiles > 0) add(UiText.plural(R.plurals.msg_csv_other_profiles, r.skippedOtherProfiles))
            if (r.createdProfile) add(UiText.res(R.string.msg_csv_profile_created))
        }
        send(UiText.Joined(parts, " · "))
    }

    /** The file picked for the CSV could not be opened. */
    private class FileNotOpened : Exception()

    private fun errorDetail(e: Throwable): UiText =
        if (e is FileNotOpened) UiText.res(R.string.msg_file_open_failed) else UiText.raw(e.message ?: e.javaClass.simpleName)

    companion object {
        val Factory = viewModelFactory {
            initializer {
                val app = this[APPLICATION_KEY] as GlobalFitnessApp
                MainViewModel(app.container)
            }
        }
    }
}
