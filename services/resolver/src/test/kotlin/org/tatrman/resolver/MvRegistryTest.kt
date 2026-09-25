// SPDX-License-Identifier: Apache-2.0
package org.tatrman.resolver

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.maps.shouldNotContainKey
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import org.tatrman.resolver.MvEstate.CA_STATE
import org.tatrman.resolver.MvEstate.CUSTOMER
import org.tatrman.resolver.MvEstate.CUSTOMER_ADDRESS
import org.tatrman.resolver.MvEstate.STORE
import org.tatrman.resolver.MvEstate.STORE_NAME
import org.tatrman.resolver.MvEstate.STORE_SALES
import org.tatrman.resolver.MvEstate.STORE_STATE
import org.tatrman.resolver.MvEstate.WAREHOUSE
import org.tatrman.resolver.MvEstate.WAREHOUSE_STATE
import org.tatrman.resolver.model.membersOf
import org.tatrman.resolver.model.refByCategory
import org.tatrman.resolver.pipeline.ResolverPipeline
import org.tatrman.resolver.model.ResolverThresholds
import org.tatrman.resolver.registry.LexiconArchiveRegistrySource

/**
 * MV-T3 T2/T3 (member-vocabulary contracts §5.2, §6) — the registry learns every member vocabulary
 * from the v5 archive, and `membersOf` is how an anchor reaches its entity's vocabularies.
 *
 * The archive is compiled from a model by the real compiler ([MvEstate]), never hand-built: the
 * facet this projects is one `LexiconCompiler.targetFacts` fills, and a fixture that injected it
 * would pass even if the compiler stopped (the rule `LexiconArchiveRegistrySourceTest` lives by).
 */
class MvRegistryTest :
    StringSpec({

        val members = listOf(STORE_NAME, STORE_STATE, CA_STATE, WAREHOUSE_STATE)

        // ── T2 — archive projection ──────────────────────────────────────────────────────

        "T2 — an indexed attribute with NO terms projects as an entry with no values" {
            val vocab = runBlocking { LexiconArchiveRegistrySource(MvEstate.writeArchive()).fetch() }

            val state = vocab.entries.single { it.targetRef == STORE_STATE }
            state.category shouldBe STORE_STATE
            state.values.shouldBeEmpty()
            state.memberVocabulary shouldBe true
            state.ownerRef shouldBe STORE
            state.objectKind shouldBe "attribute"
            vocab.entries.filter { it.memberVocabulary }.map { it.targetRef } shouldContainExactlyInAnyOrder members
            // an object is not a member vocabulary, whatever its anchors
            vocab.entries.single { it.targetRef == STORE }.memberVocabulary shouldBe false
        }

        "T2 — it reaches the registry as a type gated by its own ref, owned by its entity" {
            val types = MvEstate.entityTypes()

            val state = types.single { it.ref == STORE_STATE }
            state.categories shouldContainExactly listOf(STORE_STATE)
            state.ownerRef shouldBe STORE
            state.memberVocabulary shouldBe true
            // no anchors — a member vocabulary is the value layer, never a word naming an object
            state.anchors.shouldBeEmpty()
            // ✅MV-3 / A-MV-3 reading: a member carries no reach of its own
            state.reachedFrom.shouldBeEmpty()
            types.filter { it.memberVocabulary }.map { it.ref } shouldContainExactlyInAnyOrder members
        }

        "T2 — an indexed attribute WITH a term keeps its anchors and gains the flag" {
            val extra =
                """
                schema: ttr-lexicon/v1
                defaults: { lang: en }
                entries:
                  - terms: [ { text: "branch name" } ]
                    target: er.entity.store.store_name
                """.trimIndent()
            val types = MvEstate.entityTypes(MvEstate.writeArchive(extra = extra))

            val name = types.single { it.ref == STORE_NAME }
            name.anchors shouldContainExactly listOf("branch name")
            name.memberVocabulary shouldBe true
            // one type per ref still — the terms and the facet land on the same entry
            types.count { it.ref == STORE_NAME } shouldBe 1
        }

        "T2 — compat §6: a v4 archive projects no member vocabulary, and nothing else moves" {
            val v4 = MvEstate.entityTypes(MvEstate.writeArchive(transform = MvEstate::asV4))
            val v5 = MvEstate.entityTypes()

            v4.none { it.memberVocabulary } shouldBe true
            v4.map { it.ref } shouldContainExactlyInAnyOrder
                listOf(STORE, STORE_SALES, CUSTOMER, CUSTOMER_ADDRESS, WAREHOUSE, MvEstate.WEB_SALES)
            // the objects themselves project identically from either archive
            v5.filterNot { it.memberVocabulary } shouldContainExactlyInAnyOrder v4
        }

        "T2 — the override channel carries the flag too (it never lags the archive)" {
            val types = ResolverPipeline.fromProto(MhMembers.REGISTRY, ResolverThresholds.LIVE).entityTypes

            types.filter { it.memberVocabulary }.map { it.ref } shouldContainExactlyInAnyOrder members
        }

        // ── T3 — membersOf ───────────────────────────────────────────────────────────────

        "T3 — membersOf(store) = the store's two indexed attributes" {
            val byOwner = MvEstate.entityTypes().membersOf()

            byOwner[STORE].orEmpty() shouldContainExactlyInAnyOrder listOf(STORE_NAME, STORE_STATE)
            byOwner[CUSTOMER_ADDRESS] shouldBe listOf(CA_STATE)
            byOwner[WAREHOUSE] shouldBe listOf(WAREHOUSE_STATE)
            // absent, not empty: `customer` holds no member vocabulary — its reach does
            byOwner shouldNotContainKey CUSTOMER
            byOwner shouldNotContainKey STORE_SALES
        }

        "T3 — membersOf is empty on a v4 archive (the governed path is then today's)" {
            MvEstate.entityTypes(MvEstate.writeArchive(transform = MvEstate::asV4)).membersOf() shouldBe emptyMap()
        }

        "T3 — refByCategory is injective on the projected registry" {
            // §5.2: one category, one ref, and no two categories for one ref. `refByCategory` is a
            // `toMap`, so a category declared by two types would be silently overwritten — the
            // first half of the assertion is what makes the map's size mean anything.
            val types = MvEstate.entityTypes()
            val categories = types.flatMap { it.categories }
            categories.distinct().size shouldBe categories.size

            val byCategory = types.refByCategory()
            byCategory.size shouldBe categories.size
            byCategory.values.distinct().size shouldBe byCategory.size
            members.forEach { byCategory[it] shouldBe it }
        }
    })
