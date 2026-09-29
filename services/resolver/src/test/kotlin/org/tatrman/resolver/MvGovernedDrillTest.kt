// SPDX-License-Identifier: Apache-2.0
package org.tatrman.resolver

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import org.tatrman.nlp.v1.NerEntity
import org.tatrman.nlp.v1.Token
import org.tatrman.resolver.MvEstate.CA_STATE
import org.tatrman.resolver.MvEstate.CUSTOMER_ADDRESS
import org.tatrman.resolver.MvEstate.STORE
import org.tatrman.resolver.MvEstate.STORE_NAME
import org.tatrman.resolver.MvEstate.STORE_STATE
import org.tatrman.resolver.MvEstate.WAREHOUSE
import org.tatrman.resolver.MvEstate.WAREHOUSE_STATE
import org.tatrman.resolver.v1.ResolveResponse
import org.tatrman.resolver.v1.ValueKind

/**
 * MV-T6 (member-vocabulary contracts §9.5) — the MH tier-M drills re-run under MV, on the ARCHIVE
 * channel: a v5 archive compiled from a model, no registry override, the §8.5 parses with the
 * Czech ones as hartland's live Stanza emits them (`TN` NOUN).
 *
 * **How "bound through the governed lookup" is proven.** The lattice records no path — a member
 * found by the governed question and one found by its open sibling produce the same binding, the
 * same class, the same rung-log entry. So the drill takes the other path AWAY: the matcher answers
 * every OPEN question (one that asks a member vocabulary outside the governor's scope, or the
 * cross-category BROAD lookup) with nothing. A value that still binds was bound by the governed
 * question alone; one that does not, was not.
 */
class MvGovernedDrillTest :
    StringSpec({

        /** `customer_address.state` is in every OPEN scope and no governed one this fixture has. */
        val openBlind: (List<String>) -> Boolean = { it.isEmpty() || CA_STATE in it }

        fun ResolveResponse.attributionsOf(text: String): List<String> =
            resolutionState.valuesList
                .filter { it.span.text == text }
                .flatMap { v -> v.attributionsList.map { it.binding.ref } }

        data class Drill(
            val id: String,
            val text: String,
            val tokens: Array<Token>,
            val lang: String,
            val value: String,
            val binds: String,
        )

        val governed =
            listOf(
                Drill("E11-en", "Stores in TN", MhMembers.e11En(), "en", "TN", "$STORE_STATE#TN"),
                Drill("E11-cs", "Prodejny v TN", MhMembers.e11CsReal(), "cs", "TN", "$STORE_STATE#TN"),
                Drill("E11-count", "How many stores in TN", MhMembers.e11Count(), "en", "TN", "$STORE_STATE#TN"),
                Drill("E4-en", "Stores in Nashville", MhMembers.e4En(), "en", "Nashville", "$STORE_NAME#Nashville"),
            )

        for (d in governed) {
            "${d.id} `${d.text}` binds through the GOVERNED lookup — the open path blinded, the same answer" {
                val archive = MvEstate.writeArchive()
                val (blinded, index) = MvEstate.resolve(d.text, d.tokens, d.lang, archive, blind = openBlind)
                val (open, _) = MvEstate.resolve(d.text, d.tokens, d.lang, archive)

                blinded.attributionsOf(d.value) shouldContainExactly listOf(d.binds)
                // …and it is the answer the full pipeline gives: the governed reading wins its
                // span, so the open sibling is dropped and nothing downstream changes
                open.attributionsOf(d.value) shouldContainExactly listOf(d.binds)
                // the one question that found it was the first — the governed candidate's
                index.log
                    .first { it.query == d.value }
                    .members shouldContainExactly listOf(d.binds)
            }
        }

        "E11 — the bound member's owner is the STORE entity (§9.5 `owner er.…store`)" {
            val (response, _) = MvEstate.resolve("How many stores in TN", MhMembers.e11Count())

            response.hasAwaiting() shouldBe false
            val domain =
                response.resolution.bindingsList
                    .single { it.domain.rawText == "TN" }
                    .domain
            domain.entityTypeRef shouldBe STORE
            // review-104 F6 — and the ATTRIBUTE, which entity_type_ref stopped naming at MV-T3.
            domain.memberOf shouldBe STORE_STATE
            domain.resolvedId shouldBe "TN"
        }

        "review-104 F6 — a resumed member pin keeps its attribute: the SIGNED option rebuilds it" {
            val (asked, _) = MvEstate.resolve("TN", MhMembers.e12Bare())
            val pick = asked.awaiting.optionsList.single { it.memberOf == CA_STATE }

            val pinned =
                MvEstate
                    .resume(asked.awaiting.resumeToken, pick.id)
                    .resolution.bindingsList
                    .single()
                    .domain

            pinned.entityTypeRef shouldBe CUSTOMER_ADDRESS
            pinned.memberOf shouldBe CA_STATE
            pinned.resolvedId shouldBe "TN"
        }

        "§9.6 — the binding is addressed by the ATTRIBUTE and its value (A-MV-15); the attribution names the category" {
            val (response, _) = MvEstate.resolve("How many stores in TN", MhMembers.e11Count())

            val attribution =
                response.resolutionState.valuesList
                    .single { it.span.text == "TN" }
                    .attributionsList
                    .single()
            attribution.binding.ref shouldBe "$STORE_STATE#TN"
            attribution.attributeRef shouldBe STORE_STATE
        }

        "compat §9.8 — a v4 archive gets exactly the pre-MV answer (T1's before-table)" {
            // Measured at T1 on this very estate: neither the governed nor the open question can
            // name a member category (the archive registers none), so only the cross-category
            // BROAD round finds `TN` — and, with no governor to consult there, all three.
            val v4 = MvEstate.writeArchive(transform = MvEstate::asV4)
            val (response, index) = MvEstate.resolve("Stores in TN", MhMembers.e11En(), archive = v4)

            index.log.filter { it.query == "TN" && it.via == "batch" }.all { it.members.isEmpty() } shouldBe true
            response.attributionsOf("TN") shouldContainExactlyInAnyOrder
                listOf("$STORE_STATE#TN", "$CA_STATE#TN", "$WAREHOUSE_STATE#TN")
        }

        "compat §9.8 — a pre-MV override (member types, no flag): the M2 fallback still binds" {
            // The shape MH-P3 was built against: the member types are registered but nothing says
            // they are member vocabularies, so `membersOf` is empty and the governed lookup asks
            // what it asked before MV. The open sibling + M3 bind, as they did.
            val unflagged =
                MhMembers.REGISTRY
                    .toBuilder()
                    .clearEntityTypes()
                    .addAllEntityTypes(
                        MhMembers.REGISTRY.entityTypesList.map { it.toBuilder().clearMemberVocabulary().build() },
                    ).build()
            val tn =
                MhMembers
                    .resolve("Stores in TN", MhMembers.e11En(), registry = unflagged)
                    .resolutionState.valuesList
                    .single { it.span.text == "TN" }

            tn.attributionsList.map { it.binding.ref } shouldContainExactly listOf("$STORE_STATE#TN")
        }

        "compat §6 — on a v4 archive the governed question reaches no member, so blinded nothing binds" {
            val v4 = MvEstate.writeArchive(transform = MvEstate::asV4)
            val (blinded, index) = MvEstate.resolve("Stores in TN", MhMembers.e11En(), archive = v4, blind = openBlind)

            index.log
                .first { it.query == "TN" }
                .members
                .shouldBeEmpty()
            blinded.attributionsOf("TN").shouldBeEmpty()
        }

        // `customer` holds no member vocabulary — its REACH does. The governed question is asked
        // (scoped to `customer`) and finds nothing, by construction; the open lookup finds all three
        // and M3 keeps the one the governor reaches. MV does not change this path, and must not:
        // widening the governed scope along declared relations would be M3's job done twice.
        for ((id, tokens, lang) in listOf(
            Triple("E13-en", MhMembers.e13En(), "en"),
            Triple("E13-cs", MhMembers.e13CsReal(), "cs"),
        )) {
            "$id binds through the open lookup + M3 governance, NOT the governed lookup" {
                val text = if (lang == "cs") "Zákazníci v TN" else "Customers in TN"
                val archive = MvEstate.writeArchive()
                val (open, index) = MvEstate.resolve(text, tokens, lang, archive)
                val (blinded, _) = MvEstate.resolve(text, tokens, lang, archive, blind = openBlind)

                open.attributionsOf("TN") shouldContainExactly listOf("$CA_STATE#TN")
                index.log
                    .first { it.query == "TN" }
                    .members
                    .shouldBeEmpty()
                blinded.attributionsOf("TN").shouldBeEmpty()
            }
        }

        // ── UD (ttr-server#118) — the same drills NER=ON, with the entity the live service emits ──
        //
        // hartland's NLP types `TN`/`Nashville` a place (en `GPE`; cs NameTag `cnec:g…`) or, from a
        // coarse-label engine, a code-less `MISC`. Before UD every one of these left as a grounded
        // place with no attribution (tasks-ud-p1 T1). Now the governed argument is ALSO read as the
        // anchor's member (option 1), and a coarse MISC is not universal at all (option 3).

        data class NerDrill(
            val id: String,
            val text: String,
            val tokens: Array<Token>,
            val lang: String,
            val entity: NerEntity,
            val value: String,
            val binds: String,
        )

        val nerGoverned =
            listOf(
                NerDrill(
                    "E11-en GPE",
                    "Stores in TN",
                    MhMembers.e11En(),
                    "en",
                    MhMembers.ner("TN", 10, 12, "GPE"),
                    "TN",
                    "$STORE_STATE#TN",
                ),
                NerDrill(
                    "E11-cs cnec:gu",
                    "Prodejny v TN",
                    MhMembers.e11CsReal(),
                    "cs",
                    MhMembers.ner("TN", 11, 13, "LOCATION", "cnec:gu"),
                    "TN",
                    "$STORE_STATE#TN",
                ),
                // option 3: not a dual reading — simply not universal, so the plain governed path
                NerDrill(
                    "E11-cs MISC",
                    "Prodejny v TN",
                    MhMembers.e11CsReal(),
                    "cs",
                    MhMembers.ner("TN", 11, 13, "MISC"),
                    "TN",
                    "$STORE_STATE#TN",
                ),
                NerDrill(
                    "E11-count GPE",
                    "How many stores in TN",
                    MhMembers.e11Count(),
                    "en",
                    MhMembers.ner("TN", 19, 21, "GPE"),
                    "TN",
                    "$STORE_STATE#TN",
                ),
                NerDrill(
                    "E4-en GPE",
                    "Stores in Nashville",
                    MhMembers.e4En(),
                    "en",
                    MhMembers.ner("Nashville", 10, 19, "GPE"),
                    "Nashville",
                    "$STORE_NAME#Nashville",
                ),
            )

        for (d in nerGoverned) {
            "UD ${d.id} `${d.text}` NER=ON binds through the GOVERNED lookup and the place is gone" {
                val archive = MvEstate.writeArchive()
                val (blinded, _) =
                    MvEstate.resolve(d.text, d.tokens, d.lang, archive, blind = openBlind, entities = listOf(d.entity))
                val (open, _) = MvEstate.resolve(d.text, d.tokens, d.lang, archive, entities = listOf(d.entity))

                blinded.attributionsOf(d.value) shouldContainExactly listOf(d.binds)
                open.attributionsOf(d.value) shouldContainExactly listOf(d.binds)
                // one finding on the span, and it is the member — no grounded twin beside it
                open.resolutionState.valuesList
                    .filter { it.span.text == d.value }
                    .map { it.kind } shouldContainExactly listOf(ValueKind.VALUE_KIND_LITERAL)
            }
        }

        for ((id, tokens, lang) in listOf(
            Triple("E13-en GPE", MhMembers.e13En(), "en"),
            Triple("E13-cs cnec:gu", MhMembers.e13CsReal(), "cs"),
        )) {
            "UD $id NER=ON binds through the open lookup + M3; blinded, the place stands" {
                val text = if (lang == "cs") "Zákazníci v TN" else "Customers in TN"
                val start = text.indexOf("TN")
                val entity =
                    if (lang == "cs") {
                        MhMembers.ner("TN", start, start + 2, "LOCATION", "cnec:gu")
                    } else {
                        MhMembers.ner("TN", start, start + 2, "GPE")
                    }
                val archive = MvEstate.writeArchive()
                val (open, _) = MvEstate.resolve(text, tokens, lang, archive, entities = listOf(entity))
                val (blinded, _) =
                    MvEstate.resolve(text, tokens, lang, archive, blind = openBlind, entities = listOf(entity))

                open.attributionsOf("TN") shouldContainExactly listOf("$CA_STATE#TN")
                // With the open answer taken away the dual reading found nothing — withdrawn, so the
                // NER's reading stands exactly as it did before UD (A-UD-2).
                blinded.resolutionState.valuesList
                    .single { it.span.text == "TN" }
                    .kind shouldBe ValueKind.VALUE_KIND_GROUNDED
            }
        }

        "UD E12-en `Sales in TN` NER=ON — the dual reading answers only for the owner the fact reaches (✅UD-7)" {
            // This model relates `store_sales` to `store` alone (`rel_store_sales_store`), so of the
            // three `TN`s only the store's is a member the sentence scoped. NER=OFF still asks among
            // all three (the plain pair keeps MH M2); a dual reading is held to the scope, so a place
            // is never taken by a member of an entity the fact cannot filter by.
            val (response, _) =
                MvEstate.resolve("Sales in TN", MhMembers.e12En(), entities = listOf(MhMembers.ner("TN", 9, 11, "GPE")))

            response.hasAwaiting() shouldBe false
            response.resolutionState.valuesList
                .single { it.span.text == "TN" }
                .attributionsList
                .map { it.attributeRef } shouldContainExactly listOf(STORE_STATE)
        }

        "UD E12-bare `TN` NER=ON — nothing governs it: still a place (no dual reading, option 2 not built)" {
            val (response, _) =
                MvEstate.resolve(
                    "TN",
                    MhMembers.e12Bare(),
                    entities = listOf(MhMembers.ner("TN", 0, 2, "GPE")),
                )

            response.hasAwaiting() shouldBe false
            response.resolutionState.valuesList
                .single { it.span.text == "TN" }
                .kind shouldBe ValueKind.VALUE_KIND_GROUNDED
        }

        "UD `Stores in Paris` NER=ON — no member anywhere: the place stands" {
            val tokens =
                arrayOf(
                    MhMembers.tok("Stores", 0, 6, "store", "NOUN", 0, "root"),
                    MhMembers.tok("in", 7, 9, "in", "ADP", 3, "case"),
                    MhMembers.tok("Paris", 10, 15, "Paris", "PROPN", 1, "nmod"),
                )
            val (response, _) =
                MvEstate.resolve("Stores in Paris", tokens, entities = listOf(MhMembers.ner("Paris", 10, 15, "GPE")))

            val paris = response.resolutionState.valuesList.single { it.span.text == "Paris" }
            paris.kind shouldBe ValueKind.VALUE_KIND_GROUNDED
            paris.attributionsList.shouldBeEmpty()
        }

        "E12-bare — a lone `TN` still asks, three options labelled by OWNER" {
            val (response, _) = MvEstate.resolve("TN", MhMembers.e12Bare())

            response.hasAwaiting() shouldBe true
            val options = response.awaiting.optionsList
            options.map { it.label }.distinct() shouldContainExactly listOf("TN")
            options.map { it.entityTypeRef } shouldContainExactlyInAnyOrder listOf(STORE, CUSTOMER_ADDRESS, WAREHOUSE)
            options.map { it.memberOf } shouldContainExactlyInAnyOrder listOf(STORE_STATE, CA_STATE, WAREHOUSE_STATE)
        }
    })
