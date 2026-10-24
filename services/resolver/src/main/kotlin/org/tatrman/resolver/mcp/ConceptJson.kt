// SPDX-License-Identifier: Apache-2.0
package org.tatrman.resolver.mcp

import com.google.protobuf.Message
import com.google.protobuf.util.JsonFormat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.tatrman.concept.Diagnostic
import org.tatrman.concept.Explanation
import org.tatrman.concept.LowerProvenance
import org.tatrman.concept.LowerResult
import org.tatrman.concept.Rendering
import org.tatrman.concept.RowObjects
import org.tatrman.concept.Severity
import org.tatrman.resolver.concept.ConceptSearch

/**
 * CN-P5 S5.1 — the cn doors' results as JSON, in ONE shape per result (cn contracts §3's data classes). Keys are the
 * Kotlin property names, lowerCamel — the spelling protobuf `JsonFormat` gives the frames and the plan, and the one
 * ttr-concept's goldens are written in (`expected.provenance.json`, `expected.rows.json`), so a door result and a golden
 * read alike. Lists keep the module's order, which is deterministic (ttr-concept `DeterminismSpec`); an absent optional
 * is left out, never written as null. Two calls on one snapshot are therefore byte-equal.
 *
 * Every result carries `refused`: true when a finding is an ERROR — a refusal is a result, not an error (A-CN-57), so
 * it rides `structuredContent` with `isError = false`, the way `resolve.bind:v1` answers an empty resolution.
 */
internal object ConceptJson {
    private val printer: JsonFormat.Printer = JsonFormat.printer().omittingInsignificantWhitespace()
    private val json = Json

    fun proto(message: Message): JsonElement = json.parseToJsonElement(printer.print(message))

    fun refused(diagnostics: List<Diagnostic>): Boolean = diagnostics.any { it.severity == Severity.ERROR }

    fun diagnostics(diagnostics: List<Diagnostic>): JsonArray =
        buildJsonArray {
            for (d in diagnostics) {
                add(
                    buildJsonObject {
                        put("slug", d.slug)
                        put("severity", d.severity.name)
                        put("message", d.message)
                        put("subject", d.subject)
                        d.location?.let { l ->
                            put(
                                "location",
                                buildJsonObject {
                                    put("file", l.file)
                                    put("line", l.line)
                                    put("column", l.column)
                                },
                            )
                        }
                    },
                )
            }
        }

    fun validation(
        snapshotHash: String,
        diagnostics: List<Diagnostic>,
    ): JsonObject =
        buildJsonObject {
            put("snapshotHash", snapshotHash)
            put("refused", refused(diagnostics))
            put("diagnostics", diagnostics(diagnostics))
        }

    fun lowering(
        snapshotHash: String,
        r: LowerResult,
    ): JsonObject =
        buildJsonObject {
            put("snapshotHash", snapshotHash)
            put("refused", r.plan == null || refused(r.diagnostics))
            r.plan?.let { put("plan", proto(it)) }
            if (r.dotPath.isNotEmpty()) put("dotPath", r.dotPath)
            put("provenance", provenance(r.provenance))
            put("diagnostics", diagnostics(r.diagnostics))
        }

    fun provenance(p: LowerProvenance): JsonObject =
        buildJsonObject {
            put("snapshotHash", p.snapshotHash)
            put("grammarVersion", p.grammarVersion)
            put("catalogVersion", p.catalogVersion)
            put(
                "bindingPaths",
                buildJsonArray {
                    for (b in p.bindingPaths) {
                        add(
                            buildJsonObject {
                                put("slot", b.slot)
                                put("concept", b.concept)
                                put("bindingDef", b.bindingDef)
                                put("element", b.element)
                                put("via", b.via.name)
                            },
                        )
                    }
                },
            )
            put("inlinedDefinitions", strings(p.inlinedDefinitions))
            put("parametersBound", JsonObject(p.parametersBound.mapValues { JsonPrimitive(it.value) }))
            put("asOf", p.asOf)
            put("imprecisionMode", p.imprecisionMode.name)
        }

    fun explanation(
        snapshotHash: String,
        e: Explanation,
    ): JsonObject =
        buildJsonObject {
            put("snapshotHash", snapshotHash)
            put("locale", e.locale)
            put("text", e.text)
            put("diagnostics", diagnostics(e.diagnostics))
        }

    fun rows(
        snapshotHash: String,
        r: RowObjects,
    ): JsonObject =
        buildJsonObject {
            put("snapshotHash", snapshotHash)
            put("refused", refused(r.diagnostics))
            put(
                "rows",
                buildJsonArray {
                    for (row in r.rows) {
                        add(
                            buildJsonObject {
                                put("entity", row.entity)
                                put("values", JsonObject(row.values.mapValues { JsonPrimitive(it.value) }))
                            },
                        )
                    }
                },
            )
            r.assertedAsOf?.let { put("assertedAsOf", it) }
            put(
                "meta",
                buildJsonObject {
                    r.meta.provenance?.let { put("provenance", proto(it)) }
                    put("derivedFrom", strings(r.meta.derivedFrom))
                    put("snapshotHash", r.meta.snapshotHash)
                    put("locale", r.meta.locale)
                    put("endState", r.meta.endState.name)
                    put("verbatims", JsonObject(r.meta.verbatims.mapValues { JsonPrimitive(it.value) }))
                },
            )
            put(
                "obligations",
                buildJsonArray {
                    for (o in r.obligations) {
                        add(
                            buildJsonObject {
                                put("label", o.label)
                                put("value", o.value)
                                put("relations", strings(o.relations))
                                put("anchor", o.anchor)
                                put("anchoredBy", o.anchoredBy)
                            },
                        )
                    }
                },
            )
            put("diagnostics", diagnostics(r.diagnostics))
        }

    fun rendering(
        snapshotHash: String,
        r: Rendering,
    ): JsonObject =
        buildJsonObject {
            put("snapshotHash", snapshotHash)
            put("refused", refused(r.diagnostics))
            put("text", r.text)
            put(
                "parametersRead",
                buildJsonArray {
                    for (p in r.parametersRead) {
                        add(
                            buildJsonObject {
                                put("name", p.name)
                                p.derivation?.let { put("derivation", it) }
                            },
                        )
                    }
                },
            )
            put("definitionsInlined", strings(r.definitionsInlined))
            put("imprecisionMode", r.imprecisionMode.name)
            put("diagnostics", diagnostics(r.diagnostics))
        }

    /**
     * `concept.search:v1`'s answer. A candidate is `Candidate`'s fields (cn contracts §2.1: `qname`, `confidence` —
     * producer-tagged, RG S-4) plus what §4 asks the door to stamp: the stereotype, `via`, every source, the ungrounded
     * note, and the evidence class the score is comparable within. `resume_token` is never minted here (T7).
     */
    fun search(
        snapshotHash: String,
        locale: String,
        hits: List<ConceptSearch.Hit>,
    ): JsonObject =
        buildJsonObject {
            put("snapshotHash", snapshotHash)
            put("locale", locale)
            put(
                "candidates",
                buildJsonArray {
                    for (h in hits) {
                        add(
                            buildJsonObject {
                                put("qname", h.qname)
                                put("stereotype", h.stereotype)
                                put("via", h.via)
                                put("sources", strings(h.sources))
                                if (h.note.isNotEmpty()) put("note", h.note)
                                put("evidenceClass", h.evidenceClass.name)
                                put(
                                    "confidence",
                                    buildJsonObject {
                                        put("value", h.score)
                                        put("producer", CONFIDENCE_PRODUCER)
                                        put("method", CONCEPT_SEARCH_TOOL_NAME)
                                    },
                                )
                            },
                        )
                    }
                },
            )
        }

    /** RG S-4 — the producer a search candidate's confidence is tagged with: the service that scored it. */
    const val CONFIDENCE_PRODUCER = "resolver"

    private fun strings(values: List<String>): JsonArray = JsonArray(values.map(::JsonPrimitive))
}
