// SPDX-License-Identifier: Apache-2.0
package org.tatrman.fuzzy.core

import io.grpc.inprocess.InProcessChannelBuilder
import io.grpc.inprocess.InProcessServerBuilder
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.maps.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.runBlocking
import org.tatrman.fuzzy.api.GrpcService
import org.tatrman.fuzzy.config.AppConfig
import org.tatrman.fuzzy.config.NlpConfig
import org.tatrman.fuzzy.config.TokenBasedConfig
import org.tatrman.fuzzy.loader.StaticLoaderSource
import org.tatrman.fuzzy.v1.FuzzyServiceGrpcKt
import org.tatrman.fuzzy.v1.FuzzyStatusRequest
import java.util.concurrent.atomic.AtomicInteger

/**
 * UD-P3 — the named-entity profile of the member vocabularies (`EntityProfiler`), its packing, its
 * cache, its degrade, and its trip through `GetStatus`.
 */
class EntityProfilerTest :
    StringSpec({

        /**
         * A NER that knows a few names and finds every occurrence of them, CNEC-coded as NameTag
         * reports them. It records what it was asked, so packing and caching can be observed.
         */
        class FakeNer(
            private val model: String = "nametag3:czech-cnec2.0-240830",
            private val identity: () -> String? = { "nametag3:240830" },
        ) : NlpBatchClient {
            val calls = AtomicInteger()
            val texts = mutableListOf<String>()
            var fail = false

            private val known =
                listOf(
                    Triple("Brno", "LOCATION", "cnec:gu"),
                    Triple("Praha", "LOCATION", "cnec:gu"),
                    Triple("Jan Novák", "PERSON", "cnec:pf"),
                    // a carrier word must never be counted for a value
                    Triple("v", "LOCATION", "cnec:gu"),
                )

            override suspend fun batchLemmatize(
                texts: List<String>,
                language: String,
            ): List<List<String>> = texts.map { listOf(it) }

            override suspend fun entities(
                text: String,
                language: String,
            ): NerResult {
                calls.incrementAndGet()
                texts += text
                if (fail) error("nlp down")
                val spans =
                    known.flatMap { (name, label, code) ->
                        // (?U): a Czech letter is a word character, so `v` is not found inside `včas`
                        Regex("(?U)\\b${Regex.escape(name)}\\b").findAll(text).map { m ->
                            NerSpan(m.range.first, m.range.last + 1, label, code)
                        }
                    }
                return NerResult(spans, model)
            }

            override suspend fun nerIdentity(language: String): String? = identity()
        }

        val warehouses = listOf("Brno DC", "Praha DC", "Ostrava DC")
        val reasons = listOf("Nedorazilo včas", "Chybí díly")

        "values are packed one per line in the data language's carrier, and mapped back by offset" {
            val profiler = EntityProfiler(FakeNer(), "cs")
            val chunk = profiler.pack(listOf("Brno DC", "Praha DC")).single()

            chunk.text shouldBe "v Brno DC.\nv Praha DC.\n"
            // `Brno DC` sits at [2, 9): an entity on `Brno` is the first value's, one on the carrier is no one's
            chunk.valuesOverlapping(2, 6) shouldContainExactly listOf(0)
            chunk.valuesOverlapping(0, 1).shouldBeEmpty()
            chunk.valuesOverlapping(13, 18) shouldContainExactly listOf(1)

            EntityProfiler(FakeNer(), "en-US").pack(listOf("Dallas DC")).single().text shouldBe "in Dallas DC.\n"
            EntityProfiler(FakeNer(), "de").pack(listOf("Berlin")).single().text shouldBe "Berlin.\n"
        }

        "a chunk holds at most chunk-chars, but never fewer than one value" {
            val profiler = EntityProfiler(FakeNer(), "cs", chunkChars = 24)
            val chunks = profiler.pack(listOf("Brno DC", "Praha DC", "Ostrava DC", "a name longer than any chunk"))

            chunks.map { it.text } shouldContainExactly
                listOf("v Brno DC.\nv Praha DC.\n", "v Ostrava DC.\n", "v a name longer than any chunk.\n")
            // offsets are per chunk, value indices are global
            chunks[1].valuesOverlapping(2, 9) shouldContainExactly listOf(2)
        }

        "the profile counts the values carrying each kind of name; a vocabulary with none is profiled empty" {
            val ner = FakeNer()
            val profiles =
                runBlocking {
                    EntityProfiler(ner, "cs").profile(
                        mapOf(
                            "er.entity.warehouse.warehouse_name" to ("v1" to warehouses),
                            "er.entity.reason.reason_desc" to ("v2" to reasons),
                            "er.entity.user.name" to ("v3" to listOf("Jan Novák", "Brno Jan Novák")),
                        ),
                    )
                }

            val dc = profiles.getValue("er.entity.warehouse.warehouse_name")
            dc.members shouldBe 3
            dc.entities shouldContainExactly listOf(EntityCount("LOCATION", "cnec:gu", 2))
            dc.nerModel shouldBe "nametag3:czech-cnec2.0-240830"

            val reason = profiles.getValue("er.entity.reason.reason_desc")
            reason.members shouldBe 2
            reason.entities.shouldBeEmpty()

            // two kinds in one value count once each; one kind twice in a value counts the value once
            profiles.getValue("er.entity.user.name").entities shouldContainExactly
                listOf(EntityCount("LOCATION", "cnec:gu", 1), EntityCount("PERSON", "cnec:pf", 2))
        }

        "values are trimmed, collapsed and de-duplicated before they are counted" {
            EntityProfiler.distinctValues(listOf("Brno DC   ", "Brno DC", " ", "Praha\nDC")) shouldContainExactly
                listOf("Brno DC", "Praha DC")
        }

        "an unchanged vocabulary under the same NER is not profiled again" {
            val ner = FakeNer()
            val profiler = EntityProfiler(ner, "cs")
            val input = mapOf("dc" to ("v1" to warehouses))
            val first = runBlocking { profiler.profile(input) }
            val second = runBlocking { profiler.profile(input) }

            ner.calls.get() shouldBe 1
            second shouldBe first
        }

        "new data or a new NER model is profiled again" {
            var identity = "nametag3:240830"
            val ner = FakeNer(identity = { identity })
            val profiler = EntityProfiler(ner, "cs")
            runBlocking { profiler.profile(mapOf("dc" to ("v1" to warehouses))) }

            runBlocking { profiler.profile(mapOf("dc" to ("v2" to warehouses + "Plzeň DC"))) }
            ner.calls.get() shouldBe 2

            identity = "nametag3:250101"
            runBlocking { profiler.profile(mapOf("dc" to ("v2" to warehouses + "Plzeň DC"))) }
            ner.calls.get() shouldBe 3
        }

        "an unreadable NER identity keeps a profile whose data is unchanged, and still profiles new data" {
            val ner = FakeNer(identity = { error("status down") })
            val profiler = EntityProfiler(ner, "cs")
            runBlocking { profiler.profile(mapOf("dc" to ("v1" to warehouses))) }
            runBlocking { profiler.profile(mapOf("dc" to ("v1" to warehouses))) }
            ner.calls.get() shouldBe 1

            runBlocking { profiler.profile(mapOf("dc" to ("v1" to warehouses), "r" to ("v9" to reasons))) }
                .keys shouldBe setOf("dc", "r")
        }

        "a failed NER leaves no profile — unless the same data was profiled before (degradable, I-12)" {
            var identity = "nametag3:240830"
            val ner = FakeNer(identity = { identity })
            val profiler = EntityProfiler(ner, "cs")
            ner.fail = true
            runBlocking { profiler.profile(mapOf("dc" to ("v1" to warehouses))) }.shouldBeEmpty()

            ner.fail = false
            runBlocking { profiler.profile(mapOf("dc" to ("v1" to warehouses))) }.keys shouldBe setOf("dc")

            // the model moved and the re-profile fails: the values are the same, so is what was measured
            identity = "nametag3:next"
            ner.fail = true
            runBlocking { profiler.profile(mapOf("dc" to ("v1" to warehouses))) }.keys shouldBe setOf("dc")
            // …but new values were never measured, and the old profile does not describe them
            runBlocking { profiler.profile(mapOf("dc" to ("v2" to warehouses + "Plzeň DC"))) }.shouldBeEmpty()
        }

        "a client that cannot ask for entities profiles nothing" {
            val lemmasOnly =
                object : NlpBatchClient {
                    override suspend fun batchLemmatize(
                        texts: List<String>,
                        language: String,
                    ): List<List<String>> = texts.map { listOf(it) }
                }
            runBlocking {
                EntityProfiler(
                    lemmasOnly,
                    "cs",
                ).profile(mapOf("dc" to ("v1" to warehouses)))
            }.shouldBeEmpty()
        }

        "⚑UD-13 (b) — with a member limit, a larger vocabulary is skipped and the rest profiled" {
            val ner = FakeNer()
            val profiles =
                runBlocking {
                    EntityProfiler(ner, "cs", maxMembers = 2).profile(
                        mapOf("dc" to ("v1" to warehouses), "r" to ("v2" to reasons)),
                    )
                }
            profiles.keys shouldBe setOf("r")
        }

        "a category that left the estate leaves the cache" {
            val ner = FakeNer()
            val profiler = EntityProfiler(ner, "cs")
            runBlocking { profiler.profile(mapOf("dc" to ("v1" to warehouses))) }
            runBlocking { profiler.profile(emptyMap()) }
            runBlocking { profiler.profile(mapOf("dc" to ("v1" to warehouses))) }

            ner.calls.get() shouldBe 2
        }

        // ── through the repository and GetStatus ──────────────────────────────────────────────

        fun cfg() =
            AppConfig(
                serverPort = 7132,
                grpcPort = 7232,
                grpcReflectionEnabled = false,
                refreshIntervalSeconds = 0,
                tokenBasedConfig = TokenBasedConfig(),
                nlp = NlpConfig(),
            )

        val dcRef = "er.entity.warehouse.warehouse_name"
        val reasonRef = "er.entity.reason.reason_desc"

        fun loader() =
            StaticLoaderSource(
                mapOf(
                    dcRef to warehouses.map { Candidate.fromValues(it, it, "TOKENS", dcRef) },
                    reasonRef to reasons.map { Candidate.fromValues(it, it, "TOKENS", reasonRef) },
                ),
            )

        "GetStatus reports each member vocabulary's profile once a pass has run" {
            val repo = StringRepository(cfg(), loader(), entityProfiler = EntityProfiler(FakeNer(), "cs"))
            val service = GrpcService(FuzzyMatcher(repo), repo)
            val name = "fuzzy-profile-${System.identityHashCode(service)}"
            val server =
                InProcessServerBuilder
                    .forName(name)
                    .directExecutor()
                    .addService(service)
                    .build()
                    .start()
            val channel = InProcessChannelBuilder.forName(name).directExecutor().build()
            try {
                runBlocking {
                    repo.forceRefresh()
                    repo.profileEntities()
                    val status =
                        FuzzyServiceGrpcKt
                            .FuzzyServiceCoroutineStub(channel)
                            .getStatus(FuzzyStatusRequest.getDefaultInstance())
                    val byCategory = status.categoriesList.associateBy { it.category }

                    val dc = byCategory.getValue(dcRef)
                    dc.hasEntityProfile() shouldBe true
                    dc.entityProfile.members shouldBe 3
                    dc.entityProfile.nerModel shouldContain "nametag3"
                    dc.entityProfile.entitiesList.map { Triple(it.label, it.code, it.members) } shouldContainExactly
                        listOf(Triple("LOCATION", "cnec:gu", 2))

                    val reason = byCategory.getValue(reasonRef)
                    reason.hasEntityProfile() shouldBe true
                    reason.entityProfile.entitiesList.shouldBeEmpty()
                }
            } finally {
                channel.shutdownNow()
                server.shutdownNow()
                repo.close()
            }
        }

        "without a profiler no category carries a profile (I-12)" {
            val repo = StringRepository(cfg(), loader())
            try {
                runBlocking {
                    repo.forceRefresh()
                    repo.profileEntities()
                }
                repo.categoryStatuses().map { it.entityProfile }.toSet() shouldBe setOf(null)
            } finally {
                repo.close()
            }
        }
    })
