// SPDX-License-Identifier: Apache-2.0
package org.tatrman.fuzzy.loader

import org.tatrman.ttr.lexicon.CompiledEntry
import org.tatrman.ttr.lexicon.CompiledLexicon
import org.tatrman.ttr.lexicon.MatchMethod
import org.tatrman.ttr.lexicon.SourceTag
import org.tatrman.ttr.lexicon.TargetFacts
import org.tatrman.ttr.lexicon.sha256

/**
 * ttr-server#209 — several compiled lexicon archives, served as ONE declared layer.
 *
 * An estate whose packages come from two sources (Veles' `METADATA_GIT2_*`) gets one archive per
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
 * if either says so. A scalar the archives disagree on is reported in [Merged.factConflicts].
 *
 * ## Twin
 *
 * `services/resolver/.../registry/DeclaredLayer.kt` is the same code. RS-24 keeps the two archive
 * readers conformant by contract until the RO-13 shared lib exists, and both copies are held to the
 * PRODUCER by the same test: two archives merged must equal one archive compiled over both.
 */
internal object DeclaredLayer {
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
                    // Every archive's model, named — the field is provenance, and no reader keys on it.
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
        // non-blank values are a disagreement about the SAME model object, said out loud.
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
            reachedFrom = (a.reachedFrom + b.reachedFrom).distinctBy { it.factRef }.sortedBy { it.factRef },
            nameRef = pick("nameRef", a.nameRef, b.nameRef),
            codeRef = pick("codeRef", a.codeRef, b.codeRef),
            codeFormat = pick("codeFormat", a.codeFormat, b.codeFormat),
            memberVocabulary = a.memberVocabulary || b.memberVocabulary,
        )
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
