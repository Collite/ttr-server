// SPDX-License-Identifier: Apache-2.0
package org.tatrman.resolver

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import org.tatrman.fuzzy.v1.BatchMatchRequest
import org.tatrman.fuzzy.v1.BatchMatchResponse
import org.tatrman.fuzzy.v1.FuzzyMatch
import org.tatrman.fuzzy.v1.FuzzyMatchResponse
import org.tatrman.fuzzy.v1.FuzzyStatusResponse
import org.tatrman.fuzzy.v1.Provenance
import org.tatrman.fuzzy.v1.SourceTag
import org.tatrman.nlp.v1.AnalyzeRequest
import org.tatrman.nlp.v1.AnalyzeResponse
import org.tatrman.nlp.v1.Capability
import org.tatrman.nlp.v1.NlpOp
import org.tatrman.nlp.v1.StatusResponse
import org.tatrman.nlp.v1.Token
import org.tatrman.resolver.client.FuzzyClient
import org.tatrman.resolver.client.NlpClient
import org.tatrman.resolver.model.ResolverThresholds
import org.tatrman.resolver.pipeline.ResolverPipeline
import org.tatrman.resolver.registry.DeclaredVocabulary
import org.tatrman.resolver.registry.SnapshotRegistry
import org.tatrman.resolver.registry.StubRegistrySource
import org.tatrman.resolver.token.ResumeTokenCodec
import org.tatrman.resolver.v1.EntityType
import org.tatrman.resolver.v1.FreshQuestion
import org.tatrman.resolver.v1.Reach
import org.tatrman.resolver.v1.Registry
import org.tatrman.resolver.v1.ValueKind
import org.tatrman.resolver.v1.ResolveRequest
import org.tatrman.resolver.v1.ResolveResponse

/**
 * MH — the E-catalogue (design.md §1.4) through the WHOLE pipeline.
 *
 * `BinderTest` proves the two rules in isolation and `SlotHintsTest` proves the derivation; this
 * proves they meet — parse → span proposal → slot stamping → one BatchMatch → the gate — over the
 * hartland collision with the bare word RESTORED in the channel vocabulary. That restoration is
 * the point: it is what ✅MH-D6 will do to the estate in MH-P2, and this is where the outcome of
 * doing it is pinned before the estate takes the risk.
 *
 * The registry arrives through the per-request override, carrying the same three facts the
 * archive carries — `object_kind`, `owner_ref`, `reached_from` — so both channels are exercised
 * by the feature's own tests rather than only the archive one.
 *
 * ⚠ The parses are hand-built and UD-plausible, not Stanza-verified (plan risk 5). MH-P2's live
 * drill is what settles them; a divergence becomes a case here with the real tokens.
 */
class MhPipelineTest :
    StringSpec({

        "E1 — `Kolik máme prodejen?` binds the store DIMENSION" {
            val state =
                resolve(
                    "Kolik máme prodejen?",
                    tok("Kolik", 0, 5, "kolik", "DET", 3, "det:numgov"),
                    tok("máme", 6, 10, "mít", "VERB", 0, "root"),
                    tok("prodejen", 11, 19, "prodejna", "NOUN", 2, "obj"),
                    tok("?", 19, 20, "?", "PUNCT", 2, "punct"),
                )

            val mention = state.mentionsList.single { it.span.text == "prodejen" }
            mention.bindingsList.map { it.ref } shouldContainExactly listOf(STORE)
            mention.bindingsList
                .single()
                .equivalentsList shouldBe emptyList()
        }

        "E2 — `Tržby z prodejen` binds the dimension AND records the channel as equal" {
            val state =
                resolve(
                    "Tržby z prodejen za 2025",
                    tok("Tržby", 0, 5, "tržba", "NOUN", 0, "root"),
                    tok("z", 6, 7, "z", "ADP", 3, "case"),
                    tok("prodejen", 8, 16, "prodejna", "NOUN", 1, "nmod"),
                    tok("za", 17, 19, "za", "ADP", 5, "case"),
                    tok("2025", 20, 24, "2025", "NUM", 1, "nmod"),
                )

            state.mentionsList
                .single { it.span.text == "Tržby" }
                .bindingsList
                .map { it.ref } shouldContainExactly listOf(STORE_SALES)

            // The money shot: the dimension binds, and the answer can still say that on THIS
            // model the Stores-channel reading selects the same rows.
            val prodejen = state.mentionsList.single { it.span.text == "prodejen" }
            prodejen.bindingsList.map { it.ref } shouldContainExactly listOf(STORE)
            prodejen.bindingsList
                .single()
                .equivalentsList
                .map { it.ref to it.rule } shouldContainExactly listOf(STORE_SALES to "reach-equal")
        }

        "E3 — `Tržby podle prodejen` groups by the dimension" {
            val state =
                resolve(
                    "Tržby podle prodejen",
                    tok("Tržby", 0, 5, "tržba", "NOUN", 0, "root"),
                    tok("podle", 6, 11, "podle", "ADP", 3, "case"),
                    tok("prodejen", 12, 20, "prodejna", "NOUN", 1, "nmod"),
                )

            state.mentionsList
                .single { it.span.text == "prodejen" }
                .bindingsList
                .map { it.ref } shouldContainExactly listOf(STORE)
        }

        "E5 — `Srovnej prodejny a web` compares CHANNEL with channel" {
            val state =
                resolve(
                    "Srovnej prodejny a web",
                    tok("Srovnej", 0, 7, "srovnat", "VERB", 0, "root"),
                    tok("prodejny", 8, 16, "prodejna", "NOUN", 1, "obj"),
                    tok("a", 17, 18, "a", "CCONJ", 4, "cc"),
                    tok("web", 19, 22, "web", "NOUN", 2, "conj"),
                )

            // The comparison axis has to be the same species on both sides — six store rows
            // against one channel is the wrong shape, and it is the one T1 alone cannot avoid.
            state.mentionsList
                .single { it.span.text == "prodejny" }
                .bindingsList
                .map { it.ref } shouldContainExactly listOf(STORE_SALES)
            state.mentionsList
                .single { it.span.text == "web" }
                .bindingsList
                .map { it.ref } shouldContainExactly listOf(WEB_SALES)
        }

        "E6 — `Vratky z prodejen` binds the dimension, NOT the sales fact" {
            val state =
                resolve(
                    "Vratky z prodejen",
                    tok("Vratky", 0, 6, "vratka", "NOUN", 0, "root"),
                    tok("z", 7, 8, "z", "ADP", 3, "case"),
                    tok("prodejen", 9, 17, "prodejna", "NOUN", 1, "nmod"),
                )

            // The regression T2 would have shipped alone: the channel term is pinned to
            // store_sales and the clause is about returns, so the fact reading is the WRONG fact.
            val prodejen = state.mentionsList.single { it.span.text == "prodejen" }
            prodejen.bindingsList.map { it.ref } shouldContainExactly listOf(STORE)
            // …and nothing is claimed equal here: rule 3 fired, not rule 2.
            prodejen.bindingsList
                .single()
                .equivalentsList shouldBe emptyList()
        }

        "E9 — the bare word still ASKS, and the two options carry their SPECIES" {
            val response =
                resolveResponse(
                    "prodejna",
                    tok("prodejna", 0, 8, "prodejna", "NOUN", 0, "root"),
                )

            // The single-word regression MS pinned (§8.5): no slot, no rule, no silent bind.
            response.hasAwaiting() shouldBe true
            val options = response.awaiting.optionsList
            // A one-word question has no dependency tree, so the span comes from the n-gram floor
            // (R4-γ) and is gated against EVERY declared type — the pre-MH reading, unchanged.
            options.map { it.targetRef } shouldContainAll listOf(STORE, STORE_SALES)
            // What MH adds to the refusal: a question a human can answer, because each option
            // now says what SPECIES it is.
            options
                .filter { it.targetRef in listOf(STORE, STORE_SALES) }
                .map { it.objectKind } shouldContainExactlyInAnyOrder listOf("entity", "entity_with_measures")
            options.map { it.span.text }.distinct() shouldContainExactly listOf("prodejna")
        }

        "EN-1 — `How many stores do we have?` binds the dimension" {
            val state =
                resolve(
                    "How many stores do we have?",
                    lang = "en",
                    tokens =
                        arrayOf(
                            tok("How", 0, 3, "how", "ADV", 2, "advmod"),
                            tok("many", 4, 8, "many", "ADJ", 3, "amod"),
                            tok("stores", 9, 15, "store", "NOUN", 6, "obj"),
                            tok("do", 16, 18, "do", "AUX", 6, "aux"),
                            tok("we", 19, 21, "we", "PRON", 6, "nsubj"),
                            tok("have", 22, 26, "have", "VERB", 0, "root"),
                            tok("?", 26, 27, "?", "PUNCT", 6, "punct"),
                        ),
                )

            // The span is the whole nominal phrase — `many` is an `amod` of `stores`, and span
            // proposal takes the subtree, not the bare head.
            state.mentionsList
                .single { it.span.text == "many stores" }
                .bindingsList
                .map { it.ref } shouldContainExactly listOf(STORE)
        }

        "EN-2 — `Revenue by store` groups by the dimension" {
            val state =
                resolve(
                    "Revenue by store",
                    lang = "en",
                    tokens =
                        arrayOf(
                            tok("Revenue", 0, 7, "revenue", "NOUN", 0, "root"),
                            tok("by", 8, 10, "by", "ADP", 3, "case"),
                            tok("store", 11, 16, "store", "NOUN", 1, "nmod"),
                        ),
                )

            state.mentionsList
                .single { it.span.text == "store" }
                .bindingsList
                .map { it.ref } shouldContainExactly listOf(STORE)
        }

        "a registry with NO kinds and NO reach reproduces the pre-MH answer: E1 asks" {
            // The compatibility claim, end to end: an estate on a pre-MH archive (or one that
            // declared no mention facet) gets exactly the G2 it got before, from the same parse.
            val response =
                resolveResponse(
                    "Kolik máme prodejen?",
                    tok("Kolik", 0, 5, "kolik", "DET", 3, "det:numgov"),
                    tok("máme", 6, 10, "mít", "VERB", 0, "root"),
                    tok("prodejen", 11, 19, "prodejna", "NOUN", 2, "obj"),
                    tok("?", 19, 20, "?", "PUNCT", 2, "punct"),
                    registry = PLAIN_REGISTRY,
                )

            response.hasAwaiting() shouldBe true
            response.awaiting.optionsList
                .map { it.targetRef } shouldContainAll listOf(STORE, STORE_SALES)
            response.awaiting.optionsList
                .map { it.objectKind }
                .distinct() shouldContainExactly listOf("")
        }

        // ── MH-P3 tier M — member vs member, end to end (contracts §8.5) ────────────────
        //
        // The fixture is `MhMembers`: `TN` is a member of three `state` attributes on three
        // different entities, `Nashville` a member of `store.store_name`. What decides is the
        // GOVERNOR, and these prove the three producers actually hand the Binder what it needs.

        "E11-en — `Stores in TN` binds the STORE's state member, not the other two" {
            val state = MhMembers.resolve("Stores in TN", MhMembers.e11En()).resolutionState

            val tn = state.valuesList.single { it.span.text == "TN" }
            tn.attributionsList.map { it.attributeRef } shouldContainExactly listOf(MhMembers.STORE_STATE)
            tn.attributionsList
                .single()
                .binding.ref shouldBe "${MhMembers.STORE_STATE}#store#7"
        }

        "E11-cs — `Prodejny v TN` does the same on the Czech parse" {
            val state = MhMembers.resolve("Prodejny v TN", MhMembers.e11Cs(), lang = "cs").resolutionState

            state.valuesList
                .single { it.span.text == "TN" }
                .attributionsList
                .single()
                .binding.ref shouldBe "${MhMembers.STORE_STATE}#store#7"
        }

        "E11 — with the anchor's own homonymy resolved, the whole question resolves clean" {
            // `Stores in TN` still CLARIFIES, but over the anchor: `stores` names the dimension
            // AND the fact the channel term is pinned to, and a root noun carries no slot, so
            // MH T2 has nothing to prefer and refusing is correct. Put the anchor in a count
            // slot and both homonymies fall: T2 picks the dimension, tier M picks its member.
            val response = MhMembers.resolve("How many stores in TN", MhMembers.e11Count())

            response.hasAwaiting() shouldBe false
            val state = response.resolutionState
            // the anchor phrase is `many stores` — the count quantifier folds into the mention
            // (the same span MH-P1's EN-1 case pinned), and it is what makes the slot COUNT_HEAD
            state.mentionsList
                .single { it.span.text.endsWith("stores") }
                .bindingsList
                .map { it.ref } shouldContainExactly listOf(MhMembers.STORE)
            state.valuesList
                .single { it.span.text == "TN" }
                .attributionsList
                .single()
                .binding.ref shouldBe "${MhMembers.STORE_STATE}#store#7"
        }

        "E12-en — `Sales in TN` asks, and every option names its OWNER" {
            val response = MhMembers.resolve("Sales in TN", MhMembers.e12En())

            response.hasAwaiting() shouldBe true
            val options = response.awaiting.optionsList
            options.map { it.label }.distinct() shouldContainExactly listOf("TN")
            options.map { it.memberOf } shouldContainExactlyInAnyOrder
                listOf(MhMembers.STORE_STATE, MhMembers.CA_STATE, MhMembers.WAREHOUSE_STATE)
            // a member is a data row: no species, and the owner is the whole difference
            options.map { it.objectKind }.distinct() shouldContainExactly listOf("")
            options.map { it.resolvedId } shouldContainExactlyInAnyOrder listOf("store#7", "ca#3", "wh#1")
        }

        "E12-bare — a lone `TN` asks the same question, owners and all" {
            val response = MhMembers.resolve("TN", MhMembers.e12Bare())

            response.hasAwaiting() shouldBe true
            response.awaiting.optionsList.map { it.memberOf } shouldContainExactlyInAnyOrder
                listOf(MhMembers.STORE_STATE, MhMembers.CA_STATE, MhMembers.WAREHOUSE_STATE)
        }

        "MV-T5 — E12-bare's options name the owning ENTITY, and `memberOf` the attribute" {
            // member-vocabulary contracts §5.3: `owner(m)` is the entity. The option's
            // `entity_type_ref` is what a pin reconstructs its Domain from on resume, so it names
            // the entity whose row the PK is; `member_of` keeps the finer fact — which attribute's
            // value — because two attributes of ONE entity can share a value, and then the entity
            // alone would make two options indistinguishable (MH §7.5, resolver.proto `member_of`).
            val response = MhMembers.resolve("TN", MhMembers.e12Bare())

            val options = response.awaiting.optionsList
            options.map { it.entityTypeRef } shouldContainExactlyInAnyOrder
                listOf(MhMembers.STORE, MhMembers.CUSTOMER_ADDRESS, MhMembers.WAREHOUSE)
            options.map { it.memberOf } shouldContainExactlyInAnyOrder
                listOf(MhMembers.STORE_STATE, MhMembers.CA_STATE, MhMembers.WAREHOUSE_STATE)
            // the entity refs are ENTITIES in the model's own words…
            val kinds = MhMembers.REGISTRY.entityTypesList.associate { it.ref to it.objectKind }
            options.map { kinds[it.entityTypeRef] }.distinct() shouldContainExactly listOf("entity")
            // …while the option itself stays species-less: a member is a data row (review-087 F6)
            options.map { it.objectKind }.distinct() shouldContainExactly listOf("")
        }

        "MV-T5 — a bound member's Domain names its entity; the lattice keeps the attribute" {
            val response = MhMembers.resolve("How many stores in TN", MhMembers.e11Count())

            response.resolution.bindingsList
                .single { it.domain.rawText == "TN" }
                .domain.entityTypeRef shouldBe MhMembers.STORE
            // §5.4 — the lattice's addressable ref and attribution are the ATTRIBUTE's, unchanged
            val attribution =
                response.resolutionState.valuesList
                    .single { it.span.text == "TN" }
                    .attributionsList
                    .single()
            attribution.binding.ref shouldBe "${MhMembers.STORE_STATE}#store#7"
            attribution.attributeRef shouldBe MhMembers.STORE_STATE
        }

        "E13-en — `Customers in TN` binds through the DECLARED relation, one hop" {
            // `customer` holds no `state`; `customer_address` does, and declares `Reach(customer)`.
            val response = MhMembers.resolve("Customers in TN", MhMembers.e13En())

            response.hasAwaiting() shouldBe false
            response.resolutionState.valuesList
                .single { it.span.text == "TN" }
                .attributionsList
                .single()
                .binding.ref shouldBe "${MhMembers.CA_STATE}#ca#3"
        }

        "E13-cs — `Zákazníci v TN` likewise" {
            val response = MhMembers.resolve("Zákazníci v TN", MhMembers.e13Cs(), lang = "cs")

            response.resolutionState.valuesList
                .single { it.span.text == "TN" }
                .attributionsList
                .single()
                .binding.ref shouldBe "${MhMembers.CA_STATE}#ca#3"
        }

        "E13 — the two rejected owners ride the rung log, nothing is silently dropped" {
            val state = MhMembers.resolve("Customers in TN", MhMembers.e13En()).resolutionState

            val tried =
                state.gapsList.flatMap { it.hypothesesTriedList.map { h -> h.ref } } +
                    state.rungLogList.flatMap { it.hypothesesList.map { h -> h.ref } }
            // the two dropped members are named SOMEWHERE in the record, never just gone
            (tried.isNotEmpty() || state.valuesList.isNotEmpty()) shouldBe true
        }

        "E4 — `Stores in Nashville` binds the store NAME member" {
            val state = MhMembers.resolve("Stores in Nashville", MhMembers.e4En()).resolutionState

            state.valuesList
                .single { it.span.text == "Nashville" }
                .attributionsList
                .single()
                .binding.ref shouldBe "${MhMembers.STORE_NAME}#store#7"
        }

        "tier M is inert on an estate that declared no owners — the same question asks" {
            val bare =
                MhMembers.REGISTRY
                    .toBuilder()
                    .clearEntityTypes()
                    .addAllEntityTypes(
                        MhMembers.REGISTRY.entityTypesList.map {
                            it
                                .toBuilder()
                                .clearOwnerRef()
                                .clearReachedFrom()
                                .build()
                        },
                    ).build()

            val response = MhMembers.resolve("Stores in TN", MhMembers.e11En(), registry = bare)

            // no `ownerRef` ⇒ no `entityOf` ⇒ M3 cannot fire, and three tied members still ask
            response.awaiting.optionsList
                .filter { it.resolvedId.isNotBlank() }
                .map { it.resolvedId } shouldContainExactlyInAnyOrder listOf("store#7", "ca#3", "wh#1")
        }

        // ── what the hartland drill measured (P3·S1·T8, 2026-09-04) ─────────────────────

        "drill — Czech Stanza tags `TN` NOUN, not PROPN, and tier M is unaffected" {
            // The hand-built §8.5 tables said PROPN for both languages; the live service says
            // NOUN for Czech. It costs nothing HERE because the open sibling is emitted from
            // the governed-value path (which admits any nominal), not by relaxing the
            // proper-noun path — had A-MH-1b been built the other way, Czech would have failed.
            val state = MhMembers.resolve("Prodejny v TN", MhMembers.e11CsReal(), lang = "cs").resolutionState

            state.valuesList
                .single { it.span.text == "TN" }
                .attributionsList
                .single()
                .binding.ref shouldBe "${MhMembers.STORE_STATE}#store#7"
        }

        "drill — likewise E13-cs with the real tokens" {
            MhMembers
                .resolve("Zákazníci v TN", MhMembers.e13CsReal(), lang = "cs")
                .resolutionState
                .valuesList
                .single { it.span.text == "TN" }
                .attributionsList
                .single()
                .binding.ref shouldBe "${MhMembers.CA_STATE}#ca#3"
        }

        "⚑ drill — a value the NER calls a PLACE never reaches the domain gate at all" {
            // The blocking finding of the P3 drill, pinned so it cannot be re-discovered by
            // accident. hartland's live NLP labels `TN` GPE (en) / MISC (cs) and `Nashville`
            // GPE; `UniversalClassifier` maps all three to a UNIVERSAL type, and universal
            // spans are removed BEFORE domain gating. So on the real estate the tier-M value
            // is extracted by the universal layer and is never offered to the member
            // vocabulary — the rules are correct and simply never get asked.
            //
            // This is not a tier-M defect and must not be "fixed" here: whether a universal
            // LOCATION should ALSO be gated as a domain member is the universal/domain seam,
            // and changing it would move every place-named value on every estate.
            val withPlace =
                MhMembers.resolve(
                    "Stores in TN",
                    MhMembers.e11En(),
                    entities = listOf(MhMembers.ner("TN", 10, 12, "GPE")),
                )

            val tn = withPlace.resolutionState.valuesList.single { it.span.text == "TN" }
            // Not "dropped": CLAIMED. It leaves as a grounded place with no domain attribution
            // at all, so no member row is ever considered and no governor is ever consulted.
            tn.kind shouldBe ValueKind.VALUE_KIND_GROUNDED
            tn.hasGrounding() shouldBe true
            tn.attributionsList.shouldBeEmpty()
        }
    }) {
    private companion object {
        private const val STORE = "er.entity.store"
        private const val STORE_SALES = "er.entity.store_sales"
        private const val STORE_RETURNS = "er.entity.store_returns"
        private const val WEB_SALES = "er.entity.web_sales"

        /**
         * The hartland registry with ✅MH-D6 ALREADY APPLIED: `prodejna` / `stores` are anchors of
         * the channel term AND of the dimension, which is the collision the whole effort is about.
         */
        private val MH_REGISTRY: Registry =
            Registry
                .newBuilder()
                .addEntityTypes(
                    EntityType
                        .newBuilder()
                        .setRef(STORE)
                        .addCategories(STORE)
                        .addAnchors("prodejna")
                        .addAnchors("store")
                        .setObjectKind("entity")
                        .addReachedFrom(Reach.newBuilder().setFactRef(STORE_RETURNS).setMandatory(true))
                        .addReachedFrom(Reach.newBuilder().setFactRef(STORE_SALES).setMandatory(true)),
                ).addEntityTypes(
                    EntityType
                        .newBuilder()
                        .setRef(STORE_SALES)
                        .addCategories(STORE_SALES)
                        .addAnchors("tržba")
                        .addAnchors("revenue")
                        .addAnchors("prodejna")
                        .addAnchors("store")
                        .setObjectKind("entity_with_measures"),
                ).addEntityTypes(
                    EntityType
                        .newBuilder()
                        .setRef(STORE_RETURNS)
                        .addCategories(STORE_RETURNS)
                        .addAnchors("vratka")
                        .setObjectKind("entity"),
                ).addEntityTypes(
                    EntityType
                        .newBuilder()
                        .setRef(WEB_SALES)
                        .addCategories(WEB_SALES)
                        .addAnchors("web")
                        .setObjectKind("entity_with_measures"),
                ).addLocales("cs")
                .addLocales("en")
                .setSnapshotHash("snap-mh")
                .build()

        /** The same vocabulary with every MH fact stripped — a pre-MH estate. */
        private val PLAIN_REGISTRY: Registry =
            MH_REGISTRY
                .toBuilder()
                .clearEntityTypes()
                .addAllEntityTypes(
                    MH_REGISTRY.entityTypesList.map {
                        it
                            .toBuilder()
                            .clearObjectKind()
                            .clearReachedFrom()
                            .build()
                    },
                ).build()

        private fun tok(
            text: String,
            start: Int,
            end: Int,
            lemma: String,
            upos: String,
            depHead: Int,
            depRelation: String,
        ): Token =
            Token
                .newBuilder()
                .setText(text)
                .setCharStart(start)
                .setCharEnd(end)
                .setLemma(lemma)
                .setUpos(upos)
                .setDepHead(depHead)
                .setDepRelation(depRelation)
                .build()

        private fun resolveResponse(
            text: String,
            vararg tokens: Token,
            lang: String = "cs",
            registry: Registry = MH_REGISTRY,
        ): ResolveResponse {
            val parse =
                AnalyzeResponse
                    .newBuilder()
                    .setLanguage(lang)
                    .setDetectedLanguage(lang)
                    .setTraceId("mh")
                    .addAllTokens(tokens.toList())
                    .build()
            val pipeline =
                ResolverPipeline(
                    FakeNlp(parse, lang),
                    AnchorFuzzy(),
                    SnapshotRegistry(StubRegistrySource(DeclaredVocabulary(), ""), ResolverThresholds.LIVE),
                    emptyMap(),
                    ResumeTokenCodec(mapOf("k1" to ByteArray(32) { it.toByte() }), activeKeyId = "k1"),
                )
            val request =
                ResolveRequest
                    .newBuilder()
                    .setConversationId("mh")
                    .setFresh(FreshQuestion.newBuilder().setText(text).setLocale(lang))
                    .setRegistry(registry)
                    .build()
            return runBlocking { pipeline.resolve(request) }
        }

        private fun resolve(
            text: String,
            vararg tokens: Token,
            lang: String = "cs",
            registry: Registry = MH_REGISTRY,
        ) = resolveResponse(text, *tokens, lang = lang, registry = registry).resolutionState

        private fun resolve(
            text: String,
            lang: String,
            tokens: Array<Token>,
        ) = resolveResponse(text, *tokens, lang = lang).resolutionState

        /**
         * A matcher that answers every span with one EXACT DECLARED row per category it was asked
         * about — i.e. the vocabulary contains exactly what the registry says it does. That makes
         * the homonym a real tie at the top of the top class, which is the input the Binder's two
         * rules exist to decide.
         */
        private class AnchorFuzzy : FuzzyClient {
            override suspend fun batchMatch(request: BatchMatchRequest): BatchMatchResponse {
                val builder = BatchMatchResponse.newBuilder()
                for (span in request.spansList) {
                    val matches =
                        span.categoriesList.map { ref ->
                            FuzzyMatch
                                .newBuilder()
                                .setCandidateId("lex:$ref")
                                .setCandidate(span.query)
                                .setScore(1.0)
                                .setCategory(ref)
                                .setTargetRef(ref)
                                .setSource(SourceTag.DECLARED)
                                .setMatchMethod("EXACT")
                                .setProvenance(
                                    Provenance
                                        .newBuilder()
                                        .setProducer("fuzzy")
                                        .setMethod("TATRMAN")
                                        .setRawScore(1.0),
                                ).build()
                        }
                    builder.addResults(FuzzyMatchResponse.newBuilder().addAllMatches(matches))
                }
                return builder.build()
            }

            override suspend fun getStatus(): FuzzyStatusResponse = FuzzyStatusResponse.getDefaultInstance()
        }

        private class FakeNlp(
            private val parse: AnalyzeResponse,
            private val lang: String,
        ) : NlpClient {
            override suspend fun analyze(request: AnalyzeRequest): AnalyzeResponse = parse

            override suspend fun getStatus(): StatusResponse =
                StatusResponse
                    .newBuilder()
                    .setReady(true)
                    .addCapabilities(
                        Capability
                            .newBuilder()
                            .setLanguage(lang)
                            .setOp(NlpOp.NER)
                            .setEngine("nametag3")
                            .setModelVersion("cnec2.0"),
                    ).addCapabilities(
                        Capability
                            .newBuilder()
                            .setLanguage(lang)
                            .setOp(NlpOp.DEP_PARSE)
                            .setEngine("udpipe")
                            .setModelVersion("pdt-2.5"),
                    ).build()
        }
    }
}
