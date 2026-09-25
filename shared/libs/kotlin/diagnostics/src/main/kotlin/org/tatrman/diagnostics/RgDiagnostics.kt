// SPDX-License-Identifier: Apache-2.0
package org.tatrman.diagnostics

/**
 * RG-* diagnostics registry (RG-P0.S3.T7) — the house convention for the
 * resolution & grounding services: named, stable, severity-typed, fixture-backed.
 *
 * The id set + severities are pinned by contracts §8 (and asserted by
 * `RgDiagnosticsSpec`, whose table is the source of truth). Every service that
 * emits one of these conditions renders it through here so the id, severity, and
 * wording stay consistent across nlp/fuzzy/grounding/resolver.
 *
 * Message templates use `{name}` placeholders filled by [RgDiagnostics.render].
 */
enum class Severity { ERROR, WARNING, INFO }

data class RgDiagnostic(
    val id: String,
    val severity: Severity,
    val messageTemplate: String,
    val suggestion: String,
)

object RgDiagnostics {
    private val registry: Map<String, RgDiagnostic> =
        listOf(
            RgDiagnostic(
                "RG-NLP-001",
                Severity.ERROR,
                "No engine backend reachable for routed ({language}, {op}) at startup.",
                "Deploy or repoint the {op} backend for {language}; verify the front routing table.",
            ),
            RgDiagnostic(
                "RG-NLP-002",
                Severity.WARNING,
                "Route ({language}, {op}) points at a REMOTE_UNPINNED tier ({endpoint}) — non-conformant for parity/determinism.",
                "Repoint to a self-hosted, model-pinned backend (SELF_HOSTED_PINNED); keep Lindat as a labelled dev/eval tier only.",
            ),
            RgDiagnostic(
                "RG-NLP-003",
                Severity.ERROR,
                "Backend '{engine}' launched without an explicit model id (S-1 violation).",
                "Set the model id in the backend config/chart; no empty or default model is permitted.",
            ),
            RgDiagnostic(
                "RG-NLP-010",
                Severity.INFO,
                "Unsupported ({language}, {op}) — degrade floor applied (tokenize + fold + langid).",
                "Branch on GetStatus; expect only the degrade-floor ops for this language.",
            ),
            RgDiagnostic(
                "RG-FUZ-001",
                Severity.WARNING,
                "Member vocabulary '{category}' skipped — its owner has no single key (none declared, or a composite one).",
                "Give the owning entity a single key attribute, or the table a single-column primary key. A view declares none: index its column through an entity over the view.",
            ),
            RgDiagnostic(
                "RG-FUZ-002",
                Severity.ERROR,
                "Explicit but unknown fuzzy category '{category}' — leak guard returns EMPTY (never a global match).",
                "Use a category advertised by GetStatus, or omit the category for a global match.",
            ),
            // MV-T1 — Veles cannot render a member vocabulary's read plan. Listed, `read_sql` empty:
            // an index the translator cannot read is skipped loudly, never composed some other way.
            RgDiagnostic(
                "RG-FUZ-003",
                Severity.WARNING,
                "Member vocabulary '{category}' has no read plan — {reason}",
                "The reason names the cause: an expression-mapped attribute, or an owner outside the translator's default namespace, is not renderable yet; anything else is the translator's own error. The vocabulary stays unloaded until it renders.",
            ),
            // MV-T2 — the lex-matcher could not list member vocabularies. It keeps serving its previous
            // load rather than an empty member layer: an outage must not read as "the estate has no values".
            RgDiagnostic(
                "RG-FUZ-004",
                Severity.WARNING,
                "Member vocabularies unavailable — {reason}; the previous load is still served.",
                "A Veles that predates ListMemberVocabularies answers UNIMPLEMENTED: release Veles and lex-matcher together. Otherwise check that Veles is reachable and has a model loaded.",
            ),
            RgDiagnostic(
                "RG-GND-001",
                Severity.WARNING,
                "Geo capability off (no Nominatim/PostGIS) — geo grounding degraded, fixtures conditional.",
                "Configure a Nominatim endpoint + primed boundary cache, or expect geo results to be skipped/dark.",
            ),
            RgDiagnostic(
                "RG-GND-002",
                Severity.ERROR,
                "FX requested against a time-versioned fx_rate table without an as-of date.",
                "Supply an as-of/reference date so the rate is resolvable; the service fails loud rather than guessing.",
            ),
            RgDiagnostic(
                "RG-RES-001",
                Severity.INFO,
                "Binding '{span}' degraded — the capability matrix forced a floor.",
                "Check GetStatus; the required engine/capability (e.g. cs NER) is unavailable for this language.",
            ),
            RgDiagnostic(
                "RG-RES-002",
                Severity.ERROR,
                "Resume token invalid/expired/blocked-key (HMAC verify failed).",
                "Re-issue clarification through the resolver; never fabricate the option set — the resolver signs it.",
            ),
        ).associateBy { it.id }

    /** Every registered diagnostic. */
    fun all(): List<RgDiagnostic> = registry.values.toList()

    /** The diagnostic for [id]; throws [IllegalArgumentException] on an unknown id. */
    operator fun get(id: String): RgDiagnostic =
        registry[id] ?: throw IllegalArgumentException("Unknown RG diagnostic id: $id")

    /** Render [id]'s message template, substituting `{name}` placeholders from [args]. */
    fun render(
        id: String,
        vararg args: Pair<String, String>,
    ): String {
        var msg = get(id).messageTemplate
        for ((k, v) in args) msg = msg.replace("{$k}", v)
        return msg
    }
}

/** Carries an [RgDiagnostic] so a caller can branch on the stable id/severity. */
class RgDiagnosticException(
    val diagnostic: RgDiagnostic,
    detail: String? = null,
) : RuntimeException(
        buildString {
            append("[").append(diagnostic.id).append("] ").append(diagnostic.messageTemplate)
            if (!detail.isNullOrBlank()) append(" — ").append(detail)
        },
    )
