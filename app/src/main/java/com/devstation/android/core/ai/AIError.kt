package com.devstation.android.core.ai

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * HTTP status + exception based error normalization.
 * All messages are sanitized before reaching this layer.
 */
object AIErrorMapper {

    /**
     * Map an HTTP status code and provider error message to a normalized [AIError].
     */
    fun fromHttpCode(statusCode: Int, providerMessage: String?, modelId: String? = null): AIError {
        val sanitized = providerMessage?.take(300)?.trim().takeUnless { it.isNullOrEmpty() }
        return when (statusCode) {
            400 -> AIError.InvalidRequestError(sanitized ?: "The request was rejected as invalid.")
            401, 403 -> if (statusCode == 401) {
                AIError.AuthenticationError(sanitized ?: "Authentication failed. Check your API key.")
            } else {
                AIError.AuthorizationError(sanitized ?: "Permission denied for this operation.")
            }
            404 -> if (modelId != null) {
                AIError.ModelNotFoundError(modelId, "Selected model is unavailable.")
            } else {
                AIError.InvalidRequestError(sanitized ?: "Endpoint not found.")
            }
            408 -> AIError.TimeoutError()
            413 -> AIError.InvalidRequestError("Request too large for the provider.")
            429 -> AIError.RateLimitError(message = sanitized ?: "Rate limit reached.")
            in 500..599 -> AIError.ServerError(sanitized ?: "Provider server error. Try again later.")
            529 -> AIError.ServerError("Provider is overloaded. Try again later.")
            else -> AIError.UnknownError(sanitized ?: "Unexpected provider response (HTTP $statusCode).")
        }
    }

    fun fromIOException(e: IOException): AIError = when (e) {
        is SocketTimeoutException -> AIError.TimeoutError()
        is InterruptedIOException -> AIError.TimeoutError()
        is ConnectException -> AIError.NetworkError("Could not reach the provider.")
        is UnknownHostException -> AIError.NetworkError("No internet connection.")
        else -> AIError.NetworkError(cause = e)
    }

    /**
     * Coroutine cancellation must surface as cancellation, not a network error.
     */
    fun fromThrowable(e: Throwable): AIError = when (e) {
        is CancellationException -> AIError.CancelledError()
        is AIError -> e
        is IOException -> fromIOException(e)
        is SecurityException -> AIError.AuthorizationError("Request blocked by security policy (HTTPS required).")
        is IllegalArgumentException -> AIError.InvalidRequestError(e.message ?: "Invalid request configuration.")
        else -> AIError.UnknownError(cause = e)
    }

    /** Whether [error] is transient and may be retried under the retry policy. */
    fun isRetryable(error: AIError): Boolean = when (error) {
        is AIError.NetworkError,
        is AIError.ServerError,
        is AIError.TimeoutError -> true
        is AIError.RateLimitError -> true
        else -> false
    }
}

/**
 * Bounded retry with exponential backoff for transient failures only.
 * Never retries authentication/validation errors; never loops forever.
 * Honors provider Retry-After hints when present.
 */
class RetryPolicy(
    private val maxRetries: Int = DEFAULT_MAX_RETRIES,
    private val baseDelayMs: Long = DEFAULT_BASE_DELAY_MS,
    private val maxDelayMs: Long = DEFAULT_MAX_DELAY_MS
) {

    suspend fun <T> execute(block: suspend (attempt: Int) -> T): T {
        var attempt = 0
        while (attempt <= maxRetries) {
            try {
                return block(attempt)
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                val error = AIErrorMapper.fromThrowable(t)
                if (!AIErrorMapper.isRetryable(error) || attempt == maxRetries) {
                    throw error
                }
                attempt++
                val retryAfterMs = (error as? AIError.RateLimitError)?.retryAfterSeconds?.times(1000)
                val backoff = computeBackoff(attempt)
                val delayMs = maxOf(retryAfterMs ?: 0L, backoff).coerceAtMost(maxDelayMs)
                delay(delayMs)
            }
        }
        throw AIError.UnknownError()
    }

    private fun computeBackoff(attempt: Int): Long {
        // attempt is 1-based after increment
        val exponential = baseDelayMs shl (attempt - 1).coerceIn(0, 6)
        return exponential.coerceAtMost(maxDelayMs)
    }

    companion object {
        const val DEFAULT_MAX_RETRIES = 2
        const val DEFAULT_BASE_DELAY_MS = 500L
        const val DEFAULT_MAX_DELAY_MS = 4000L
    }
}
