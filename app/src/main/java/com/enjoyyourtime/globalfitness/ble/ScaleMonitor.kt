package com.enjoyyourtime.globalfitness.ble

import com.enjoyyourtime.globalfitness.domain.MeasurementRecorder
import com.enjoyyourtime.globalfitness.domain.RecordOutcome
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** What the live scale card shows. */
sealed interface LiveScale {
    data object Idle : LiveScale
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

/**
 * Glue between the scanner, the [MeasurementAssembler] and the [MeasurementRecorder].
 * Lives in the application scope.
 */
class ScaleMonitor(
    scanner: ScaleScanner,
    private val recorder: MeasurementRecorder,
    private val scope: CoroutineScope,
    wallClock: () -> Long,
    elapsedClock: () -> Long,
) {
    private val assembler = MeasurementAssembler(wallClock, elapsedClock)
    private val mutex = Mutex()

    private val _live = MutableStateFlow<LiveScale>(LiveScale.Idle)
    val live: StateFlow<LiveScale> = _live.asStateFlow()

    private val _outcomes = MutableSharedFlow<RecordOutcome>(extraBufferCapacity = 16)

    /** Persisted results, for snackbars / assignment prompts. */
    val outcomes: SharedFlow<RecordOutcome> = _outcomes.asSharedFlow()

    private val _errors = MutableSharedFlow<String>(extraBufferCapacity = 4)

    /** Spanish error messages when a measurement could not be stored. */
    val errors: SharedFlow<String> = _errors.asSharedFlow()

    /** Result per committed scale key: an outcome, nothing stored, or an error message. */
    private sealed interface KeyResult {
        data class Stored(val outcome: RecordOutcome) : KeyResult
        data object NothingStored : KeyResult
        data class Error(val message: String) : KeyResult
    }

    private var lastResult: Pair<String, KeyResult>? = null
    private var tickJob: Job? = null

    init {
        scope.launch {
            scanner.frames.collect { ad ->
                mutex.withLock {
                    process(assembler.onFrame(ad.frame))
                    ensureTickingLocked()
                }
            }
        }
    }

    /**
     * The 1 s tick only runs while the assembler has time-based work pending. Must be called
     * with [mutex] held; the loop clears [tickJob] under the same lock before exiting.
     */
    private fun ensureTickingLocked() {
        if (tickJob != null || !assembler.needsTick) return
        tickJob = scope.launch {
            while (true) {
                delay(TICK_MS)
                val stop = mutex.withLock {
                    if (assembler.needsTick) process(assembler.tick())
                    if (assembler.needsTick) {
                        false
                    } else {
                        tickJob = null
                        true
                    }
                }
                if (stop) break
            }
        }
    }

    private suspend fun process(completed: List<CompletedMeasurement>) {
        for (c in completed) {
            val result = try {
                recorder.record(c)?.let { KeyResult.Stored(it) } ?: KeyResult.NothingStored
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val message = "No se pudo guardar la medición: ${e.message ?: e.javaClass.simpleName}"
                _errors.tryEmit(message)
                KeyResult.Error(message)
            }
            lastResult = c.scaleKey to result
            if (result is KeyResult.Stored) _outcomes.emit(result.outcome)
        }
        _live.value = when (val s = assembler.state) {
            AssemblerState.Idle -> LiveScale.Idle
            is AssemblerState.Measuring -> LiveScale.Measuring(s.weightKg)
            is AssemblerState.WaitingImpedance -> LiveScale.Stabilized(s.weightKg)
            is AssemblerState.Saved -> savedState(s.measurement)
        }
    }

    private fun savedState(m: CompletedMeasurement): LiveScale {
        val result = lastResult?.takeIf { it.first == m.scaleKey }?.second
        return when (result) {
            is KeyResult.Stored -> {
                val outcome = result.outcome
                val profileName = when (outcome) {
                    is RecordOutcome.Saved -> outcome.profile?.name
                    is RecordOutcome.Upgraded -> outcome.profile?.name
                    is RecordOutcome.Duplicate -> null
                }
                LiveScale.Saved(
                    weightKg = m.weightKg,
                    impedance = outcome.measurement.impedance,
                    profileName = profileName,
                    alreadyStored = outcome is RecordOutcome.Duplicate,
                )
            }
            is KeyResult.Error -> LiveScale.SaveFailed(m.weightKg, result.message)
            // Nothing stored (e.g. upgrade of a discarded measurement): never claim "Guardado".
            KeyResult.NothingStored, null -> LiveScale.Idle
        }
    }

    companion object {
        private const val TICK_MS = 1_000L
    }
}
