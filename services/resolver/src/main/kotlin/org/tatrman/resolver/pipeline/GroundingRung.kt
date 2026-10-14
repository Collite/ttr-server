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
import org.tatrman.resolver.token.KernelQuestion
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
     * ✅ ttr-server#231 — what a kernel ASKED about one span instead of grounding it: the readings
     * it offered, and [question], the exact question it was asked. The resolver turns the readings
     * into its own `awaiting` options (one HITL protocol per turn) and signs [question] into its own
     * resume token, so a pick can be put to the kernel again with the answer and nothing else
     * changed (see [groundPick]).
     */
    data class Asked(
        val start: Int,
        val end: Int,
        val text: String,
        val options: List<KernelOption>,
        val question: KernelQuestion,
    )

    /**
     * One reading a kernel offered. [id] is the kernel's own (`2025`, `202511`, `2025Q1`): it is
     * what the kernel matches a `clarification_answer_id` against, so it is what a resume hands back.
     * [label] is passed through as the kernel wrote it — naming the period in the question's
     * language is the kernel's job, not this service's.
     */
    data class KernelOption(
        val id: String,
        val label: String,
        val intervalStart: String,
        val intervalEndExclusive: String,
    ) {
        /** Rendered the way [Grounded.normalizedValue] is: start inclusive, end EXCLUSIVE. */
        val normalizedValue: String
            get() = "$intervalStart/$intervalEndExclusive".takeIf { it != "/" }.orEmpty()
    }

    /**
     * Every span the kernel answered: grounded, or asked about. A span in neither stays as the NER typed it.
     *
     * [unanchored] are the spans of that last kind the kernel refused as `no_anchor`: a period counted
     * from another period, with no single one in the conversation to count from. They stay ungrounded
     * too; the lattice names them (`LatticeAssembler.GROUND_UNANCHORED_ACTION`) so a consumer does not
     * read them against today.
     */
    data class Outcome(
        val grounded: Map<Pair<Int, Int>, Grounded> = emptyMap(),
        val asked: Map<Pair<Int, Int>, Asked> = emptyMap(),
        val unanchored: Set<Pair<Int, Int>> = emptySet(),
    )

    /** What putting a user's pick back to the kernel produced ([groundPick]). */
    sealed interface Pick {
        /** The kernel grounded the pick: the same [Grounded] an unambiguous span gets. */
        data class Done(
            val grounded: Grounded,
        ) : Pick

        /** The kernel answered, and did not ground the pick (asked again, or does not know the id). */
        data class Refused(
            val reason: String,
        ) : Pick

        /** The kernel could not be asked at all. */
        data class Failed(
            val cause: Throwable,
        ) : Pick
    }

    /** The kernel the rung talks to; the only one wired (see [kindOf]). */
    const val CHRONO = "chrono"

    /**
     * chrono's message code (ttr-server#214) for an anaphoric period with nothing to be read
     * against: "the previous year" after a question naming two years. The date stays ungrounded,
     * and the span is carried out in [Outcome.unanchored]: the lattice names its value with a
     * `ground-unanchored` rung-log entry, because the one reading left to a consumer that sees only
     * an ungrounded date (count it from today) is the reading the kernel refused.
     */
    private const val NO_ANCHOR = "no_anchor"

    /**
     * Ground every time-typed universal, keyed by span, in ONE pass. Only the grounded half of
     * [groundAll]; kept for the callers that never read a question.
     */
    suspend fun ground(
        client: GroundingClient,
        universals: List<UniversalBinding>,
        questionText: String,
        pkg: String,
        referenceDatetime: String,
        locale: String = "",
        priorQuestions: List<String> = emptyList(),
    ): Map<Pair<Int, Int>, Grounded> =
        groundAll(client, universals, questionText, pkg, referenceDatetime, locale, priorQuestions).grounded

    /**
     * Ground every time-typed universal, keyed by span, in ONE pass — and keep what the kernel ASKED.
     *
     * Sequential rather than concurrent, deliberately: a question carries one or two dates, the
     * kernel talks to veles per call, and a fan-out here would trade a bounded cost for an
     * unbounded one against a service the resolver does not own.
     *
     * ✅ ttr-server#231 — an `AWAITING_CLARIFICATION` used to be dropped here as "not grounded", on
     * the argument that splicing a second service's options into the resolver's clarification would
     * give a turn two HITL protocols. The argument holds; the conclusion did not. The caller got a
     * date with no range and nothing to ask, and could only guess the period the user should have
     * been asked for. The question is now TRANSLATED: the resolver asks it under its own token
     * ([Asked]), and a pick comes back to the kernel through [groundPick].
     */
    suspend fun groundAll(
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
    ): Outcome {
        if (pkg.isBlank()) {
            // Not a warning per resolve — this is a deployment mistake, and repeating it per turn
            // would bury the turn's real story. Application logs the same fact once at boot.
            log.debug("grounding: skipped, no package (neither ResolveContext.package nor resolver.package)")
            return Outcome()
        }
        val grounded = mutableMapOf<Pair<Int, Int>, Grounded>()
        val asked = mutableMapOf<Pair<Int, Int>, Asked>()
        val unanchored = mutableSetOf<Pair<Int, Int>>()
        for (u in universals) {
            val kind = kindOf(u.entityType) ?: continue
            val question =
                KernelQuestion(
                    kernel = CHRONO,
                    kind = kind.name,
                    spanStart = u.start,
                    spanEnd = u.end,
                    spanText = u.text,
                    questionText = questionText,
                    priorQuestions = priorQuestions,
                    referenceDatetime = referenceDatetime,
                    locale = locale,
                    pkg = pkg,
                )
            val response =
                runCatching { client.ground(request(question)) }.getOrElse { e ->
                    log.warn(
                        "grounding: kernel call failed for span '{}' — value stays ungrounded: {}",
                        u.text,
                        e.toString(),
                    )
                    continue
                }
            when (response.status) {
                GroundResponse.Status.OK -> interpret(response, u.text)?.let { grounded[u.start to u.end] = it }
                GroundResponse.Status.AWAITING_CLARIFICATION ->
                    askedOf(response, u, question)?.let { asked[u.start to u.end] = it }
                else -> {
                    if (response.messagesList.any { it.code == NO_ANCHOR }) {
                        unanchored += u.start to u.end
                        log.info(
                            "grounding: [{},{}) names a period with nothing to read it against ({}) — left ungrounded",
                            u.start,
                            u.end,
                            NO_ANCHOR,
                        )
                    } else {
                        log.debug("grounding: span '{}' returned {}", u.text, response.status)
                    }
                }
            }
        }
        return Outcome(grounded, asked, unanchored)
    }

    /**
     * ✅ ttr-server#231 — put a user's pick back to the kernel: [question] exactly as it was asked,
     * plus `clarification_answer_id`.
     *
     * chrono RE-READS the span on resume and then picks the reading whose id matches, so the read
     * must be the one that offered the id: the same span, question, earlier questions, reference
     * date, locale and package. That is why they come from the signed [question] and never from the
     * resume request — by then the caller's "now" may be later, and its earlier questions include
     * the one that was asked, and either could make the same words name different periods.
     *
     * Through chrono rather than from the interval the option already carries, because an
     * `AWAITING_CLARIFICATION` names no anchor column: a kernel discovers the column only when it
     * grounds, and an interval nothing can be filtered by is not an answer. The kernel also checks
     * the id is one of its readings, which a stored interval could not.
     */
    suspend fun groundPick(
        client: GroundingClient,
        question: KernelQuestion,
        kernelOptionId: String,
    ): Pick {
        val response =
            runCatching { client.ground(request(question, kernelOptionId)) }.getOrElse { return Pick.Failed(it) }
        if (response.status != GroundResponse.Status.OK) {
            val said = response.messagesList.joinToString("; ") { it.humanMessage.ifBlank { it.code } }
            return Pick.Refused(
                "${question.kernel} answered ${response.status}" + if (said.isBlank()) "" else ": $said",
            )
        }
        val grounded =
            interpret(response, question.spanText)?.takeIf { it.normalizedValue.isNotBlank() }
                ?: return Pick.Refused("${question.kernel} answered OK with no interval")
        return Pick.Done(grounded)
    }

    /**
     * The universal type a kernel kind grounds — what `Grounding.kind` says on the value. The
     * inverse of [kindOf], for a value rebuilt from a signed [KernelQuestion] on resume.
     */
    fun universalKindOf(question: KernelQuestion): String =
        when (question.kind) {
            EntityKind.DATE_TIME.name -> UniversalEntityType.DATE.name
            EntityKind.MONEY.name -> UniversalEntityType.MONEY.name
            else -> question.kind
        }

    /** The ONE request builder: the asking call and the resume call cannot differ except by [answerId]. */
    private fun request(
        q: KernelQuestion,
        answerId: String = "",
    ): GroundRequest {
        val builder =
            GroundRequest
                .newBuilder()
                .setSpanText(q.spanText)
                .setQuestionText(q.questionText)
                .setKind(enumValues<EntityKind>().firstOrNull { it.name == q.kind } ?: EntityKind.DATE_TIME)
                .setPackage(q.pkg)
                .setContext(
                    GroundingContext
                        .newBuilder()
                        // ⚑ The kernel is forbidden from reading a clock (its own
                        // NoClockReadsTest asserts it), so an empty reference_datetime
                        // is not "use now" — it is a relative span the kernel cannot
                        // resolve. Absolute spans like `2025` are unaffected.
                        .setReferenceDatetime(q.referenceDatetime)
                        .setLocale(q.locale)
                        .addAllPriorQuestions(q.priorQuestions),
                )
        if (answerId.isNotEmpty()) builder.clarificationAnswerId = answerId
        return builder.build()
    }

    /**
     * The kernel's question, if it is one the resolver can ask: at least two readings, each with
     * an id. An option with no id cannot be handed back (the kernel matches the answer by id), and
     * a question with one answer is not a question. Anything less stays what it was before #231 —
     * an ungrounded date — rather than an ask the user could not answer.
     */
    private fun askedOf(
        response: GroundResponse,
        u: UniversalBinding,
        question: KernelQuestion,
    ): Asked? {
        val options =
            response.optionsList
                .filter { it.id.isNotBlank() }
                .distinctBy { it.id }
                .map {
                    KernelOption(
                        id = it.id,
                        label = it.label,
                        intervalStart = it.normalized.interval.start,
                        intervalEndExclusive = it.normalized.interval.end,
                    )
                }
        if (options.size < 2) {
            log.info(
                "grounding: [{},{}) asked with {} option(s) the resolver could hand back — left ungrounded",
                u.start,
                u.end,
                options.size,
            )
            return null
        }
        log.info("grounding: [{},{}) has {} readings — asked", u.start, u.end, options.size)
        return Asked(u.start, u.end, u.text, options, question)
    }

    private fun interpret(
        response: GroundResponse,
        spanText: String,
    ): Grounded? {
        if (response.status != GroundResponse.Status.OK) {
            log.debug("grounding: span '{}' returned {}", spanText, response.status)
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

/**
 * ttr-server#231 — a user picked one of a kernel's options and the kernel did not ground it.
 *
 * Neither outcome a resume could otherwise return is honest: asking again would loop on a question
 * the user has answered, and a resolution without the period answers a wider question than the one
 * asked. [retryable] says which failure it was — the kernel could not be reached (try again), or
 * it answered and would not ground the pick (it will not, on a retry either). The gRPC layer maps
 * them to `UNAVAILABLE` and `FAILED_PRECONDITION`.
 */
class KernelPickException(
    val retryable: Boolean,
    val reason: String,
) : Exception(reason)
