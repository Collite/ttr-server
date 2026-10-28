// SPDX-License-Identifier: Apache-2.0
package org.tatrman.fuzzy.loader

import com.typesafe.config.Config
import com.typesafe.config.ConfigValueType
import org.tatrman.ttr.lexicon.CompiledEntry
import org.tatrman.ttr.lexicon.CompiledLexicon
import org.tatrman.ttr.lexicon.ConceptLift
import org.tatrman.ttr.lexicon.LexiconArchive
import org.tatrman.ttr.lexicon.MatchMethod
import org.tatrman.ttr.lexicon.Reach
import org.tatrman.ttr.lexicon.SourceTag
import org.tatrman.ttr.lexicon.TargetFacts
import org.tatrman.ttr.lexicon.sha256
import org.tatrman.ttr.snapshot.SnapshotId
import org.tatrman.ttr.snapshot.SnapshotReadResult
import org.tatrman.ttr.snapshot.SnapshotReader
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.readBytes

/**
 * ttr-server#209 — several compiled lexicon archives, served as ONE declared layer.
 *
 * An estate whose packages come from two sources (the metadata service's `METADATA_GIT2_*`) gets one archive per
 * source: each source's own model gate builds and publishes it, and the deployment mounts all of
 * them. This object is where they become one layer again, and the bar it is held to is exact:
 * **the merge of the archives equals the archive ONE build over all of those packages packs** —
 * the same rows, in the same order, so the same entry-table hash. Nothing downstream can tell a
 * two-source estate from a one-source one.
 *
 * ## The merge rule is the compiler's, not a new one
 *
 * `LexiconCompiler.merge` collapses its rows to one per **(normalized term, lang, target ref)** and
 * picks the survivor by **DECLARED over METADATA, then the widest method, then the earliest
 * provenance**. The same key and the same precedence apply here, across archives. That one rule
 * covers both cases a merge meets:
 *
 *  - **Identical rows** — every archive embeds the stdlib (the `op:` operator words, the `ground:`
 *    trigger slices, the `pred:` predicate slices), so two archives carry the same stdlib row twice.
 *    They collapse to one: a duplicate would add a second candidate with the same id to the same
 *    category, which changes the matcher's token statistics and therefore its scores.
 *  - **The same identity matched two ways** (one source says `TYPOS(1)`, the other `EXACT`) — the
 *    compiler's `RG-LEXC-002` case. The precedence picks the winner exactly as one build would, and
 *    the disagreement is returned in [Merged.methodConflicts] so the reader can say so.
 *
 * A form declared by two packages for two DIFFERENT refs is not a duplicate: the identity includes
 * the ref, so both rows survive and the form gets both readings — which is the point of #209.
 *
 * ## Per-ref facts
 *
 * `targets` is keyed by ref. A ref present in one archive is copied. A ref in two (a package both
 * sources build against) keeps the first archive's scalar facts and takes the UNION of `reachedFrom`
 * — a build over both models would see every relation that reaches the ref — and `memberVocabulary`
 * if either says so. The v6 concept facts follow the same rules (CN-P4 S4.3): `stereotype`,
 * `erTarget`, `via` and `ofRef` are scalar facts, and `liftsTo` is the UNION by concept — a build over
 * both models would see every concept grounded on the element. Every field of `TargetFacts` is named
 * in [union]: one left out is a fact a two-archive estate silently drops.
 *
 * **One winner rule for every per-ref fact: the first archive that states it** — the primary, then
 * the additive archives in declared order — and every disagreement is reported in
 * [Merged.factConflicts]. That includes a `reachedFrom` entry both archives carry for the same
 * `factRef` with a different `mandatory`: it is a disagreement about the SAME relation, and
 * `mandatory` is what the Binder's T3 reachability collapse decides on, so it must never be dropped
 * silently. (Inside one build the compiler keeps the first relation per `factRef`; across archives
 * the first archive's is kept, and the loss is said out loud.)
 *
 * ## Which archives are served: the primary, and additive ones that degrade alone
 *
 * [ArchiveSet] reads the configured archives on every pass. The FIRST is the **primary** — the
 * estate's own archive — and keeps the single-archive posture: if it cannot be read, the pass says
 * so ([Pass.PrimaryFailed]) and the reader keeps the layer it had loaded (nothing, at boot). Every
 * later archive is **additive**: one that is absent, unreadable, not a lexicon, undecodable, or
 * declares a different `schemaVersion` from the primary's is served from its LAST GOOD copy when it
 * has one (a transient failure never drops words that were being served), and otherwise skipped —
 * so a broken or drifted side archive costs its own words, never the estate's. [Pass.Served.layerId]
 * covers exactly the archives served, in declared order, so the served version shows the degraded
 * state.
 *
 * ## Twin
 *
 * This file exists twice, as the SAME code: `services/resolver/.../registry/DeclaredLayer.kt` and
 * `services/lex-matcher/.../loader/DeclaredLayer.kt`. RS-24 keeps the two archive readers conformant
 * by contract (no service→service import) until the RO-13 shared lib exists. Two things hold them:
 *
 *  - both copies are held to the PRODUCER by the same test: two archives merged must equal one
 *    archive compiled over both;
 *  - the resolver's `verifyDeclaredLayerTwin` task (part of `check`) fails the build when the two
 *    files differ in anything but their `package` line. Edit one, copy it to the other.
 *
 * [PRECEDENCE], [breadth] and [ENTRY_ORDER] restate `LexiconCompiler`'s private rule, read off the
 * wire. The producer-side home for this merge — a public `CompiledLexicon.merge` next to the
 * compiler's precedence, so a serving merge cannot drift from a single build — is asked for in
 * Collite/ttr-core#190.
 */
internal object DeclaredLayer {
    /** One archive's read: decoded, or why not. */
    sealed interface Read

    /** One archive as read: its path, the content id of its bytes, and its decoded lexicon. */
    data class Archive(
        val path: Path,
        val id: String,
        val lexicon: CompiledLexicon,
    ) : Read {
        val schema: String get() = lexicon.header.schemaVersion
    }

    /** An archive that could not be served this pass, and why. */
    data class Failure(
        val path: Path,
        val reason: String,
    ) : Read

    /** What one pass over the configured archives decided. */
    sealed interface Pass {
        /** The primary could not be read: keep what was loaded. */
        data class PrimaryFailed(
            val failure: Failure,
        ) : Pass

        data class Served(
            /** Primary first, then every additive archive served, in declared order. */
            val archives: List<Archive>,
            /** Additive archives served from their last good copy, with why the fresh read failed. */
            val fallbacks: List<Failure>,
            /** Additive archives not served at all — no last good copy to fall back to. */
            val skipped: List<Failure>,
            /** How many archives are configured. */
            val configured: Int,
        ) : Pass {
            val layerId: String get() = layerId(archives.map { it.id })
        }
    }

    /**
     * The configured archives and what each last read as. Thread-safe: a concurrent pass re-reads
     * the same files and reaches the same answer.
     */
    class ArchiveSet(
        val paths: List<Path>,
        private val readBytes: (Path) -> ByteArray = { it.readBytes() },
    ) {
        init {
            require(paths.isNotEmpty()) { "a declared layer needs at least one archive path" }
        }

        /** The last copy of each additive archive that was SERVED — what a failed pass falls back to. */
        private val lastGood = ConcurrentHashMap<Path, Archive>()

        /** The last decode of each path, so unchanged bytes are never parsed twice. */
        private val decoded = ConcurrentHashMap<Path, Archive>()

        /** The last failure of each path by content id, so a broken file is not re-parsed per call. */
        private val failed = ConcurrentHashMap<Path, Pair<String, String>>()

        fun pass(): Pass {
            val primary =
                when (val read = read(paths.first())) {
                    is Archive -> read
                    is Failure -> return Pass.PrimaryFailed(read)
                }
            val served = mutableListOf(primary)
            val fallbacks = mutableListOf<Failure>()
            val skipped = mutableListOf<Failure>()
            for (path in paths.drop(1)) {
                val failure =
                    when (val read = read(path)) {
                        is Failure -> read
                        is Archive ->
                            if (read.schema == primary.schema) {
                                lastGood[path] = read
                                served += read
                                null
                            } else {
                                Failure(
                                    path,
                                    "declares schema '${read.schema}', the primary declares '${primary.schema}'",
                                )
                            }
                    } ?: continue
                val last = lastGood[path]?.takeIf { it.schema == primary.schema }
                if (last != null) {
                    served += last
                    fallbacks += failure
                } else {
                    skipped += failure
                }
            }
            return Pass.Served(served, fallbacks, skipped, paths.size)
        }

        /** The additive archives — every configured one after the primary. */
        val additive: List<Path> get() = paths.drop(1)

        /** One archive, decoded — or why not. Never throws. */
        private fun read(path: Path): Read {
            val bytes =
                try {
                    readBytes(path)
                } catch (e: java.nio.file.NoSuchFileException) {
                    return Failure(path, "absent")
                } catch (e: Exception) {
                    return Failure(path, "unreadable: ${e.message}")
                }
            val id = SnapshotId.of(bytes)
            decoded[path]?.let { if (it.id == id) return it }
            failed[path]?.let { (failedId, reason) -> if (failedId == id) return Failure(path, reason) }
            return when (val outcome = decode(path, id, bytes)) {
                is Archive -> outcome.also { decoded[path] = it }
                is Failure -> outcome.also { failed[path] = id to it.reason }
            }
        }

        private fun decode(
            path: Path,
            id: String,
            bytes: ByteArray,
        ): Read {
            val contents =
                when (val read = SnapshotReader.read(bytes)) {
                    is SnapshotReadResult.Ok -> read.contents
                    is SnapshotReadResult.Failure -> return Failure(path, "not a readable archive: ${read.reason}")
                }
            // A model snapshot pointed at the lexicon slot would otherwise load as an empty
            // vocabulary and read as "the estate authored nothing".
            if (contents.manifest.kind != LexiconArchive.KIND) {
                return Failure(
                    path,
                    "kind='${contents.manifest.kind}', expected '${LexiconArchive.KIND}' — not a lexicon archive",
                )
            }
            val json = contents.docs[LexiconArchive.LEXICON] ?: return Failure(path, "no ${LexiconArchive.LEXICON}")
            return try {
                Archive(path, id, CompiledLexicon.fromJson(json))
            } catch (e: Exception) {
                Failure(path, "undecodable ${LexiconArchive.LEXICON}: ${e.message}")
            }
        }
    }

    /** Failures as one log fragment: `<path> (<reason>); …`. */
    fun describe(failures: List<Failure>): String = failures.joinToString("; ") { "${it.path} (${it.reason})" }

    /**
     * A list of archive paths from config: a HOCON list, or a comma-separated string (how a list
     * arrives from the environment). Blanks dropped, so an unset `${?VAR}` reads as empty. Both
     * readers parse their `…archive-paths` key with this one function.
     */
    fun pathList(
        config: Config,
        key: String,
    ): List<String> {
        if (!config.hasPath(key)) return emptyList()
        val raw =
            when (config.getValue(key).valueType()) {
                ConfigValueType.LIST -> config.getStringList(key)
                else -> config.getString(key).split(',')
            }
        return raw.map { it.trim() }.filter { it.isNotEmpty() }
    }

    /** What a merge produced, plus everything the reader should say out loud. */
    data class Merged(
        val lexicon: CompiledLexicon,
        val methodConflicts: List<String> = emptyList(),
        val factConflicts: List<String> = emptyList(),
    )

    /**
     * The layer's identity: the archive id itself for one archive — so a single-archive deployment
     * reports exactly the snapshot hash it always did — and for several a `sha256:` over their ids
     * in declared order, so a change to ANY archive is a new snapshot.
     */
    fun layerId(archiveIds: List<String>): String =
        archiveIds.singleOrNull()
            ?: sha256(("$LAYER_ID_DOMAIN\n" + archiveIds.joinToString("\n")).toByteArray(Charsets.UTF_8))

    fun merge(lexicons: List<CompiledLexicon>): Merged {
        require(lexicons.isNotEmpty()) { "a declared layer needs at least one archive" }
        if (lexicons.size == 1) return Merged(lexicons.single())

        val methodConflicts = mutableListOf<String>()
        val byIdentity = LinkedHashMap<Triple<String, String, String>, MutableList<IndexedValue<CompiledEntry>>>()
        lexicons.forEachIndexed { archive, lexicon ->
            for (entry in lexicon.entries) {
                byIdentity.getOrPut(Triple(entry.termNormalized, entry.lang, entry.targetRef)) { mutableListOf() } +=
                    IndexedValue(archive, entry)
            }
        }
        val entries =
            byIdentity
                .map { (key, rows) ->
                    val winner = rows.sortedWith(PRECEDENCE).first().value
                    if (rows.any { it.value.method != winner.method || it.value.matchProfile != winner.matchProfile }) {
                        methodConflicts +=
                            "\"${key.first}\" (${key.second}) → ${key.third}: " +
                            rows.joinToString(" vs ") { "${it.value.method} in archive ${it.index + 1}" } +
                            "; kept ${winner.method}"
                    }
                    // v6 (review-130 R13) — the row's sense (§1.5 steps 3–4) is a per-row fact too: two archives
                    // that disagree on it decide the ordering by precedence, so it is reported like the method.
                    if (rows.any {
                            it.value.defaultSense != winner.defaultSense ||
                                it.value.sensePriority != winner.sensePriority
                        }
                    ) {
                        methodConflicts +=
                            "\"${key.first}\" (${key.second}) → ${key.third}: sense " +
                            rows.joinToString(" vs ") { "${sense(it.value)} in archive ${it.index + 1}" } +
                            "; kept ${sense(winner)}"
                    }
                    winner
                }.sortedWith(ENTRY_ORDER)

        val factConflicts = mutableListOf<String>()
        val targets = LinkedHashMap<String, TargetFacts>()
        for (lexicon in lexicons) {
            for ((ref, facts) in lexicon.targets) {
                val existing = targets[ref]
                targets[ref] = if (existing == null) facts else union(ref, existing, facts, factConflicts)
            }
        }

        val first = lexicons.first().header
        return Merged(
            lexicon =
                CompiledLexicon(
                    // Every archive's model, named, comma-joined. The resolver's cn doors read it: they serve a model
                    // tree only when it is one of these (`ConceptModels`).
                    header = first.copy(modelSnapshotHash = lexicons.joinToString(",") { it.header.modelSnapshotHash }),
                    entries = entries,
                    targets = targets.toSortedMap(),
                ),
            methodConflicts = methodConflicts,
            factConflicts = factConflicts,
        )
    }

    private fun union(
        ref: String,
        a: TargetFacts,
        b: TargetFacts,
        conflicts: MutableList<String>,
    ): TargetFacts {
        if (a == b) return a

        // The first archive's value wins; a blank one is filled from the second. Two different
        // non-blank values are a disagreement about the SAME model object, said out loud. One rule
        // for every per-ref fact, `reachedFrom[*].mandatory` included (see the class KDoc).
        fun pick(
            field: String,
            x: String?,
            y: String?,
        ): String? {
            if (!x.isNullOrEmpty() && !y.isNullOrEmpty() && x != y) conflicts += "$ref.$field: '$x' vs '$y'; kept '$x'"
            return if (x.isNullOrEmpty()) y ?: x else x
        }
        return TargetFacts(
            objectKind = pick("objectKind", a.objectKind, b.objectKind).orEmpty(),
            ownerRef = pick("ownerRef", a.ownerRef, b.ownerRef),
            reachedFrom = reach(ref, a.reachedFrom, b.reachedFrom, conflicts),
            nameRef = pick("nameRef", a.nameRef, b.nameRef),
            codeRef = pick("codeRef", a.codeRef, b.codeRef),
            codeFormat = pick("codeFormat", a.codeFormat, b.codeFormat),
            memberVocabulary = a.memberVocabulary || b.memberVocabulary,
            stereotype = pick("stereotype", a.stereotype, b.stereotype),
            erTarget = pick("erTarget", a.erTarget, b.erTarget),
            via = pick("via", a.via, b.via),
            ofRef = pick("ofRef", a.ofRef, b.ofRef),
            liftsTo = lifts(ref, a.liftsTo, b.liftsTo, conflicts),
        )
    }

    /**
     * The union of two `reachedFrom` lists by `factRef`, sorted as the compiler sorts it. A `factRef`
     * both carry keeps [a]'s `mandatory` (the first archive's), and a different value in [b] is a
     * conflict like any other per-ref fact.
     */
    private fun reach(
        ref: String,
        a: List<Reach>,
        b: List<Reach>,
        conflicts: MutableList<String>,
    ): List<Reach> {
        val byFact = LinkedHashMap<String, Reach>()
        for (r in a) byFact.putIfAbsent(r.factRef, r)
        for (r in b) {
            val kept = byFact.putIfAbsent(r.factRef, r) ?: continue
            if (kept.mandatory != r.mandatory) {
                conflicts +=
                    "$ref.reachedFrom[${r.factRef}].mandatory: '${kept.mandatory}' vs '${r.mandatory}'; " +
                    "kept '${kept.mandatory}'"
            }
        }
        return byFact.values.sortedBy { it.factRef }
    }

    /** v6 — a row's sense, as a merge conflict names it. */
    private fun sense(e: CompiledEntry): String = "default=${e.defaultSense} priority=${e.sensePriority}"

    /**
     * CN-P4 (v6) — the union of two `liftsTo` lists by concept, sorted by concept as the compiler sorts
     * it. A concept both carry keeps [a]'s `via` (the first archive's), and a different one in [b] is a
     * conflict like any other per-ref fact: `via` is how the concept lands, which the resolver's lift
     * reports.
     */
    private fun lifts(
        ref: String,
        a: List<ConceptLift>,
        b: List<ConceptLift>,
        conflicts: MutableList<String>,
    ): List<ConceptLift> {
        val byConcept = LinkedHashMap<String, ConceptLift>()
        for (l in a) byConcept.putIfAbsent(l.qname, l)
        for (l in b) {
            val kept = byConcept.putIfAbsent(l.qname, l) ?: continue
            if (kept.via != l.via) {
                conflicts += "$ref.liftsTo[${l.qname}].via: '${kept.via}' vs '${l.via}'; kept '${kept.via}'"
            }
        }
        return byConcept.values.sortedBy { it.qname }
    }

    /** How much a method admits — `LexiconCompiler.breadth`, read off the wire. Unknown ranks last. */
    private fun breadth(wire: String): Int =
        when (val method = MatchMethod.ofWire(wire)) {
            is MatchMethod.Typos -> 10 + method.maxDistance
            MatchMethod.Tokens -> 5
            MatchMethod.Exact -> 0
            null -> -1
        }

    /** `LexiconCompiler.PRECEDENCE`, then the archive's declared position as the final tie-break. */
    private val PRECEDENCE =
        compareBy<IndexedValue<CompiledEntry>>(
            { if (it.value.sourceTag == SourceTag.DECLARED) 0 else 1 },
            { -breadth(it.value.method) },
            { it.value.provenance.file },
            { it.value.provenance.line },
            { it.index },
        )

    /** `LexiconCompiler.ENTRY_ORDER` — the table order one build writes. */
    private val ENTRY_ORDER =
        compareBy<CompiledEntry>({ it.termNormalized }, { it.lang }, { it.targetRef }, { it.method })

    /** Domain-separates a layer id from an archive id, so neither can be mistaken for the other. */
    private const val LAYER_ID_DOMAIN = "ttr-lexicon-layer/v1"
}
