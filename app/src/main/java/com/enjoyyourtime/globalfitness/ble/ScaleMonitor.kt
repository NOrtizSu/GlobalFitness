package com.enjoyyourtime.globalfitness.ble

import com.enjoyyourtime.globalfitness.domain.MeasurementRecorder
import com.enjoyyourtime.globalfitness.domain.RecordOutcome
import com.enjoyyourtime.globalfitness.scale.AdvertisementInfo
import com.enjoyyourtime.globalfitness.scale.ConfiguredScale
import com.enjoyyourtime.globalfitness.scale.DetectedScale
import com.enjoyyourtime.globalfitness.scale.DetectionResult
import com.enjoyyourtime.globalfitness.scale.DriverEnv
import com.enjoyyourtime.globalfitness.scale.DriverStore
import com.enjoyyourtime.globalfitness.scale.GattLink
import com.enjoyyourtime.globalfitness.scale.GattScaleDriver
import com.enjoyyourtime.globalfitness.scale.LiveState
import com.enjoyyourtime.globalfitness.scale.MatchConfidence
import com.enjoyyourtime.globalfitness.scale.PassiveScaleDriver
import com.enjoyyourtime.globalfitness.scale.PassiveSession
import com.enjoyyourtime.globalfitness.scale.ScaleConfigStore
import com.enjoyyourtime.globalfitness.scale.ScaleContext
import com.enjoyyourtime.globalfitness.scale.ScaleDetector
import com.enjoyyourtime.globalfitness.scale.ScaleEvent
import com.enjoyyourtime.globalfitness.scale.ScaleReading
import com.enjoyyourtime.globalfitness.scale.ScaleRegistry
import com.enjoyyourtime.globalfitness.scale.SessionStatus
import com.enjoyyourtime.globalfitness.scale.drivers.MiScale2Driver
import com.enjoyyourtime.globalfitness.scale.runSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.Locale
import java.util.UUID

/** What the live scale card shows. */
sealed interface LiveScale {
    data object Idle : LiveScale

    /** A GATT scale is being connected to (it was stepped on). */
    data class Connecting(val scaleName: String) : LiveScale
    data class Measuring(val weightKg: Double) : LiveScale
    data class Stabilized(val weightKg: Double) : LiveScale
    data class Saved(
        val weightKg: Double,
        val impedance: Int?,
        val profileName: String?,
        val alreadyStored: Boolean,
    ) : LiveScale

    /** The weighing was complete but could not be stored. */
    data class SaveFailed(val weightKg: Double, val message: String) : LiveScale
}

/** Live status of a GATT scale session, for the devices screen. */
data class GattSessionInfo(
    val configId: String,
    val scaleName: String,
    val status: SessionStatus,
    val detail: String? = null,
    val weightKg: Double? = null,
    val atMillis: Long,
)

/** A nearby scale recognised with certainty that the user has to confirm ("¿Es tu báscula?"). */
data class ScaleSuggestion(
    val driverId: String,
    val driverName: String,
    val brand: String,
    val address: String,
    val deviceName: String?,
    val verified: Boolean,
)

/**
 * Glue between the scanner, the scale drivers and the [MeasurementRecorder]. All its state lives
 * on one worker thread ([dispatcher]), off the main thread; results are published as flows.
 *
 * - Every advertisement feeds the [ScaleDetector] (detected list, auto-configuration of passive
 *   scales, suggestions for scales that need a connection).
 * - Advertisements of configured passive scales go to a per-device [PassiveSession]; scales
 *   configured without an address only take HIGH-confidence devices and bind to the first one
 *   that produces a stored weighing.
 * - When a configured GATT scale advertises (stepped on) a session is run, one at a time. After
 *   a session it is not reconnected until the scale went quiet (stopped advertising) or a long
 *   cooldown passed; failures back off exponentially (no reconnect storms).
 * - Readings are persisted serially through a queue, so the advertisement flow never waits on
 *   the database.
 */
class ScaleMonitor(
    advertisements: Flow<AdvertisementInfo>,
    private val registry: ScaleRegistry,
    private val settings: ScaleConfigStore,
    private val recorder: MeasurementRecorder,
    private val contextProvider: () -> ScaleContext,
    private val linkFactory: (address: String) -> GattLink,
    private val scope: CoroutineScope,
    private val wallClock: () -> Long,
    private val elapsedClock: () -> Long,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default.limitedParallelism(1),
) {
    private val detector = ScaleDetector(registry)

    /** Passive readings and GATT readings are stored one at a time (dedupe needs it). */
    private val recordMutex = Mutex()

    private val _live = MutableStateFlow<LiveScale>(LiveScale.Idle)
    val live: StateFlow<LiveScale> = _live.asStateFlow()

    private val _outcomes = MutableSharedFlow<RecordOutcome>(extraBufferCapacity = 16)

    /** Persisted results, for snackbars. */
    val outcomes: SharedFlow<RecordOutcome> = _outcomes.asSharedFlow()

    private val _errors = MutableSharedFlow<String>(extraBufferCapacity = 4)

    /** Spanish error messages when a measurement could not be stored. */
    val errors: SharedFlow<String> = _errors.asSharedFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)

    /** Spanish informational messages (auto-configuration, missing bind key, ...). */
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    private val _detected = MutableStateFlow<List<DetectedScale>>(emptyList())

    /** Recently seen devices that some driver recognises. */
    val detected: StateFlow<List<DetectedScale>> = _detected.asStateFlow()

    private val _sessions = MutableStateFlow<Map<String, GattSessionInfo>>(emptyMap())

    /** Latest GATT session status per configured scale id. */
    val sessions: StateFlow<Map<String, GattSessionInfo>> = _sessions.asStateFlow()

    private val _lastSeen = MutableStateFlow<Map<String, Long>>(emptyMap())

    /** Last time (wall clock) each configured scale was heard, by configured scale id. */
    val lastSeen: StateFlow<Map<String, Long>> = _lastSeen.asStateFlow()

    private val _suggestion = MutableStateFlow<ScaleSuggestion?>(null)

    /** A scale that needs the user's confirmation before it is set up (null when none). */
    val suggestion: StateFlow<ScaleSuggestion?> = _suggestion.asStateFlow()

    private val _gattConnecting = MutableStateFlow(false)

    /** True while a GATT connection is being established (scanning can pause meanwhile). */
    val gattConnecting: StateFlow<Boolean> = _gattConnecting.asStateFlow()

    /** Result per committed reading key: an outcome, nothing stored, or an error message. */
    private sealed interface KeyResult {
        data class Stored(val outcome: RecordOutcome) : KeyResult
        data object NothingStored : KeyResult
        data class Error(val message: String) : KeyResult
    }

    private class PassiveEntry(val configId: String, val driverId: String, val address: String, val session: PassiveSession) {
        var lastAdvElapsed: Long = 0L
    }

    private class Saved(val reading: ScaleReading, val result: KeyResult)

    /** Readings waiting to be stored, in order. */
    private class PendingRecord(val readings: List<ScaleReading>, val owner: String, val configId: String, val address: String)

    private val records = Channel<PendingRecord>(Channel.UNLIMITED)

    private val passive = HashMap<String, PassiveEntry>()
    private var lastSaved: Saved? = null
    private var liveOwner: String? = null
    private var lastEviction = 0L

    private var gattJob: Job? = null
    private var gattConfigId: String? = null
    private var gattLive: LiveScale = LiveScale.Idle
    private var gattLiveAt: Long = 0L
    private val nextGattAttempt = HashMap<String, Long>()
    private val gattFailures = HashMap<String, Int>()
    private val lastGattAdvert = HashMap<String, Long>()

    /** Session end time for scales that must go quiet before the next connection. */
    private val quietSince = HashMap<String, Long>()

    private var tickJob: Job? = null
    private var lastDetectedPublish = 0L
    private var knownConfigured: List<ConfiguredScale> = settings.configuredScales.value

    init {
        scope.launch(dispatcher) {
            advertisements.collect { adv ->
                try {
                    onAdvertisement(adv)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // One malformed advertisement or driver bug never stops the monitor.
                }
            }
        }
        scope.launch(dispatcher) {
            settings.configuredScales.collect { list ->
                knownConfigured = list
                pruneSessions(list)
                if (list.isNotEmpty()) _suggestion.value = null
            }
        }
        scope.launch(dispatcher) {
            for (p in records) {
                if (record(p.readings, p.owner)) bindAddress(p.configId, p.address)
                refreshLive()
                ensureTicking()
            }
        }
        scope.launch(dispatcher) { createLegacyMiScaleConfig() }
    }

    // ---- Advertisement routing ---------------------------------------------------------------

    private fun onAdvertisement(adv: AdvertisementInfo) {
        val now = elapsedClock()
        val configured = settings.configuredScales.value
        when (val result = detector.onAdvertisement(adv, now, configured, settings.dismissedAutoConfig.value)) {
            is DetectionResult.AutoConfigure -> autoConfigure(result)
            is DetectionResult.Suggest -> suggest(result)
            DetectionResult.Updated -> publishDetected(force = false)
            DetectionResult.Ignored -> Unit
        }
        evictIdleSessions(now)
        for (cs in configured) {
            val driver = registry.find(cs.driverId) ?: continue
            val belongs = if (cs.address != null) {
                cs.address.equals(adv.address, ignoreCase = true)
            } else {
                // "Any compatible device": only an unambiguous match (matches are cached per address).
                detector.matchesFor(adv.address).any { it.driver.id == driver.id && it.confidence == MatchConfidence.HIGH }
            }
            if (!belongs) continue
            markSeen(cs.id)
            when (driver) {
                is PassiveScaleDriver -> handlePassive(cs, driver, adv, now)
                is GattScaleDriver -> maybeStartGatt(cs, driver, adv, now)
            }
        }
    }

    private fun markSeen(configId: String) {
        val now = wallClock()
        val previous = _lastSeen.value[configId]
        if (previous != null && now - previous < DETECTED_PUBLISH_MS) return
        _lastSeen.value = _lastSeen.value + (configId to now)
    }

    private fun autoConfigure(result: DetectionResult.AutoConfigure) {
        val scale = ConfiguredScale(
            id = UUID.randomUUID().toString(),
            driverId = result.driver.id,
            address = result.address.uppercase(Locale.ROOT),
            name = result.driver.displayName,
            addedAt = wallClock(),
            autoConfigured = true,
        )
        settings.updateConfiguredScales { list -> if (list.isEmpty()) list + scale else list }
        publishDetected(force = true)
        val extra = if (result.driver.capabilities.needsConfig) " Completa su configuración en Dispositivos." else ""
        _messages.tryEmit("Báscula detectada y configurada: ${result.driver.displayName}.$extra")
    }

    private fun suggest(result: DetectionResult.Suggest) {
        if (settings.configuredScales.value.isNotEmpty()) return
        _suggestion.value = ScaleSuggestion(
            driverId = result.driver.id,
            driverName = result.driver.displayName,
            brand = result.driver.brand,
            address = result.address.uppercase(Locale.ROOT),
            deviceName = result.name,
            verified = result.driver.verified,
        )
        publishDetected(force = true)
    }

    /** "Sí, es mi báscula": sets up the suggested scale. */
    fun acceptSuggestion() {
        scope.launch(dispatcher) {
            val s = _suggestion.value ?: return@launch
            _suggestion.value = null
            val driver = registry.find(s.driverId) ?: return@launch
            settings.updateConfiguredScales { list ->
                if (list.any { it.address.equals(s.address, ignoreCase = true) }) {
                    list
                } else {
                    list + ConfiguredScale(UUID.randomUUID().toString(), driver.id, s.address, driver.displayName, addedAt = wallClock())
                }
            }
            val extra = if (driver.capabilities.needsConfig) " Completa su configuración." else " Súbete a la báscula para que la app se conecte."
            _messages.tryEmit("Báscula añadida: ${driver.displayName}.$extra")
        }
    }

    /** "No es mía": never suggested again. */
    fun declineSuggestion() {
        scope.launch(dispatcher) {
            val s = _suggestion.value ?: return@launch
            _suggestion.value = null
            settings.dismissAutoConfig(s.address)
        }
    }

    private fun publishDetected(force: Boolean) {
        val now = elapsedClock()
        if (!force && now - lastDetectedPublish < DETECTED_PUBLISH_MS) return
        lastDetectedPublish = now
        _detected.value = detector.detected(now)
    }

    fun refreshDetected() {
        scope.launch(dispatcher) { publishDetected(force = true) }
    }

    private fun handlePassive(cs: ConfiguredScale, driver: PassiveScaleDriver, adv: AdvertisementInfo, now: Long) {
        val key = sessionKey(cs.id, adv.address)
        val env = envFor(cs, adv.address)
        val entry = passive.getOrPut(key) { PassiveEntry(cs.id, cs.driverId, adv.address, driver.newSession(env)) }
        entry.lastAdvElapsed = now
        val readings = try {
            entry.session.parseAdvertisement(adv, env)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emptyList()
        }
        if (entry.session.live != LiveState.Idle || readings.isNotEmpty()) liveOwner = key
        if (readings.isNotEmpty()) records.trySend(PendingRecord(readings, key, cs.id, adv.address))
        refreshLive()
        ensureTicking()
    }

    /** Passive sessions of devices not heard for a long time are dropped (memory, stale state). */
    private fun evictIdleSessions(now: Long) {
        if (now - lastEviction < EVICTION_INTERVAL_MS) return
        lastEviction = now
        passive.entries.removeAll { (key, e) ->
            key != liveOwner && !e.session.needsTick && now - e.lastAdvElapsed > PASSIVE_IDLE_EVICT_MS
        }
    }

    /** A scale configured for "any compatible device" is bound to the first one that produced a weighing. */
    private fun bindAddress(configId: String, address: String) {
        val cs = settings.configuredScales.value.firstOrNull { it.id == configId } ?: return
        if (cs.address != null) return
        val upper = address.uppercase(Locale.ROOT)
        settings.updateConfiguredScales { list ->
            val taken = list.any { it.id != configId && it.driverId == cs.driverId && it.address.equals(upper, ignoreCase = true) }
            if (taken) list else list.map { if (it.id == configId && it.address == null) it.copy(address = upper) else it }
        }
    }

    // ---- GATT sessions ------------------------------------------------------------------------

    private fun maybeStartGatt(cs: ConfiguredScale, driver: GattScaleDriver, adv: AdvertisementInfo, now: Long) {
        val previousAdvert = lastGattAdvert.put(cs.id, now)
        if (gattJob?.isActive == true) return
        if (now < (nextGattAttempt[cs.id] ?: 0L)) return
        quietSince[cs.id]?.let { endedAt ->
            // After a session the scale keeps advertising for a while: wait until it went quiet
            // (the next weighing wakes it up again) or a long cooldown passed.
            val wentQuiet = previousAdvert == null || now - previousAdvert >= SCALE_GONE_MS
            if (!wentQuiet && now - endedAt < MAX_QUIET_WAIT_MS) return
            quietSince.remove(cs.id)
        }
        val env = envFor(cs, adv.address)
        gattConfigId = cs.id
        gattJob = scope.launch(dispatcher) {
            var connectFailed = false
            var linkFailed = false
            try {
                driver.runSession(linkFactory(adv.address), env, adv).collect { event ->
                    when (event) {
                        is ScaleEvent.Status -> {
                            updateSession(cs, event.status, event.detail)
                            when (event.status) {
                                SessionStatus.CONNECTING -> {
                                    _gattConnecting.value = true
                                    setGattLive(LiveScale.Connecting(cs.name))
                                }
                                SessionStatus.CONNECTED -> _gattConnecting.value = false
                                SessionStatus.FAILED -> {
                                    if (_gattConnecting.value) connectFailed = true else linkFailed = true
                                    _gattConnecting.value = false
                                }
                                else -> Unit
                            }
                        }
                        is ScaleEvent.Live -> {
                            updateSession(cs, SessionStatus.MEASURING, null, event.weightKg)
                            setGattLive(LiveScale.Measuring(event.weightKg))
                        }
                        is ScaleEvent.Reading -> {
                            val stored = record(listOf(event.reading), "gatt:${cs.id}")
                            if (stored) {
                                bindAddress(cs.id, adv.address)
                                updateSession(cs, SessionStatus.SAVED, null, event.reading.weightKg)
                            }
                        }
                        is ScaleEvent.Message -> _messages.tryEmit(event.text)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                linkFailed = true
                updateSession(cs, SessionStatus.FAILED, e.message)
            } finally {
                _gattConnecting.value = false
                val end = elapsedClock()
                if (connectFailed || linkFailed) {
                    val n = (gattFailures[cs.id] ?: 0) + 1
                    gattFailures[cs.id] = n
                    nextGattAttempt[cs.id] = end + (GATT_RETRY_BASE_MS shl (n - 1).coerceAtMost(5)).coerceAtMost(GATT_RETRY_MAX_MS)
                } else {
                    // Done (with or without data): not a failure, but no reconnection while the
                    // scale is still awake from this weighing.
                    gattFailures.remove(cs.id)
                    nextGattAttempt[cs.id] = end + GATT_COOLDOWN_MS
                    quietSince[cs.id] = end
                }
                if (gattLive is LiveScale.Connecting || gattLive is LiveScale.Measuring) setGattLive(LiveScale.Idle)
                gattConfigId = null
            }
        }
    }

    private fun updateSession(cs: ConfiguredScale, status: SessionStatus, detail: String?, weight: Double? = null) {
        val previous = _sessions.value[cs.id]
        _sessions.value = _sessions.value + (
            cs.id to GattSessionInfo(
                configId = cs.id,
                scaleName = cs.name,
                status = if (status == SessionStatus.DONE && previous?.status == SessionStatus.SAVED) SessionStatus.SAVED else status,
                detail = detail,
                weightKg = weight ?: previous?.weightKg?.takeIf { status != SessionStatus.CONNECTING },
                atMillis = wallClock(),
            )
            )
    }

    private fun setGattLive(state: LiveScale) {
        gattLive = state
        gattLiveAt = elapsedClock()
        liveOwner = GATT_OWNER
        refreshLive()
        ensureTicking()
    }

    // ---- Recording ----------------------------------------------------------------------------

    /** Persists [readings]; returns true when at least one was stored or upgraded. */
    private suspend fun record(readings: List<ScaleReading>, owner: String): Boolean {
        var stored = false
        for (r in readings) {
            val result = recordMutex.withLock {
                try {
                    recorder.record(r)?.let { KeyResult.Stored(it) } ?: KeyResult.NothingStored
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    val message = "No se pudo guardar la medición: ${e.message ?: e.javaClass.simpleName}"
                    _errors.tryEmit(message)
                    KeyResult.Error(message)
                }
            }
            if (result is KeyResult.Stored) {
                stored = stored || result.outcome !is RecordOutcome.Duplicate
                _outcomes.emit(result.outcome)
            }
            lastSaved = Saved(r, result)
            liveOwner = owner
            if (owner.startsWith("gatt:")) {
                gattLive = savedState(r, result)
                gattLiveAt = elapsedClock()
                liveOwner = GATT_OWNER
            }
        }
        if (readings.isNotEmpty()) refreshLive()
        return stored
    }

    private fun savedState(r: ScaleReading, result: KeyResult): LiveScale = when (result) {
        is KeyResult.Stored -> LiveScale.Saved(
            weightKg = r.weightKg,
            impedance = result.outcome.measurement.impedance,
            profileName = contextProvider().name.takeIf { it.isNotBlank() },
            alreadyStored = result.outcome is RecordOutcome.Duplicate,
        )
        is KeyResult.Error -> LiveScale.SaveFailed(r.weightKg, result.message)
        // Nothing stored (deleted by the user, or an upgrade of a deleted row): never claim "Guardado".
        KeyResult.NothingStored -> LiveScale.Idle
    }

    // ---- Live card ----------------------------------------------------------------------------

    private fun refreshLive() {
        val now = elapsedClock()
        val owner = liveOwner
        _live.value = when {
            owner == GATT_OWNER -> {
                val stale = gattLive !is LiveScale.Connecting && gattLive !is LiveScale.Measuring &&
                    now - gattLiveAt > SAVED_DISPLAY_MS
                if (stale) LiveScale.Idle else gattLive
            }
            owner != null -> {
                val entry = passive[owner]
                if (entry == null) {
                    LiveScale.Idle
                } else {
                    // Broadcast scales just stop advertising when stepped off: an in-progress
                    // state without adverts expires (the Mi Scale 2 assembler has its own timeouts).
                    val stale = entry.driverId != MiScale2Driver.ID && now - entry.lastAdvElapsed > LIVE_STALE_MS
                    when (val s = entry.session.live) {
                        LiveState.Idle -> LiveScale.Idle
                        is LiveState.Measuring -> if (stale) LiveScale.Idle else LiveScale.Measuring(s.weightKg)
                        is LiveState.Stabilized -> if (stale) LiveScale.Idle else LiveScale.Stabilized(s.weightKg)
                        LiveState.Done -> {
                            val saved = lastSaved
                            val expired = !entry.session.needsTick && now - entry.lastAdvElapsed > SAVED_DISPLAY_MS
                            if (saved == null || expired) LiveScale.Idle else savedState(saved.reading, saved.result)
                        }
                    }
                }
            }
            else -> LiveScale.Idle
        }
    }

    /**
     * The 1 s tick only runs while something time-based is pending (a passive session that needs
     * it, or a non-idle live card that must expire).
     */
    private fun ensureTicking() {
        if (tickJob?.isActive == true) return
        if (!needsTick()) return
        tickJob = scope.launch(dispatcher) {
            while (true) {
                delay(TICK_MS)
                for ((key, entry) in passive.toList()) {
                    if (!entry.session.needsTick) continue
                    val cs = knownConfigured.firstOrNull { it.id == entry.configId } ?: continue
                    val readings = try {
                        entry.session.tick(envFor(cs, entry.address))
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        emptyList()
                    }
                    if (readings.isNotEmpty()) {
                        liveOwner = key
                        records.trySend(PendingRecord(readings, key, cs.id, entry.address))
                    }
                }
                refreshLive()
                if (!needsTick()) break
            }
        }
    }

    private fun needsTick(): Boolean = passive.values.any { it.session.needsTick } || _live.value != LiveScale.Idle

    private fun pruneSessions(list: List<ConfiguredScale>) {
        val ids = list.map { it.id }.toSet()
        passive.entries.removeAll { it.value.configId !in ids }
        val running = gattConfigId
        if (running != null && running !in ids) gattJob?.cancel()
        _sessions.value = _sessions.value.filterKeys { it in ids }
        _lastSeen.value = _lastSeen.value.filterKeys { it in ids }
        if (liveOwner != null && liveOwner != GATT_OWNER && liveOwner !in passive) liveOwner = null
        refreshLive()
    }

    /**
     * Upgrade from versions before multi-scale support without a pinned scale: their weighings
     * came from a Mi Body Composition Scale 2, so one is set up (bound to the first device seen).
     */
    private suspend fun createLegacyMiScaleConfig() {
        if (settings.legacyScaleCheckDone) return
        val hasRows = try {
            recorder.hasLegacyMiScaleRows()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            false
        }
        settings.markLegacyScaleCheckDone()
        if (!hasRows) return
        settings.updateConfiguredScales { list ->
            if (list.isNotEmpty()) {
                list
            } else {
                list + ConfiguredScale(
                    id = UUID.randomUUID().toString(),
                    driverId = MiScale2Driver.ID,
                    address = null,
                    name = "Mi Body Composition Scale 2",
                    addedAt = wallClock(),
                    autoConfigured = true,
                )
            }
        }
    }

    // ---- Environment ---------------------------------------------------------------------------

    private fun envFor(cs: ConfiguredScale, address: String): DriverEnv {
        val ctx = contextProvider().copy(userSlot = cs.userSlot)
        return DriverEnv(
            ctx = ctx,
            store = ConfiguredScaleStore(settings, cs.id),
            driverId = cs.driverId,
            address = address,
            wallClock = wallClock,
            elapsedClock = elapsedClock,
            messages = { _messages.tryEmit(it) },
        )
    }

    /** Driver settings backed by the configured scale's config map (always the latest values). */
    private class ConfiguredScaleStore(private val settings: ScaleConfigStore, private val configId: String) : DriverStore {
        private fun current(): ConfiguredScale? = settings.configuredScales.value.firstOrNull { it.id == configId }

        override fun getString(key: String): String? = current()?.config?.get(key)

        override fun putString(key: String, value: String) {
            if (getString(key) == value) return
            settings.updateConfiguredScales { list ->
                list.map { if (it.id == configId) it.copy(config = it.config + (key to value)) else it }
            }
        }

        override fun remove(key: String) {
            if (getString(key) == null) return
            settings.updateConfiguredScales { list ->
                list.map { if (it.id == configId) it.copy(config = it.config - key) else it }
            }
        }
    }

    companion object {
        private const val TICK_MS = 1_000L
        private const val SAVED_DISPLAY_MS = 30_000L
        private const val LIVE_STALE_MS = 10_000L
        private const val DETECTED_PUBLISH_MS = 1_000L
        private const val GATT_COOLDOWN_MS = 45_000L
        private const val SCALE_GONE_MS = 20_000L
        private const val MAX_QUIET_WAIT_MS = 5 * 60_000L
        private const val GATT_RETRY_BASE_MS = 10_000L
        private const val GATT_RETRY_MAX_MS = 5 * 60_000L
        private const val EVICTION_INTERVAL_MS = 60_000L
        private const val PASSIVE_IDLE_EVICT_MS = 10 * 60_000L
        private const val GATT_OWNER = "gatt"

        private fun sessionKey(configId: String, address: String) = configId + "|" + address.uppercase(Locale.ROOT)
    }
}
