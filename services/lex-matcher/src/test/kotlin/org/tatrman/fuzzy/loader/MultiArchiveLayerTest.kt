// SPDX-License-Identifier: Apache-2.0
package org.tatrman.fuzzy.loader

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain as shouldContainText
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.runBlocking
import org.tatrman.fuzzy.config.AppConfig
import org.tatrman.fuzzy.config.LoaderSourceConfig
import org.tatrman.fuzzy.config.MetadataConfig
import org.tatrman.fuzzy.config.NlpConfig
import org.tatrman.fuzzy.config.TokenBasedConfig
import org.tatrman.fuzzy.core.Candidate
import org.tatrman.fuzzy.core.FuzzyMatcher
import org.tatrman.fuzzy.core.LookupQuery
import org.tatrman.fuzzy.core.StringRepository
import org.tatrman.ttr.lexicon.CompiledLexicon
import org.tatrman.ttr.lexicon.CompiledLexiconHeader
import org.tatrman.ttr.lexicon.LexiconArea
import org.tatrman.ttr.lexicon.LexiconDataFile
import org.tatrman.ttr.lexicon.LexiconLoad
import org.tatrman.ttr.lexicon.LexiconValidator
import org.tatrman.ttr.lexicon.Reach
import org.tatrman.ttr.lexicon.SourceHashes
import org.tatrman.ttr.lexicon.TargetClass
import org.tatrman.ttr.lexicon.TargetFacts
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
import org.tatrman.fuzzy.core.TargetClass as CoreTargetClass

/**
 * ttr-server#209 — several declared lexicon archives, ONE declared layer.
 *
 * An estate whose packages come from two sources builds one archive per source and mounts both. The
 * bar is the one the issue sets: the two archives must behave **exactly as if both packages were
 * built into one archive**. So every fixture here is compiled THREE times by the real producer —
 * package A alone, package B alone, and A + B in one build — and the merged pair is held to the
 * single build, not to a hand-written expectation. Each archive embeds the stdlib, as every real
 * build does (`LexiconBuild.run`), which is what makes "stdlib rows once" a real test.
 *
 * The two packages are generic: a retail package and an investment package that both declare the
 * word `client`, for two different objects.
 */
class MultiArchiveLayerTest :
    StringSpec({

        val modelHash = "sha256:" + "ab".repeat(32)

        val retail =
            """
            schema: ttr-lexicon/v1
            defaults: { lang: en }
            entries:
              - terms: [ { text: "customer" }, { text: "client" } ]
                target: er.entity.customer
              - terms: [ { text: "order", method: TYPOS(1) }, { text: "orders" } ]
                target: er.entity.order
              - terms: [ { text: "objednávka", lang: cs } ]
                target: er.entity.order
            """.trimIndent()

        val investment =
            """
            schema: ttr-lexicon/v1
            defaults: { lang: en }
            entries:
              - terms: [ { text: "client" } ]
                target: er.entity.client
              - terms: [ { text: "portfolio", method: TYPOS(1) }, { text: "portfolios" } ]
                target: er.entity.portfolio
              - terms: [ { text: "portfolio", lang: cs } ]
                target: er.entity.portfolio
            """.trimIndent()

        val refs =
            setOf("er.entity.customer", "er.entity.order", "er.entity.client", "er.entity.portfolio")

        fun dataFile(
            yaml: String,
            file: String,
        ): LexiconDataFile =
            LexiconValidator
                .loadDataFile(yaml, file)
                .shouldBeInstanceOf<LexiconLoad.Ok<LexiconDataFile>>()
                .value

        /** One build, exactly as `LexiconBuild.run` composes it: the stdlib under the estate's own files. */
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
            producer: String = "test",
        ): Path = path.also { it.writeBytes(LexiconPacker.pack(result, modelHash, producer).bytes) }

        val retailFile = dataFile(retail, "aliases/retail.lex.yaml")
        val investmentFile = dataFile(investment, "aliases/investment.lex.yaml")

        /** Package A's archive, package B's archive, and the ONE archive a build over both packs. */
        fun estate(dir: Path = Files.createTempDirectory("lex-multi")): Triple<Path, Path, Path> =
            Triple(
                write(dir.resolve("retail.tar.zst"), compile(retailFile)),
                write(dir.resolve("investment.tar.zst"), compile(investmentFile)),
                write(dir.resolve("both.tar.zst"), compile(retailFile, investmentFile)),
            )

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

        /** A Lookup row reduced to what a caller reads off it. */
        fun rows(
            repo: StringRepository,
            term: String,
        ) = runBlocking {
            FuzzyMatcher(repo)
                .lookup(LookupQuery(term = term, maxCandidates = 50))
                .candidates
                .map { listOf(it.candidate, it.targetRef, it.score, it.autoBindable, it.source, it.matchMethod) }
        }

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

        // ---- one layer, exactly as one build ------------------------------------------------------

        "two archives load as the ONE archive a single build over both packages packs" {
            val (a, b, both) = estate()
            val merged = LexiconArchiveSource(listOf(a, b))
            val single = LexiconArchiveSource(both)

            runBlocking {
                merged.fetch() shouldBe single.fetch()
            }
            // RV-39's `lexicon_artifact_hash` is the entry table's hash, so equal tables must report
            // the same artifact: the resolver cannot tell the two-source estate from the one-source one.
            merged.hash()
            single.hash()
            merged.artifactHash() shouldNotBe ""
            merged.artifactHash() shouldBe single.artifactHash()
        }

        "Lookup — a word declared only in one package finds that package's ref, as in one build" {
            val (a, b, both) = estate()
            val merged = repository(LexiconArchiveSource(listOf(a, b)))
            val single = repository(LexiconArchiveSource(both))

            for (word in listOf("order", "ordr", "objednávka", "portfolio", "portfolos", "customer")) {
                withClue(word) { rows(merged, word) shouldBe rows(single, word) }
            }
            rows(merged, "portfolio").map { it[1] } shouldContain "er.entity.portfolio"
            rows(merged, "order").map { it[1] } shouldContain "er.entity.order"
        }

        "Lookup — a form two packages declare gets BOTH readings, as in one build" {
            val (a, b, both) = estate()
            val merged = repository(LexiconArchiveSource(listOf(a, b)))
            val single = repository(LexiconArchiveSource(both))

            val client = rows(merged, "client").filter { it[0] == "client" }
            client.map { it[1] } shouldContainExactlyInAnyOrder listOf("er.entity.customer", "er.entity.client")
            rows(merged, "client") shouldBe rows(single, "client")
            // …while either archive ALONE gives one reading — the bug #209 is about.
            rows(repository(LexiconArchiveSource(b)), "client").filter { it[0] == "client" }.map { it[1] } shouldBe
                listOf("er.entity.client")
        }

        "the stdlib rows every archive embeds are served ONCE — no doubled candidate, no moved score" {
            val (a, b, both) = estate()
            val vocabulary = runBlocking { LexiconArchiveSource(listOf(a, b)).fetch() }

            val stdlib =
                vocabulary.entries.filter { e ->
                    listOf("op:", "ground:", "pred:").any { e.targetRef.startsWith(it) }
                }
            stdlib.shouldNotBeEmpty()
            // Each archive carries the full stdlib; the layer carries it once — exactly the count of
            // ONE archive, and exactly the rows of one build.
            val onlyA =
                runBlocking { LexiconArchiveSource(a).fetch() }.entries.filter {
                    it.targetRef in
                        stdlib.map { s -> s.targetRef }
                }
            stdlib.sumOf { it.values.size } shouldBe onlyA.sumOf { it.values.size }
            vocabulary.entries.flatMap { e -> e.values.map { e.category to it.id } }.let { ids ->
                ids.size shouldBe ids.toSet().size
            }

            // …and a stdlib word scores exactly as it does with one archive mounted: a duplicate row
            // would have changed the token statistics the score is computed from.
            val word =
                stdlib
                    .first { it.targetRef.startsWith("op:") }
                    .values
                    .first()
                    .value
            val merged = repository(LexiconArchiveSource(listOf(a, b)))
            rows(merged, word) shouldBe rows(repository(LexiconArchiveSource(both)), word)
            rows(merged, word).filter { (it[1] as String).startsWith("op:") }.shouldNotBeEmpty()
        }

        // ---- the snapshot hash covers every archive ----------------------------------------------

        "one archive keeps its own id; the layer id moves when EITHER archive changes" {
            val dir = Files.createTempDirectory("lex-multi")
            val (a, b, _) = estate(dir)

            // One path is the pre-#209 reader: the archive's own content id, unchanged.
            LexiconArchiveSource(listOf(a)).hash() shouldBe SnapshotId.of(a.readBytes())

            val source = LexiconArchiveSource(listOf(a, b))
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
            withClue("a change to the SECOND archive must be a new snapshot") { second shouldNotBe first }

            write(a, compile(dataFile(retail.replace("\"orders\"", "\"purchases\""), "aliases/retail.lex.yaml")))
            withClue("a change to the FIRST archive must be a new snapshot") { source.hash() shouldNotBe second }

            // A repack with identical content is still a different FILE (producer stamp), and the id
            // is the file's, as it always was.
            val before = source.hash()
            write(
                b,
                compile(dataFile(investment.replace("\"portfolios\"", "\"holdings\""), "aliases/investment.lex.yaml")),
                producer = "rebuild",
            )
            source.hash() shouldNotBe before
        }

        "refresh picks up a change to EITHER archive — the two-clock reload covers the whole layer" {
            val (a, b, _) = estate()
            val repo = repository(LexiconArchiveSource(listOf(a, b)))
            rows(repo, "holdings").none { it[0] == "holdings" } shouldBe true

            write(
                b,
                compile(dataFile(investment.replace("\"portfolios\"", "\"holdings\""), "aliases/investment.lex.yaml")),
            )
            runBlocking { repo.forceRefresh() }
            rows(repo, "holdings").first { it[0] == "holdings" }[1] shouldBe "er.entity.portfolio"
            // A reload replaces the layer: the second archive's old word is gone, the first's stays.
            rows(repo, "portfolios").none { it[0] == "portfolios" } shouldBe true
            rows(repo, "orders").first { it[0] == "orders" }[1] shouldBe "er.entity.order"

            write(a, compile(dataFile(retail.replace("\"orders\"", "\"purchases\""), "aliases/retail.lex.yaml")))
            runBlocking { repo.forceRefresh() }
            rows(repo, "purchases").first { it[0] == "purchases" }[1] shouldBe "er.entity.order"
            rows(repo, "holdings").first { it[0] == "holdings" }[1] shouldBe "er.entity.portfolio"
        }

        // ---- the primary, and additive archives that degrade alone ----------------------------------

        /** Every WARN and ERROR the reader logs while [body] runs, as `LEVEL message`. */
        fun logsFrom(body: () -> Unit): List<String> {
            val logger =
                (org.slf4j.LoggerFactory.getILoggerFactory() as LoggerContext)
                    .getLogger(LexiconArchiveSource::class.java.name)
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

        "a bad ADDITIVE archive is skipped with an ERROR — the primary is served alone, under its own id" {
            val dir = Files.createTempDirectory("lex-multi")
            val (a, _, _) = estate(dir)
            val primaryAlone = LexiconArchiveSource(a)
            val bad =
                mapOf(
                    "absent" to dir.resolve("absent.tar.zst"),
                    "not a readable archive" to
                        dir.resolve("junk.tar.zst").also { it.writeBytes("not an archive".toByteArray()) },
                    "declares schema 'ttr-lexicon-compiled/v9'" to foreignSchema(dir.resolve("foreign.tar.zst")),
                )
            for ((reason, additive) in bad) {
                withClue(reason) {
                    val source = LexiconArchiveSource(listOf(a, additive))
                    val logs = logsFrom { source.hash() }

                    // The estate's words stay served, exactly as with the primary alone — and the hash
                    // says so: one served archive is that archive's own id.
                    source.hash() shouldBe SnapshotId.of(a.readBytes())
                    source.hash() shouldBe primaryAlone.hash()
                    runBlocking { source.fetch() shouldBe primaryAlone.fetch() }
                    source.skippedArchives() shouldBe 1

                    val error = logs.single { it.startsWith("ERROR") }
                    error shouldContainText additive.toString()
                    error shouldContainText reason
                    logs.single { it.contains("serving 1 of 2 lexicon archives") } shouldContainText "skipped"
                }
            }
        }

        "a skipped additive archive is served as soon as it reads — the hash moves to the layer id" {
            val dir = Files.createTempDirectory("lex-multi")
            val (a, b, _) = estate(dir)
            val additive = dir.resolve("late.tar.zst")
            val source = LexiconArchiveSource(listOf(a, additive))
            source.hash() shouldBe SnapshotId.of(a.readBytes())

            Files.copy(b, additive)
            source.hash() shouldBe LexiconArchiveSource(listOf(a, b)).hash()
            source.skippedArchives() shouldBe 0
            runBlocking { source.fetch().entries.map { it.targetRef } shouldContain "er.entity.portfolio" }
        }

        "a bad PRIMARY keeps the single-archive posture: empty at boot, the loaded layer kept after" {
            val dir = Files.createTempDirectory("lex-multi")
            val (a, b, _) = estate(dir)

            // At boot there is nothing to keep — and an additive archive is never served on its own.
            val noPrimary = LexiconArchiveSource(listOf(dir.resolve("absent.tar.zst"), b))
            noPrimary.hash() shouldBe ""
            runBlocking { noPrimary.fetch().entries.shouldBeEmpty() }

            // Loaded, then the primary breaks: the whole loaded layer stays, both packages' words.
            val source = LexiconArchiveSource(listOf(a, b))
            val loaded = source.hash()
            Files.write(a, "not an archive".toByteArray())
            source.hash() shouldBe loaded
            Files.delete(a)
            source.hash() shouldBe loaded
            runBlocking {
                source.fetch().entries.map { it.targetRef } shouldContain "er.entity.portfolio"
                source.fetch().entries.map { it.targetRef } shouldContain "er.entity.order"
            }
        }

        "an additive archive that turns bad on refresh keeps its LAST GOOD copy — no served word is dropped" {
            val dir = Files.createTempDirectory("lex-multi")
            val (a, b, _) = estate(dir)
            val source = LexiconArchiveSource(listOf(a, b))
            val repo = repository(source)
            val loaded = source.hash()
            rows(repo, "portfolios").first { it[0] == "portfolios" }[1] shouldBe "er.entity.portfolio"

            // A torn write, a foreign-schema rebuild, a vanished mount — each a failed tick.
            for (turn in listOf<(Path) -> Unit>(
                { Files.write(it, "not an archive".toByteArray()) },
                { foreignSchema(it) },
                { Files.delete(it) },
            )) {
                turn(b)
                val logs = logsFrom { runBlocking { repo.forceRefresh() } }
                source.hash() shouldBe loaded
                source.skippedArchives() shouldBe 0
                rows(repo, "portfolios").first { it[0] == "portfolios" }[1] shouldBe "er.entity.portfolio"
                logs.single { it.contains("last good copy") } shouldContainText b.toString()
                logs.none { it.startsWith("ERROR") } shouldBe true
            }
        }

        "a primary that moves to another schema drops an additive archive's last good copy" {
            // The last good copy is only good AGAINST the primary it was served with: once the primary
            // is rebuilt on a new schema, an additive archive still on the old one is skipped, not served.
            val dir = Files.createTempDirectory("lex-multi")
            val (a, b, _) = estate(dir)
            val source = LexiconArchiveSource(listOf(a, b))
            source.hash()

            write(
                a,
                compile(retailFile).let { r ->
                    r.copy(
                        lexicon =
                            r.lexicon.copy(
                                header = r.lexicon.header.copy(schemaVersion = "ttr-lexicon-compiled/v9"),
                            ),
                    )
                },
            )
            source.hash() shouldBe SnapshotId.of(a.readBytes())
            source.skippedArchives() shouldBe 1
            runBlocking {
                source
                    .fetch()
                    .entries
                    .map { it.targetRef }
                    .contains("er.entity.portfolio") shouldBe false
            }
        }

        // ---- the per-ref facts --------------------------------------------------------------------

        "per-ref facts: a ref in both archives keeps its facts and takes the UNION of reachedFrom" {
            // A package both sources build against: each build sees only its own relations to it,
            // and one build over both would see all of them.
            fun lexicon(
                reach: List<Reach>,
                nameRef: String?,
            ) = CompiledLexicon(
                header =
                    CompiledLexiconHeader(
                        modelSnapshotHash = modelHash,
                        sourceHashes = SourceHashes("d", "m"),
                        builtAt = "t",
                    ),
                entries = emptyList(),
                targets =
                    mapOf(
                        "er.entity.customer" to
                            TargetFacts(objectKind = "entity", reachedFrom = reach, nameRef = nameRef),
                    ),
            )
            val merged =
                DeclaredLayer.merge(
                    listOf(
                        lexicon(listOf(Reach("er.entity.order", true)), "er.entity.customer.name"),
                        lexicon(listOf(Reach("er.entity.portfolio", false)), null),
                    ),
                )
            val facts = merged.lexicon.targets.getValue("er.entity.customer")
            facts.reachedFrom shouldBe listOf(Reach("er.entity.order", true), Reach("er.entity.portfolio", false))
            facts.nameRef shouldBe "er.entity.customer.name"
            merged.factConflicts.shouldBeEmpty()

            val disagreeing =
                DeclaredLayer.merge(
                    listOf(
                        lexicon(emptyList(), "er.entity.customer.name"),
                        lexicon(emptyList(), "er.entity.customer.full_name"),
                    ),
                )
            disagreeing.lexicon.targets
                .getValue("er.entity.customer")
                .nameRef shouldBe "er.entity.customer.name"
            disagreeing.factConflicts.single() shouldContainText "nameRef"
        }

        "the same term matched two ways across archives resolves by the compiler's precedence, and says so" {
            val dir = Files.createTempDirectory("lex-multi")
            val exact =
                """
                schema: ttr-lexicon/v1
                defaults: { lang: en }
                entries:
                  - terms: [ { text: "portfolio" } ]
                    target: er.entity.portfolio
                """.trimIndent()
            val a = write(dir.resolve("a.tar.zst"), compile(dataFile(exact, "aliases/a.lex.yaml")))
            val b = write(dir.resolve("b.tar.zst"), compile(investmentFile))
            val both =
                write(dir.resolve("both.tar.zst"), compile(dataFile(exact, "aliases/a.lex.yaml"), investmentFile))

            val merged = LexiconArchiveSource(listOf(a, b))
            val warns = warnsFrom { runBlocking { merged.fetch() } }
            // The widest method wins — TYPOS(1) over EXACT — exactly as one build decides it.
            runBlocking { merged.fetch() } shouldBe runBlocking { LexiconArchiveSource(both).fetch() }
            warns.single { it.contains("two ways") } shouldContainText "portfolio"
        }

        "the class each row carries survives the merge" {
            val (a, b, _) = estate()
            val classes =
                runBlocking { LexiconArchiveSource(listOf(a, b)).fetch() }
                    .entries
                    .flatMap { it.values }
                    .mapNotNull { it.targetClass }
                    .toSet()
            classes shouldContain CoreTargetClass.MODEL_OBJECT
            classes shouldContain CoreTargetClass.OPERATOR
            classes shouldContain CoreTargetClass.GROUNDING_TRIGGER
            classes shouldContain CoreTargetClass.STRING_PREDICATE
        }

        // ---- review of #244 -------------------------------------------------------------------------

        /** Every line the reader logs at INFO or above while [body] runs, as `LEVEL message`. */
        fun linesFrom(body: () -> Unit): List<String> {
            val logger =
                (org.slf4j.LoggerFactory.getILoggerFactory() as LoggerContext)
                    .getLogger(LexiconArchiveSource::class.java.name)
            val level = logger.level
            logger.level = Level.INFO
            val appender = ListAppender<ILoggingEvent>().apply { start() }
            logger.addAppender(appender)
            return try {
                body()
                appender.list.map { "${it.level} ${it.formattedMessage}" }
            } finally {
                logger.detachAppender(appender)
                appender.stop()
                logger.level = level
            }
        }

        /** [file]'s archive, with `er.entity.customer` reached from `er.entity.order` at [mandatory]. */
        fun reaching(
            path: Path,
            file: LexiconDataFile,
            mandatory: Boolean,
        ): Path =
            write(
                path,
                compile(file).let { r ->
                    r.copy(
                        lexicon =
                            r.lexicon.copy(
                                targets =
                                    r.lexicon.targets +
                                        (
                                            "er.entity.customer" to
                                                TargetFacts(
                                                    objectKind = "entity",
                                                    reachedFrom = listOf(Reach("er.entity.order", mandatory)),
                                                )
                                        ),
                            ),
                    )
                },
            )

        "per-ref facts: a reachedFrom `mandatory` the archives disagree on is a conflict; the first is kept" {
            // `mandatory` is what the Binder's T3 rule collapses on, so a disagreement about it is a
            // disagreement about the SAME relation — said like every other per-ref fact, never dropped.
            fun lexicon(mandatory: Boolean) =
                CompiledLexicon(
                    header =
                        CompiledLexiconHeader(
                            modelSnapshotHash = modelHash,
                            sourceHashes = SourceHashes("d", "m"),
                            builtAt = "t",
                        ),
                    entries = emptyList(),
                    targets =
                        mapOf(
                            "er.entity.customer" to
                                TargetFacts(
                                    objectKind = "entity",
                                    reachedFrom =
                                        listOf(Reach("er.entity.order", mandatory), Reach("er.entity.store", true)),
                                ),
                        ),
                )
            for (first in listOf(true, false)) {
                withClue("first archive says mandatory=$first") {
                    val merged = DeclaredLayer.merge(listOf(lexicon(first), lexicon(!first)))
                    merged.lexicon.targets
                        .getValue("er.entity.customer")
                        .reachedFrom shouldBe
                        listOf(Reach("er.entity.order", first), Reach("er.entity.store", true))
                    val conflict = merged.factConflicts.single()
                    conflict shouldContainText "er.entity.customer.reachedFrom[er.entity.order].mandatory"
                    conflict shouldContainText "kept '$first'"
                }
            }
            DeclaredLayer.merge(listOf(lexicon(true), lexicon(true))).factConflicts.shouldBeEmpty()
        }

        "a reachedFrom `mandatory` disagreement across served archives is WARNed by the reader" {
            val dir = Files.createTempDirectory("lex-multi")
            val a = reaching(dir.resolve("a.tar.zst"), retailFile, mandatory = true)
            val b = reaching(dir.resolve("b.tar.zst"), investmentFile, mandatory = false)
            val warns = warnsFrom { runBlocking { LexiconArchiveSource(listOf(a, b)).fetch() } }
            warns.single { it.contains("per-ref fact") } shouldContainText
                "er.entity.customer.reachedFrom[er.entity.order].mandatory"
        }

        "a missing PRIMARY beside additive archives is an ERROR naming both; the gauge counts them" {
            // With additive archives configured, an absent primary is a misconfiguration — never the
            // pre-RV estate with no lexicon, which configures no list at all.
            val dir = Files.createTempDirectory("lex-multi")
            val (a, b, _) = estate(dir)
            val primary = dir.resolve("primary.tar.zst")
            val bad =
                mapOf(
                    "absent" to { _: Path -> },
                    "not a readable archive" to { p: Path -> p.writeBytes("not an archive".toByteArray()) },
                )
            for ((reason, make) in bad) {
                withClue(reason) {
                    Files.deleteIfExists(primary)
                    make(primary)
                    val source = LexiconArchiveSource(listOf(primary, b))
                    val logs = logsFrom { source.hash() }

                    source.hash() shouldBe ""
                    source.skippedArchives() shouldBe 1
                    val error = logs.single { it.startsWith("ERROR") }
                    error shouldContainText primary.toString()
                    error shouldContainText reason
                    error shouldContainText b.toString()
                    error shouldContainText "NOT served"

                    // …and the moment the primary reads, both are served and the gauge drops.
                    Files.copy(a, primary, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                    source.hash() shouldBe LexiconArchiveSource(listOf(a, b)).hash()
                    source.skippedArchives() shouldBe 0
                }
            }

            // ONE archive, absent at boot, is still the pre-RV estate: silent, nothing skipped.
            val alone = LexiconArchiveSource(dir.resolve("never.tar.zst"))
            logsFrom { alone.hash() }.shouldBeEmpty()
            alone.skippedArchives() shouldBe 0
        }

        "the load line counts the archives MERGED, not the configured ones, and names a skipped one" {
            val dir = Files.createTempDirectory("lex-multi")
            val (a, b, _) = estate(dir)
            val absent = dir.resolve("absent.tar.zst")

            val degraded = linesFrom { runBlocking { LexiconArchiveSource(listOf(a, absent)).fetch() } }
            val line = degraded.single { it.contains("Compiled lexicon loaded") }
            line shouldContainText "from 1 of 2 archive(s)"
            line shouldContainText "skipped: $absent (absent)"

            val whole = linesFrom { runBlocking { LexiconArchiveSource(listOf(a, b)).fetch() } }
            whole.single { it.contains("Compiled lexicon loaded") }.let {
                it shouldContainText "from 2 of 2 archive(s)"
                it.contains("skipped") shouldBe false
                it.startsWith("INFO") shouldBe true
            }
        }
    })

private fun withClue(
    clue: String,
    block: () -> Unit,
) = io.kotest.assertions.withClue(clue, block)
