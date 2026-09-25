// SPDX-License-Identifier: Apache-2.0
package org.tatrman.fuzzy.loader

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain as shouldContainText
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.runBlocking
import org.tatrman.fuzzy.config.AppConfig
import org.tatrman.fuzzy.config.LoaderSourceConfig
import org.tatrman.fuzzy.config.MetadataConfig
import org.tatrman.fuzzy.config.NlpConfig
import org.tatrman.fuzzy.config.TokenBasedConfig
import org.tatrman.fuzzy.core.AlgorithmType
import org.tatrman.fuzzy.core.Candidate
import org.tatrman.fuzzy.core.FuzzyMatcher
import org.tatrman.fuzzy.core.SourceTag
import org.tatrman.fuzzy.core.StringRepository
import org.tatrman.fuzzy.core.TargetClass as CoreTargetClass
import org.tatrman.ttr.lexicon.CompiledLexiconHeader
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
import org.tatrman.ttr.lexicon.LexiconArchive
import org.tatrman.ttr.snapshot.SnapshotManifest
import org.tatrman.ttr.snapshot.SnapshotReadResult
import org.tatrman.ttr.snapshot.SnapshotReader
import org.tatrman.ttr.snapshot.SnapshotWriter
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readBytes
import kotlin.io.path.writeBytes

/**
 * RV-P1.4 T3 — the compiled-lexicon archive reader.
 *
 * The fixture is packed by the **real** `LexiconPacker` (test scope only), not by a
 * re-implementation of the layout here. A change to the producer's layout therefore breaks this
 * test instead of silently diverging from it — which is the only way two repos can share an
 * on-disk contract without a schema registry between them.
 */
class LexiconArchiveSourceTest :
    StringSpec({

        val modelHash = "sha256:" + "ab".repeat(32)

        /** Compiles + packs a lexicon exactly as the RV-P1.2 build does, and writes it to disk. */
        fun writeArchive(
            dir: Path,
            yaml: String,
            name: String = "lexicon.tar.zst",
            transform: (CompileResult) -> CompileResult = { it },
        ): Path {
            val file =
                LexiconValidator
                    .loadDataFile(yaml, "aliases/er.lex.yaml")
                    .shouldBeInstanceOf<LexiconLoad.Ok<LexiconDataFile>>()
                    .value
            val result =
                LexiconCompiler.compile(
                    LexiconSources(area = LexiconArea(listOf(file), emptyList())),
                    ModelRefIndex { ref ->
                        when (ref) {
                            "er.entity.customer", "md.measure.revenue" -> TargetClass.MODEL_OBJECT
                            else -> null
                        }
                    },
                    modelHash,
                    "2026-08-03T00:00:00Z",
                )
            val packed = LexiconPacker.pack(transform(result), modelHash, "test")
            return dir.resolve(name).also { it.writeBytes(packed.bytes) }
        }

        val aliases =
            """
            schema: ttr-lexicon/v1
            defaults: { lang: cs }
            entries:
              - terms:
                  - { text: "zákazník" }
                  - { text: "odběratel", method: TYPOS(1) }
                target: er.entity.customer
              - terms: [ { text: "tržba", method: TOKENS } ]
                target: md.measure.revenue
            """.trimIndent()

        // ---- MS-P2·S2 (contracts §6): the canary, and the version check ----------------------
        //
        // This reader is the SECOND of the two duplicated archive readers (RS-24 / plan risk 2).
        // MS moves the archive to `ttr-lexicon-compiled/v2` with a new `targets` map, and this
        // side ignores that map entirely — but it must keep reading the file, and it must say
        // something useful when the versions disagree. Both are asserted here, because nothing
        // else in this repo would notice the day a bump makes this reader blind.

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

        // ---- LP-P2b·T2 (§3.2): the `pred:` class, off a REAL stdlib-carrying archive ----------

        "the stdlib predicate slice arrives as STRING_PREDICATE rows, keyed by `pred:` ref" {
            // Compiled from `LexiconStdlib.predicateSlices()` — the actual shipped file, not a
            // hand-written stand-in. Every archive the P2a toolchain builds carries these rows, so
            // this is the shape this reader meets on every estate from the next lexicon build on.
            val dir = Files.createTempDirectory("fuzzy-lex")
            val result =
                LexiconCompiler.compile(
                    LexiconSources(
                        area = LexiconArea(LexiconStdlib.predicateSlices(), emptyList()),
                    ),
                    ModelRefIndex { null },
                    modelHash,
                    "2026-09-24T00:00:00Z",
                )
            val path =
                dir.resolve("lexicon.tar.zst").also {
                    it.writeBytes(LexiconPacker.pack(result, modelHash, "test").bytes)
                }

            val vocabulary = runBlocking { LexiconArchiveSource(path).fetch() }

            // A `pred:` target ref IS the category key here, as `ground:` is.
            vocabulary.entries.map { it.category }.toSet() shouldBe
                LexiconValidator.PREDICATE_KINDS.map { "pred:$it" }.toSet()
            vocabulary.entries
                .flatMap { it.values }
                .map { it.targetClass }
                // The CORE enum, not the artifact's — this reader's whole job on this field is to
                // cross that boundary, and two same-named enums make the assertion worth spelling.
                .toSet() shouldBe setOf(CoreTargetClass.STRING_PREDICATE)
            // The authored method rides too, and every `pred:` form is EXACT (✅LP-24): a fragment
            // of a multi-word form must not fire the predicate on its own.
            val byValue = vocabulary.entries.flatMap { it.values }.associateBy { it.value }
            byValue.getValue("začínající na").matchMethod shouldBe "EXACT"
            byValue.getValue("obsahuje").matchMethod shouldBe "EXACT"
        }

        "a v4 archive needs no reader gate — this one maps the new class rather than degrading" {
            // The §8.1 posture from the READER's side. A reader that had not been rebuilt would
            // fail to decode the whole document here (kotlinx refuses an enum value it does not
            // know) and log "undecodable", leaving the vocabulary EMPTY. This build maps it, so
            // the version WARN is the only thing that fires — and only if the versions differ.
            val dir = Files.createTempDirectory("fuzzy-lex")
            val result =
                LexiconCompiler.compile(
                    LexiconSources(area = LexiconArea(LexiconStdlib.predicateSlices(), emptyList())),
                    ModelRefIndex { null },
                    modelHash,
                    "2026-09-24T00:00:00Z",
                )
            val path =
                dir.resolve("lexicon.tar.zst").also {
                    it.writeBytes(LexiconPacker.pack(result, modelHash, "test").bytes)
                }

            val warnings = warnsFrom { runBlocking { LexiconArchiveSource(path).fetch() } }

            warnings.none { it.contains("undecodable") } shouldBe true
            result.lexicon.header.schemaVersion shouldBe CompiledLexiconHeader.SCHEMA_VERSION
        }

        "a v2 archive reads here unchanged — the targets map is simply ignored" {
            val dir = Files.createTempDirectory("lex-archive")
            val source = LexiconArchiveSource(writeArchive(dir, aliases))

            runBlocking {
                // The producer writes `targets` into EVERY v2 archive (encodeDefaults), including
                // this one, so "reads fine" here is a statement about the real bytes.
                source.fetch().entries.shouldNotBeEmpty()
            }
            warnsFrom { runBlocking { source.fetch() } }.none { it.contains("schema") } shouldBe true
        }

        "a foreign schemaVersion is read anyway, but WARNs naming both versions" {
            val dir = Files.createTempDirectory("lex-archive")
            val path =
                writeArchive(dir, aliases) { r ->
                    r.copy(
                        lexicon =
                            r.lexicon.copy(
                                header = r.lexicon.header.copy(schemaVersion = "ttr-lexicon-compiled/v9"),
                            ),
                    )
                }
            val source = LexiconArchiveSource(path)

            val warns = warnsFrom { runBlocking { source.fetch() } }

            // Read anyway: refusing would blank the estate's vocabulary, which is the degrade
            // that made this whole class of problem silent (review-082 F1).
            runBlocking { source.fetch() }.entries.shouldNotBeEmpty()
            val warn = warns.single { it.contains("schema") }
            warn shouldContainText "ttr-lexicon-compiled/v9"
            warn shouldContainText CompiledLexiconHeader.SCHEMA_VERSION
        }

        "an archive loads into declared vocabulary keyed by target ref" {
            val dir = Files.createTempDirectory("lex-archive")
            val source = LexiconArchiveSource(writeArchive(dir, aliases))

            runBlocking {
                val vocab = source.fetch()

                vocab.entries.map { it.category } shouldContainExactlyInAnyOrder
                    listOf("er.entity.customer", "md.measure.revenue")

                val customer = vocab.entries.single { it.category == "er.entity.customer" }
                customer.targetRef shouldBe "er.entity.customer"
                customer.values.map { it.value } shouldContainExactlyInAnyOrder listOf("zákazník", "odběratel")
            }
        }

        "each row keeps its own source tag and AUTHORED match method" {
            val dir = Files.createTempDirectory("lex-archive")
            val source = LexiconArchiveSource(writeArchive(dir, aliases))

            runBlocking {
                val byTerm =
                    source
                        .fetch()
                        .entries
                        .flatMap { it.values }
                        .associateBy { it.value }

                byTerm.getValue("zákazník").source shouldBe SourceTag.DECLARED
                byTerm.getValue("zákazník").matchMethod shouldBe "EXACT"
                byTerm.getValue("odběratel").matchMethod shouldBe "TYPOS(1)"
                byTerm.getValue("tržba").matchMethod shouldBe "TOKENS"
            }
        }

        "terms keep their diacritics — the archive is not folded on the way in" {
            // The engine folds for its token index; the stored value stays as authored. That is
            // what lets T4's EXACT dispatch compare on the authored word instead of a form where
            // `vyroba` would EXACT-match `výroba`.
            val dir = Files.createTempDirectory("lex-archive")
            val source = LexiconArchiveSource(writeArchive(dir, aliases))

            runBlocking {
                source
                    .fetch()
                    .entries
                    .flatMap { it.values }
                    .map { it.value } shouldContainExactlyInAnyOrder
                    listOf("zákazník", "odběratel", "tržba")
            }
        }

        "the two hashes answer different questions" {
            // hash() = "is this a different FILE?" (drives the two-clock reload)
            // artifactHash() = "did the VOCABULARY change?" (the RV-39 layer tuple)
            val dir = Files.createTempDirectory("lex-archive")
            val source = LexiconArchiveSource(writeArchive(dir, aliases))

            source.hash() shouldNotBe ""
            source.artifactHash() shouldNotBe ""
            source.hash() shouldNotBe source.artifactHash()
        }

        "artifactHash never touches the disk — it reports what the last read loaded" {
            // `StringRepository.layerVersions()` calls this, and `GrpcService` calls THAT on every
            // response. Loading here meant a full file read plus a content hash of the archive on
            // the hot path of every question. Reading is `hash()`'s job, once per refresh.
            val dir = Files.createTempDirectory("lex-archive")
            val path = writeArchive(dir, aliases)
            val source = LexiconArchiveSource(path)

            // Nothing has read the file yet, so there is no artifact to name.
            source.artifactHash() shouldBe ""

            val loaded = source.hash()
            loaded shouldNotBe ""
            val artifact = source.artifactHash()
            artifact shouldNotBe ""

            // With the file gone, a method that read the disk would now answer differently. This
            // one cannot: it never looked.
            Files.delete(path)
            source.artifactHash() shouldBe artifact
        }

        "an unchanged file yields an unchanged hash, so the refresh loop does not reload" {
            val dir = Files.createTempDirectory("lex-archive")
            val source = LexiconArchiveSource(writeArchive(dir, aliases))

            source.hash() shouldBe source.hash()
        }

        "rewriting the archive with different terms moves both hashes" {
            val dir = Files.createTempDirectory("lex-archive")
            val path = writeArchive(dir, aliases)
            val source = LexiconArchiveSource(path)
            val firstArchive = source.hash()
            val firstVocab = source.artifactHash()

            writeArchive(
                dir,
                """
                schema: ttr-lexicon/v1
                defaults: { lang: cs }
                entries:
                  - terms: [ { text: "klient" } ]
                    target: er.entity.customer
                """.trimIndent(),
            )

            source.hash() shouldNotBe firstArchive
            source.artifactHash() shouldNotBe firstVocab
            runBlocking {
                source
                    .fetch()
                    .entries
                    .single()
                    .values
                    .single()
                    .value shouldBe "klient"
            }
        }

        // ---- degradation: a broken layer must be loud in the log and invisible to a query ------

        "a missing archive is an empty vocabulary, not a failure" {
            val source = LexiconArchiveSource(Path.of("/nonexistent/lexicon.tar.zst"))

            source.hash() shouldBe ""
            source.artifactHash() shouldBe ""
            runBlocking { source.fetch().entries shouldBe emptyList() }
        }

        "a file that is not an archive at all degrades instead of throwing" {
            val dir = Files.createTempDirectory("lex-archive")
            val junk = dir.resolve("lexicon.tar.zst").also { it.writeBytes("not an archive".toByteArray()) }

            val source = LexiconArchiveSource(junk)
            source.hash() shouldBe ""
            runBlocking { source.fetch().entries shouldBe emptyList() }
        }

        "the wrong archive KIND is refused even when it carries a valid lexicon.json" {
            // Deliberately adversarial: the payload is a perfectly good compiled lexicon, so the
            // ONLY thing that can reject this archive is the `kind` check. A weaker fixture (a real
            // model snapshot) passes whether or not that check exists, because it also lacks
            // lexicon.json — two guards, one observable outcome, and the test proves neither.
            // Verified by mutation: relaxing the kind comparison turns this red.
            val dir = Files.createTempDirectory("lex-archive")
            val good = LexiconArchiveSource(writeArchive(dir, aliases, "good.tar.zst"))
            val payload =
                SnapshotReader
                    .read(dir.resolve("good.tar.zst").readBytes())
                    .shouldBeInstanceOf<SnapshotReadResult.Ok>()
                    .contents.docs
                    .getValue(LexiconArchive.LEXICON)

            val mislabelled =
                SnapshotWriter.write(
                    SnapshotManifest(kind = "models", producedBy = "test"),
                    mapOf(LexiconArchive.LEXICON to payload),
                )
            val path = dir.resolve("lexicon.tar.zst").also { it.writeBytes(mislabelled) }

            // The payload is readable — proven by `good` — so only the kind gate stands between
            // this and a silently-loaded vocabulary. `hash()` first because that is the method that
            // reads the file; `artifactHash()` only reports what a read already loaded.
            good.hash() shouldNotBe ""
            good.artifactHash() shouldNotBe ""

            val source = LexiconArchiveSource(path)
            source.hash() shouldBe ""
            runBlocking { source.fetch().entries shouldBe emptyList() }
        }

        "an archive that disappears leaves the loaded vocabulary in place" {
            // A vanished mount should not empty a running matcher's declared layer.
            val dir = Files.createTempDirectory("lex-archive")
            val path = writeArchive(dir, aliases)
            val source = LexiconArchiveSource(path)

            val loaded = source.hash()
            runBlocking { source.fetch().entries.size shouldBe 2 }

            Files.delete(path)

            source.hash() shouldBe loaded
            runBlocking { source.fetch().entries.size shouldBe 2 }
        }

        "a metadata-tagged row arrives as METADATA with no authored method" {
            val dir = Files.createTempDirectory("lex-archive")
            // The METADATA layer comes from model labels, which carry no authored method; compile
            // one through the real pipeline rather than hand-building the artifact.
            val source =
                LexiconArchiveSource(
                    writeArchive(
                        dir,
                        """
                        schema: ttr-lexicon/v1
                        entries:
                          - terms: [ { text: "zákazník" } ]
                            target: er.entity.customer
                        """.trimIndent(),
                    ),
                )
            runBlocking {
                val v =
                    source
                        .fetch()
                        .entries
                        .single()
                        .values
                        .single()
                v.source shouldBe SourceTag.DECLARED
                v.matchMethod shouldBe "EXACT"
                v.id shouldBe "lex:er.entity.customer:cs|en:zákazník"
            }
        }

        "the repository loads the archive and reloads it on the /refresh hook" {
            // T3's actual requirement: loaded at startup and on the existing admin-gated refresh.
            // `refreshIntervalSeconds = 0` is manual mode, so forceRefresh IS the /refresh path.
            val dir = Files.createTempDirectory("lex-archive")
            val path = writeArchive(dir, aliases)
            val repo =
                StringRepository(
                    AppConfig(
                        serverPort = 7112,
                        grpcPort = 7212,
                        grpcReflectionEnabled = false,
                        refreshIntervalSeconds = 0,
                        tokenBasedConfig = TokenBasedConfig(),
                        nlp = NlpConfig(),
                        loaderSource = LoaderSourceConfig(source = "static"),
                        metadata = MetadataConfig(),
                    ),
                    object : LoaderSource {
                        override suspend fun loadNextCache(): Map<String, List<Candidate>> = emptyMap()
                    },
                    snapshotSource = LexiconArchiveSource(path),
                )

            runBlocking {
                repo.forceRefresh()

                // Not `.single()`: the cascade legitimately also returns lower-scoring neighbours
                // from the same category. The assertion is about the term we asked for.
                val hit =
                    FuzzyMatcher(repo)
                        .match("zákazník", "er.entity.customer", AlgorithmType.TATRMAN, 10)
                        .first { it.candidate == "zákazník" }
                hit.source shouldBe SourceTag.DECLARED
                hit.targetRef shouldBe "er.entity.customer"
                hit.matchMethod shouldBe "EXACT"

                // RV-39: the tuple names the artifact, and the entry-table hash — not the archive
                // id — is what the resolver echoes as `lexicon_artifact_hash`.
                repo.layerVersions().lexiconArtifactHash shouldNotBe ""
                repo.layerVersions().overlayVersion.shouldBeNull()

                // Rewrite the archive; the refresh hook picks it up (two-clock discipline).
                writeArchive(
                    dir,
                    """
                    schema: ttr-lexicon/v1
                    defaults: { lang: cs }
                    entries:
                      - terms: [ { text: "klient" } ]
                        target: er.entity.customer
                    """.trimIndent(),
                )
                repo.forceRefresh()

                FuzzyMatcher(repo)
                    .match("klient", "er.entity.customer", AlgorithmType.TATRMAN, 10)
                    .first { it.candidate == "klient" }
                    .source shouldBe SourceTag.DECLARED
                // The old vocabulary is gone, not merged: a reload replaces the layer.
                FuzzyMatcher(repo)
                    .match("zákazník", "er.entity.customer", AlgorithmType.TATRMAN, 10)
                    .none { it.candidate == "zákazník" } shouldBe true
            }
        }

        "a dangling target never reaches the matcher — the compiler dropped it" {
            val dir = Files.createTempDirectory("lex-archive")
            val source =
                LexiconArchiveSource(
                    writeArchive(
                        dir,
                        """
                        schema: ttr-lexicon/v1
                        entries:
                          - terms: [ { text: "duch" } ]
                            target: er.entity.ghost
                        """.trimIndent(),
                    ),
                )
            runBlocking {
                source.fetch().entries shouldBe emptyList()
                // ...and nothing here had to know about RV-20 to get that right.
                source
                    .fetch()
                    .entries
                    .firstOrNull()
                    .shouldBeNull()
            }
        }
    })
