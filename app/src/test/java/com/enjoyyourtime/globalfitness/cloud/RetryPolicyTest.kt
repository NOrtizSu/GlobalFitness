package com.enjoyyourtime.globalfitness.cloud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RetryPolicyTest {

    @Test
    fun delaysDoubleUntilTheCap() {
        val policy = RetryPolicy(baseDelayMs = 2_000, maxDelayMs = 20_000, maxAttempts = 10)
        assertEquals(listOf(2_000L, 4_000L, 8_000L, 16_000L, 20_000L, 20_000L), (1..6).map(policy::delayFor))
    }

    @Test
    fun hugeAttemptNumbersDoNotOverflow() {
        val policy = RetryPolicy(baseDelayMs = 30_000, maxDelayMs = 600_000, maxAttempts = Int.MAX_VALUE)
        assertEquals(600_000L, policy.delayFor(1_000))
    }

    @Test
    fun stopsAfterMaxAttempts() {
        val policy = RetryPolicy(maxAttempts = 3)
        assertTrue(policy.canRetry(2))
        assertFalse(policy.canRetry(3))
    }
}
