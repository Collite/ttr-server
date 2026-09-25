// SPDX-License-Identifier: Apache-2.0
package org.tatrman.resolver.registry

import org.tatrman.resolver.model.ResolverEntityType
import org.tatrman.resolver.model.ResolverRegistry
import org.tatrman.resolver.model.ResolverThresholds
import java.util.concurrent.atomic.AtomicReference

/**
 * Builds the resolver's [ResolverRegistry] from a [RegistrySource] snapshot and
 * caches it by the snapshot [RegistrySource.hash] — a hash change reloads (the
 * two-clock refresh, RG-P5.S2.T2). The `snapshot_hash` it stamps flows into every
 * `BindingProvenance` (S-1/one-channel).
 *
 * Projection (RG-P4 `serializeVocabularySnapshot` alignment, RS-9): each declared
 * entry's `category == targetRef` is the entity ref; its declared values are the
 * anchor words for that entity's gating and its fuzzy category. Thresholds/locales
 * come from config (the snapshot carries locales when present).
 *
 * The **caller-supplied per-request `Registry` override still wins** — that merge
 * is the pipeline's job (RS-24); this is only the snapshot-fed default.
 */
class SnapshotRegistry(
    private val source: RegistrySource,
    private val thresholds: ResolverThresholds,
    private val configLocales: List<String> = emptyList(),
) {
    private val cache = AtomicReference<Pair<String, ResolverRegistry>?>(null)

    suspend fun current(): ResolverRegistry {
        val h = source.hash()
        cache.get()?.let { if (it.first == h) return it.second }
        // Check-then-set without a lock: a concurrent first-load may fetch+set twice,
        // but the projection is a pure function of (hash, snapshot) so both writes
        // produce an equal registry — last-write-wins is harmless, and we avoid
        // holding a lock across the suspending fetch().
        val projected = project(source.fetch(), h)
        cache.set(h to projected)
        return projected
    }

    private fun project(
        vocab: DeclaredVocabulary,
        hash: String,
    ): ResolverRegistry {
        // Group by entity ref (category == targetRef) so an entity with several
        // declared categories collapses to one type with the union of categories.
        val byRef = LinkedHashMap<String, MutableList<DeclaredVocabularyEntry>>()
        for (e in vocab.entries) byRef.getOrPut(e.targetRef) { mutableListOf() }.add(e)

        val entityTypes =
            byRef.map { (ref, entries) ->
                ResolverEntityType(
                    ref = ref,
                    categories = entries.map { it.category }.distinct(),
                    anchors = entries.flatMap { it.values.map { v -> v.value } }.distinct(),
                    // MS: verbatim. The grouping above is by targetRef and the facts are per
                    // ref, so every entry in a group carries the same pair — `first` is not a
                    // tie-break, there is nothing to break.
                    objectKind = entries.first().objectKind,
                    ownerRef = entries.first().ownerRef,
                    // MH: same "first is not a tie-break" argument — the facts are per ref and
                    // the grouping is by ref, so every entry in the group carries the same list.
                    reachedFrom = entries.first().reachedFrom,
                    // LP: and again — per-ref facts, grouped by ref.
                    nameRef = entries.first().nameRef,
                    codeRef = entries.first().codeRef,
                    codeFormat = entries.first().codeFormat,
                    // MV: per-ref as well.
                    memberVocabulary = entries.first().memberVocabulary,
                )
            }
        val locales = (vocab.locales + configLocales).distinct()
        return ResolverRegistry(entityTypes, locales, thresholds, hash)
    }
}
