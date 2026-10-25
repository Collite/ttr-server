// SPDX-License-Identifier: Apache-2.0
package org.tatrman.resolver.registry

import org.tatrman.resolver.model.Lift
import org.tatrman.resolver.model.Reach

/**
 * The declared-vocabulary snapshot the registry is built from. These types mirror
 * the RG-P2 `SnapshotVocabularySource` seam
 * (`services/lex-matcher/.../loader/SnapshotVocabularySource.kt`) field-for-field —
 * this is the **one channel, two consumers** contract (RS-24): fuzzy loads its
 * candidates from it, the resolver builds its registry from it, off the SAME
 * snapshot identity ([hash]). The shared physical home (a snapshot lib) is the
 * RO-13 extraction, still pending — until then each side declares the shape and
 * the conformance is by contract, not a shared import (no service→service coupling).
 */
data class DeclaredValue(
    val id: String,
    val value: String,
    /**
     * CN-P4 (cn contracts §1.5, A-CN-8) — the entry's `default: true` and `sense_priority:`, from the
     * v6 archive's row (`CompiledEntry.defaultSense` / `sensePriority`). The ordering reads them at
     * steps 3 and 4. `false` / null on a row that states neither, which is every pre-v6 row.
     */
    val defaultSense: Boolean = false,
    val sensePriority: Double? = null,
)

data class DeclaredVocabularyEntry(
    val category: String,
    val targetRef: String,
    val values: List<DeclaredValue>,
    /**
     * MS (contracts §5/§6) — what [targetRef] IS in the model: `measure` | `attribute` |
     * `entity` | `entity_with_measures`, derived by `MentionKinds` at COMPILE time and carried
     * per ref in the archive's `targets` map. Read here by lookup and copy; `""` when the
     * archive declares nothing for the ref, which is also every pre-v3 archive.
     *
     * ⛔ Never derive this from the ref STRING. The whole point of routing it through the
     * model is that one rule decides, and a second rule reading `md.measure.*` prefixes here
     * would be free to drift from it.
     */
    val objectKind: String = "",
    /** The declaring entity/table's ref for a member; `""` for an owner or an unknown ref. */
    val ownerRef: String = "",
    /**
     * MH — the facts with a declared relation TO [targetRef], from the archive's
     * `targets[ref].reachedFrom` (schema `ttr-lexicon-compiled/v3`). Empty for members, for
     * entities nothing relates to, and for every pre-v3 archive.
     */
    val reachedFrom: List<Reach> = emptyList(),
    /**
     * LP (contracts §2.1) — the MENTION facet: which attribute carries [targetRef] under the
     * aspect a quoted literal is about, as FULL attribute refs, plus the code attribute's declared
     * `code_format:`. From the archive's `targets[ref]` (schema `ttr-lexicon-compiled/v4`); `""`
     * for a member, for an object whose model declares no `semantics { name: · code: }`, and for
     * every pre-v4 archive.
     *
     * ⛔ Same rule as [objectKind]: never derived from the ref string, and never from a column
     * NAMED "name". "The entity's name column" is a declared fact, and the one rule that decides
     * it lives in the model.
     */
    val nameRef: String = "",
    val codeRef: String = "",
    val codeFormat: String = "",
    /**
     * MV (member-vocabulary contracts §5.2) — [targetRef] is an INDEXED attribute/column: it has a
     * member vocabulary, registered under this very ref as its category. From the archive's
     * `targets[ref].memberVocabulary` (schema `ttr-lexicon-compiled/v5`), which lists every indexed
     * attribute whether or not anyone wrote a term for it — so an entry may carry this and no
     * [values] at all. `false` for everything else and for every pre-v5 archive.
     */
    val memberVocabulary: Boolean = false,
    /**
     * CN-P4 (cn contracts §5, v6) — a concept's facts, from the archive's `targets[ref]`: its
     * `stereotype`, its ER landing `erTarget` (an entity, or the attribute for a value kind or a
     * quality), `via` (`direct` | `md-hop`) and its `of:` as `ofRef`. `""` on every element and on every
     * pre-v6 archive. [objectKind] is `concept` for a concept, and [ownerRef] keeps its meaning: the
     * entity around the landing.
     */
    val stereotype: String = "",
    val erTarget: String = "",
    val via: String = "",
    val ofRef: String = "",
    /**
     * CN-P4 (A-CN-4) — the reverse index: the concepts this er or md element lifts to, from
     * `targets[ref].liftsTo`. Empty on a concept, a member, an element nothing is grounded on, and on
     * every pre-v6 archive.
     */
    val liftsTo: List<Lift> = emptyList(),
)

data class DeclaredVocabulary(
    val entries: List<DeclaredVocabularyEntry> = emptyList(),
    val locales: List<String> = emptyList(),
)

/**
 * The seam (RS-24). Three implementers over time, all satisfying this interface
 * (rule 6 — name them, don't invent couplings):
 *  - [StubRegistrySource] — the fixture used until the snapshot archive lands;
 *  - [LiveMetadataRegistryAdapter] — the E3-β *step-one* dev-mode reader off Metadata
 *    (`meta.v1`), the same interface;
 *  - the real snapshot-archive reader (RO-13), later.
 *
 * [hash] is the snapshot identity: the registry reloads only when it changes.
 */
interface RegistrySource {
    suspend fun fetch(): DeclaredVocabulary

    fun hash(): String

    /**
     * CN-P5 S5.1 — the content id of the model this vocabulary was compiled from (a lexicon archive header's
     * `modelSnapshotHash`, several comma-joined for a layer of archives); `""` when the source does not say.
     */
    fun modelSnapshotHash(): String = ""
}

/** A fixed fixture source — the stub used until the snapshot archive lands. */
class StubRegistrySource(
    private val vocabulary: DeclaredVocabulary,
    private val snapshotHash: String,
) : RegistrySource {
    override suspend fun fetch(): DeclaredVocabulary = vocabulary

    override fun hash(): String = snapshotHash
}

/**
 * E3-β step one (RS-24): the dev-mode live-metadata adapter. Named per rule 6 so
 * the coupling to Metadata/`meta.v1` is explicit rather than invented ad hoc. The
 * body (a `meta.v1` read projected into [DeclaredVocabulary]) lands with the
 * capability-matrix work; until then it yields an empty snapshot with a stable
 * hash, so the pipeline runs against a caller-supplied `Registry` override.
 */
class LiveMetadataRegistryAdapter(
    private val snapshotHash: String = "live-metadata:step-one",
) : RegistrySource {
    override suspend fun fetch(): DeclaredVocabulary = DeclaredVocabulary()

    override fun hash(): String = snapshotHash
}
