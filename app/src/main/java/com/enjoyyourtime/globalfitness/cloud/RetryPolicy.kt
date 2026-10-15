package com.enjoyyourtime.globalfitness.cloud

/** Exponential backoff: base, 2x base, 4x base... capped at [maxDelayMs], for at most [maxAttempts] tries. */
data class RetryPolicy(
    val baseDelayMs: Long = 2_000,
    val maxDelayMs: Long = 60_000,
    val maxAttempts: Int = 5,
) {
    /** Delay before retry number [attempt] (1 = first retry). */
    fun delayFor(attempt: Int): Long {
        require(attempt >= 1)
        val shift = (attempt - 1).coerceAtMost(30)
        return (baseDelayMs shl shift).coerceIn(baseDelayMs, maxDelayMs)
    }

    /** True when another try is allowed after [attemptsMade] failed tries. */
    fun canRetry(attemptsMade: Int): Boolean = attemptsMade < maxAttempts
}
