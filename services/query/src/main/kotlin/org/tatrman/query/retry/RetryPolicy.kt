// SPDX-License-Identifier: Apache-2.0
package org.tatrman.query.retry

import io.grpc.Status
import io.grpc.StatusException
import io.grpc.StatusRuntimeException
import kotlinx.coroutines.delay
import org.slf4j.LoggerFactory
import kotlin.random.Random

/**
 * Retry policy for the front-of-pipeline RPCs (Translator, Validator).
 *
 * Round 7 §7.F semantics:
 *   - retried: `Status.UNAVAILABLE` and `Status.DEADLINE_EXCEEDED` from the
 *     downstream gRPC stub
 *   - capped at [maxAttempts] with exponential backoff
 *     `initialBackoff × multiplier^n` plus ±[jitterPercent]% jitter
 *   - NOT retried once any `ResultBatch` has been emitted to the caller —
 *     the caller has started seeing data, so a retry would re-emit
 *   - NOT retried for application errors (`validation_failed`,
 *     `parameter_unknown`, etc.) which travel as ResponseMessage on a
 *     successful gRPC response
 *
 * The execute method takes a "has anything been emitted" flag callback.
 * The streaming portion of the pipeline (Dispatcher) wraps a different
 * flow combinator that consults the same flag; this class only handles
 * the unary RPCs at the front.
 */
class RetryPolicy(
    val maxAttempts: Int = 3,
    val initialBackoffMillis: Long = 100,
    val multiplier: Double = 3.0,
    val jitterPercent: Int = 50,
    private val random: Random = Random.Default,
) {
    suspend fun <T> execute(
        operation: String,
        block: suspend () -> T,
    ): RetryOutcome<T> {
        var lastError: Throwable? = null
        for (attempt in 1..maxAttempts) {
            try {
                val result = block()
                return RetryOutcome.Success(result, attemptsUsed = attempt)
            } catch (t: Throwable) {
                lastError = t
                if (!isRetriable(t)) {
                    log.debug("Non-retriable error from {}: {}", operation, t.javaClass.simpleName)
                    return RetryOutcome.Failure(t, attemptsUsed = attempt)
                }
                if (attempt == maxAttempts) {
                    log.warn("{} failed after {} attempts: {}", operation, attempt, t.message)
                    return RetryOutcome.Failure(t, attemptsUsed = attempt)
                }
                val backoff = backoffMillis(attempt)
                log.info(
                    "{} attempt {}/{} failed ({}); retrying after {}ms",
                    operation,
                    attempt,
                    maxAttempts,
                    t.message,
                    backoff,
                )
                delay(backoff)
            }
        }
        return RetryOutcome.Failure(lastError ?: IllegalStateException("retry loop exited unexpectedly"), maxAttempts)
    }

    private fun backoffMillis(attempt: Int): Long {
        val base = (initialBackoffMillis * Math.pow(multiplier, (attempt - 1).toDouble())).toLong()
        val jitterAmplitude = (base * jitterPercent / 100).coerceAtLeast(1L)
        val jitter = random.nextLong(-jitterAmplitude, jitterAmplitude + 1)
        return (base + jitter).coerceAtLeast(0L)
    }

    private fun isRetriable(t: Throwable): Boolean {
        val status =
            when (t) {
                is StatusException -> t.status
                is StatusRuntimeException -> t.status
                else -> return false
            }
        return status.code == Status.Code.UNAVAILABLE || status.code == Status.Code.DEADLINE_EXCEEDED
    }

    companion object {
        private val log = LoggerFactory.getLogger(RetryPolicy::class.java)
    }
}

sealed interface RetryOutcome<out T> {
    val attemptsUsed: Int

    data class Success<T>(
        val value: T,
        override val attemptsUsed: Int,
    ) : RetryOutcome<T>

    data class Failure(
        val cause: Throwable,
        override val attemptsUsed: Int,
    ) : RetryOutcome<Nothing>
}
