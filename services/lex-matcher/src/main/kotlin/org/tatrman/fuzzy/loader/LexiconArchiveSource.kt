// SPDX-License-Identifier: Apache-2.0
package org.tatrman.fuzzy.loader

import org.slf4j.LoggerFactory
import org.tatrman.fuzzy.core.MatchMethod
import org.tatrman.fuzzy.core.MatchProfile
import org.tatrman.fuzzy.core.Norm
import org.tatrman.fuzzy.core.NormRule
import org.tatrman.fuzzy.core.SourceTag
import org.tatrman.fuzzy.core.TyposRule
import org.tatrman.fuzzy.core.TargetClass
import org.tatrman.ttr.lexicon.CompiledEntry
import org.tatrman.ttr.lexicon.CompiledLexicon
import org.tatrman.ttr.lexicon.CompiledLexiconHeader
import org.tatrman.ttr.lexicon.LexiconArchive
import org.tatrman.ttr.snapshot.SnapshotId
import org.tatrman.ttr.snapshot.SnapshotReadResult
import org.tatrman.ttr.snapshot.SnapshotReader
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readBytes
import org.tatrman.ttr.lexicon.MatchProfile as ArtifactMatchProfile
import org.tatrman.ttr.lexicon.SourceTag as ArtifactSourceTag
import org.tatrman.ttr.lexicon.TargetClass as ArtifactTargetClass

/**
 * RV-P1.4 T3 — the real declared-vocabulary layer: the RV-P1.2 compiled lexicon archive.
 *
 * This is a **new implementation of the existing [SnapshotVocabularySource] seam**, which is what
 * that seam was left open for (RO-13). Nothing about the refresh machinery changes: the two-clock
 * discipline already reloads declared vocabulary only when [hash] moves, and the admin-gated
 * `POST /refresh` already drives it. No new endpoint (S-3).
 *
 * Reading an archive costs `ttr-snapshot` + `ttr-lexicon` — no compiler. That is the (a3) ruling's
 * payoff, and `LexiconArchiveSourceTest` packs its fixture with the real `LexiconPacker` so this
 * reader is held to the actual producer's layout rather than a copy of it.
 *
 * **Missing or unreadable is not fatal.** A lex-matcher with no lexicon archive is the pre-RV
 * service: member vocabulary only, no declared layer. Failing startup because an optional layer
 * is absent would take down matching for estates that have not authored a lexicon yet.
 */
class LexiconArchiveSource(
    private val archivePath: Path,
) : SnapshotVocabularySource {
    private val logger = LoggerFactory.getLogger(LexiconArchiveSource::class.java)

    /** Last successfully read archive, keyed by its content id so a re-read is not a re-parse. */
    @Volatile
    private var cached: Loaded? = null

    private data class Loaded(
        val archiveId: String,
        val lexicon: CompiledLexicon,
    )

    /**
     * The **archive** id — what the two-clock refresh compares. Deliberately not
     * [CompiledLexicon.contentHash]: this answers "is the file on disk a different file?", and it
     * must move when anything in the archive moves, including the operator library.
     *
     * Empty string when there is no readable archive, which is a stable value — so an absent
     * lexicon does not make the refresh loop reload on every tick.
     */
    override fun hash(): String = load()?.archiveId ?: ""

    /**
     * The **vocabulary** id — RV-39's `lexicon_artifact_hash`, which covers the entry table only.
     * Different question from [hash]: this one is "did the vocabulary change?", and an operator
     * body edit must not answer it yes.
     *
     * **A pure accessor: it never touches the disk.** `StringRepository.layerVersions()` calls this,
     * and `GrpcService` calls that on *every* response — match, status, lookup, and the error path.
     * Loading here meant a full file read plus a content hash of the whole archive on the hot path
     * of every question, concurrently across every in-flight request. Reading the file is [hash]'s
     * job, which the two-clock refresh calls once per interval; this reports what that last read
     * loaded. Empty until the first refresh, which is honest — no artifact is serving yet.
     */
    override fun artifactHash(): String = cached?.lexicon?.contentHash ?: ""

    /**
     * Entries grouped by target ref, which IS the category key here — the convention the declared
     * layer already uses (`md.measure.net`, `er.branch`).
     *
     * The candidate's value is the artifact's `termNormalized`, **diacritics intact**. That is not
     * a second normalizer: the engine folds it into the token index itself, exactly as it does for
     * member values. Keeping the unfolded form is what lets T4's EXACT dispatch compare on the
     * authored word — on the folded form, `vyroba` would EXACT-match `výroba`, a TYPOS decision
     * the author never made.
     */
    override suspend fun fetch(): DeclaredVocabulary {
        val lexicon = load()?.lexicon ?: return DeclaredVocabulary()

        val entries =
            lexicon.entries
                .groupBy { it.targetRef }
                .map { (targetRef, rows) ->
                    DeclaredVocabularyEntry(
                        category = targetRef,
                        targetRef = targetRef,
                        values = rows.map { it.toDeclaredValue() },
                    )
                }.sortedBy { it.category }

        logger.info(
            "Compiled lexicon loaded: {} entries over {} targets (artifact={})",
            lexicon.entries.size,
            entries.size,
            lexicon.contentHash,
        )
        reportUnrecognisedMethods(entries)
        return DeclaredVocabulary(entries)
    }

    /**
     * One WARN per load naming the **distinct** methods this build cannot parse, and how many rows
     * carry each.
     *
     * `MatchMethod.parse` used to warn itself, but it runs per candidate per request: a single
     * `SEMANTIC(0.8)` row from a newer toolchain would then log on every query that surfaced it,
     * forever. The condition is a property of the *archive*, so it is reported where the archive is
     * read — bounded, and far more useful (a count, not a drip).
     *
     * Still a warning, not a failure: an unparseable method degrades that row to "unauthored" and
     * the rest of the lexicon serves normally (T3's posture).
     */
    private fun reportUnrecognisedMethods(entries: List<DeclaredVocabularyEntry>) {
        val unrecognised =
            entries
                .asSequence()
                .flatMap { it.values.asSequence() }
                .mapNotNull { it.matchMethod }
                .filter { MatchMethod.parse(it) == null }
                .groupingBy { it }
                .eachCount()
        if (unrecognised.isEmpty()) return

        logger.warn(
            "Compiled lexicon at {} carries {} unrecognised match method(s) — those rows serve as " +
                "unauthored (no EXACT/TYPOS gate, no RV-32 margin): {}",
            archivePath,
            unrecognised.values.sum(),
            unrecognised.entries.joinToString(", ") { "${it.key} x${it.value}" },
        )
    }

    /**
     * `id` must be stable and unique per row — it is the candidate id the resolver echoes back.
     * Term + lang, because one term can legitimately point at one target in two languages.
     */
    private fun CompiledEntry.toDeclaredValue(): DeclaredValue =
        DeclaredValue(
            id = "lex:$targetRef:$lang:$termNormalized",
            value = termNormalized,
            source =
                when (sourceTag) {
                    ArtifactSourceTag.DECLARED -> SourceTag.DECLARED
                    ArtifactSourceTag.METADATA -> SourceTag.METADATA
                },
            matchMethod = method,
            // RV-38 — carried, never derived here. The artifact states the class; the *kind* is
            // what downstream derives from it, and re-deriving the class from the ref's prefix
            // would be a second, quietly divergent rule.
            targetClass =
                when (targetClass) {
                    ArtifactTargetClass.MODEL_OBJECT -> TargetClass.MODEL_OBJECT
                    ArtifactTargetClass.MEMBER -> TargetClass.MEMBER
                    ArtifactTargetClass.OPERATOR -> TargetClass.OPERATOR
                    ArtifactTargetClass.GROUNDING_TRIGGER -> TargetClass.GROUNDING_TRIGGER
                    ArtifactTargetClass.STRING_PREDICATE -> TargetClass.STRING_PREDICATE
                },
            // RV-44 — carried, never re-derived. The compiler already expanded `method:` sugar into
            // this profile; expanding it again here would be a second implementation of M-T6, and
            // the two would eventually disagree about a number nobody would think to check.
            matchProfile = matchProfile?.toCore(),
        )

    /**
     * Artifact profile → the engine's own mirror. An unknown `norm` from a newer toolchain drops
     * that ONE rule rather than the row: the rest of the profile is still the author's statement,
     * and a rule this engine cannot evaluate is a rule that would never have fired anyway.
     */
    private fun ArtifactMatchProfile.toCore(): MatchProfile? {
        val rules =
            rules.mapNotNull { rule ->
                val norm = Norm.ofWire(rule.norm.wire) ?: return@mapNotNull null
                NormRule(
                    norm = norm,
                    exact = rule.exact,
                    typos = rule.typos?.let { TyposRule(it.distance, it.penalty) },
                    tokens = rule.tokens,
                )
            }
        return if (rules.isEmpty()) null else MatchProfile(rules)
    }

    /**
     * Reads and parses, reusing the cache when the bytes hash to what is already loaded.
     *
     * Returns null — never throws — for absent, unreadable, wrong-kind or malformed archives. Each
     * is logged once per distinct cause at WARN: a broken lexicon should be loud in the log and
     * invisible to a query, not the reverse.
     */
    private fun load(): Loaded? {
        if (!archivePath.exists()) {
            cached?.let { logger.warn("Compiled lexicon disappeared from {} — keeping the loaded one", archivePath) }
            return cached
        }

        val bytes =
            try {
                archivePath.readBytes()
            } catch (e: Exception) {
                logger.warn("Compiled lexicon at {} is unreadable: {}", archivePath, e.message)
                return cached
            }

        val id = SnapshotId.of(bytes)
        cached?.let { if (it.archiveId == id) return it }

        val contents =
            when (val read = SnapshotReader.read(bytes)) {
                is SnapshotReadResult.Ok -> read.contents
                is SnapshotReadResult.Failure -> {
                    logger.warn("Compiled lexicon at {} is not a readable archive: {}", archivePath, read.reason)
                    return cached
                }
            }

        if (contents.manifest.kind != LexiconArchive.KIND) {
            // A model snapshot pointed at the lexicon slot would otherwise load as an empty
            // vocabulary and look like "the estate authored nothing".
            logger.warn(
                "Archive at {} is kind='{}', expected '{}' — not a lexicon archive",
                archivePath,
                contents.manifest.kind,
                LexiconArchive.KIND,
            )
            return cached
        }

        val json =
            contents.docs[LexiconArchive.LEXICON] ?: run {
                logger.warn("Lexicon archive at {} has no {}", archivePath, LexiconArchive.LEXICON)
                return cached
            }

        return try {
            Loaded(id, CompiledLexicon.fromJson(json)).also {
                checkSchemaVersion(it.lexicon)
                cached = it
            }
        } catch (e: Exception) {
            logger.warn(
                "Lexicon archive at {} has an undecodable {}: {}",
                archivePath,
                LexiconArchive.LEXICON,
                e.message,
            )
            cached
        }
    }

    /**
     * MS-P2·S2 (contracts §6) — the version check this reader never had.
     *
     * review-082 F2: neither this reader nor the resolver's twin read `schemaVersion` at all, so
     * an archive from a producer this build has never heard of arrived as a generic
     * *"undecodable"*. The WARN names BOTH versions, because the useful question in a cluster is
     * *which* side is behind.
     *
     * ⛑ It **reads the archive anyway**. Refusing on a version mismatch would blank the estate's
     * vocabulary — the degrade (review-082 F1) that makes this class of problem silent.
     */
    private fun checkSchemaVersion(lexicon: CompiledLexicon) {
        val found = lexicon.header.schemaVersion
        if (found == CompiledLexiconHeader.SCHEMA_VERSION) return
        logger.warn(
            "Lexicon archive at {} declares schema '{}'; this reader was built against '{}'. " +
                "Reading it anyway — unknown fields are ignored — but a field this reader needs " +
                "may be absent, and an OLDER reader than the producer cannot be fixed by config: " +
                "roll the readers first (MS contracts §6, readers before producers).",
            archivePath,
            found,
            CompiledLexiconHeader.SCHEMA_VERSION,
        )
    }
}
