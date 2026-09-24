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
 */
class LexiconArchiveRegistrySource(
    private val archivePath: Path,
) : RegistrySource {
    private val logger = LoggerFactory.getLogger(LexiconArchiveRegistrySource::class.java)

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
     *  - **`OPERATOR`** is tatrman-server#58's territory and is deliberately left alone here. Q-20
     *    cut over-generation from 33 spurious binds to 0; widening proposal as a side effect of a
     *    plumbing change is exactly how that result gets lost.
     *  - **`GROUNDING_TRIGGER`** already has its own annotation path (`GroundingTriggers`).
     *  - **`STRING_PREDICATE`** (LP §3.4) is the comparison, not either side of it. It is also the
     *    class most likely to WIN its span — *obsahující* is a whole authored word — so letting it
     *    anchor would tie a content subtree to a preposition-shaped verb. It reaches the lattice
     *    through the same round as any other row and is refused at accept, in `Bindings`.
     *
     * The terms keep their diacritics as authored: `SpanProposal` folds when it builds its anchor
     * index, and folding twice would lose the authored form for no gain.
     */
    override suspend fun fetch(): DeclaredVocabulary {
        val lexicon = load()?.lexicon ?: return DeclaredVocabulary()

        val entries =
            lexicon.entries
                .filter { it.targetClass == TargetClass.MODEL_OBJECT }
                .groupBy { it.targetRef }
                .map { (targetRef, rows) ->
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
                    )
                }.sortedBy { it.category }

        // ⛑ The line whose absence is the whole issue: an empty vocabulary looks exactly like a
        // small one, so the size is stated every time it is projected rather than inferred.
        logger.info(
            "declared vocabulary from {}: {} entity type(s), {} anchor(s), from {} archive entries " +
                "({} non-object rows excluded)",
            archivePath,
            entries.size,
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
     */
    private fun load(): Loaded? {
        if (!archivePath.exists()) {
            warnOnce("absent", "no compiled lexicon at {} — the declared vocabulary is EMPTY", archivePath)
            return cached
        }

        val bytes =
            try {
                archivePath.readBytes()
            } catch (e: Exception) {
                warnOnce("unreadable", "compiled lexicon at {} is unreadable: {}", archivePath, e.message)
                return cached
            }

        val id = SnapshotId.of(bytes)
        cached?.let { if (it.archiveId == id) return it }

        val contents =
            when (val read = SnapshotReader.read(bytes)) {
                is SnapshotReadResult.Ok -> read.contents
                is SnapshotReadResult.Failure -> {
                    warnOnce(
                        "unarchivable",
                        "compiled lexicon at {} is not a readable archive: {}",
                        archivePath,
                        read.reason,
                    )
                    return cached
                }
            }

        if (contents.manifest.kind != LexiconArchive.KIND) {
            // A model snapshot pointed at the lexicon slot would otherwise load as an empty
            // vocabulary and read as "the estate authored nothing" — the exact failure mode this
            // whole change exists to end.
            warnOnce(
                "wrong-kind",
                "archive at {} is kind='{}', expected '{}' — not a lexicon archive",
                archivePath,
                contents.manifest.kind,
                LexiconArchive.KIND,
            )
            return cached
        }

        val json =
            contents.docs[LexiconArchive.LEXICON] ?: run {
                warnOnce("no-doc", "lexicon archive at {} has no {}", archivePath, LexiconArchive.LEXICON)
                return cached
            }

        return try {
            Loaded(id, CompiledLexicon.fromJson(json)).also {
                cached = it
                // Cleared BEFORE the version check, not after: `warnOnce` dedups by cause, and
                // clearing afterwards would drop the very key just recorded — so a file that
                // never changes would re-warn on every reload.
                reported.clear()
                checkSchemaVersion(it.lexicon)
            }
        } catch (e: Exception) {
            warnOnce(
                "undecodable",
                "lexicon archive at {} has an undecodable {}: {}",
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
            archivePath,
            found,
            CompiledLexiconHeader.SCHEMA_VERSION,
        )
    }

    private fun warnOnce(
        cause: String,
        message: String,
        vararg args: Any?,
    ) {
        if (reported.add(cause)) logger.warn(message, *args)
    }
}
