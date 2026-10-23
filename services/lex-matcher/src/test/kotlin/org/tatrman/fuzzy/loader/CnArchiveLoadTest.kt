// SPDX-License-Identifier: Apache-2.0
package org.tatrman.fuzzy.loader

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.runBlocking
import org.tatrman.fuzzy.config.AppConfig
import org.tatrman.fuzzy.config.LoaderSourceConfig
import org.tatrman.fuzzy.config.MetadataConfig
import org.tatrman.fuzzy.config.NlpConfig
import org.tatrman.fuzzy.config.TokenBasedConfig
import org.tatrman.fuzzy.core.Candidate
import org.tatrman.fuzzy.core.SourceTag
import org.tatrman.fuzzy.core.StringRepository
import org.tatrman.fuzzy.core.TargetClass
import org.tatrman.fuzzy.loader.CnEstate.replace
import org.tatrman.ttr.lexicon.CompiledLexiconHeader
import org.tatrman.ttr.lexicon.TargetFacts
import org.tatrman.ttr.lexicon.compile.LexiconBuildOutcome
import org.tatrman.ttr.lexicon.compile.LexiconPacker
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeBytes

/**
 * CN-P4 S4.3 (cn contracts §5, A-CN-2 / 10 / 69) — lex-matcher reads archive v6.
 *
 * The concept rows load as declared vocabulary, each under its own `cn.*` ref, exactly as an er object's rows load under
 * theirs: no loader or matcher change, and none was needed. What the tier does need of this reader is pinned here: the
 * archive is the one the producer compiles from the shared estate; a change to concept facts alone, which the lexicon's
 * `contentHash` does not cover, still reloads the layer; a v5 archive is read under the WARN; and two archives that share
 * the estate's er model merge to what one build over both packs — the concept facts and the reverse index included
 * (the [DeclaredLayer] twin; the resolver reads those facts).
 */
class CnArchiveLoadTest :
    StringSpec({
        val estate = CnEstate.build()
        val plain = CnEstate.build(CnEstate.withoutCn())

        fun archive(
            outcome: LexiconBuildOutcome,
            dir: Path = Files.createTempDirectory("cn-lex"),
        ): Path = CnEstate.pack(outcome, dir.resolve("lexicon.tar.zst"))

        fun cfg() =
            AppConfig(
                serverPort = 0,
                grpcPort = 0,
                grpcReflectionEnabled = false,
                refreshIntervalSeconds = 0,
                tokenBasedConfig = TokenBasedConfig(),
                nlp = NlpConfig(),
                loaderSource = LoaderSourceConfig(source = "static"),
                metadata = MetadataConfig(),
            )

        val noMembers =
            object : LoaderSource {
                override suspend fun loadNextCache(): Map<String, List<Candidate>> = emptyMap()
            }

        fun repository(source: LexiconArchiveSource): StringRepository =
            StringRepository(cfg(), noMembers, snapshotSource = source).also { runBlocking { it.forceRefresh() } }

        fun warnsFrom(body: () -> Unit): List<String> {
            val logger =
                (org.slf4j.LoggerFactory.getILoggerFactory() as LoggerContext)
                    .getLogger(LexiconArchiveSource::class.java.name)
            val appender = ListAppender<ILoggingEvent>().apply { start() }
            logger.addAppender(appender)
            return try {
                body()
                appender.list.filter { it.level == Level.WARN }.map { it.formattedMessage }
            } finally {
                logger.detachAppender(appender)
                appender.stop()
            }
        }

        fun String.isConcept() = startsWith("cn.")

        "the shared estate compiles, on the pinned producer, to the golden v6 lexicon it carries" {
            CompiledLexiconHeader.SCHEMA_VERSION shouldBe "ttr-lexicon-compiled/v6"
            estate.result.lexicon shouldBe CnEstate.golden()
            // A drifted copy of ttr-core's fixture fails here, not in a matching test three steps later.
        }

        "each concept row is declared vocabulary under its own ref; the other rows are the plain build's" {
            val served = runBlocking { LexiconArchiveSource(archive(estate)).fetch() }
            val concepts = served.entries.filter { it.targetRef.isConcept() }

            concepts.map { it.category } shouldContainExactlyInAnyOrder
                estate.result.lexicon.entries
                    .map { it.targetRef }
                    .filter { it.isConcept() }
                    .distinct()
            concepts.forEach { it.category shouldBe it.targetRef }
            concepts.flatMap { it.values }.forEach {
                it.source shouldBe SourceTag.DECLARED
                it.targetClass shouldBe TargetClass.MODEL_OBJECT
            }
            concepts
                .single {
                    it.targetRef == "cn.DebtorCustomer"
                }.values
                .map { it.value } shouldContainExactlyInAnyOrder
                listOf("debtor", "dlužník", "overdue customer", "zákazník po splatnosti")

            // Purely additive: without the concept rows, the layer is the one a build with no cn content serves.
            val withoutCn = runBlocking { LexiconArchiveSource(archive(plain)).fetch() }
            withoutCn.entries.shouldNotBeEmpty()
            served.entries.filterNot { it.targetRef.isConcept() } shouldBe withoutCn.entries
        }

        "a change to concept facts alone is a new snapshot: the matcher reloads, its artifact hash stays (A-CN-69)" {
            val dir = Files.createTempDirectory("cn-lex")
            val path = archive(estate, dir)
            val source = LexiconArchiveSource(path)
            val repo = repository(source)
            val hash = source.hash()
            val version = repo.vocabularyVersion()
            val artifact = repo.layerVersions().lexiconArtifactHash

            // `TopCustomer` is a phase with no terms. Moving it from Customer to Party moves its facts and no row.
            val moved =
                CnEstate.build(
                    CnEstate.variant {
                        it.resolve("model/cn/shop.ttrm").replace(
                            "def phase TopCustomer { of: Customer",
                            "def phase TopCustomer { of: Party",
                        )
                    },
                )
            moved.result.lexicon.entries shouldBe estate.result.lexicon.entries
            moved.result.lexicon.contentHash shouldBe estate.result.lexicon.contentHash
            moved.result.lexicon.targets
                .getValue("cn.TopCustomer")
                .ofRef shouldBe "cn.Party"

            CnEstate.pack(moved, path)
            runBlocking { repo.forceRefresh() }

            source.hash() shouldNotBe hash
            repo.vocabularyVersion() shouldNotBe version
            repo.layerVersions().lexiconArtifactHash shouldBe artifact
        }

        "a v5 archive under this v6 reader is read, with the WARN naming both schemas (readers first, A-CN-2)" {
            val v5 =
                plain.result.let { r ->
                    r.copy(
                        lexicon =
                            r.lexicon.copy(
                                header = r.lexicon.header.copy(schemaVersion = "ttr-lexicon-compiled/v5"),
                            ),
                    )
                }
            val path =
                Files
                    .createTempDirectory("cn-lex")
                    .resolve("v5.tar.zst")
                    .also { it.writeBytes(LexiconPacker.pack(v5, CnEstate.SNAPSHOT_ID, "v5-producer").bytes) }
            val source = LexiconArchiveSource(path)

            val warning = warnsFrom { source.hash() }.single { "declares schema" in it }
            warning shouldContain "'ttr-lexicon-compiled/v5'"
            warning shouldContain "'ttr-lexicon-compiled/v6'"
            runBlocking { source.fetch() } shouldBe runBlocking { LexiconArchiveSource(archive(plain)).fetch() }
        }

        "two archives sharing the er model merge as one build: concept facts and liftsTo survive, either order" {
            val full = estate.result.lexicon
            val part = plain.result.lexicon
            // The plain build states the same elements without their lifts: the case the merge has to union.
            part.targets
                .getValue("er.entity.customer")
                .liftsTo
                .shouldBeEmpty()
            full.targets
                .getValue("er.entity.customer")
                .liftsTo
                .shouldNotBeEmpty()

            for (order in listOf(listOf(full, part), listOf(part, full))) {
                val merged = DeclaredLayer.merge(order)
                merged.lexicon.entries shouldBe full.entries
                merged.lexicon.targets shouldBe full.targets
                merged.factConflicts.shouldBeEmpty()
                merged.methodConflicts.shouldBeEmpty()
            }
        }

        "two archives that disagree on a concept fact say so, and keep the first archive's" {
            val full = estate.result.lexicon
            val customer = full.targets.getValue("er.entity.customer")
            val role = full.targets.getValue("cn.Customer")
            val other =
                full.copy(
                    targets =
                        full.targets +
                            mapOf(
                                "er.entity.customer" to
                                    customer.copy(
                                        liftsTo = customer.liftsTo.map { it.copy(via = TargetFacts.VIA_MD_HOP) },
                                    ),
                                "cn.Customer" to role.copy(stereotype = "kind"),
                            ),
                )

            val merged = DeclaredLayer.merge(listOf(full, other))
            merged.lexicon.targets.getValue("er.entity.customer") shouldBe customer
            merged.lexicon.targets.getValue("cn.Customer") shouldBe role
            merged.factConflicts shouldContainExactlyInAnyOrder
                listOf(
                    "er.entity.customer.liftsTo[cn.Customer].via: 'direct' vs 'md-hop'; kept 'direct'",
                    "cn.Customer.stereotype: 'role' vs 'kind'; kept 'role'",
                )
        }
    })
