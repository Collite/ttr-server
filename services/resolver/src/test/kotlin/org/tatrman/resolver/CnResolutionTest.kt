// SPDX-License-Identifier: Apache-2.0
package org.tatrman.resolver

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.tatrman.fuzzy.v1.FuzzyMatch
import org.tatrman.fuzzy.v1.SourceTag
import org.tatrman.resolver.MhMembers.tok
import org.tatrman.resolver.model.ConceptFacts
import org.tatrman.resolver.model.ResolverEntityType
import org.tatrman.resolver.model.ResolverThresholds
import org.tatrman.resolver.model.TermSense
import org.tatrman.resolver.model.conceptFacts
import org.tatrman.resolver.model.governingRefByRef
import org.tatrman.resolver.model.ownersByRef
import org.tatrman.resolver.model.valueCategoriesByRef
import io.kotest.matchers.collections.shouldContain
import org.tatrman.resolver.CnEstate.replace
import org.tatrman.resolver.model.kindsByRef
import org.tatrman.resolver.pipeline.Binder
import org.tatrman.resolver.pipeline.ConceptOrder
import org.tatrman.resolver.pipeline.DomainSpanCandidate
import org.tatrman.resolver.pipeline.FrameRolePreps
import org.tatrman.resolver.pipeline.SlotHints
import org.tatrman.resolver.pipeline.SpanProposal
import org.tatrman.resolver.v1.Hypothesis
import org.tatrman.resolver.v1.Span
import org.tatrman.resolver.pipeline.EquivalentReading
import org.tatrman.resolver.v1.ConceptReading
import org.tatrman.resolver.v1.EntityBinding
import org.tatrman.resolver.v1.EvidenceClass
import org.tatrman.resolver.v1.ResolveResponse

/**
 * CN-P4 S4.4 (cn contracts §1.5 / §5, A-CN-5…8, rulebook R-cn-1) — the resolver over a concept-bearing archive: the
 * S0.2 spike's five cases grown up (`fixtures/s0.2/CnLiftSpikeTest.kt` was the seed), the resolution-order table,
 * and the ungrounded concept. The archive is the shared estate's, compiled by the real producer ([CnEstate]); the
 * matcher serves its rows as lex-matcher does.
 */
class CnResolutionTest :
    StringSpec({
        val archive = CnEstate.pack()
        val types = CnEstate.entityTypes(archive)

        fun ResolveResponse.domainAt(text: String): EntityBinding =
            withClue("bindings: ${resolution.bindingsList.map { it.span.text + "→" + it.domain.entityTypeRef }}") {
                resolution.bindingsList.single { it.span.text == text && it.hasDomain() }
            }

        /** The governed lookups of [query]: a BatchMatch scoped to a few categories (not the open ask, not a trigger). */
        fun CnEstate.Matcher.governedAsks(query: String) =
            log
                .filter {
                    it.via == "batch" && it.query == query && it.categories.isNotEmpty() && it.categories.size < 6
                }.filter { it.categories.none { c -> c.startsWith("ground:") } }
                .map { it.categories }

        fun ConceptReading.brief() = "$conceptQname|$stereotype|$via|${liftProvenanceList.joinToString(",")}|$note"

        // "zákazníci po splatnosti z Prahy"
        val debtorsFromPrague =
            arrayOf(
                tok("zákazníci", 0, 9, "zákazník", "NOUN", 0, "root"),
                tok("po", 10, 12, "po", "ADP", 3, "case"),
                tok("splatnosti", 13, 23, "splatnost", "NOUN", 1, "nmod"),
                tok("z", 24, 25, "z", "ADP", 5, "case"),
                tok("Prahy", 26, 31, "Praha", "PROPN", 1, "nmod"),
            )

        // NameTag types the city as a place: under a multi-word anchor only a place is proposed as a governed value (UD).
        val pragueIsAPlace = listOf(MhMembers.ner("Prahy", 26, 31, "LOCATION", "cnec:gu"))

        "the shared estate compiles, on the pinned producer, to the golden v6 lexicon the resolver reads" {
            CnEstate.build().result.lexicon shouldBe CnEstate.golden()
        }

        "A-CN-6 — a concept governs as its ER landing; one with no landing normalizes to nothing" {
            val governing = types.governingRefByRef()
            governing["cn.DebtorCustomer"] shouldBe "er.entity.customer"
            governing["cn.Channel"] shouldBe "er.entity.channel_sales.channel"
            governing.containsKey("cn.Supplier") shouldBe false
            governing.containsKey("cn.revenue") shouldBe false // md-grounded, hop missing

            // ⚑R130-c — own categories first (the concept's terms), then the landing's scope.
            val scope = types.valueCategoriesByRef()
            scope["cn.DebtorCustomer"] shouldBe listOf("cn.DebtorCustomer") + scope.getValue("er.entity.customer")
            scope["cn.Channel"] shouldBe listOf("cn.Channel", "er.entity.channel_sales.channel") // nothing wider
            scope["cn.Supplier"] shouldBe listOf("cn.Supplier") // as before P4

            // A concept's `ownerRef` (the entity around its landing) is no containment the Binder may collapse on.
            types
                .ownersByRef()
                .keys
                .filter { it.startsWith("cn.") }
                .shouldBeEmpty()
        }

        "(1) 'VIP zákazníci' — the member lifts from its category and from the category's owner, never to a phase" {
            val (r, _) =
                CnEstate.resolve(
                    "VIP zákazníci",
                    arrayOf(
                        tok("VIP", 0, 3, "VIP", "PROPN", 2, "nmod"),
                        tok("zákazníci", 4, 13, "zákazník", "NOUN", 0, "root"),
                    ),
                    archive = archive,
                )
            val vip = r.domainAt("VIP")
            vip.domain.memberOf shouldBe "er.entity.customer.segment"
            vip.conceptsList.map { it.brief() } shouldContainExactly
                listOf(
                    "cn.Segment|kind|direct|lift:er.entity.customer.segment|",
                    "cn.Customer|role|direct|lift:er.entity.customer|",
                )
            // (4) `DebtorCustomer` and `TopCustomer` land on `customer` too; a lift never reaches a phase.
            vip.conceptsList.map { it.stereotype } shouldContainExactlyInAnyOrder listOf("kind", "role")
        }

        "(2) 'tržby' — one cn.revenue reading from both sources; the door keeps naming the measure (A-CN-70)" {
            val (r, _) =
                CnEstate.resolve(
                    "tržby",
                    arrayOf(tok("tržby", 0, 5, "tržba", "NOUN", 0, "root")),
                    archive = archive,
                )
            val revenue = r.domainAt("tržby")
            revenue.conceptsList.map { it.brief() } shouldContainExactly
                listOf("cn.revenue|quality|md-hop|term:cn.revenue,lift:md.measure.revenue|md/er-unmapped")
            revenue.domain.entityTypeRef shouldBe "md.measure.revenue"
            revenue.domain.candidatesList.map { it.targetRef } shouldContainExactly listOf("md.measure.revenue")
            // The lattice discloses the element reading R-cn-1 folded in.
            r.resolutionState.mentionsList
                .single { it.span.text == "tržby" }
                .bindingsList
                .single()
                .equivalentsList
                .map { it.ref + "|" + it.rule } shouldContainExactly listOf("md.measure.revenue|cn-grounds")
        }

        "(3) + (5) 'zákazníci po splatnosti z Prahy' — DebtorCustomer, and Praha bound under it by tier-M reach" {
            val (r, _) =
                CnEstate.resolve(
                    "zákazníci po splatnosti z Prahy",
                    debtorsFromPrague,
                    archive = archive,
                    entities = pragueIsAPlace,
                )
            r.hasAwaiting() shouldBe false
            val debtor = r.domainAt("zákazníci po splatnosti")
            debtor.domain.entityTypeRef shouldBe "er.entity.customer"
            debtor.conceptsList.map { it.brief() } shouldContainExactly
                listOf("cn.DebtorCustomer|phase|direct|term:cn.DebtorCustomer|")

            val praha = r.domainAt("Prahy")
            praha.domain.resolvedId shouldBe "Praha"
            praha.domain.memberOf shouldBe "er.entity.customer_address.city"
            // (3) a city of `customer_address` lifts to nothing: no concept grounds it, and no lift crosses a relation.
            praha.conceptsList.shouldBeEmpty()
        }

        "'dlužníci z Prahy' — no place typed: the open lookup finds both cities, tier M keeps the customer's" {
            val (r, matcher) =
                CnEstate.resolve(
                    "dlužníci z Prahy",
                    arrayOf(
                        tok("dlužníci", 0, 8, "dlužník", "NOUN", 0, "root"),
                        tok("z", 9, 10, "z", "ADP", 3, "case"),
                        tok("Prahy", 11, 16, "Praha", "PROPN", 1, "nmod"),
                    ),
                    archive = archive,
                )
            // The governed lookup asks the customer's own vocabularies (MV scope), where no city is …
            matcher.governedAsks("Prahy") shouldContainExactly
                listOf(listOf("cn.DebtorCustomer", "er.entity.customer", "er.entity.customer.segment"))
            // … so the open sibling speaks, and M3 decides by the governor `customer_address` declares a reach from.
            r.hasAwaiting() shouldBe false
            r.domainAt("Prahy").domain.memberOf shouldBe "er.entity.customer_address.city"
            r.domainAt("dlužníci").conceptsList.map { it.conceptQname } shouldContainExactly listOf("cn.DebtorCustomer")
        }

        "'channel web' — the governed lookup under a value kind asks the attribute's vocabulary and nothing wider" {
            val (r, matcher) =
                CnEstate.resolve(
                    "channel web",
                    arrayOf(
                        tok("channel", 0, 7, "channel", "NOUN", 0, "root"),
                        tok("web", 8, 11, "web", "NOUN", 1, "appos"),
                    ),
                    lang = "en",
                    archive = archive,
                )
            matcher.governedAsks("web") shouldContainExactly
                listOf(listOf("cn.Channel", "er.entity.channel_sales.channel"))
            val web = r.domainAt("web")
            web.domain.memberOf shouldBe "er.entity.channel_sales.channel"
            // A member of `channel` lifts to the value kind on its attribute AND to the event its entity is.
            web.conceptsList.map { it.conceptQname } shouldContainExactly listOf("cn.Channel", "cn.Sale")
        }

        "T5 — 'dodavatelé z Brna': the ungrounded concept binds with its note; Brno resolves as an open value" {
            val tokens =
                arrayOf(
                    tok("dodavatelé", 0, 10, "dodavatel", "NOUN", 0, "root"),
                    tok("z", 11, 12, "z", "ADP", 3, "case"),
                    tok("Brna", 13, 17, "Brno", "PROPN", 1, "nmod"),
                )
            val (r, _) = CnEstate.resolve("dodavatelé z Brna", tokens, archive = archive)
            r.hasAwaiting() shouldBe false
            val supplier = r.domainAt("dodavatelé")
            supplier.domain.entityTypeRef shouldBe "" // no ER landing, and no cn.* ref where the door names an object
            supplier.domain.candidatesList.shouldBeEmpty()
            supplier.conceptsList.map { it.brief() } shouldContainExactly
                listOf("cn.Supplier|kind||term:cn.Supplier|cn/ungrounded")
            r.domainAt("Brna").domain.memberOf shouldBe "er.entity.customer_address.city" // unique: bound
        }

        "T5 — 'dodavatelé z Prahy': two cities, nothing governs them — asked, never refused" {
            val tokens =
                arrayOf(
                    tok("dodavatelé", 0, 10, "dodavatel", "NOUN", 0, "root"),
                    tok("z", 11, 12, "z", "ADP", 3, "case"),
                    tok("Prahy", 13, 18, "Praha", "PROPN", 1, "nmod"),
                )
            val (r, _) = CnEstate.resolve("dodavatelé z Prahy", tokens, archive = archive)
            r.hasAwaiting() shouldBe true
            r.awaiting.optionsList.map { it.memberOf } shouldContainExactlyInAnyOrder
                listOf("er.entity.customer_address.city", "er.entity.warehouse.city")
        }

        // ── the resolution-order table (cn contracts §1.5, A-CN-8) ──────────────────────────────────────────────

        fun row(
            ref: String,
            form: String,
            score: Double = 1.0,
            evidence: EvidenceClass = EvidenceClass.EVIDENCE_CLASS_EXACT,
        ) = Binder.ClassedMatch(
            FuzzyMatch
                .newBuilder()
                .setCandidate(form)
                .setCandidateId("lex:$ref")
                .setTargetRef(ref)
                .setCategory(ref)
                .setScore(score)
                .setSource(SourceTag.DECLARED)
                .build(),
            evidence,
        )

        /** The estate's types with [ref]'s senses replaced — the authoring a row needs that the estate does not carry. */
        fun withSenses(
            ref: String,
            senses: Map<String, TermSense>,
            base: List<ResolverEntityType> = types,
        ) = base.map { if (it.ref == ref) it.copy(senses = senses) else it }

        data class Outcome(
            val refs: List<String>,
            val ambiguous: Boolean,
            val grounded: List<String>,
        )

        fun decide(
            rows: List<Binder.ClassedMatch>,
            concepts: ConceptFacts = types.conceptFacts(),
        ): Outcome {
            val v = Binder.decide(rows, ResolverThresholds.LIVE, concepts = concepts)
            val grounded =
                ((v as? Binder.Bind)?.equivalents.orEmpty()).filter {
                    it.rule == ConceptOrder.RULE_CN_GROUNDS
                }
            return Outcome(
                v.admitted.map { it.match.targetRef },
                v is Binder.Ambiguous,
                grounded.map(EquivalentReading::ref),
            )
        }

        "the §1.5 ordering table, row by row" {
            val customer = "cn.Customer"
            val debtor = "cn.DebtorCustomer"
            val item = "cn.Item"
            val supplier = "cn.Supplier"
            val bound = { ref: String -> Outcome(listOf(ref), false, emptyList()) }

            val table: List<Triple<String, () -> Outcome, Outcome>> =
                listOf(
                    Triple(
                        // ⚑R130-d — the element Customer grounds to shares its default, so R-cn-1 folds it in.
                        "1 · zákazník: Customer (default) · er.entity.customer → Customer (the default and R-cn-1 agree)",
                        { decide(listOf(row("er.entity.customer", "zákazník"), row(customer, "zákazník"))) },
                        Outcome(listOf(customer), false, listOf("er.entity.customer")),
                    ),
                    Triple(
                        "2 · zákazník: Customer · er.entity.customer, no default → Customer, by R-cn-1 (step 6)",
                        {
                            decide(
                                listOf(row("er.entity.customer", "zákazník"), row(customer, "zákazník")),
                                withSenses(customer, emptyMap()).conceptFacts(),
                            )
                        },
                        Outcome(listOf(customer), false, listOf("er.entity.customer")),
                    ),
                    Triple(
                        "3 · zákazník (bad authoring): Customer · DebtorCustomer (default) → Customer (step 1)",
                        {
                            decide(
                                listOf(row(debtor, "zákazník"), row(customer, "zákazník")),
                                withSenses(debtor, mapOf("zákazník" to TermSense(default = true))).conceptFacts(),
                            )
                        },
                        bound(customer),
                    ),
                    Triple(
                        "4 · zákazník po splatnosti: DebtorCustomer (3 words) · Customer (1) → DebtorCustomer (step 0)",
                        {
                            val (r, _) =
                                CnEstate.resolve(
                                    "zákazníci po splatnosti z Prahy",
                                    debtorsFromPrague,
                                    archive = archive,
                                    entities = pragueIsAPlace,
                                )
                            val objects =
                                r.resolution.bindingsList.filter {
                                    it.conceptsCount > 0 &&
                                        it.domain.resolvedId.isEmpty()
                                }
                            Outcome(
                                objects.flatMap { b ->
                                    b.conceptsList.map { it.conceptQname }
                                },
                                r.hasAwaiting(),
                                emptyList(),
                            )
                        },
                        bound(debtor),
                    ),
                    Triple(
                        "5 · dlužník: DebtorCustomer alone → DebtorCustomer (a phase-only term is legal)",
                        { decide(listOf(row(debtor, "dlužník"))) },
                        bound(debtor),
                    ),
                    Triple(
                        "6 · tržby: cn.revenue · md.measure.revenue → cn.revenue (step 6, grounds via its md target)",
                        { decide(listOf(row("md.measure.revenue", "tržby"), row("cn.revenue", "tržby"))) },
                        Outcome(listOf("cn.revenue"), false, listOf("md.measure.revenue")),
                    ),
                    Triple(
                        "7 · kanál: Channel · channel_sales → ambiguous, concepts first (Channel lands on the attribute)",
                        { decide(listOf(row("er.entity.channel_sales", "kanál"), row("cn.Channel", "kanál"))) },
                        Outcome(listOf("cn.Channel", "er.entity.channel_sales"), true, emptyList()),
                    ),
                    Triple(
                        "8 · Item (partial, default) · Supplier (exact) → Supplier (step 2 before step 3)",
                        {
                            decide(
                                listOf(
                                    row(item, "item", 0.8, EvidenceClass.EVIDENCE_CLASS_ANCHORED_FUZZY_STRONG),
                                    row(supplier, "supplier"),
                                ),
                                withSenses(item, mapOf("item" to TermSense(default = true))).conceptFacts(),
                            )
                        },
                        bound(supplier),
                    ),
                    Triple(
                        "9 · Item (priority 1, score .5) · Supplier (score .9) → Item (step 4 before step 5)",
                        {
                            decide(
                                listOf(
                                    row(item, "položka", 0.5, EvidenceClass.EVIDENCE_CLASS_ANCHORED_FUZZY_STRONG),
                                    row(supplier, "dodavatel", 0.9, EvidenceClass.EVIDENCE_CLASS_ANCHORED_FUZZY_STRONG),
                                ),
                            )
                        },
                        bound(item),
                    ),
                )
            table.size shouldBe 9
            for ((name, run, expected) in table) withClue(name) { run() shouldBe expected }
        }

        "a phase's default is ignored: the bare form never defaults to a phase (AM-36)" {
            // TopCustomer and DebtorCustomer both phases of Customer; a default on a phase is not a default.
            val concepts =
                withSenses("cn.TopCustomer", mapOf("zákazník" to TermSense(default = true))).conceptFacts()
            val v =
                Binder.decide(
                    listOf(row("cn.TopCustomer", "zákazník"), row("er.entity.warehouse", "zákazník")),
                    ResolverThresholds.LIVE,
                    concepts = concepts,
                )
            v.shouldBeInstanceOf<Binder.Ambiguous>()
            v.admitted.map { it.match.targetRef } shouldContainExactly listOf("cn.TopCustomer", "er.entity.warehouse")
        }

        "an estate with no concept tier decides exactly as before" {
            val rows = listOf(row("er.entity.channel_sales", "kanál"), row("er.entity.warehouse", "kanál"))
            Binder.decide(rows, ResolverThresholds.LIVE, concepts = ConceptFacts.NONE).admitted shouldBe
                Binder.decide(rows, ResolverThresholds.LIVE).admitted
        }

        // ── review-130 ───────────────────────────────────────────────────────────────────────────────────────────

        "R1 — a concept option names its landing, and its pick binds the concept reading (A-CN-70)" {
            val (r, _) =
                CnEstate.resolve(
                    "kanál",
                    arrayOf(tok("kanál", 0, 5, "kanál", "NOUN", 0, "root")),
                    archive = archive,
                )
            r.hasAwaiting() shouldBe true
            val options = r.awaiting.optionsList
            options.map { it.targetRef } shouldContainExactly listOf("cn.Channel", "er.entity.channel_sales")
            val channel = options.first()
            channel.entityTypeRef shouldBe "er.entity.channel_sales.channel"
            channel.objectKind shouldBe "concept"

            val pin =
                CnEstate
                    .resume(r.awaiting.resumeToken, channel.id, archive)
                    .resolution.bindingsList
                    .single()
            pin.domain.entityTypeRef shouldBe "er.entity.channel_sales.channel"
            pin.domain.candidatesList.map { it.targetRef } shouldContainExactly
                listOf("er.entity.channel_sales.channel")
            pin.conceptsList.map { it.brief() } shouldContainExactly listOf("cn.Channel|kind|direct|term:cn.Channel|")
        }

        "R2 — two concepts with one landing are two door bindings" {
            val (r, _) =
                CnEstate.resolve(
                    "zákazníci a dlužníci",
                    arrayOf(
                        tok("zákazníci", 0, 9, "zákazník", "NOUN", 0, "root"),
                        tok("a", 10, 11, "a", "CCONJ", 3, "cc"),
                        tok("dlužníci", 12, 20, "dlužník", "NOUN", 1, "conj"),
                    ),
                    archive = archive,
                )
            val objects = r.resolution.bindingsList.filter { it.hasDomain() }
            objects.map { it.conceptsList.single().conceptQname } shouldContainExactlyInAnyOrder
                listOf("cn.Customer", "cn.DebtorCustomer")
            objects.map { it.domain.entityTypeRef }.distinct() shouldContainExactly listOf("er.entity.customer")
        }

        "R3 — a quoted literal after a concept is attributed through the concept's landing" {
            val named =
                CnEstate.pack(
                    CnEstate.build(
                        CnEstate.variant {
                            it.resolve("model/er/shop.ttrm").replace(
                                "def entity customer {\n    displayLabel: { cs: \"Zákazník\", en: \"Customer\" },",
                                "def entity customer {\n    displayLabel: { cs: \"Zákazník\", en: \"Customer\" },\n" +
                                    "    semantics { name: customer_code },",
                            )
                        },
                    ),
                )
            val (r, _) =
                CnEstate.resolve(
                    "zákazník \"Novák\"",
                    arrayOf(
                        tok("zákazník", 0, 8, "zákazník", "NOUN", 0, "root"),
                        tok("\"", 9, 10, "\"", "PUNCT", 3, "punct"),
                        tok("Novák", 10, 15, "Novák", "PROPN", 1, "nmod"),
                        tok("\"", 15, 16, "\"", "PUNCT", 3, "punct"),
                    ),
                    archive = named,
                )
            r.resolutionState.mentionsList
                .single { it.span.text == "zákazník" }
                .bindingsList
                .map { it.ref } shouldContainExactly listOf("cn.Customer")
            r.resolutionState.valuesList
                .single { it.span.text.contains("Novák") }
                .attributionsList
                .map { it.attributeRef } shouldContainExactly listOf("er.entity.customer.customer_code")
        }

        "R4 — the element a concept grounds to shares its sense, and R-cn-1 keeps it as a source" {
            val (r, _) =
                CnEstate.resolve(
                    "položka",
                    arrayOf(tok("položka", 0, 7, "položka", "NOUN", 0, "root")),
                    archive = archive,
                )
            r.domainAt("položka").conceptsList.map { it.brief() } shouldContainExactly
                listOf("cn.Item|kind|direct|term:cn.Item,lift:er.entity.item|")
        }

        "R5 — a concept a lookup round binds keeps R-cn-1's source and its door fallback" {
            // The broad pass's question about the mention „tržby" is answered with nothing; the round finds both readings.
            val (r, _) =
                CnEstate.resolve(
                    "tržby celkem",
                    arrayOf(
                        tok("tržby", 0, 5, "tržba", "NOUN", 0, "root"),
                        tok("celkem", 6, 12, "celkem", "ADV", 1, "advmod"),
                    ),
                    archive = archive,
                    blindBatch = { "cn.revenue" in it },
                )
            val revenue = r.domainAt("tržby")
            revenue.domain.entityTypeRef shouldBe "md.measure.revenue"
            revenue.conceptsList.map { it.brief() } shouldContainExactly
                listOf("cn.revenue|quality|md-hop|term:cn.revenue,lift:md.measure.revenue|md/er-unmapped")
        }

        "R6 — a Gate hypothesis naming a concept reaches the concept's own term, and carries R-cn-1's source" {
            val (r, _) =
                CnEstate.resolve(
                    "zákazník",
                    arrayOf(tok("zákazník", 0, 8, "zákazník", "NOUN", 0, "root")),
                    archive = archive,
                )
            val hypothesis =
                Hypothesis
                    .newBuilder()
                    .setSpan(
                        Span
                            .newBuilder()
                            .setStart(0)
                            .setEnd(8)
                            .setText("zákazník"),
                    ).setRef("cn.Customer")
                    .setProposingRung("local")
                    .build()
            val gated = CnEstate.gate(r.resolutionState, listOf(hypothesis), archive)
            gated.outcomesList.single().accepted shouldBe true
            val binding = gated.gatedBindingsList.single()
            binding.ref shouldBe "cn.Customer"
            binding.equivalentsList.map { it.ref + "|" + it.rule } shouldContainExactly
                listOf("er.entity.customer|cn-grounds")
        }

        "R7 / R9 — a candidate is gated to what its refs stand for; a coordination sibling lends its landing's kind" {
            val parse =
                MhMembers.parse(
                    "zákazníci a sklady",
                    arrayOf(
                        tok("zákazníci", 0, 9, "zákazník", "NOUN", 0, "root"),
                        tok("a", 10, 11, "a", "CCONJ", 3, "cc"),
                        tok("sklady", 12, 18, "sklad", "NOUN", 1, "conj"),
                    ),
                    "cs",
                )
            val proposed = SpanProposal.proposeDomainSpans(parse, types)
            val customers = proposed.single { it.text == "zákazníci" }
            customers.gatedEntityRefs shouldContainExactly listOf("er.entity.customer")
            customers.categories shouldContain "cn.Customer" // the concept's own term rows are still asked
            val stamped =
                SlotHints.stamp(
                    parse,
                    proposed,
                    types.kindsByRef(),
                    types.ownersByRef(),
                    "cs",
                    FrameRolePreps.shipped(),
                )
            stamped.single { it.text == "sklady" }.slot.coordSiblingKinds shouldBe setOf("entity")
        }

        "R8 — a concept landing on a measure governs no value, as the measure does not" {
            val onMeasure =
                CnEstate.entityTypes(
                    CnEstate.pack(
                        CnEstate.build(
                            CnEstate.variant {
                                it.resolve("model/er/shop.ttrm").replace(
                                    "def entity channel_sales {\n    attributes: [",
                                    "def entity channel_sales {\n    semantics { measures: [ ext_sales_price ] },\n    attributes: [",
                                )
                                it.resolve("model/binding/shop.ttrm").replace(
                                    "def cn2md_quality revenue_g { quality: cn.revenue, target: md.revenue }",
                                    "def cn2er_quality revenue_g { quality: cn.revenue, target: er.entity.channel_sales.ext_sales_price }",
                                )
                                it
                                    .resolve(
                                        "model/lexicon/cs/terms.ttrm",
                                    ).replace("forms: [\"tržby\", \"obrat\"]", "forms: [\"obrat\"]")
                            },
                        ),
                    ),
                )
            onMeasure.single { it.ref == "cn.revenue" }.erTarget shouldBe "er.entity.channel_sales.ext_sales_price"
            val parse =
                MhMembers.parse(
                    "tržby za elektroniku",
                    arrayOf(
                        tok("tržby", 0, 5, "tržba", "NOUN", 0, "root"),
                        tok("za", 6, 8, "za", "ADP", 3, "case"),
                        tok("elektroniku", 9, 20, "elektronika", "NOUN", 1, "nmod"),
                    ),
                    "cs",
                )
            SpanProposal
                .proposeDomainSpans(parse, onMeasure)
                .filter { it.origin == DomainSpanCandidate.Origin.GOVERNED_VALUE }
                .shouldBeEmpty()
        }

        "R10 — an ungrounded concept grounds to nothing, a blank ref included" {
            types.conceptFacts().groundsTo("cn.Supplier", "") shouldBe false
        }
    })
