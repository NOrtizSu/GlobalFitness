package com.enjoyyourtime.globalfitness.ble

import kotlin.math.abs

/** A finished weighing ready to be persisted. */
data class CompletedMeasurement(
    val weightKg: Double,
    val impedance: Int?,
    /** Dedupe key (scale time + raw weight). */
    val scaleKey: String,
    val receivedAtMillis: Long,
    /**
     * True when this only adds the impedance to a measurement that was already committed
     * weight-only under [scaleKey]; the receiver must update instead of inserting.
     */
    val isImpedanceUpgrade: Boolean = false,
)

/** Live state of the weighing in progress. */
sealed interface AssemblerState {
    data object Idle : AssemblerState

    /** Weight is still changing. */
    data class Measuring(val weightKg: Double) : AssemblerState

    /** Weight is stable; the scale is measuring impedance. */
    data class WaitingImpedance(val weightKg: Double) : AssemblerState

    /** A measurement was committed. */
    data class Saved(val measurement: CompletedMeasurement) : AssemblerState
}

/**
 * Turns the stream of advertisement frames into exactly-once [CompletedMeasurement]s.
 *
 * Rules:
 * - Commit as soon as a frame is stabilized and has a valid impedance.
 * - If a stabilized frame was seen but no impedance arrived, commit weight-only on a
 *   load-removed frame, when a new weighing starts (live weight more than 1 kg away), or after
 *   [weightOnlyTimeoutMs] without frames (see [tick]).
 * - The scale re-broadcasts the final frame for a minute or more: an in-memory set of
 *   dedupe keys (scale time + raw weight) prevents committing twice. As a safeguard against the
 *   scale clock ticking during re-broadcast, a complete frame with the same raw weight and
 *   impedance as the last commit within [REBROADCAST_WINDOW_MS] is ignored too.
 * - An impedance frame arriving after a weight-only commit of the same weighing produces an
 *   upgrade ([CompletedMeasurement.isImpedanceUpgrade]) instead of a new measurement.
 *
 * [wallClock] only stamps measurements; every timeout uses the monotonic [elapsed] clock.
 * Not thread-safe: call from a single thread/coroutine.
 */
class MeasurementAssembler(
    private val wallClock: () -> Long = System::currentTimeMillis,
    private val elapsed: () -> Long = { System.nanoTime() / 1_000_000 },
    private val weightOnlyTimeoutMs: Long = WEIGHT_ONLY_TIMEOUT_MS,
) {
    private class Pending(val frame: ScaleFrame, val key: String, val seenAtWall: Long)

    private class WeightOnlyCommit(
        val key: String,
        val rawWeight: Int,
        val committedAt: Long,
        val measurement: CompletedMeasurement,
    )

    private class LastCommit(val rawWeight: Int, val impedance: Int?, val at: Long)

    var state: AssemblerState = AssemblerState.Idle
        private set

    private var pendingStable: Pending? = null
    private var lastFrameAt: Long = 0L
    private var lastCommit: LastCommit? = null
    private val committedKeys = LinkedHashSet<String>()
    private val weightOnlyCommits = LinkedHashMap<String, WeightOnlyCommit>()

    /** True while there is something that may change on [tick]. */
    val needsTick: Boolean get() = state != AssemblerState.Idle || pendingStable != null

    fun onFrame(frame: ScaleFrame): List<CompletedMeasurement> {
        val wall = wallClock()
        val now = elapsed()
        lastFrameAt = now
        val key = frame.dedupeKey

        if (frame.stabilized && frame.impedance != null && frame.weightKg >= MIN_WEIGHT_KG) {
            return handleCompleteFrame(frame, frame.impedance, key, wall, now)
        }

        if (frame.loadRemoved) {
            val pending = pendingStable
            if (pending != null) {
                return listOf(commitWeightOnly(pending, now))
            }
            if (frame.stabilized && frame.weightKg >= MIN_WEIGHT_KG && key !in committedKeys) {
                // Final weight-only frame of a weighing we caught after the user stepped off.
                return listOf(commitWeightOnly(Pending(frame, key, wall), now))
            }
            if (state !is AssemblerState.Saved) state = AssemblerState.Idle
            return emptyList()
        }

        if (frame.stabilized) {
            if (key in committedKeys || frame.weightKg < MIN_WEIGHT_KG) return emptyList()
            if (pendingStable?.key != key) pendingStable = Pending(frame, key, wall)
            state = AssemblerState.WaitingImpedance(frame.weightKg)
            return emptyList()
        }

        // Live (not yet stable) weight. A clearly different weight means the previous weighing
        // is over (stepped off / someone else stepped on): do not lose its stable weight.
        val pending = pendingStable
        val out = if (pending != null && abs(frame.weightKg - pending.frame.weightKg) > NEW_WEIGHING_DELTA_KG) {
            listOf(commitWeightOnly(pending, now))
        } else {
            emptyList()
        }
        if (frame.weightKg >= MIN_WEIGHT_KG) {
            state = AssemblerState.Measuring(frame.weightKg)
        }
        return out
    }

    /** Call periodically (about once per second) to apply time-based rules. */
    fun tick(): List<CompletedMeasurement> {
        val now = elapsed()
        val silentFor = now - lastFrameAt
        val pending = pendingStable
        if (pending != null && silentFor >= weightOnlyTimeoutMs) {
            return listOf(commitWeightOnly(pending, now))
        }
        when (state) {
            is AssemblerState.Measuring, is AssemblerState.WaitingImpedance ->
                if (silentFor >= weightOnlyTimeoutMs) state = AssemblerState.Idle
            is AssemblerState.Saved ->
                if (silentFor >= SAVED_DISPLAY_MS) state = AssemblerState.Idle
            AssemblerState.Idle -> Unit
        }
        return emptyList()
    }

    private fun handleCompleteFrame(
        frame: ScaleFrame,
        impedance: Int,
        key: String,
        wall: Long,
        now: Long,
    ): List<CompletedMeasurement> {
        if (key in committedKeys) {
            val weightOnly = weightOnlyCommits.remove(key) ?: return emptyList() // plain re-broadcast
            return listOf(upgrade(weightOnly, impedance, now))
        }

        val last = lastCommit
        if (last != null && last.rawWeight == frame.rawWeight && last.impedance == impedance &&
            now - last.at <= REBROADCAST_WINDOW_MS
        ) {
            // Same reading re-broadcast with a ticked scale clock.
            remember(key)
            return emptyList()
        }

        // Same weighing but the scale clock ticked between frames: match by raw weight and time.
        val recentWeightOnly = weightOnlyCommits.values.lastOrNull {
            it.rawWeight == frame.rawWeight && now - it.committedAt <= UPGRADE_WINDOW_MS
        }
        if (recentWeightOnly != null) {
            weightOnlyCommits.remove(recentWeightOnly.key)
            remember(key)
            return listOf(upgrade(recentWeightOnly, impedance, now))
        }

        val measurement = CompletedMeasurement(
            weightKg = frame.weightKg,
            impedance = impedance,
            scaleKey = key,
            receivedAtMillis = wall,
        )
        remember(key)
        pendingStable = null
        lastCommit = LastCommit(frame.rawWeight, impedance, now)
        state = AssemblerState.Saved(measurement)
        return listOf(measurement)
    }

    private fun upgrade(weightOnly: WeightOnlyCommit, impedance: Int, now: Long): CompletedMeasurement {
        val upgraded = weightOnly.measurement.copy(impedance = impedance, isImpedanceUpgrade = true)
        pendingStable = null
        lastCommit = LastCommit(weightOnly.rawWeight, impedance, now)
        state = AssemblerState.Saved(upgraded)
        return upgraded
    }

    private fun commitWeightOnly(pending: Pending, now: Long): CompletedMeasurement {
        val measurement = CompletedMeasurement(
            weightKg = pending.frame.weightKg,
            impedance = null,
            scaleKey = pending.key,
            receivedAtMillis = pending.seenAtWall,
        )
        remember(pending.key)
        weightOnlyCommits[pending.key] = WeightOnlyCommit(pending.key, pending.frame.rawWeight, now, measurement)
        while (weightOnlyCommits.size > MAX_REMEMBERED_WEIGHT_ONLY) {
            weightOnlyCommits.remove(weightOnlyCommits.keys.first())
        }
        pendingStable = null
        lastCommit = LastCommit(pending.frame.rawWeight, null, now)
        state = AssemblerState.Saved(measurement)
        return measurement
    }

    private fun remember(key: String) {
        committedKeys.add(key)
        while (committedKeys.size > MAX_REMEMBERED_KEYS) {
            committedKeys.remove(committedKeys.first())
        }
    }

    companion object {
        const val WEIGHT_ONLY_TIMEOUT_MS = 10_000L
        const val SAVED_DISPLAY_MS = 30_000L
        const val UPGRADE_WINDOW_MS = 3 * 60_000L
        const val REBROADCAST_WINDOW_MS = 10 * 60_000L
        const val NEW_WEIGHING_DELTA_KG = 1.0
        const val MIN_WEIGHT_KG = 1.0
        private const val MAX_REMEMBERED_KEYS = 512
        private const val MAX_REMEMBERED_WEIGHT_ONLY = 16
    }
}
