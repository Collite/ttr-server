// SPDX-License-Identifier: Apache-2.0
package org.tatrman.resolver.pipeline

import org.slf4j.LoggerFactory
import org.tatrman.grounding.v1.EntityKind
import org.tatrman.grounding.v1.GroundRequest
import org.tatrman.grounding.v1.GroundResponse
import org.tatrman.grounding.v1.GroundingContext
import org.tatrman.grounding.v1.GroundingResult
import org.tatrman.plan.v1.QualifiedName
import org.tatrman.plan.v1.SchemaCode
import org.tatrman.resolver.client.GroundingClient
import org.tatrman.resolver.v1.UniversalEntityType

/**
 * ✅ **R1 (Bora, 2026-08-28) — the grounding rung.**
 *
 * A universal DATE span arrives from the NER layer typed but **inert**: `2025` carries a kind and
 * a surface string, and nothing that could restrict a query. Two facts are missing, and neither
 * can be recovered inside this service:
 *
 *  - **what interval it denotes** — generative in the kernel, which is why a pure-pattern span
 *    like `12.5.2024` grounds with zero lexicon entries;
 *  - **which column that interval applies to** — discovered from `meta.v1` semantic roles
 *    (`role: event_date`), and the resolver has no metadata client.
 *
 * The second is the one that matters. Without it the composed question carries a grain nothing
 * restricts by, and `TransDslQueryDoor` refuses — correctly, because the alternative is picking a
 * date column by guessing and answering a different question confidently.
 *
 * ## What this is NOT
 *
 * It is not a lexicon problem, and it is worth saying so because it is the natural first guess.
 * The grounding *trigger* vocabulary already reaches the lattice — `month` → `ground:chrono` has
 * bound all along ([GroundingTriggers]) — and the lib is explicit that it is *"a trigger
 * vocabulary, not an interpretation grammar"*. A trigger ANCHORS the kernel; it does not say what
 * the span means. And `2025` needs no trigger at all. No amount of vocabulary substitutes for the
 * kernel.
 *
 * ## Fail-open, on purpose
 *
 * Every failure path — rung off, no package, kernel down, deadline, `UNGROUNDABLE`, a non-ER
 * anchor — leaves the value exactly as it was and returns no entry for it. The lattice then is
 * the pre-R1 lattice, and the door refuses the way it did before. An optional rung that could
 * make a turn *worse* than not running would not be optional.
 */
object GroundingRung {
    private val log = LoggerFactory.getLogger(GroundingRung::class.java)

    /**
     * The namespace an ER attribute's `QualifiedName` carries. Verified off the live wire
     * (hartland, 2026-08-31), both sides of the hop:
     *
     * ```
     * anchor_column { schema_code: ER namespace: "entity" name: "date_dim.cal_date" }
     * ```
     *
     * The ENTITY is in `name`, not in the namespace — `name` is `<entity>.<attribute>`.
     */
    private const val ENTITY_NAMESPACE = "entity"

    /**
     * What a kernel added to one span: the interval, and the attribute to apply it to.
     *
     * [anchorAttributeRef] is blank when the kernel grounded the span but named no column it
     * could be applied to — a real and distinct outcome (the estate declares no `event_date`),
     * and one worth carrying rather than dropping: the normalized value still improves the
     * door's refusal message from `time grain ()` to a grain that names itself.
     */
    data class Grounded(
        val normalizedValue: String,
        val anchorAttributeRef: String,
        val intervalStart: String,
        val intervalEndExclusive: String,
    )

    /**
     * Which spans are offered to a kernel.
     *
     * DATE only. `MONEY` is a universal type too and the money kernel speaks the same contract,
     * but it is not deployed and — more to the point — a money span restricts by an AMOUNT, which
     * is a filter the composer can already build from a plain literal. The date case is the one
     * where the missing anchor column makes the question unanswerable, so it is the one wired.
     * Adding MONEY later is a line here plus a second client; it is not wired speculatively.
     */
    private fun kindOf(type: UniversalEntityType): EntityKind? =
        when (type) {
            UniversalEntityType.DATE -> EntityKind.DATE_TIME
            else -> null
        }

    /**
     * Ground every time-typed universal, keyed by span, in ONE pass.
     *
     * Sequential rather than concurrent, deliberately: a question carries one or two dates, the
     * kernel talks to veles per call, and a fan-out here would trade a bounded cost for an
     * unbounded one against a service the resolver does not own.
     */
    suspend fun ground(
        client: GroundingClient,
        universals: List<UniversalBinding>,
        questionText: String,
        pkg: String,
        referenceDatetime: String,
        locale: String = "",
        // The caller's earlier questions, newest first (ResolveContext.prior_questions). Passed
        // through untouched: the kernel is what reads a date, so it is what decides what a
        // yearless "říjen" leans on — this rung only carries the conversation to it.
        priorQuestions: List<String> = emptyList(),
    ): Map<Pair<Int, Int>, Grounded> {
        if (pkg.isBlank()) {
            // Not a warning per resolve — this is a deployment mistake, and repeating it per turn
            // would bury the turn's real story. Application logs the same fact once at boot.
            log.debug("grounding: skipped, no package (neither ResolveContext.package nor resolver.package)")
            return emptyMap()
        }
        val out = mutableMapOf<Pair<Int, Int>, Grounded>()
        for (u in universals) {
            val kind = kindOf(u.entityType) ?: continue
            val response =
                runCatching {
                    client.ground(
                        GroundRequest
                            .newBuilder()
                            .setSpanText(u.text)
                            .setQuestionText(questionText)
                            .setKind(kind)
                            .setPackage(pkg)
                            .setContext(
                                GroundingContext
                                    .newBuilder()
                                    // ⚑ The kernel is forbidden from reading a clock (its own
                                    // NoClockReadsTest asserts it), so an empty reference_datetime
                                    // is not "use now" — it is a relative span the kernel cannot
                                    // resolve. Absolute spans like `2025` are unaffected.
                                    .setReferenceDatetime(referenceDatetime)
                                    .setLocale(locale)
                                    .addAllPriorQuestions(priorQuestions),
                            ).build(),
                    )
                }.getOrElse { e ->
                    log.warn(
                        "grounding: kernel call failed for span '{}' — value stays ungrounded: {}",
                        u.text,
                        e.toString(),
                    )
                    continue
                }
            interpret(response, u)?.let { out[u.start to u.end] = it }
        }
        return out
    }

    private fun interpret(
        response: GroundResponse,
        u: UniversalBinding,
    ): Grounded? {
        if (response.status != GroundResponse.Status.OK) {
            // AWAITING_CLARIFICATION is deliberately treated as "not grounded" rather than
            // surfaced: the resolver has its own clarification contract with its own resume
            // token, and splicing a second service's options into it would give one turn two
            // HITL protocols. An ambiguous date is a gap, and the gap machinery already exists.
            log.debug("grounding: span '{}' returned {}", u.text, response.status)
            return null
        }
        val result: GroundingResult = response.result
        val interval = result.normalized.interval
        val anchor =
            result
                .takeIf { it.hasFilter() }
                ?.filter
                ?.anchorColumn
                ?.let { attributeRef(it) }
                .orEmpty()
        if (interval.start.isBlank() && interval.end.isBlank() && anchor.isBlank()) return null
        return Grounded(
            // The pair, rendered the way the interval is documented: start inclusive, end
            // EXCLUSIVE. Kept as one string because `Grounding.normalized_value` is one string;
            // the two halves travel separately below for anything that must compare them.
            normalizedValue = "${interval.start}/${interval.end}".takeIf { it != "/" }.orEmpty(),
            anchorAttributeRef = anchor,
            intervalStart = interval.start,
            intervalEndExclusive = interval.end,
        )
    }

    /**
     * chrono's anchor column → this repo's attribute ref.
     *
     * An attribute's `QualifiedName` is `{package, schema_code, namespace = "entity",
     * name = "<entity>.<attribute>"}` — the namespace is the bare object class, and the entity
     * lives in the first half of `name`. The resolver's ref grammar is
     * `er.entity.<entity>.<attribute>` (`TransDslRenderer.address`), so the ref is
     * `er.` + namespace + `.` + name, with nothing inserted between.
     *
     * ⛑ Twice wrong here, both times by INFERRING this shape instead of reading it.
     *
     * First it read `"er.entity.\${q.namespace}.\${q.name}"`, because the unit test's fake set
     * `namespace = "date_dim"` — the assumption the code was written from, restated as the input
     * that would confirm it. Against veles that produced `er.entity.entity.date_dim.cal_date`;
     * golem read the doubled segment as a SECOND entity and refused for want of a relation.
     *
     * The fix then guessed again — `namespace` was assumed to be `entity.<entity>`, since either
     * split explains the doubled string — and the new guard rejected the real `entity`, dropping
     * the attribution entirely and moving the refusal back to "nothing restricts by the grain".
     * A guard written from a guess rejects exactly the shape it was meant to admit.
     *
     * The shape above is now quoted from the wire, not derived from the symptom.
     *
     * ⚑ **ER only.** A `DB`-coded anchor names a physical column, and an attribution is a
     * statement about the MODEL — mapping one to the other here would be inventing the er2db
     * binding that the estate is supposed to declare. Returns blank instead, which degrades to
     * "grounded but unanchored" rather than to a confident wrong column.
     */
    private fun attributeRef(q: QualifiedName): String {
        if (q.schemaCode != SchemaCode.ER) {
            log.warn(
                "grounding: anchor column {}.{} is {}, not ER — the interval is not applied, " +
                    "because an attribution names a model attribute and this names a physical column",
                q.namespace,
                q.name,
                q.schemaCode,
            )
            return ""
        }
        if (q.namespace.isBlank() || q.name.isBlank()) return ""
        // The namespace must already be `entity.<entity>`. Anything else is a shape this
        // function does not know how to render, and emitting a ref anyway is what caused the
        // bug above: golem cannot tell a malformed ref from a real entity it has never heard
        // of, so it reports a missing RELATION and the actual fault stays invisible. Blank
        // degrades to "grounded but unanchored", which refuses honestly.
        // `name` must be `<entity>.<attribute>`: a ref missing either half addresses nothing,
        // and emitting it anyway is what turned a column problem into a phantom-entity problem.
        // Blank degrades to "grounded but unanchored", which refuses honestly.
        if (q.namespace != ENTITY_NAMESPACE || !q.name.contains('.')) {
            log.warn(
                "grounding: anchor column {{namespace={}, name={}}} is not {{'{}', '<entity>.<attribute>'}} " +
                    "— the interval is not applied, because a ref built from it would address nothing",
                q.namespace,
                q.name,
                ENTITY_NAMESPACE,
            )
            return ""
        }
        return "er.${q.namespace}.${q.name}"
    }
}
