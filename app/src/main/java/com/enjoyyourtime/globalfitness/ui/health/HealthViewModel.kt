package com.enjoyyourtime.globalfitness.ui.health

import androidx.activity.result.contract.ActivityResultContract
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.enjoyyourtime.globalfitness.GlobalFitnessApp
import com.enjoyyourtime.globalfitness.data.Profile
import com.enjoyyourtime.globalfitness.data.Repository
import com.enjoyyourtime.globalfitness.health.DailyActivity
import com.enjoyyourtime.globalfitness.health.ExerciseSession
import com.enjoyyourtime.globalfitness.health.ExportState
import com.enjoyyourtime.globalfitness.health.HcAvailability
import com.enjoyyourtime.globalfitness.health.HealthMappings
import com.enjoyyourtime.globalfitness.health.HealthModule
import com.enjoyyourtime.globalfitness.health.HealthPermissions
import com.enjoyyourtime.globalfitness.health.HealthSettings
import com.enjoyyourtime.globalfitness.health.HrSample
import com.enjoyyourtime.globalfitness.health.PointKind
import com.enjoyyourtime.globalfitness.health.SyncState
import com.enjoyyourtime.globalfitness.health.TimedValue
import com.enjoyyourtime.globalfitness.health.domain.DailyHrRange
import com.enjoyyourtime.globalfitness.health.domain.DaySummary
import com.enjoyyourtime.globalfitness.health.domain.HealthStats
import com.enjoyyourtime.globalfitness.health.domain.HeartRateZones
import com.enjoyyourtime.globalfitness.health.domain.HrStats
import com.enjoyyourtime.globalfitness.health.domain.NightSleep
import com.enjoyyourtime.globalfitness.health.domain.SleepAnalysis
import com.enjoyyourtime.globalfitness.health.domain.SleepQuality
import com.enjoyyourtime.globalfitness.health.domain.Trend
import com.enjoyyourtime.globalfitness.health.domain.WindowAverage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId

enum class DayRange(val label: String, val days: Int) {
    D7("7 días", 7),
    D30("30 días", 30),
    D90("90 días", 90),
}

data class ExerciseItem(
    val session: ExerciseSession,
    val name: String,
    val source: String,
    val heartRate: HrStats?,
    /** Milliseconds per HR zone 0..5 (empty without samples or profile age). */
    val zoneMs: List<Long>,
)

/** Everything read from the cache for the screen. */
data class HealthDashboard(
    val today: LocalDate,
    val todaySummary: DaySummary,
    val stepsAvg7: WindowAverage?,
    val stepsAvg30: WindowAverage?,
    val activeKcalAvg7: WindowAverage?,
    val restingTrend: Trend,
    val sleepAvg7: WindowAverage?,
    /** Daily activity of the last 90 days by date. */
    val daily: Map<LocalDate, DailyActivity>,
    val hrDay: LocalDate,
    val hrDaySamples: List<HrSample>,
    val hrDayResting: Int?,
    val hrDays: List<LocalDate>,
    val hrRanges: List<DailyHrRange>,
    val lastNight: NightSleep?,
    val lastNightQuality: SleepQuality?,
    /** Nights of the last 30 days by date. */
    val nights: Map<LocalDate, NightSleep>,
    val spo2: List<TimedValue>,
    val spo2Start: Long,
    val spo2End: Long,
    val exercises: List<ExerciseItem>,
    val sources: List<String>,
    val externalWeight: TimedValue?,
    val externalWeightSource: String?,
) {
    val hasData: Boolean
        get() = daily.isNotEmpty() || hrRanges.isNotEmpty() || nights.isNotEmpty() || spo2.isNotEmpty() ||
            exercises.isNotEmpty() || todaySummary.restingHr != null
}

data class HealthUiState(
    val availability: HcAvailability = HcAvailability.NOT_SUPPORTED,
    val granted: Set<String> = emptySet(),
    val historyAvailable: Boolean = false,
    val sync: SyncState = SyncState(),
    val export: ExportState = ExportState(),
    val exportEnabled: Boolean = false,
    val profile: Profile? = null,
) {
    val canRead: Boolean get() = HealthPermissions.hasAnyWearableRead(granted)
    val canExport: Boolean get() = HealthPermissions.canExport(granted)
    val allGranted: Boolean get() = HealthPermissions.allGranted(granted, historyAvailable)

    /** Every data permission granted (the optional history permission aside). */
    val allDataGranted: Boolean get() = HealthPermissions.allGranted(granted, historyAvailable = false)
}

class HealthViewModel(private val health: HealthModule, private val repository: Repository) : ViewModel() {

    private val zone: ZoneId get() = ZoneId.systemDefault()
    private val selectedHrDay = MutableStateFlow<LocalDate?>(null)
    private val today = MutableStateFlow(LocalDate.now(zone))

    private val _detail = MutableStateFlow<ExerciseItem?>(null)

    /** Exercise whose details are open. */
    val detail: StateFlow<ExerciseItem?> = _detail.asStateFlow()

    private val permissionState = combine(
        health.gateway.availability,
        health.gateway.granted,
        health.gateway.historyAvailable,
    ) { availability, granted, history -> Triple(availability, granted, history) }

    val uiState: StateFlow<HealthUiState> = combine(
        permissionState,
        health.sync.state,
        health.exporter.state,
        health.settings.exportChoice,
        repository.profiles,
    ) { (availability, granted, history), sync, export, choice, profiles ->
        HealthUiState(
            availability = availability,
            granted = granted,
            historyAvailable = history,
            sync = sync,
            export = export,
            exportEnabled = HealthSettings.exportEnabled(choice, HealthPermissions.canExport(granted)),
            // Single-profile app: Health Connect data belongs to the only profile.
            profile = profiles.firstOrNull(),
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, HealthUiState())

    val dashboard: StateFlow<HealthDashboard?> = combine(
        health.cache.version,
        selectedHrDay,
        today,
        repository.profiles,
    ) { _, hrDay, day, profiles -> Triple(hrDay, day, profiles.firstOrNull()) }
        .map { (hrDay, day, profile) -> load(day, hrDay ?: day, profile) }
        .flowOn(Dispatchers.IO)
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val permissionContract: ActivityResultContract<Set<String>, Set<String>> = health.gateway.permissionContract()

    fun requestedPermissions(): Set<String> = health.gateway.requestedPermissions()

    /**
     * Screen resumed: the date may have changed. (Permissions and the throttled sync are
     * refreshed by [HealthModule] on every activity resume.)
     */
    fun onResume() {
        today.value = LocalDate.now(zone)
    }

    fun onPermissionsResult() {
        viewModelScope.launch {
            health.gateway.refresh()
            health.sync.sync()
        }
    }

    fun syncNow() {
        health.sync.requestSync()
    }

    fun openHealthConnectSettings(): Boolean = health.gateway.openSettings()

    fun openPlayStore(): Boolean = health.gateway.openPlayStore()

    fun setExportEnabled(enabled: Boolean) = health.exporter.setEnabled(enabled)

    fun exportHistory() {
        viewModelScope.launch { health.exporter.exportHistory() }
    }

    fun selectHrDay(date: LocalDate) {
        selectedHrDay.value = date.takeIf { it != today.value }
    }

    fun openExercise(item: ExerciseItem) {
        _detail.value = item
        viewModelScope.launch {
            val updated = health.sync.completeExercise(item.session) ?: return@launch
            if (_detail.value?.session?.id == updated.id) _detail.value = item.copy(session = updated)
        }
    }

    fun closeExercise() {
        _detail.value = null
    }

    private fun load(today: LocalDate, hrDay: LocalDate, profile: Profile?): HealthDashboard {
        val cache = health.cache
        val zone = zone
        val now = System.currentTimeMillis()
        val endOfToday = HealthStats.dayBounds(today, zone).second
        fun startOf(d: LocalDate) = HealthStats.dayBounds(d, zone).first

        val daily = cache.loadDaily(today.minusDays(DAILY_DAYS - 1L), today).associateBy { it.date }
        val hr30Start = startOf(today.minusDays(HR_RANGE_DAYS - 1L))
        val hr30 = cache.loadHr(hr30Start, endOfToday)
        val (hrDayStart, hrDayEnd) = HealthStats.dayBounds(hrDay, zone)
        val hrDaySamples = if (hrDayStart >= hr30Start) hr30.filter { it.time in hrDayStart until hrDayEnd } else cache.loadHr(hrDayStart, hrDayEnd)
        val resting = cache.loadPoints(PointKind.RESTING_HR, startOf(today.minusDays(TREND_DAYS - 1L)), endOfToday)
        val restingByDay = HealthStats.dailyMeans(resting, zone)
        val nights = SleepAnalysis.nights(cache.loadSleep(startOf(today.minusDays(TREND_DAYS - 1L)), endOfToday), zone)
        val spo2Start = startOf(today.minusDays(SPO2_DAYS - 1L))
        val spo2 = cache.loadPoints(PointKind.SPO2, spo2Start, endOfToday)
        val age = profile?.ageAt(now, zone)

        val exercises = cache.loadExercises(startOf(today.minusDays(EXERCISE_DAYS - 1L)), endOfToday).map { e ->
            val samples = if (e.start >= hr30Start) hr30.filter { it.time in e.start..e.end } else cache.loadHr(e.start, e.end + 1)
            ExerciseItem(
                session = e,
                name = e.title?.takeIf { it.isNotBlank() } ?: HealthMappings.exerciseName(e.type),
                source = health.gateway.originName(e.origin),
                heartRate = HeartRateZones.stats(samples, e.start, e.end),
                zoneMs = if (age != null && samples.isNotEmpty()) HeartRateZones.timeInZones(samples, e.start, e.end, age).toList() else emptyList(),
            )
        }
        val lastNight = nights.lastOrNull { it.date == today } ?: nights.lastOrNull { it.date == today.minusDays(1) }
        val externalWeight = cache.latestPoint(PointKind.WEIGHT)

        val steps = daily.mapNotNull { (d, a) -> a.steps?.let { d to it.toDouble() } }.toMap()
        val activeKcal = daily.mapNotNull { (d, a) -> a.activeKcal?.let { d to it } }.toMap()
        val sleepMinutes = nights.associate { it.date to it.asleepMinutes.toDouble() }
        val yesterday = today.minusDays(1)

        return HealthDashboard(
            today = today,
            todaySummary = HealthStats.daySummary(today, zone, daily[today], hr30.filter { it.time >= startOf(today) }, resting, nights, spo2),
            // Activity averages use complete days (up to yesterday).
            stepsAvg7 = HealthStats.windowAverage(steps, yesterday, 7),
            stepsAvg30 = HealthStats.windowAverage(steps, yesterday, 30),
            activeKcalAvg7 = HealthStats.windowAverage(activeKcal, yesterday, 7),
            restingTrend = HealthStats.trend(restingByDay, today, 7),
            sleepAvg7 = HealthStats.windowAverage(sleepMinutes, today, 7),
            daily = daily,
            hrDay = hrDay,
            hrDaySamples = hrDaySamples,
            hrDayResting = restingByDay[hrDay]?.let { Math.round(it).toInt() },
            hrDays = (HR_RANGE_DAYS - 1 downTo 0).map { today.minusDays(it.toLong()) },
            hrRanges = HealthStats.dailyHrRanges(hr30, zone),
            lastNight = lastNight,
            lastNightQuality = lastNight?.let { SleepAnalysis.quality(it) },
            nights = nights.filter { it.date > today.minusDays(SLEEP_DAYS.toLong()) }.associateBy { it.date },
            spo2 = spo2,
            spo2Start = spo2Start,
            spo2End = minOf(now, endOfToday),
            exercises = exercises,
            sources = cache.origins().map { health.gateway.originName(it) }.distinct(),
            externalWeight = externalWeight,
            externalWeightSource = externalWeight?.let { health.gateway.originName(it.origin) },
        )
    }

    companion object {
        const val STEPS_GOAL = 10_000L
        private const val DAILY_DAYS = 90
        private const val HR_RANGE_DAYS = 30
        private const val TREND_DAYS = 60
        private const val SLEEP_DAYS = 30
        private const val SPO2_DAYS = 14
        private const val EXERCISE_DAYS = 90

        val Factory = viewModelFactory {
            initializer {
                val app = this[APPLICATION_KEY] as GlobalFitnessApp
                HealthViewModel(app.container.health, app.container.repository)
            }
        }
    }
}
