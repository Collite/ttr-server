// SPDX-License-Identifier: Apache-2.0
package org.tatrman.resolver.concept

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import org.tatrman.concept.CnConceptService
import org.tatrman.concept.ConceptService
import org.tatrman.concept.ResolvedCnModel
import org.tatrman.resolver.model.ResolverRegistry
import java.time.Clock

/**
 * The model one cn door call is answered from: ttr-concept's service over [resolved], pinned to a snapshot.
 *
 * [resolved]'s `snapshotHash` is the identity a frame names (`Statement.snapshot_hash`, `Assertion.snapshot_hash`) and
 * every lower echoes: the resolver's own snapshot hash — the lexicon archive id its bindings carry — when the model is
 * the one that archive was compiled from; the model's content id when no archive says ([modelId]).
 */
class ConceptModel(
    val resolved: ResolvedCnModel,
    val service: ConceptService,
    val modelId: String,
)

/**
 * CN-P5 S5.1 (cn contracts §4, A-CN-34) — the cn doors' [ConceptModel], cached by what it is built from: the model tree's
 * content id and the resolver's snapshot. Either moving drops it; nothing else does (a definition parse per snapshot,
 * never per call).
 *
 * **The pin.** A lexicon archive records the content id of the model it was compiled from (`modelSnapshotHash`). The
 * doors serve a model only when its content id is that one, so a frame built on today's bindings is lowered over the
 * model those bindings came from — the resolver's snapshot hash is then the frame's, and a frame from another snapshot
 * is `cn/snapshot-mismatch` (ttr-concept's warning, A-CN-34) rather than silently re-read. A model the lexicon does not
 * name is refused ([State.Unavailable] `CN_MODEL_SKEW`) — the doors never answer from a model the resolver does not bind
 * against. With no lexicon archive (or one from before the header named its model), the model's content id is the
 * snapshot hash.
 */
class ConceptModels(
    private val source: ConceptModelSource?,
    private val registry: suspend () -> ResolverRegistry,
    private val clock: Clock = Clock.systemUTC(),
) {
    sealed interface State {
        data class Ready(
            val model: ConceptModel,
        ) : State

        data class Unavailable(
            val code: String,
            val message: String,
        ) : State
    }

    private val logger = LoggerFactory.getLogger(ConceptModels::class.java)
    private val lock = Mutex()

    @Volatile
    private var cached: Pair<Pair<String, String>, ConceptModel>? = null

    suspend fun current(): State {
        if (source == null) {
            return State.Unavailable(
                CN_MODEL_UNAVAILABLE,
                "no cn model is configured for this resolver (resolver.concept.model-path)",
            )
        }
        val reg = registry()
        val tree =
            withContext(Dispatchers.IO) { source.current() }
                ?: return State.Unavailable(
                    CN_MODEL_UNAVAILABLE,
                    "the cn model could not be loaded (see the resolver log)",
                )
        val expected = reg.modelSnapshotHash
        // A layer of archives names every archive's model, comma-joined (`DeclaredLayer.merge`): the tree must be one
        // of them — several archives compiled from one model name it once per archive (review-134 F1).
        val named = expected.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        if (named.isNotEmpty() && tree.contentId !in named) {
            val compiledFrom = named.distinct().joinToString(" + ")
            return State.Unavailable(
                CN_MODEL_SKEW,
                "the cn model is ${tree.contentId}; the resolver's vocabulary was compiled from $compiledFrom — " +
                    "the doors serve only the model the resolver binds against",
            )
        }
        val snapshotHash = if (expected.isBlank()) tree.contentId else reg.snapshotHash
        val key = tree.contentId to snapshotHash
        cached?.let { if (it.first == key) return State.Ready(it.second) }
        return lock.withLock {
            cached?.let { if (it.first == key) return@withLock State.Ready(it.second) }
            val resolved = withContext(Dispatchers.Default) { ResolvedCnModel.of(tree.model, snapshotHash, tree.md) }
            val model = ConceptModel(resolved, CnConceptService(resolved, clock), tree.contentId)
            cached = key to model
            logger.info(
                "cn doors: serving model {} as snapshot {} ({} definition finding(s))",
                tree.contentId,
                snapshotHash,
                resolved.definitions.diagnostics.size,
            )
            State.Ready(model)
        }
    }

    companion object {
        const val CN_MODEL_UNAVAILABLE = "CN_MODEL_UNAVAILABLE"
        const val CN_MODEL_SKEW = "CN_MODEL_SKEW"
    }
}
