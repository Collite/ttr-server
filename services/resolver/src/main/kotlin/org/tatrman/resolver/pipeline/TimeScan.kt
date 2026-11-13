// SPDX-License-Identifier: Apache-2.0
package org.tatrman.resolver.pipeline

import kotlinx.coroutines.CancellationException
import org.slf4j.LoggerFactory
import org.tatrman.common.v1.Severity
import org.tatrman.grounding.v1.EntityKind
import org.tatrman.grounding.v1.GroundingContext
import org.tatrman.nlp.v1.AnalyzeResponse
import org.tatrman.nlp.v1.NerEntity
import org.tatrman.resolver.client.GroundingClient
import org.tatrman.resolver.v1.UniversalEntityType

/** The Scan wire boundary: blank literals, keep NER dates, and add the kernel's remaining dates. */
object TimeScan {
    private val log = LoggerFactory.getLogger(TimeScan::class.java)

    data class Outcome(
        val parse: AnalyzeResponse,
        val failed: Boolean = false,
    )

    suspend fun augment(
        client: GroundingClient,
        parse: AnalyzeResponse,
        text: String,
        literals: Literals,
        context: GroundingContext,
        pkg: String,
    ): Outcome {
        try {
            // Literals and NLP use code points; Scan uses UTF-16. Blank in the wire's coordinate
            // space, retaining every code unit, then convert returned positions at this boundary
            // exactly as QuoteScanner.toTokenSpans does for its own UTF-16 scanner.
            val blanked = text.toCharArray()
            for (literal in literals.spans) {
                val start = text.offsetByCodePoints(0, literal.literal.start)
                val end = text.offsetByCodePoints(0, literal.literal.end)
                blanked.fill(' ', start, end)
            }
            val scanText = blanked.concatToString()
            val response = client.scan(scanText, context, pkg)
            require(response.messagesList.none { it.severity == Severity.ERROR }) { "Scan returned an error" }
            val dates =
                parse.entitiesList.filter {
                    UniversalClassifier.classify(it.label, it.normalizedValue, it.text) == UniversalEntityType.DATE
                }
            val added =
                response.spansList.mapNotNull { span ->
                    require(
                        span.kind == EntityKind.DATE_TIME &&
                            span.start >= 0 &&
                            span.start < span.end &&
                            span.end <= text.length,
                    ) {
                        "Scan returned an invalid date span"
                    }
                    require(scanText.substring(span.start, span.end) == span.text && span.text.isNotBlank()) {
                        "Scan returned text inconsistent with its offsets"
                    }
                    val start = text.codePointCount(0, span.start)
                    val end = text.codePointCount(0, span.end)
                    if (literals.overlaps(start, end) || dates.any { it.charStart < end && start < it.charEnd }) {
                        null
                    } else {
                        NerEntity
                            .newBuilder()
                            .setText(span.text)
                            .setLabel("DATE")
                            .setCharStart(start)
                            .setCharEnd(end)
                            .setSourceEngine("chrono.scan")
                            .build()
                    }
                }
            return Outcome(
                if (added.isEmpty()) {
                    parse
                } else {
                    parse
                        .toBuilder()
                        .clearEntities()
                        .addAllEntities((parse.entitiesList + added).sortedBy { it.charStart })
                        .build()
                },
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("scan_failed: Scan unavailable or invalid; keeping NER spans: {}", e.toString())
            return Outcome(parse, failed = true)
        }
    }
}
