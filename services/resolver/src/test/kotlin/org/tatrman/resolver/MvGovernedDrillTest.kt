// SPDX-License-Identifier: Apache-2.0
package org.tatrman.resolver

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import org.tatrman.nlp.v1.Token
import org.tatrman.resolver.MvEstate.CA_STATE
import org.tatrman.resolver.MvEstate.CUSTOMER_ADDRESS
import org.tatrman.resolver.MvEstate.STORE
import org.tatrman.resolver.MvEstate.STORE_NAME
import org.tatrman.resolver.MvEstate.STORE_STATE
import org.tatrman.resolver.MvEstate.WAREHOUSE
import org.tatrman.resolver.MvEstate.WAREHOUSE_STATE
import org.tatrman.resolver.v1.ResolveResponse

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
                Drill("E11-en", "Stores in TN", MhMembers.e11En(), "en", "TN", "$STORE_STATE#store#7"),
                Drill("E11-cs", "Prodejny v TN", MhMembers.e11CsReal(), "cs", "TN", "$STORE_STATE#store#7"),
                Drill("E11-count", "How many stores in TN", MhMembers.e11Count(), "en", "TN", "$STORE_STATE#store#7"),
                Drill("E4-en", "Stores in Nashville", MhMembers.e4En(), "en", "Nashville", "$STORE_NAME#store#7"),
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
            response.resolution.bindingsList
                .single { it.domain.rawText == "TN" }
                .domain.entityTypeRef shouldBe STORE
        }

        "§9.6 — the binding is addressed by the ATTRIBUTE and its key; the attribution names the category" {
            val (response, _) = MvEstate.resolve("How many stores in TN", MhMembers.e11Count())

            val attribution =
                response.resolutionState.valuesList
                    .single { it.span.text == "TN" }
                    .attributionsList
                    .single()
            attribution.binding.ref shouldBe "$STORE_STATE#store#7"
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
                listOf("$STORE_STATE#store#7", "$CA_STATE#ca#3", "$WAREHOUSE_STATE#wh#1")
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

            tn.attributionsList.map { it.binding.ref } shouldContainExactly listOf("$STORE_STATE#store#7")
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

                open.attributionsOf("TN") shouldContainExactly listOf("$CA_STATE#ca#3")
                index.log
                    .first { it.query == "TN" }
                    .members
                    .shouldBeEmpty()
                blinded.attributionsOf("TN").shouldBeEmpty()
            }
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
