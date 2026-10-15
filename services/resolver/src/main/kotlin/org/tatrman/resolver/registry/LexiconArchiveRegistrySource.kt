// SPDX-License-Identifier: Apache-2.0
package org.tatrman.resolver.registry

import org.tatrman.resolver.model.Reach
import org.slf4j.LoggerFactory
import org.tatrman.ttr.lexicon.CompiledEntry
import org.tatrman.ttr.lexicon.CompiledLexicon
import org.tatrman.ttr.lexicon.CompiledLexiconHeader
import org.tatrman.ttr.lexicon.LexiconArchive
import org.tatrman.ttr.lexicon.TargetClass
import org.tatrman.ttr.snapshot.SnapshotId
import org.tatrman.ttr.snapshot.SnapshotReadResult
import org.tatrman.ttr.snapshot.SnapshotReader
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readBytes

/**
 * ✅ **Q-7 (Bora, 2026-08-14): the declared-vocabulary channel is the compiled lexicon archive.**
 *
 * The third implementer of the [RegistrySource] seam its own KDoc always named, and the one that
 * makes RS-24 true rather than aspirational: *"one channel, two consumers — fuzzy loads its
 * candidates from it, the resolver builds its registry from it, **off the SAME snapshot
 * identity**."* Both services now read the **same file**, delivered the same way (olymp renders
 * the archive as a ConfigMap from the `lexicon` Application and mounts it read-only).
 *
 * ⚑ **Why not veles `meta.v1`**, which [LiveMetadataRegistryAdapter]'s KDoc promised: `ListObjects`
 * returns `ObjectDescriptor`, which carries `kind`/`semantics_kind` but **no aliases** — those
 * hang off the per-object *detail* reads, so anchors that way mean an N+1 sweep over the whole
 * model at boot, to obtain terms this file already carries **with the authored match method
 * attached**. The options paper is `project/kantheon/features/turn-gate/implementation/q7-registry-channel.md`.
 *
 * ✅ **It DOES deliver `objectKind` — since MS-P2·S2.** The artifact states a `TargetClass`
 * (`MODEL_OBJECT | MEMBER | OPERATOR | GROUNDING_TRIGGER`) — the *class* — and, from
 * `ttr-lexicon-compiled/v2`, a per-ref `targets` map carrying the model fact the resolver's
 * `objectKind` wants: `measure | attribute | entity | entity_with_measures` (MS contracts §5),
 * plus the member's `ownerRef`. It is derived by `MentionKinds` at COMPILE time from the E-R
 * model's declared mention facet (`semantics { name: · code: · measures: [...] }`), so
 * `FrameRoles` R2 (*object_kind == measure → MEASURE*) now fires for an estate that declares
 * measures — the half of tatrman-server#69 that was open by design.
 *
 * ⛔ **The old warning survives, and is the rule of this file:** do not "fix" anything by
 * deriving a kind from the ref's prefix here. That would be a second rule, free to drift from
 * the model's own. What happens below is a lookup and a copy.
 *
 * ## Two readers, one producer
 *
 * This duplicates `services/lex-matcher/.../loader/LexiconArchiveSource` rather than importing it
 * — RS-24 is explicit that until the RO-13 shared lib exists, *"each side declares the shape and
 * the conformance is by contract, not a shared import (no service→service coupling)"*. The
 * conformance is held by both tests packing their fixture with the **real `LexiconPacker`**, so
 * the producer's layout is what binds them. ⚑ **RO-13 (a shared `lexicon-snapshot` lib) is the
 * standing follow-up**, and it is now more attractive than it was: this is the second consumer,
 * and "two readers kept aligned by contract" is the arrangement that let the empty-vocabulary
 * defect hide for months.
 *
 * **Missing or unreadable is not fatal**, exactly as on the lex-matcher side: an estate that has
 * authored no lexicon is a supported deployment, and this reader then reports the same empty
 * vocabulary the stub did — but loudly, which is the whole point of the change.
 *
 * ## Several archives, one layer (ttr-server#209)
 *
 * [archivePaths] may name several archives — one per source the estate's packages come from — and
 * they load as ONE declared layer ([DeclaredLayer]): exactly what one build over all of those
 * packages would have packed, so a form two packages declare gets both readings and the stdlib
 * rows every archive embeds are served once. One path is the pre-#209 reader, byte for byte: same
 * hash, same vocabulary, no merge.
 *
 * ⛔ **All or none.** The archives are read as a SET: if any one is absent, unreadable, not a
 * lexicon, undecodable, or declares a different `schemaVersion` from the others, the set is
 * refused — WARNed once, naming the archive — and the layer keeps what it had loaded (nothing, at
 * boot). Serving the readable half would make the other source's vocabulary vanish with nothing
 * but a log line to say so, which is the exact failure #209 exists to end.
 */
class LexiconArchiveRegistrySource(
    private val archivePaths: List<Path>,
) : RegistrySource {
    constructor(archivePath: Path) : this(listOf(archivePath))

    init {
        require(archivePaths.isNotEmpty()) { "LexiconArchiveRegistrySource needs at least one archive path" }
    }

    private val logger = LoggerFactory.getLogger(LexiconArchiveRegistrySource::class.java)

    /** How the archives are named in a log line: the path, or the list of them. */
    private val named: String = archivePaths.singleOrNull()?.toString() ?: archivePaths.joinToString(", ", "[", "]")

    @Volatile
    private var cached: Loaded? = null

    /** Distinct WARN causes already reported, so a broken archive logs once and not per tick. */
    private val reported =
        java.util.concurrent.ConcurrentHashMap
            .newKeySet<String>()

    private data class Loaded(
        val archiveId: String,
        val lexicon: CompiledLexicon,
    )

    /**
     * The **archive** id — what the snapshot cache in [SnapshotRegistry] compares. Deliberately
     * the file's content id and not [CompiledLexicon.contentHash]: this answers *"is the file on
     * disk a different file?"*, and it must move when anything in the archive moves.
     *
     * With several archives it is [DeclaredLayer.layerId] over all of their ids, so a change to
     * ANY one of them is a new snapshot (#209); with one it is that archive's id, unchanged.
     *
     * Empty string when there is no readable archive — a stable value, so an absent lexicon does
     * not make the registry re-project on every call.
     */
    override fun hash(): String = load()?.archiveId ?: ""

    /**
     * Entries grouped by target ref, which **is** the category key — the convention the declared
     * layer already uses (`er.entity.catalog_sales`, `md.measure.net`) and the one
     * [SnapshotRegistry.project] keys on.
     *
     * ⚑ **`MODEL_OBJECT` rows only, and this is a decision rather than a filter.**
     * [org.tatrman.resolver.model.ResolverEntityType.anchors] are *"the declared anchor words …
     * that Q-20's anchored span proposal ties content subtrees to"* — words naming a model
     * OBJECT. The other four classes are not that and must not become anchors:
     *
     *  - **`MEMBER`** is the value layer (RV-2). Every member literal becoming an anchor token
     *    would change phrase building for every question mentioning one — `anchorTokens` blocks a
     *    word from being folded into a sibling's phrase, so member vocabulary would start
     *    fragmenting ordinary noun phrases.
     *  - **`OPERATOR`** is deliberately left alone here. Q-20 cut over-generation from 33 spurious
     *    binds to 0; widening proposal as a side effect of a plumbing change is exactly how that
     *    result gets lost. Operator words reach the lattice another way since ttr-server#58: the
     *    pipeline asks the matcher about the words in an operator slot, in the operator class only,
     *    and makes a confirmed one an anchor for that one question (`OperatorWords`).
     *  - **`GROUNDING_TRIGGER`** already has its own annotation path (`GroundingTriggers`).
     *  - **`STRING_PREDICATE`** (LP §3.4) is the comparison, not either side of it. It is also the
     *    class most likely to WIN its span — *obsahující* is a whole authored word — so letting it
     *    anchor would tie a content subtree to a preposition-shaped verb. It reaches the lattice
     *    through the same round as any other row and is refused at accept, in `Bindings`.
     *
     * The terms keep their diacritics as authored: `SpanProposal` folds when it builds its anchor
     * index, and folding twice would lose the authored form for no gain.
     *
     * ✅ **MV (member-vocabulary contracts §5.2) — plus one entry per MEMBER VOCABULARY, terms or
     * not.** A v5 archive lists every indexed attribute in `targets` with `memberVocabulary`, and
     * most have no term (nobody writes an alias for `store.state`). Such an entry carries no
     * [DeclaredVocabularyEntry.values] — so no anchors, which keeps the `MEMBER` argument above
     * intact — but it registers the category its values are indexed under, with the entity that
     * owns it. Before this, a governed value under `store` could not reach `store.state` by any
     * path, and even the open lookup never asked for it (MV-T3 T1 measured both).
     */
    override suspend fun fetch(): DeclaredVocabulary {
        val lexicon = load()?.lexicon ?: return DeclaredVocabulary()

        val rowsByRef =
            lexicon.entries
                .filter { it.targetClass == TargetClass.MODEL_OBJECT }
                .groupBy { it.targetRef }
        val memberRefs = lexicon.targets.filterValues { it.memberVocabulary }.keys
        val entries =
            (rowsByRef.keys + memberRefs)
                .map { targetRef ->
                    val rows = rowsByRef[targetRef].orEmpty()
                    // MS: a lookup and a copy. A ref the archive declares nothing about — a
                    // pre-v3 archive, an md-backed estate, a ref the model does not contain —
                    // yields nulls, and "" is the correct reading of "nothing declared".
                    val facts = lexicon.targets[targetRef]
                    DeclaredVocabularyEntry(
                        category = targetRef,
                        targetRef = targetRef,
                        values = rows.map { it.toDeclaredValue() },
                        objectKind = facts?.objectKind ?: "",
                        ownerRef = facts?.ownerRef ?: "",
                        // MH: the same lookup-and-copy. A pre-v3 archive has no `reachedFrom`
                        // at all and a v3 one may legitimately have none for this ref; both
                        // read as the empty list, which leaves the Binder's rule inert.
                        reachedFrom = facts?.reachedFrom.orEmpty().map { Reach(it.factRef, it.mandatory) },
                        // LP (⚑LPQ-5, now closed): the third lookup-and-copy. Until v4 the archive
                        // had no field for this at all, so a quoted literal on an archive-fed
                        // estate landed HEADLESS — attributable only when a caller supplied a
                        // per-request `Registry` override. A pre-v4 archive still reads as "",
                        // which is the same headless behaviour, unchanged.
                        nameRef = facts?.nameRef ?: "",
                        codeRef = facts?.codeRef ?: "",
                        codeFormat = facts?.codeFormat ?: "",
                        // MV: the fourth lookup-and-copy. A pre-v5 archive decodes it as false.
                        memberVocabulary = facts?.memberVocabulary ?: false,
                    )
                }.sortedBy { it.category }

        // ⛑ The line whose absence is the whole issue: an empty vocabulary looks exactly like a
        // small one, so the size is stated every time it is projected rather than inferred.
        logger.info(
            "declared vocabulary from {}: {} entity type(s) ({} member vocabularies), {} anchor(s), " +
                "from {} archive entries ({} non-object rows excluded)",
            named,
            entries.size,
            entries.count { it.memberVocabulary },
            entries.sumOf { it.values.size },
            lexicon.entries.size,
            lexicon.entries.count { it.targetClass != TargetClass.MODEL_OBJECT },
        )
        return DeclaredVocabulary(entries)
    }

    /** `id` must be stable and unique per row — term + lang, since one term may serve two. */
    private fun CompiledEntry.toDeclaredValue(): DeclaredValue =
        DeclaredValue(id = "lex:$targetRef:$lang:$termNormalized", value = termNormalized)

    /**
     * Reads and parses, reusing the cache when the bytes hash to what is already loaded.
     *
     * Returns null — never throws — for absent, unreadable, wrong-kind or malformed archives, each
     * logged once per distinct cause. A broken lexicon should be loud in the log and invisible to
     * a query, not the reverse.
     *
     * #209: every archive is read on every call (the same per-file read the single reader always
     * did), and the set is the unit — one bad archive keeps the previously loaded layer whole.
     */
    private fun load(): Loaded? {
        val reads = archivePaths.map { path -> read(path) ?: return cached }

        val id = DeclaredLayer.layerId(reads.map { it.second })
        cached?.let { if (it.archiveId == id) return it }

        val lexicons = reads.map { (path, _, bytes) -> decode(path, bytes) ?: return cached }

        // #209 — one layer is one schema. The archives' sources are built with ONE pinned toolchain
        // for exactly this reason; two versions mean one source was rebuilt and the other not, and
        // merging them would serve a layer whose rows disagree about which fields exist.
        val schemas = archivePaths.zip(lexicons).groupBy({ it.second.header.schemaVersion }, { it.first })
        if (schemas.size > 1) {
            warnOnce(
                "schema-mixed",
                "lexicon archives {} declare different schemas ({}) — refusing the set; the declared " +
                    "layer keeps what it had loaded. Rebuild every source's archive with the same " +
                    "toolchain (ttr-server#209).",
                named,
                schemas.entries.joinToString("; ") { (schema, paths) -> "$schema: ${paths.joinToString()}" },
            )
            return cached
        }

        val merged = DeclaredLayer.merge(lexicons)
        return Loaded(id, merged.lexicon).also {
            cached = it
            // Cleared BEFORE the version check, not after: `warnOnce` dedups by cause, and
            // clearing afterwards would drop the very key just recorded — so a file that
            // never changes would re-warn on every reload.
            reported.clear()
            checkSchemaVersion(it.lexicon)
            reportConflicts(merged)
        }
    }

    /** One archive's bytes and content id, or null (WARNed once) when it cannot be read. */
    private fun read(path: Path): Triple<Path, String, ByteArray>? {
        if (!path.exists()) {
            warnOnce(
                "absent:$path",
                if (archivePaths.size == 1) {
                    "no compiled lexicon at {} — the declared vocabulary is EMPTY"
                } else {
                    "no compiled lexicon at {} — refusing the archive set; the declared layer keeps what it had loaded"
                },
                path,
            )
            return null
        }
        val bytes =
            try {
                path.readBytes()
            } catch (e: Exception) {
                warnOnce("unreadable:$path", "compiled lexicon at {} is unreadable: {}", path, e.message)
                return null
            }
        return Triple(path, SnapshotId.of(bytes), bytes)
    }

    /** One archive's lexicon, or null (WARNed once) when it is not a readable lexicon archive. */
    private fun decode(
        path: Path,
        bytes: ByteArray,
    ): CompiledLexicon? {
        val contents =
            when (val read = SnapshotReader.read(bytes)) {
                is SnapshotReadResult.Ok -> read.contents
                is SnapshotReadResult.Failure -> {
                    warnOnce(
                        "unarchivable:$path",
                        "compiled lexicon at {} is not a readable archive: {}",
                        path,
                        read.reason,
                    )
                    return null
                }
            }

        if (contents.manifest.kind != LexiconArchive.KIND) {
            // A model snapshot pointed at the lexicon slot would otherwise load as an empty
            // vocabulary and read as "the estate authored nothing" — the exact failure mode this
            // whole change exists to end.
            warnOnce(
                "wrong-kind:$path",
                "archive at {} is kind='{}', expected '{}' — not a lexicon archive",
                path,
                contents.manifest.kind,
                LexiconArchive.KIND,
            )
            return null
        }

        val json =
            contents.docs[LexiconArchive.LEXICON] ?: run {
                warnOnce("no-doc:$path", "lexicon archive at {} has no {}", path, LexiconArchive.LEXICON)
                return null
            }

        return try {
            CompiledLexicon.fromJson(json)
        } catch (e: Exception) {
            warnOnce(
                "undecodable:$path",
                "lexicon archive at {} has an undecodable {}: {}",
                path,
                LexiconArchive.LEXICON,
                e.message,
            )
            null
        }
    }

    /**
     * #209 — what the merge had to decide, said once per load. Both are the compiler's own
     * conflicts (`RG-LEXC-002`, a per-ref fact two builds disagree on), met across archives
     * instead of inside one; the merge resolved them exactly as one build would have.
     */
    private fun reportConflicts(merged: DeclaredLayer.Merged) {
        if (merged.methodConflicts.isNotEmpty()) {
            warnOnce(
                "merge-methods",
                "lexicon archives {} match {} term(s) two ways; kept the compiler's choice: {}",
                named,
                merged.methodConflicts.size,
                merged.methodConflicts.take(MAX_LISTED).joinToString("; "),
            )
        }
        if (merged.factConflicts.isNotEmpty()) {
            warnOnce(
                "merge-facts",
                "lexicon archives {} disagree on {} per-ref fact(s); kept the first archive's: {}",
                named,
                merged.factConflicts.size,
                merged.factConflicts.take(MAX_LISTED).joinToString("; "),
            )
        }
    }

    /**
     * MS-P2·S2 (contracts §6) — the version check this reader never had.
     *
     * review-082 F2: neither serving reader read `schemaVersion` at all, so an archive from a
     * producer this build has never heard of arrived as a generic *"undecodable"* — or, worse,
     * decoded into something subtly wrong. The WARN names BOTH versions, because the useful
     * question in a cluster is *which* side is behind.
     *
     * ⛑ It **reads the archive anyway**. Refusing on a version mismatch would blank the estate's
     * declared vocabulary — precisely the degrade (review-082 F1) that makes this whole class of
     * problem silent. A mismatch is a reason to say so out loud, never a reason to serve nothing.
     */
    private fun checkSchemaVersion(lexicon: CompiledLexicon) {
        val found = lexicon.header.schemaVersion
        if (found == CompiledLexiconHeader.SCHEMA_VERSION) return
        warnOnce(
            "schema-version",
            "lexicon archive at {} declares schema '{}'; this reader was built against '{}'. " +
                "Reading it anyway — unknown fields are ignored — but a field this reader needs " +
                "may be absent, and an OLDER reader than the producer cannot be fixed by config: " +
                "roll the readers first (MS contracts §6, readers before producers).",
            named,
            found,
            CompiledLexiconHeader.SCHEMA_VERSION,
        )
    }

    private companion object {
        /** How many merge conflicts a WARN spells out; the count always covers all of them. */
        const val MAX_LISTED = 5
    }

    private fun warnOnce(
        cause: String,
        message: String,
        vararg args: Any?,
    ) {
        if (reported.add(cause)) logger.warn(message, *args)
    }
}
