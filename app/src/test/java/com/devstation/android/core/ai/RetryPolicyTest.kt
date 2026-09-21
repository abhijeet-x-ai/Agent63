package com.devstation.android.core.ai

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RetryPolicyTest {

    @Test
    fun `retries transient failures then succeeds`() = runTest {
        val policy = RetryPolicy(maxRetries = 2, baseDelayMs = 1, maxDelayMs = 4)
        var attempts = 0
        val result = policy.execute<Int> { attempt ->
            attempts = attempt + 1
            if (attempt < 2) throw java.io.IOException("connection reset")
            42
        }
        assertEquals(42, result)
        assertEquals(3, attempts)
    }

    @Test
    fun `does not retry authentication errors`() = runTest {
        val policy = RetryPolicy(maxRetries = 3, baseDelayMs = 1, maxDelayMs = 4)
        var attempts = 0
        try {
            policy.execute<Int> {
                attempts++
                throw AIError.AuthenticationError()
            }
            org.junit.Assert.fail("Expected AuthenticationError")
        } catch (e: AIError.AuthenticationError) {
            assertEquals(1, attempts)
        }
    }

    @Test
    fun `does not retry invalid request errors`() = runTest {
        val policy = RetryPolicy(maxRetries = 3, baseDelayMs = 1, maxDelayMs = 4)
        var attempts = 0
        try {
            policy.execute<Int> {
                attempts++
                throw AIError.InvalidRequestError()
            }
            org.junit.Assert.fail("Expected InvalidRequestError")
        } catch (e: AIError.InvalidRequestError) {
            assertEquals(1, attempts)
        }
    }

    @Test
    fun `gives up after max retries and wraps in AIError`() = runTest {
        val policy = RetryPolicy(maxRetries = 1, baseDelayMs = 1, maxDelayMs = 4)
        var attempts = 0
        try {
            policy.execute<Int> {
                attempts++
                throw java.io.IOException("still down")
            }
            org.junit.Assert.fail("Expected AIError.NetworkError")
        } catch (e: AIError.NetworkError) {
            assertEquals(2, attempts) // initial + 1 retry
        }
    }

    @Test
    fun `rate limit is retryable`() = runTest {
        assertTrue(AIErrorMapper.isRetryable(AIError.RateLimitError()))
        assertTrue(AIErrorMapper.isRetryable(AIError.NetworkError()))
        assertTrue(AIErrorMapper.isRetryable(AIError.ServerError()))
        assertTrue(AIErrorMapper.isRetryable(AIError.TimeoutError()))
        assertTrue(!AIErrorMapper.isRetryable(AIError.ModelNotFoundError("m")))
        assertTrue(!AIErrorMapper.isRetryable(AIError.AuthorizationError()))
    }

    @Test
    fun `maps http codes to normalized errors`() {
        assertTrue(AIErrorMapper.fromHttpCode(401, null) is AIError.AuthenticationError)
        assertTrue(AIErrorMapper.fromHttpCode(403, null) is AIError.AuthorizationError)
        assertTrue(AIErrorMapper.fromHttpCode(400, null) is AIError.InvalidRequestError)
        assertTrue(AIErrorMapper.fromHttpCode(404, null, modelId = "m") is AIError.ModelNotFoundError)
        assertTrue(AIErrorMapper.fromHttpCode(429, null) is AIError.RateLimitError)
        assertTrue(AIErrorMapper.fromHttpCode(500, null) is AIError.ServerError)
        assertTrue(AIErrorMapper.fromHttpCode(503, null) is AIError.ServerError)
    }

    @Test
    fun `cancellation propagates without retry`() = runTest {
        val policy = RetryPolicy(maxRetries = 3, baseDelayMs = 1, maxDelayMs = 4)
        var attempts = 0
        try {
            policy.execute<Int> {
                attempts++
                throw kotlinx.coroutines.CancellationException("cancelled")
            }
            org.junit.Assert.fail("Expected CancellationException")
        } catch (e: kotlinx.coroutines.CancellationException) {
            assertEquals(1, attempts)
        }
    }
}
