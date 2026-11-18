// SPDX-License-Identifier: Apache-2.0
package org.tatrman.automation.apply

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory
import org.tatrman.automation.api.AutomationException
import org.tatrman.automation.api.ErrorCode
import org.tatrman.automation.bundle.ManifestWiring
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.ZonedDateTime

/**
 * S2.8 (D-239, ttr-server#443): the wiring a preview refusal belongs to. [wiring] is the wiring's key — its position in
 * the recipe, its routes and its action (`#1:way.hold->release`); [kind] is `expression` or `condition`.
 */
internal data class PreviewSite(
    val run: String,
    val wiring: String,
    val action: String,
    val kind: String = "expression",
) {
    companion object {
        fun key(
            index: Int,
            wiring: ManifestWiring,
        ): String = "#$index:${wiring.routes.joinToString(",")}->${wiring.action}"
    }
}

/**
 * A deliberate preview refusal. [reason] is a fixed token — a compiler diagnostic id or the name of the check — never
 * data; [explanation] (the compiler's own text, if any) goes to the log only, redacted like any cause.
 */
internal class PreviewRefused(
    val reason: String,
    val explanation: String? = null,
) : RuntimeException(reason, null, false, false)

/**
 * The public refusal names the wiring key, the action, the expression's declared name and the cause — never generated
 * SQL, never a bound value. The cause's full message goes to one WARN line with the run id, every bound and staged
 * value replaced by `?` (by those values, not by pattern).
 */
internal object PreviewRefusals {
    private val log = LoggerFactory.getLogger(PreviewRefusals::class.java)

    /** A refusal already named (and logged) by an inner guard passes through unchanged. */
    private fun named(error: Throwable): Boolean =
        error is AutomationException && error.code == ErrorCode.PREVIEW_UNSUPPORTED && error.detail != null

    fun cause(error: Throwable): String =
        when (error) {
            is PreviewRefused -> error.reason
            else -> error.javaClass.simpleName.ifEmpty { error.javaClass.name }
        }

    fun refuse(
        message: String,
        site: PreviewSite?,
        expression: String?,
        error: Throwable,
        data: () -> Redaction = { Redaction.NONE },
    ): Nothing {
        if (error is AutomationException && named(error)) throw error
        val cause = cause(error)
        val detail =
            buildJsonObject {
                site?.let {
                    put("wiring", it.wiring)
                    put("action", it.action)
                    put("kind", it.kind)
                }
                expression?.let { put("expression", it) }
                put("cause", cause)
            }
        if (log.isWarnEnabled) {
            val text =
                when (error) {
                    is PreviewRefused -> error.explanation
                    else -> error.message
                }.orEmpty()
            val redacted =
                runCatching { data().let { redact(text, it.bound, it.routed) } }
                    .getOrElse { "<not shown: the redaction failed>" }
            log.warn(
                "preview-unsupported run={} wiring={} action={} kind={} expression={} cause={}: {}",
                site?.run,
                site?.wiring,
                site?.action,
                site?.kind,
                expression,
                cause,
                redacted,
            )
        }
        throw AutomationException(ErrorCode.PREVIEW_UNSUPPORTED, message, detail)
    }

    /** Names refusals that carry no detail of their own, e.g. the obligation transition mapping's. */
    inline fun <T> naming(
        site: PreviewSite,
        cause: String,
        block: () -> T,
    ): T =
        try {
            block()
        } catch (e: AutomationException) {
            if (e.code != ErrorCode.PREVIEW_UNSUPPORTED || e.detail != null) throw e
            refuse(e.message ?: e.code.code, site.copy(kind = "mapping"), null, PreviewRefused(cause))
        }

    /**
     * The values a cause's message could quote: every rendering of a [bound] value (the run's bindings), and of a
     * [routed] value (the staged route rows) of at least [ROUTED_MIN] characters — a shorter route value would turn
     * the engine's own words into `?` (`INT32` → `INT3?`) and is not worth the unreadable line.
     */
    data class Redaction(
        val bound: Sequence<Any?>,
        val routed: Sequence<Any?> = emptySequence(),
    ) {
        companion object {
            val NONE = Redaction(emptySequence())
        }
    }

    private const val ROUTED_MIN = 3

    /** Every rendering is replaced, longest first, so a shorter value never splits a longer one. */
    internal fun redact(
        message: String,
        bound: Sequence<Any?>,
        routed: Sequence<Any?> = emptySequence(),
    ): String {
        if (message.isEmpty()) return message
        val forms = sortedSetOf<String>(compareByDescending<String> { it.length }.thenBy { it })
        bound.forEach { forms += renderings(it) }
        routed.forEach { value -> forms += renderings(value).filter { it.length >= ROUTED_MIN } }
        return forms.fold(message) { text, form -> if (form.isEmpty()) text else text.replace(form, "?") }
    }

    private fun renderings(
        value: Any?,
        depth: Int = 0,
    ): Set<String> =
        when {
            value == null || depth > 4 -> emptySet()
            value is JsonNull -> emptySet()
            value is JsonPrimitive -> setOf(value.content)
            value is JsonObject -> value.values.flatMap { renderings(it, depth + 1) }.toSet()
            value is JsonArray -> value.flatMap { renderings(it, depth + 1) }.toSet()
            value is Iterable<*> -> value.flatMap { renderings(it, depth + 1) }.toSet()
            value is Map<*, *> -> value.values.flatMap { renderings(it, depth + 1) }.toSet()
            value is BigDecimal ->
                setOf(value.toString(), value.toPlainString(), value.stripTrailingZeros().toPlainString())
            value is Instant -> temporal(value.atOffset(ZoneOffset.UTC))
            value is OffsetDateTime -> temporal(value)
            value is ZonedDateTime -> temporal(value.toOffsetDateTime())
            value is LocalDateTime -> setOf(value.toString(), value.toString().replace('T', ' '))
            else -> setOf(value.toString())
        }

    private fun temporal(value: OffsetDateTime): Set<String> {
        val utc = value.withOffsetSameInstant(ZoneOffset.UTC)
        val local = utc.toLocalDateTime().toString()
        return setOf(
            value.toString(),
            utc.toInstant().toString(),
            local,
            local.replace('T', ' '),
            utc.toLocalDate().toString(),
        )
    }
}
