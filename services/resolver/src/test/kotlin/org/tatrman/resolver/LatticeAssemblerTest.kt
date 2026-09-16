// SPDX-License-Identifier: Apache-2.0
package org.tatrman.resolver

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import org.tatrman.fuzzy.v1.BatchMatchResponse
import org.tatrman.fuzzy.v1.FuzzyMatch
import org.tatrman.fuzzy.v1.FuzzyMatchResponse
import org.tatrman.fuzzy.v1.LayerVersions
import org.tatrman.fuzzy.v1.Provenance
import org.tatrman.fuzzy.v1.SourceTag
import org.tatrman.fuzzy.v1.TargetClass
import org.tatrman.nlp.v1.AnalyzeResponse
import org.tatrman.resolver.model.ResolverEntityType
import org.tatrman.resolver.model.ResolverThresholds
import org.tatrman.resolver.pipeline.Binder
import org.tatrman.resolver.pipeline.Bindings
import org.tatrman.resolver.pipeline.Bound
import org.tatrman.resolver.pipeline.DomainSpanCandidate
import org.tatrman.resolver.pipeline.EquivalentReading
import org.tatrman.resolver.pipeline.EvidenceClasses
import org.tatrman.resolver.pipeline.FrameRolePreps
import org.tatrman.resolver.pipeline.GatedSpan
import org.tatrman.resolver.pipeline.LatticeAssembler
import org.tatrman.resolver.pipeline.UniversalBinding
import org.tatrman.resolver.v1.EvidenceClass
import org.tatrman.resolver.v1.FrameRole
import org.tatrman.resolver.v1.MatchMethod
import org.tatrman.resolver.v1.UniversalEntityType
import org.tatrman.resolver.v1.ValueKind
import org.tatrman.resolver.v1.SourceTag as LatticeSourceTag
import org.tatrman.resolver.v1.TargetClass as LatticeTargetClass

/**
 * RV-P2.1.T4 — the internal model → wire mapping, message family by message family. The
 * golden files (`LatticeGoldenTest`) pin whole lattices for real questions; these pin the
 * individual translation decisions, including the ones no fixture happens to exercise.
 */
class LatticeAssemblerTest :
    StringSpec({

        val thresholds = ResolverThresholds.LIVE

        "Binding: a member row becomes an addressable ref, and the layer names its class" {
            val binding =
                mapped(
                    member("501001", "md.dimension.Account.code", 1.0),
                    literal("501001", 20, 26, anchorHead = 2),
                    "snap-1",
                )

            // lex-matcher leaves a member row's target_ref EMPTY ("a data value points at nothing
            // but itself"); the lattice needs one addressable ref per binding.
            binding.ref shouldBe "md.dimension.Account.code#501001"
            binding.targetClass shouldBe LatticeTargetClass.TARGET_CLASS_MEMBER
            binding.source shouldBe LatticeSourceTag.SOURCE_TAG_DATA
            binding.evidenceClass shouldBe EvidenceClass.EVIDENCE_CLASS_EXACT
            binding.producer.snapshotHash shouldBe "snap-1"
        }

        "Binding: a declared row keeps its own ref and class" {
            val binding =
                mapped(
                    declared("op:show", "op:show", TargetClass.TARGET_CLASS_OPERATOR, 1.0),
                    mention("Zobraz", 0, 6),
                    "snap-1",
                )
            binding.ref shouldBe "op:show"
            binding.targetClass shouldBe LatticeTargetClass.TARGET_CLASS_OPERATOR
            binding.source shouldBe LatticeSourceTag.SOURCE_TAG_DECLARED
            binding.evidenceClass shouldBe EvidenceClass.EVIDENCE_CLASS_DECLARED_ALIAS
        }

        "Binding: the two TargetClass vocabularies are pinned equal — the mapping goes by NUMBER" {
            // `Bindings.targetClassOf` translates `fuzzy.v1.TargetClass` to `resolver.v1.TargetClass`
            // through `forNumber`, so these are two independent protos held together by an
            // assumption. A value added to one out of order would silently re-label every binding,
            // and the wire gate cannot see it — an ADDED enum value is legal in both files
            // (p2-1 review). This is the seam RV-P1.6.T6 closed for GROUNDING_KINDS by reading the
            // producer's vocabulary; here the wire shape forbids that, so it is pinned instead.
            numbersOf(TargetClass.values()) shouldBe numbersOf(LatticeTargetClass.values())
        }

        "Binding: the RV-32 method parameter survives the enum, and an unknown method is not guessed" {
            val typos =
                mapped(
                    declared("md.measure.cost", "md.measure.cost", TargetClass.TARGET_CLASS_MODEL_OBJECT, 0.8)
                        .toBuilder()
                        .setMatchMethod("TYPOS(2)")
                        .build(),
                    mention("naklady", 0, 7),
                    "snap-1",
                )
            typos.method shouldBe MatchMethod.MATCH_METHOD_TYPOS
            typos.maxDistance shouldBe 2

            val unknown =
                mapped(
                    declared("md.measure.cost", "md.measure.cost", TargetClass.TARGET_CLASS_MODEL_OBJECT, 0.8)
                        .toBuilder()
                        .setMatchMethod("PHONETIC(3)")
                        .build(),
                    mention("naklady", 0, 7),
                    "snap-1",
                )
            unknown.method shouldBe MatchMethod.MATCH_METHOD_UNSPECIFIED
        }

        "Binding: TOKENS keeps the uniqueness decision distinguishable from 'no decision'" {
            val decided =
                mapped(
                    declared("md.measure.cost", "md.measure.cost", TargetClass.TARGET_CLASS_MODEL_OBJECT, 0.9)
                        .toBuilder()
                        .setMatchMethod("TOKENS")
                        .setUniquenessMargin(0.4)
                        .setAutoBindable(false)
                        .build(),
                    mention("naklady", 0, 7),
                    "snap-1",
                )
            decided.hasAutoBindable().shouldBeTrue()
            decided.autoBindable.shouldBeFalse()
            decided.uniquenessMargin shouldBe 0.4

            val undecided =
                mapped(
                    declared("md.measure.cost", "md.measure.cost", TargetClass.TARGET_CLASS_MODEL_OBJECT, 0.9),
                    mention("naklady", 0, 7),
                    "snap-1",
                )
            undecided.hasAutoBindable().shouldBeFalse()
        }

        "Binding: a sub-exact hit is fuzzy-strong, and anchoring is what separates the two classes" {
            val anchored =
                mapped(
                    member("df-adnak", "er.qstred_df.nazev", 0.72),
                    DomainSpanCandidate(
                        "DF ADNAK",
                        0,
                        8,
                        listOf("er.qstred_df"),
                        listOf("er.qstred_df.nazev"),
                        anchored = true,
                        origin = DomainSpanCandidate.Origin.GOVERNED_VALUE,
                    ),
                    "snap-1",
                )
            val unanchored =
                mapped(
                    member("df-adnak", "er.qstred_df.nazev", 0.72),
                    DomainSpanCandidate(
                        "DF ADNAK",
                        0,
                        8,
                        listOf("er.qstred_df"),
                        listOf("er.qstred_df.nazev"),
                        anchored = false,
                        origin = DomainSpanCandidate.Origin.PROPER_NOUN,
                    ),
                    "snap-1",
                )
            anchored.evidenceClass shouldBe EvidenceClass.EVIDENCE_CLASS_ANCHORED_FUZZY_STRONG
            unanchored.evidenceClass shouldBe EvidenceClass.EVIDENCE_CLASS_UNANCHORED_FUZZY_STRONG
        }

        "EvidenceClass rank: UNSPECIFIED is the WEAKEST, not the strongest (proto3 zero-value trap)" {
            EvidenceClasses.rank(EvidenceClass.EVIDENCE_CLASS_EXACT) shouldBe 1
            (
                EvidenceClasses.rank(EvidenceClass.EVIDENCE_CLASS_UNSPECIFIED) >
                    EvidenceClasses.rank(EvidenceClass.EVIDENCE_CLASS_WEAK)
            ).shouldBeTrue()
        }

        "layers: the span's SOURCE decides its layer, not what happened to match it" {
            val state =
                LatticeAssembler.assemble(
                    parse = AnalyzeResponse.getDefaultInstance(),
                    gate =
                        Bound(
                            emptyList(),
                            0.0,
                            listOf(
                                gatedSpan(
                                    mention("účtu", 15, 19, head = 2),
                                    listOf(declared("md.dimension.Account", "md.dimension.Account")),
                                    ambiguous = false,
                                ),
                                gatedSpan(
                                    literal("501001", 20, 26, anchorHead = 2),
                                    listOf(member("501001", "md.dimension.Account.code", 1.0)),
                                    ambiguous = false,
                                ),
                            ),
                        ),
                    ungatedMentions =
                        listOf(
                            gatedSpan(mention("položky", 30, 37, head = 5), emptyList(), ambiguous = false),
                        ),
                    universals = emptyList(),
                    entityTypes = emptyList(),
                    snapshotHash = "snap-1",
                    lang = "cs",
                    preps = FrameRolePreps.shipped(),
                    batch = BatchMatchResponse.getDefaultInstance(),
                )

            state.mentionsList.map { it.span.text } shouldContainExactly listOf("účtu", "položky")
            state.valuesList.map { it.span.text } shouldContainExactly listOf("501001")
            // a mention that matched nothing is still a mention — the whole point of the layer
            state.mentionsList
                .last()
                .bindingsCount shouldBe 0
            state.valuesList
                .single()
                .attributionsList
                .single()
                .attributeRef shouldBe "md.dimension.Account.code"
            state.valuesList.single().anchorMentionId shouldBe "m1"
        }

        "an UNBOUND mention is not an anchor — it has no categories to lend" {
            val state =
                LatticeAssembler.assemble(
                    parse = AnalyzeResponse.getDefaultInstance(),
                    gate =
                        Bound(
                            emptyList(),
                            0.0,
                            listOf(
                                gatedSpan(mention("stanic", 0, 6, head = 0), emptyList(), ambiguous = false),
                                gatedSpan(literal("10", 8, 10, anchorHead = 0), emptyList(), ambiguous = false),
                            ),
                        ),
                    ungatedMentions = emptyList(),
                    universals = emptyList(),
                    entityTypes = emptyList(),
                    snapshotHash = "snap-1",
                    lang = "cs",
                    preps = FrameRolePreps.shipped(),
                    batch = BatchMatchResponse.getDefaultInstance(),
                )
            state.valuesList.single().anchorMentionId shouldBe ""
        }

        "⛑ a GROUNDING_TRIGGER row on a VALUE span is NOT an attribution" {
            // hartland, 2026-09-16. The live lattice for "Jak se vyvíjela tržba z tržiště v roce
            // 2025?" carried `attributions { attribute_ref: "ground:chrono" }` on the LITERAL value
            // *roce*, and golem's query door refused the whole turn — `'ground:chrono' is not an
            // addressable object or attribute` — while the kernel had meanwhile grounded *2025*
            // onto a real column. An attribution says "this literal could be a value of THAT
            // attribute"; a trigger names which KERNEL owns the span, so it can never be one.
            val state =
                LatticeAssembler.assemble(
                    parse = AnalyzeResponse.getDefaultInstance(),
                    gate =
                        Bound(
                            emptyList(),
                            0.0,
                            listOf(
                                gatedSpan(
                                    literal("roce", 34, 38, anchorHead = -1),
                                    listOf(
                                        declared(
                                            "ground:chrono",
                                            "ground:chrono",
                                            TargetClass.TARGET_CLASS_GROUNDING_TRIGGER,
                                        ),
                                    ),
                                    ambiguous = false,
                                ),
                            ),
                        ),
                    ungatedMentions = emptyList(),
                    universals = emptyList(),
                    entityTypes = emptyList(),
                    snapshotHash = "snap-1",
                    lang = "cs",
                    preps = FrameRolePreps.shipped(),
                    batch = BatchMatchResponse.getDefaultInstance(),
                )
            state.valuesList.single().attributionsList shouldBe emptyList()
        }

        "a MODEL_OBJECT row on the same span IS an attribution — the control for the rule above" {
            // Without this, the assertion above would pass equally well if the span never reached
            // the value layer at all, and a probe that cannot tell those two apart proves nothing.
            val state =
                LatticeAssembler.assemble(
                    parse = AnalyzeResponse.getDefaultInstance(),
                    gate =
                        Bound(
                            emptyList(),
                            0.0,
                            listOf(
                                gatedSpan(
                                    literal("roce", 34, 38, anchorHead = -1),
                                    listOf(declared("er.entity.date_dim.year", "er.entity.date_dim.year")),
                                    ambiguous = false,
                                ),
                            ),
                        ),
                    ungatedMentions = emptyList(),
                    universals = emptyList(),
                    entityTypes = emptyList(),
                    snapshotHash = "snap-1",
                    lang = "cs",
                    preps = FrameRolePreps.shipped(),
                    batch = BatchMatchResponse.getDefaultInstance(),
                )
            state.valuesList
                .single()
                .attributionsList
                .single()
                .attributeRef shouldBe "er.entity.date_dim.year"
        }

        "an ambiguous span keeps ALL its candidates — the lattice does not pick one" {
            val state =
                LatticeAssembler.assemble(
                    parse = AnalyzeResponse.getDefaultInstance(),
                    gate =
                        Bound(
                            emptyList(),
                            0.0,
                            listOf(
                                gatedSpan(
                                    mention("středisko", 0, 9, head = 0),
                                    listOf(
                                        declared("er.qstred_df", "er.qstred_df", score = 0.88),
                                        declared("er.qxxukazmu", "er.qxxukazmu", score = 0.86),
                                    ),
                                    ambiguous = true,
                                ),
                            ),
                        ),
                    ungatedMentions = emptyList(),
                    universals = emptyList(),
                    entityTypes = emptyList(),
                    snapshotHash = "snap-1",
                    lang = "cs",
                    preps = FrameRolePreps.shipped(),
                    batch = BatchMatchResponse.getDefaultInstance(),
                )
            state.mentionsList
                .single()
                .bindingsList
                .map { it.ref } shouldContainExactly
                listOf("er.qstred_df", "er.qxxukazmu")
        }

        "a universal extraction is a GROUNDED value carrying what typed it" {
            val state =
                LatticeAssembler.assemble(
                    parse = AnalyzeResponse.getDefaultInstance(),
                    gate = Bound(emptyList(), 0.0, emptyList()),
                    ungatedMentions = emptyList(),
                    universals =
                        listOf(
                            UniversalBinding(
                                start = 34,
                                end = 38,
                                text = "2025",
                                entityType = UniversalEntityType.DATE,
                                rawText = "2025",
                                normalizedValue = "2025",
                                sourceEngine = "nametag3",
                            ),
                        ),
                    entityTypes = emptyList(),
                    snapshotHash = "snap-1",
                    lang = "cs",
                    preps = FrameRolePreps.shipped(),
                    batch = BatchMatchResponse.getDefaultInstance(),
                )
            val value = state.valuesList.single()
            value.kind shouldBe ValueKind.VALUE_KIND_GROUNDED
            value.grounding.kind shouldBe "DATE"
            value.grounding.kernel shouldBe "nametag3"
        }

        // --- MS-P3·S3 — the kind reaches the roles through the assembler ---------------------

        "MS: a bound measure mention comes out MEASURE, and R5 still FILTERs its modifier" {
            // "webové tržby" — 0 webové(NOUN, compound→2) 1 tržby(NOUN, root), TWO mentions: the
            // premodifier binds a plain attribute, the head binds the measure.
            //
            // ⛑ review-084 F1 — this case used to be named *"and its compound modifier is not a
            // FILTER"*, which is not a rule this system has. **R5 reads the MENTION's own kind and
            // the MENTION's own head deprel; it never consults the head mention's kind.** No frame
            // rule is transitive through a head. So `webové` keeps FILTER here exactly as it did
            // before MS, and design.md §1's *"its compound modifier takes FILTER through R5"* is
            // describing something MS neither fixes nor should.
            //
            // What MS does fix is the MIRROR image — a mention that IS a compound modifier and
            // binds something measure-capable is no longer stamped FILTER — and that is pinned in
            // `FrameRolesMeasureTest` ("R5 exemption/contrast"), where the mention's own head
            // token carries the `compound` relation. Here the measure is the phrase head at
            // deprel `root`, so R5 never looks at it at all; what this case pins end to end is R2
            // reaching the roles through `objectKindOf`, and the modifier beside it as the
            // control that says the exemption did not leak sideways.
            val parse =
                AnalyzeResponse
                    .newBuilder()
                    .addAllTokens(
                        listOf(
                            token("webové", 0, 6, "web", "NOUN", 2, "compound"),
                            token("tržby", 7, 12, "tržby", "NOUN", 0, "root"),
                        ),
                    ).build()
            val modifierSpan =
                DomainSpanCandidate(
                    "webové",
                    0,
                    6,
                    listOf("er.entity.sales.channel"),
                    listOf("er.entity.sales.channel"),
                    anchored = true,
                    origin = DomainSpanCandidate.Origin.ANCHOR_PHRASE,
                    headToken = 0,
                    lemma = "web",
                )
            val headSpan =
                DomainSpanCandidate(
                    "tržby",
                    7,
                    12,
                    listOf("er.entity.sales", "er.entity.sales.amount_czk"),
                    listOf("er.entity.sales.name", "er.entity.sales.amount_czk"),
                    anchored = true,
                    origin = DomainSpanCandidate.Origin.ANCHOR_PHRASE,
                    headToken = 1,
                    lemma = "tržby",
                )
            val channel = declared("er.entity.sales.channel", "er.entity.sales.channel")
            val amount = declared("er.entity.sales.amount_czk", "er.entity.sales.amount_czk")
            val state =
                LatticeAssembler.assemble(
                    parse = parse,
                    // the mention's bindings are read off the GATED span; `Bound.bindings` is the
                    // door's answer and plays no part in role derivation
                    gate =
                        Bound(
                            emptyList(),
                            1.0,
                            listOf(
                                gatedSpan(modifierSpan, listOf(channel), ambiguous = false),
                                gatedSpan(headSpan, listOf(amount), ambiguous = false),
                            ),
                        ),
                    ungatedMentions = emptyList(),
                    universals = emptyList(),
                    entityTypes =
                        listOf(
                            ResolverEntityType(
                                "er.entity.sales",
                                listOf("er.entity.sales.name"),
                                listOf("tržby"),
                                objectKind = "entity_with_measures",
                            ),
                            ResolverEntityType(
                                "er.entity.sales.amount_czk",
                                listOf("er.entity.sales.amount_czk"),
                                listOf("tržby"),
                                objectKind = "measure",
                                ownerRef = "er.entity.sales",
                            ),
                            ResolverEntityType(
                                "er.entity.sales.channel",
                                listOf("er.entity.sales.channel"),
                                listOf("web"),
                                objectKind = "attribute",
                                ownerRef = "er.entity.sales",
                            ),
                        ),
                    snapshotHash = "snap-1",
                    lang = "cs",
                    preps = FrameRolePreps.shipped(),
                    batch = BatchMatchResponse.getDefaultInstance(),
                )
            val bySpan = state.mentionsList.associateBy { it.span.start }
            // the binding's OWN ref decides the kind — `objectKindOf` reads the registry, and the
            // measure is what the containment collapse bound
            bySpan.getValue(7).frameRolesList shouldContainExactly
                listOf(FrameRole.FRAME_ROLE_SUBJECT, FrameRole.FRAME_ROLE_MEASURE)
            // and the modifier is untouched by any of it: its own kind is `attribute`, its own head
            // relation is `compound`, so R5 fires exactly as it always did
            bySpan.getValue(0).frameRolesList shouldContainExactly listOf(FrameRole.FRAME_ROLE_FILTER)
        }

        "MS: a measure-CAPABLE entity under `podle` is exempt from GROUPING and takes no MEASURE" {
            // "prvních 10 prodejen podle prodejů" — the ORDER-BY reading. `prodejů` binds the sales
            // ENTITY, which declares measures: it must not become the grouping axis (R3 exemption
            // via measureCapable) and must not be stamped MEASURE either, because whether the
            // question wants rows, a count or a measure value is the operator layer's reading
            // (MS-R6, contracts §9). Both halves of the split are asserted by one case.
            val parse =
                AnalyzeResponse
                    .newBuilder()
                    .addAllTokens(
                        listOf(
                            token("prvních", 0, 7, "první", "ADJ", 3, "amod"),
                            token("10", 8, 10, "10", "NUM", 3, "nummod"),
                            token("prodejen", 11, 19, "prodejna", "NOUN", 0, "nsubj"),
                            token("podle", 20, 25, "podle", "ADP", 5, "case"),
                            token("prodejů", 26, 33, "prodej", "NOUN", 3, "nmod"),
                        ),
                    ).build()
            val span =
                DomainSpanCandidate(
                    "prodejů",
                    26,
                    33,
                    listOf("er.entity.sales"),
                    listOf("er.entity.sales.name"),
                    anchored = true,
                    origin = DomainSpanCandidate.Origin.ANCHOR_PHRASE,
                    headToken = 4,
                    lemma = "prodej",
                )
            val sales = declared("er.entity.sales", "er.entity.sales.name")
            val state =
                LatticeAssembler.assemble(
                    parse = parse,
                    gate = Bound(emptyList(), 1.0, listOf(gatedSpan(span, listOf(sales), ambiguous = false))),
                    ungatedMentions = emptyList(),
                    universals = emptyList(),
                    entityTypes =
                        listOf(
                            ResolverEntityType(
                                "er.entity.sales",
                                listOf("er.entity.sales.name"),
                                listOf("prodej"),
                                objectKind = "entity_with_measures",
                            ),
                        ),
                    snapshotHash = "snap-1",
                    lang = "cs",
                    preps = FrameRolePreps.shipped(),
                    batch = BatchMatchResponse.getDefaultInstance(),
                )
            val mention = state.mentionsList.single()
            mention.frameRolesList shouldNotContain FrameRole.FRAME_ROLE_GROUPING
            mention.frameRolesList shouldNotContain FrameRole.FRAME_ROLE_MEASURE
            // it is the only mention left, so R9's residue pick lands on it
            mention.frameRolesList shouldContainExactly listOf(FrameRole.FRAME_ROLE_SUBJECT)
        }

        // --- MH-D3 — the equivalent reading reaches the wire, on the WINNER only ---------------

        "MH: a binding carries the readings proven equal to it" {
            val binding =
                Bindings.of(
                    classedBy(
                        declared("er.entity.store", "prodejna", TargetClass.TARGET_CLASS_MODEL_OBJECT, 1.0),
                        mention("prodejna", 0, 8),
                    ),
                    "snap-1",
                    listOf(EquivalentReading("er.entity.store_sales", "reach-equal")),
                )

            binding.equivalentsList.map { it.ref to it.rule } shouldContainExactly
                listOf("er.entity.store_sales" to "reach-equal")
        }

        "MH: a binding with no equivalents does not print the field — the goldens depend on it" {
            // An empty repeated field is absent from the text form, which is what keeps every
            // pre-MH lattice byte-identical after the proto grew.
            val binding =
                mapped(
                    declared("er.entity.store", "prodejna", TargetClass.TARGET_CLASS_MODEL_OBJECT, 1.0),
                    mention("prodejna", 0, 8),
                    "snap-1",
                )

            binding.equivalentsList shouldBe emptyList()
            binding.toString().contains("equivalents") shouldBe false
        }

        "the RV-39 layer tuple is echoed from the matcher's answer, absence and all" {
            val batch =
                BatchMatchResponse
                    .newBuilder()
                    .addResults(FuzzyMatchResponse.getDefaultInstance())
                    .addResults(
                        FuzzyMatchResponse.newBuilder().setLayerVersions(
                            LayerVersions
                                .newBuilder()
                                .setLexiconArtifactHash("sha256:abc")
                                .putMemberIndexVersions("er.branch", "v7"),
                        ),
                    ).build()

            val state =
                LatticeAssembler.assemble(
                    parse = AnalyzeResponse.getDefaultInstance(),
                    gate = Bound(emptyList(), 0.0, emptyList()),
                    ungatedMentions = emptyList(),
                    universals = emptyList(),
                    entityTypes = emptyList(),
                    snapshotHash = "snap-1",
                    lang = "cs",
                    preps = FrameRolePreps.shipped(),
                    batch = batch,
                )
            state.lexiconVersions.lexiconArtifactHash shouldBe "sha256:abc"
            state.lexiconVersions.memberIndexVersionsMap shouldBe mapOf("er.branch" to "v7")
            // absent with no overlay loaded — and absence is the contract, not an empty string
            state.lexiconVersions.hasOverlayVersion().shouldBeFalse()
        }
    }) {
    companion object {
        /**
         * RV-P2.2 — a matcher row as the GATE now hands it on: with the class the gate derived.
         * These helpers run the real [EvidenceClasses] derivation rather than asserting a class
         * into place, so a change to the derivation is visible in every mapping test too.
         */
        private fun classedBy(
            match: FuzzyMatch,
            span: DomainSpanCandidate,
        ): Binder.ClassedMatch =
            Binder.ClassedMatch(match, EvidenceClasses.of(match, span.anchored, ResolverThresholds.LIVE))

        private fun gatedSpan(
            span: DomainSpanCandidate,
            matches: List<FuzzyMatch>,
            ambiguous: Boolean,
        ): GatedSpan = GatedSpan(span, matches.map { classedBy(it, span) }, ambiguous)

        private fun mapped(
            match: FuzzyMatch,
            span: DomainSpanCandidate,
            snapshotHash: String,
        ) = Bindings.of(classedBy(match, span), snapshotHash)

        /** Name → number, minus the generated `UNRECOGNIZED` sentinel (its `number` throws). */
        private fun numbersOf(values: Array<out Enum<*>>): Map<String, Int> =
            values
                .filter { it.name != "UNRECOGNIZED" }
                .associate { it.name to (it as com.google.protobuf.ProtocolMessageEnum).number }

        private fun token(
            text: String,
            start: Int,
            end: Int,
            lemma: String,
            upos: String,
            depHead: Int,
            depRelation: String,
        ): org.tatrman.nlp.v1.Token =
            org.tatrman.nlp.v1.Token
                .newBuilder()
                .setText(text)
                .setCharStart(start)
                .setCharEnd(end)
                .setLemma(lemma)
                .setUpos(upos)
                .setDepHead(depHead)
                .setDepRelation(depRelation)
                .build()

        private fun mention(
            text: String,
            start: Int,
            end: Int,
            head: Int = -1,
        ) = DomainSpanCandidate(
            text,
            start,
            end,
            listOf("er.x"),
            listOf("er.x"),
            anchored = true,
            origin = DomainSpanCandidate.Origin.ANCHOR_PHRASE,
            headToken = head,
            lemma = text,
        )

        private fun literal(
            text: String,
            start: Int,
            end: Int,
            anchorHead: Int,
        ) = DomainSpanCandidate(
            text,
            start,
            end,
            listOf("er.x"),
            listOf("er.x"),
            anchored = true,
            origin = DomainSpanCandidate.Origin.LITERAL,
            headToken = -1,
            anchorHeadToken = anchorHead,
        )

        private fun declared(
            targetRef: String,
            category: String,
            targetClass: TargetClass = TargetClass.TARGET_CLASS_MODEL_OBJECT,
            score: Double = 1.0,
        ): FuzzyMatch =
            FuzzyMatch
                .newBuilder()
                .setCandidateId("lex:$targetRef")
                .setCandidate(targetRef)
                .setScore(score)
                .setCategory(category)
                .setSource(SourceTag.DECLARED)
                .setTargetRef(targetRef)
                .setTargetClass(targetClass)
                .setProvenance(Provenance.newBuilder().setProducer("lex-matcher").setMethod("TATRMAN"))
                .build()

        private fun member(
            id: String,
            category: String,
            score: Double,
        ): FuzzyMatch =
            FuzzyMatch
                .newBuilder()
                .setCandidateId(id)
                .setCandidate(id)
                .setScore(score)
                .setCategory(category)
                .setSource(SourceTag.MEMBER)
                .setProvenance(Provenance.newBuilder().setProducer("lex-matcher").setMethod("TATRMAN"))
                .build()
    }
}
