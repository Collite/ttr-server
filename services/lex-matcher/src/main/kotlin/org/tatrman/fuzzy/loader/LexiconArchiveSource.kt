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
import org.tatrman.lexicon.transport.ArchiveInputs
import java.nio.file.Path
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
 *
 * ## Several archives, one layer (ttr-server#209)
 *
 * [archivePaths] may name several archives — one per source the estate's packages come from — and
 * they load as ONE declared layer ([DeclaredLayer]): exactly what one build over all of those
 * packages would have packed. A form two packages declare gets both readings; the stdlib rows every
 * archive embeds are served once, so they neither double a candidate nor move a score; and the
 * entry-table hash ([artifactHash]) is the one that single build would report. One path is the
 * pre-#209 reader, byte for byte.
 *
 * ⚑ **The first archive is the primary; the others degrade alone** ([DeclaredLayer.ArchiveSet]).
 * Every refresh tick reads them all. The primary — the estate's own archive — keeps the
 * single-archive posture: absent, unreadable or not a lexicon, it is WARNed and the layer keeps what
 * it had loaded. An additive archive that is absent, unreadable, not a lexicon, undecodable, or
 * declares a different `schemaVersion` from the primary's is served from its last good copy when it
 * has one (WARN — a transient read error never drops words that were being served), and is
 * otherwise SKIPPED with an ERROR naming it and the reason: a broken or drifted side archive costs
 * its own words, never the estate's. [hash] covers exactly the archives served, each new layer is
 * logged as `serving N of M lexicon archives`, and [skippedArchives] feeds the
 * `fuzzy_lexicon_archives_skipped` gauge. With additive archives configured, a primary that cannot
 * be read at boot is an ERROR naming it and every additive archive it leaves unserved (an additive
 * archive is never served on its own, and a configured list is never the "estate with no lexicon"
 * case), and the gauge counts those archives. This is NOT the learned layer:
 * `overlay-archive-path` stays a separate source on its own clock ([OverlayArchiveSource]).
 */
class LexiconArchiveSource(
    localPaths: List<Path>,
    archiveUrls: List<String> = emptyList(),
) : SnapshotVocabularySource {
    constructor(archivePath: Path) : this(listOf(archivePath))

    private val inputs = ArchiveInputs(localPaths, archiveUrls)
    private val archivePaths = inputs.paths

    init {
        require(archivePaths.isNotEmpty()) { "LexiconArchiveSource needs at least one archive path" }
    }

    private val logger = LoggerFactory.getLogger(LexiconArchiveSource::class.java)

    private val archives = DeclaredLayer.ArchiveSet(archivePaths, inputs::read)

    /** How many configured archives the last pass did not serve — the degraded state, as a number. */
    @Volatile
    private var skipped: Int = 0

    /**
     * #209 — configured ADDITIVE archives the last refresh did NOT serve: 0 when all are, or when
     * there is only the primary; every additive archive when the primary is unreadable at boot,
     * because none of them is served without it. A pure accessor, like [artifactHash]: it reports
     * what the last read decided.
     */
    fun skippedArchives(): Int = skipped

    /** How the archives are named in a log line: the path, or the list of them. */
    private val named: String =
        archivePaths.map(inputs::location).let {
            it.singleOrNull()
                ?: it.joinToString(", ", "[", "]")
        }

    /** Last successfully read archive, keyed by its content id so a re-read is not a re-parse. */
    @Volatile
    private var cached: Loaded? = null

    private data class Loaded(
        val archiveId: String,
        val lexicon: CompiledLexicon,
        /** The archives this layer merged, primary first. */
        val served: List<Path>,
        /** The additive archives the latest pass did not serve, with why. */
        val skipped: List<DeclaredLayer.Failure>,
    )

    /**
     * The **archive** id — what the two-clock refresh compares. Deliberately not
     * [CompiledLexicon.contentHash]: this answers "is the file on disk a different file?", and it
     * must move when anything in the archive moves, including the operator library.
     *
     * Empty string when there is no readable archive, which is a stable value — so an absent
     * lexicon does not make the refresh loop reload on every tick.
     *
     * With several archives it is [DeclaredLayer.layerId] over the ids of the archives SERVED, so the
     * refresh reloads when any one of them changes (#209), and a skipped additive archive shows in it:
     * with only the primary served it is the primary's own id, exactly as with one archive.
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
        val loaded = load() ?: return DeclaredVocabulary()
        val lexicon = loaded.lexicon

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

        // #209 — the archives MERGED, never merely the configured ones: a skipped additive archive
        // must not read as served in the very line a post-deploy check greps.
        val message =
            "Compiled lexicon loaded: {} entries over {} targets from {} of {} archive(s) (artifact={})" +
                if (loaded.skipped.isEmpty()) "" else "; skipped: {}"
        val args =
            listOf<Any>(lexicon.entries.size, entries.size, loaded.served.size, archivePaths.size, lexicon.contentHash)
        if (loaded.skipped.isEmpty()) {
            logger.info(message, *args.toTypedArray())
        } else {
            logger.warn(message, *(args + describe(loaded.skipped)).toTypedArray())
        }
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
            named,
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
     * Reads every archive and assembles the layer, reusing the cache when the archives served hash
     * to what is already loaded.
     *
     * Returns the loaded layer — never throws. A primary that cannot be read keeps what was loaded
     * (null at boot); an additive one degrades alone (see the class KDoc). Logged on every read, the
     * way this reader always has: a broken lexicon should be loud in the log and invisible to a
     * query, not the reverse.
     */
    private fun load(): Loaded? {
        val pass =
            when (val p = archives.pass()) {
                is DeclaredLayer.Pass.PrimaryFailed -> {
                    val f = p.failure
                    when {
                        cached != null ->
                            logger.warn(
                                "Compiled lexicon at {} — {}: keeping the loaded one",
                                inputs.location(f.path),
                                f.reason,
                            )
                        // #244 review — with additive archives configured, an unreadable primary is a
                        // misconfiguration, never the pre-RV estate with no lexicon: say what it costs.
                        archives.additive.isNotEmpty() -> {
                            skipped = archives.additive.size
                            logger.error(
                                "Compiled lexicon PRIMARY at {} — {}: the declared layer is EMPTY, and the " +
                                    "additive archive(s) {} are NOT served — an additive archive is never served " +
                                    "without the primary (ttr-server#209)",
                                inputs.location(f.path),
                                f.reason,
                                archives.additive.map(inputs::location),
                            )
                        }
                        // Absent at boot is the pre-RV estate with no lexicon: say nothing, as before.
                        f.reason != "absent" ->
                            logger.warn(
                                "Compiled lexicon at {} — {}",
                                inputs.location(f.path),
                                f.reason,
                            )
                    }
                    return cached
                }
                is DeclaredLayer.Pass.Served -> p
            }
        for (f in pass.fallbacks) {
            logger.warn(
                "Lexicon archive at {} — {}: serving its last good copy (ttr-server#209)",
                inputs.location(f.path),
                f.reason,
            )
        }
        for (f in pass.skipped) {
            logger.error(
                "Lexicon archive at {} SKIPPED — {}: its words are not served; the rest of the layer is (ttr-server#209)",
                inputs.location(f.path),
                f.reason,
            )
        }
        skipped = pass.skipped.size

        val id = pass.layerId
        cached?.let {
            if (it.archiveId == id) {
                // The same layer; only WHY an additive archive is skipped may have moved.
                return if (it.skipped == pass.skipped) it else it.copy(skipped = pass.skipped).also { c -> cached = c }
            }
        }

        val merged = DeclaredLayer.merge(pass.archives.map { it.lexicon })
        return Loaded(id, merged.lexicon, pass.archives.map { it.path }, pass.skipped).also {
            if (archivePaths.size > 1) reportServed(pass)
            checkSchemaVersion(it.lexicon)
            reportConflicts(merged)
            cached = it
        }
    }

    /** #209 — the degraded state in one line: which archives the new layer serves, and which not. */
    private fun reportServed(pass: DeclaredLayer.Pass.Served) {
        val served = pass.archives.map { inputs.location(it.path) }
        if (pass.skipped.isEmpty()) {
            logger.info("Declared layer: serving {} of {} lexicon archives {}", served.size, pass.configured, served)
        } else {
            logger.warn(
                "Declared layer: serving {} of {} lexicon archives {}; skipped: {}",
                served.size,
                pass.configured,
                served,
                describe(pass.skipped),
            )
        }
    }

    /**
     * #209 — what the merge had to decide, said once per load: the compiler's own conflicts
     * (`RG-LEXC-002`, a per-ref fact two builds disagree on), met across archives instead of inside
     * one, and resolved exactly as one build would have resolved them.
     */
    private fun reportConflicts(merged: DeclaredLayer.Merged) {
        if (merged.methodConflicts.isNotEmpty()) {
            logger.warn(
                "Lexicon archives {} match {} term(s) two ways; kept the compiler's choice: {}",
                named,
                merged.methodConflicts.size,
                merged.methodConflicts.take(MAX_LISTED).joinToString("; "),
            )
        }
        if (merged.factConflicts.isNotEmpty()) {
            logger.warn(
                "Lexicon archives {} disagree on {} per-ref fact(s); kept the first archive's: {}",
                named,
                merged.factConflicts.size,
                merged.factConflicts.take(MAX_LISTED).joinToString("; "),
            )
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
            named,
            found,
            CompiledLexiconHeader.SCHEMA_VERSION,
        )
    }

    private fun describe(failures: List<DeclaredLayer.Failure>): String =
        failures.joinToString("; ") { "${inputs.location(it.path)} (${it.reason})" }

    private companion object {
        /** How many merge conflicts a WARN spells out; the count always covers all of them. */
        const val MAX_LISTED = 5
    }
}
