package com.enjoyyourtime.globalfitness.band

/** A heart rate reading kept in band.db, mergeable later with Health Connect data. */
data class HeartRateSample(val timestampMs: Long, val bpm: Int, val source: String = SOURCE_BAND_LIVE) {
    companion object {
        const val SOURCE_BAND_LIVE = "band-live"
    }
}

/**
 * Decides which realtime readings are stored. The band reports 0 (or a few beats) while it is still
 * measuring — Gadgetbridge's one-shot measurement waits for a value above 10 — so those are skipped, as
 * are implausible values and more than one sample per [minIntervalMs].
 */
class HeartRateRecorder(private val minIntervalMs: Long = 1_000L) {
    private var lastStoredAt: Long? = null

    fun accept(bpm: Int, timestampMs: Long): HeartRateSample? {
        if (!isValid(bpm)) return null
        val last = lastStoredAt
        if (last != null && timestampMs in last until last + minIntervalMs) return null
        lastStoredAt = timestampMs
        return HeartRateSample(timestampMs, bpm)
    }

    fun reset() {
        lastStoredAt = null
    }

    companion object {
        const val MIN_VALID_BPM = 11
        const val MAX_VALID_BPM = 250

        fun isValid(bpm: Int): Boolean = bpm in MIN_VALID_BPM..MAX_VALID_BPM
    }
}
