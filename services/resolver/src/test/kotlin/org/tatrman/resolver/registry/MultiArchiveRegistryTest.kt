// SPDX-License-Identifier: Apache-2.0
package org.tatrman.resolver.registry

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.typesafe.config.ConfigFactory
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain as shouldContainText
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.runBlocking
import org.tatrman.resolver.model.ResolverThresholds
import org.tatrman.ttr.lexicon.LexiconArea
import org.tatrman.ttr.lexicon.LexiconDataFile
import org.tatrman.ttr.lexicon.LexiconLoad
import org.tatrman.ttr.lexicon.LexiconValidator
import org.tatrman.ttr.lexicon.TargetClass
import org.tatrman.ttr.lexicon.compile.CompileResult
import org.tatrman.ttr.lexicon.compile.LexiconCompiler
import org.tatrman.ttr.lexicon.compile.LexiconPacker
import org.tatrman.ttr.lexicon.compile.LexiconSources
import org.tatrman.ttr.lexicon.compile.LexiconStdlib
import org.tatrman.ttr.lexicon.compile.ModelRefIndex
import org.tatrman.ttr.snapshot.SnapshotId
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readBytes
import kotlin.io.path.writeBytes

/**
 * ttr-server#209 — the registry fed from SEVERAL lexicon archives, one per source the estate's
 * packages come from, read as one declared layer.
 *
 * The same discipline as lex-matcher's `MultiArchiveLayerTest` (the twin reader, RS-24): every
 * fixture is compiled by the REAL producer three times — package A alone, package B alone, A + B in
 * one build — each with the stdlib embedded, and the two archives are held to the single build.
 * The packages are generic: a retail package and an investment package that both declare `client`.
 */
class MultiArchiveRegistryTest :
    StringSpec({

        val modelHash = "sha256:" + "cd".repeat(32)

        val retail =
            """
            schema: ttr-lexicon/v1
            defaults: { lang: en }
            entries:
              - terms: [ { text: "customer" }, { text: "client" } ]
                target: er.entity.customer
              - terms: [ { text: "order", method: TYPOS(1) }, { text: "orders" } ]
                target: er.entity.order
            """.trimIndent()

        // `customer` → er.entity.customer is declared by BOTH packages for the SAME object: an
        // identical row, which one build collapses and so must the layer.
        val investment =
            """
            schema: ttr-lexicon/v1
            defaults: { lang: en }
            entries:
              - terms: [ { text: "client" } ]
                target: er.entity.client
              - terms: [ { text: "portfolio", method: TYPOS(1) }, { text: "portfolios" } ]
                target: er.entity.portfolio
              - terms: [ { text: "customer" } ]
                target: er.entity.customer
            """.trimIndent()

        val refs = setOf("er.entity.customer", "er.entity.order", "er.entity.client", "er.entity.portfolio")

        fun dataFile(
            yaml: String,
            file: String,
        ): LexiconDataFile =
            LexiconValidator
                .loadDataFile(yaml, file)
                .shouldBeInstanceOf<LexiconLoad.Ok<LexiconDataFile>>()
                .value

        /** One build, as `LexiconBuild.run` composes it: the stdlib under the estate's own files. */
        fun compile(vararg files: LexiconDataFile): CompileResult =
            LexiconCompiler.compile(
                LexiconSources(
                    area =
                        LexiconArea(
                            LexiconStdlib.groundingSlices() + LexiconStdlib.predicateSlices() + files.toList(),
                            LexiconStdlib.skills(),
                        ),
                ),
                ModelRefIndex { ref -> if (ref in refs) TargetClass.MODEL_OBJECT else null },
                modelHash,
                "2026-10-15T00:00:00Z",
            )

        fun write(
            path: Path,
            result: CompileResult,
        ): Path = path.also { it.writeBytes(LexiconPacker.pack(result, modelHash, "test").bytes) }

        val retailFile = dataFile(retail, "aliases/retail.lex.yaml")
        val investmentFile = dataFile(investment, "aliases/investment.lex.yaml")

        fun estate(dir: Path = Files.createTempDirectory("resolver-multi")): Triple<Path, Path, Path> =
            Triple(
                write(dir.resolve("retail.tar.zst"), compile(retailFile)),
                write(dir.resolve("investment.tar.zst"), compile(investmentFile)),
                write(dir.resolve("both.tar.zst"), compile(retailFile, investmentFile)),
            )

        fun registry(vararg paths: Path) =
            runBlocking {
                SnapshotRegistry(LexiconArchiveRegistrySource(paths.toList()), ResolverThresholds.LIVE).current()
            }

        /** Every WARN and ERROR the reader logs while [body] runs, as `LEVEL message`. */
        fun logsFrom(body: () -> Unit): List<String> {
            val logger =
                (org.slf4j.LoggerFactory.getILoggerFactory() as LoggerContext)
                    .getLogger(LexiconArchiveRegistrySource::class.java.name)
            val appender = ListAppender<ILoggingEvent>().apply { start() }
            logger.addAppender(appender)
            return try {
                body()
                appender.list
                    .filter { it.level == Level.WARN || it.level == Level.ERROR }
                    .map { "${it.level} ${it.formattedMessage}" }
            } finally {
                logger.detachAppender(appender)
                appender.stop()
            }
        }

        /** Package B's archive, rebuilt on a schema no primary here declares. */
        fun foreignSchema(path: Path): Path =
            write(
                path,
                compile(investmentFile).let { r ->
                    r.copy(
                        lexicon =
                            r.lexicon.copy(
                                header = r.lexicon.header.copy(schemaVersion = "ttr-lexicon-compiled/v9"),
                            ),
                    )
                },
            )

        // ---- one layer, exactly as one build ------------------------------------------------------

        "two archives project the registry ONE build over both packages projects" {
            val (a, b, both) = estate()
            registry(a, b).entityTypes shouldBe registry(both).entityTypes
            runBlocking {
                LexiconArchiveRegistrySource(listOf(a, b)).fetch() shouldBe LexiconArchiveRegistrySource(both).fetch()
            }
        }

        "a word only one package declares anchors that package's ref; a form both declare anchors BOTH" {
            val (a, b, _) = estate()
            val types = registry(a, b).entityTypes.associateBy { it.ref }

            types.getValue("er.entity.order").anchors shouldContainExactlyInAnyOrder listOf("order", "orders")
            types.getValue("er.entity.portfolio").anchors shouldContainExactlyInAnyOrder
                listOf("portfolio", "portfolios")
            // `client`: two packages, two objects — both readings, which is #209's whole point…
            types.getValue("er.entity.customer").anchors shouldContain "client"
            types.getValue("er.entity.client").anchors shouldContain "client"
            // …where either archive alone gives one.
            registry(b).entityTypes.filter { "client" in it.anchors }.map { it.ref } shouldBe listOf("er.entity.client")
        }

        "an identical row in both archives is ONE row, and the stdlib every archive embeds is served once" {
            val (a, b, _) = estate()
            val vocabulary = runBlocking { LexiconArchiveRegistrySource(listOf(a, b)).fetch() }
            // `customer` → er.entity.customer is in both archives: one value, one id.
            vocabulary.entries
                .single { it.targetRef == "er.entity.customer" }
                .values
                .map { it.id } shouldBe listOf("lex:er.entity.customer:en:client", "lex:er.entity.customer:en:customer")
            // The registry takes MODEL_OBJECT rows only, so the stdlib (`op:`, `ground:`, `pred:`)
            // never reaches it — from one archive or from two.
            vocabulary.entries.none { it.targetRef.contains(':') } shouldBe true
        }

        // ---- the snapshot hash covers every archive ----------------------------------------------

        "one archive is its own snapshot; with two, a change to EITHER is a new snapshot" {
            val dir = Files.createTempDirectory("resolver-multi")
            val (a, b, _) = estate(dir)

            // One path = the pre-#209 reader: the archive's own id, which every binding carries.
            registry(a).snapshotHash shouldBe SnapshotId.of(a.readBytes())

            val source = LexiconArchiveRegistrySource(listOf(a, b))
            val first = source.hash()
            first shouldNotBe ""
            first shouldNotBe SnapshotId.of(a.readBytes())
            first shouldNotBe SnapshotId.of(b.readBytes())
            source.hash() shouldBe first

            write(
                b,
                compile(dataFile(investment.replace("\"portfolios\"", "\"holdings\""), "aliases/investment.lex.yaml")),
            )
            val second = source.hash()
            second shouldNotBe first
            write(a, compile(dataFile(retail.replace("\"orders\"", "\"purchases\""), "aliases/retail.lex.yaml")))
            source.hash() shouldNotBe second

            // …and the registry re-projects on it: the second archive's new word is an anchor now.
            val types = runBlocking { SnapshotRegistry(source, ResolverThresholds.LIVE).current() }.entityTypes
            types.single { it.ref == "er.entity.portfolio" }.anchors shouldContain "holdings"
            types.single { it.ref == "er.entity.order" }.anchors shouldContain "purchases"
        }

        // ---- the primary, and additive archives that degrade alone ----------------------------------

        "a bad ADDITIVE archive is skipped with an ERROR — the primary is served alone, under its own id" {
            val dir = Files.createTempDirectory("resolver-multi")
            val (a, _, _) = estate(dir)
            val bad =
                mapOf(
                    "absent" to dir.resolve("absent.tar.zst"),
                    "not a readable archive" to
                        dir.resolve("junk.tar.zst").also { it.writeBytes(byteArrayOf(1, 2, 3, 4)) },
                    "declares schema 'ttr-lexicon-compiled/v9'" to foreignSchema(dir.resolve("foreign.tar.zst")),
                )
            for ((reason, additive) in bad) {
                io.kotest.assertions.withClue(reason) {
                    val source = LexiconArchiveRegistrySource(listOf(a, additive))
                    val logs = logsFrom { source.hash() }

                    // The estate's anchors stay, exactly as with the primary alone — and the snapshot
                    // says so: one served archive is that archive's own id, the hash every binding carries.
                    source.hash() shouldBe SnapshotId.of(a.readBytes())
                    registry(a, additive).entityTypes shouldBe registry(a).entityTypes
                    registry(a, additive).snapshotHash shouldBe registry(a).snapshotHash

                    val error = logs.single { it.startsWith("ERROR") }
                    error shouldContainText additive.toString()
                    error shouldContainText reason
                    logs.single { it.contains("serving 1 of 2 lexicon archives") } shouldContainText "skipped"
                    // Said once, not on every resolve: hash() runs per request.
                    logsFrom { repeat(3) { source.hash() } }.shouldBeEmpty()
                }
            }
        }

        "a bad PRIMARY keeps the single-archive posture: empty at boot, the loaded layer kept after" {
            val dir = Files.createTempDirectory("resolver-multi")
            val (a, b, _) = estate(dir)

            // At boot there is nothing to keep — and an additive archive is never served on its own.
            val noPrimary = LexiconArchiveRegistrySource(listOf(dir.resolve("absent.tar.zst"), b))
            noPrimary.hash() shouldBe ""
            runBlocking { noPrimary.fetch().entries.shouldBeEmpty() }

            // Loaded, then the primary breaks: the whole loaded layer stays, both packages' anchors.
            val source = LexiconArchiveRegistrySource(listOf(a, b))
            val loaded = source.hash()
            Files.write(a, byteArrayOf(1, 2, 3, 4))
            source.hash() shouldBe loaded
            Files.delete(a)
            source.hash() shouldBe loaded
            runBlocking {
                source.fetch().entries.map { it.targetRef } shouldContain "er.entity.portfolio"
                source.fetch().entries.map { it.targetRef } shouldContain "er.entity.order"
            }
        }

        "an additive archive that turns bad keeps its LAST GOOD copy — no served anchor is dropped" {
            val dir = Files.createTempDirectory("resolver-multi")
            val (a, b, _) = estate(dir)
            val source = LexiconArchiveRegistrySource(listOf(a, b))
            val registry = SnapshotRegistry(source, ResolverThresholds.LIVE)
            val loaded = runBlocking { registry.current() }

            for (turn in listOf<(Path) -> Unit>(
                { Files.write(it, byteArrayOf(1, 2, 3, 4)) },
                { foreignSchema(it) },
                { Files.delete(it) },
            )) {
                turn(b)
                val logs = logsFrom { runBlocking { registry.current() } }
                runBlocking { registry.current() } shouldBe loaded
                logs.none { it.startsWith("ERROR") } shouldBe true
                logs.single { it.contains("last good copy") } shouldContainText b.toString()
            }
            loaded.entityTypes.single { it.ref == "er.entity.portfolio" }.anchors shouldContain "portfolios"
        }

        // ---- the keys -----------------------------------------------------------------------------

        "the singular key is a one-element list — every existing deployment is unchanged" {
            fun read(hocon: String) = LexiconArchivePaths.read(ConfigFactory.parseString(hocon))
            read("resolver.lexicon-archive-path = /etc/a/lexicon.tar.zst") shouldBe listOf("/etc/a/lexicon.tar.zst")
            read("resolver.lexicon-archive-path = \"\"").shouldBeEmpty()
            read("").shouldBeEmpty()
        }

        "the list key takes a comma-separated string or a HOCON list, and wins over the singular" {
            fun read(
                hocon: String,
                warnings: MutableList<String> = mutableListOf(),
            ) = LexiconArchivePaths.read(ConfigFactory.parseString(hocon)) { warnings += it }

            read("resolver.lexicon-archive-paths = \" /a/l.tar.zst, /b/l.tar.zst ,\"") shouldBe
                listOf("/a/l.tar.zst", "/b/l.tar.zst")
            read("resolver.lexicon-archive-paths = [\"/a/l.tar.zst\", \"/b/l.tar.zst\"]") shouldBe
                listOf("/a/l.tar.zst", "/b/l.tar.zst")

            // The chart's shape: the singular names the FIRST archive (for an older image), and a new
            // image must not read it twice — nor warn about it.
            val quiet = mutableListOf<String>()
            read(
                "resolver { lexicon-archive-path = /a/l.tar.zst, lexicon-archive-paths = \"/a/l.tar.zst,/b/l.tar.zst\" }",
                quiet,
            ) shouldBe listOf("/a/l.tar.zst", "/b/l.tar.zst")
            quiet.shouldBeEmpty()

            // A singular the list does not name: the list wins, and the disagreement is said.
            val loud = mutableListOf<String>()
            read(
                "resolver { lexicon-archive-path = /x/l.tar.zst, lexicon-archive-paths = /a/l.tar.zst }",
                loud,
            ) shouldBe
                listOf("/a/l.tar.zst")
            loud.single() shouldContainText "/x/l.tar.zst"
        }

        "the shipped application.conf reads RESOLVER_LEXICON_ARCHIVE_PATH(S)" {
            fun shipped(vararg env: Pair<String, String>) =
                LexiconArchivePaths.read(
                    // The env vars as ROOT keys: a `${?VAR}` substitution looks there before the environment.
                    ConfigFactory
                        .parseResources("application.conf")
                        .withFallback(ConfigFactory.parseMap(env.toMap()))
                        .resolve(),
                )
            shipped().shouldBeEmpty()
            shipped("RESOLVER_LEXICON_ARCHIVE_PATH" to "/a/l.tar.zst") shouldBe listOf("/a/l.tar.zst")
            shipped(
                "RESOLVER_LEXICON_ARCHIVE_PATH" to "/a/l.tar.zst",
                "RESOLVER_LEXICON_ARCHIVE_PATHS" to "/a/l.tar.zst,/b/l.tar.zst",
            ) shouldBe listOf("/a/l.tar.zst", "/b/l.tar.zst")
        }
    })
