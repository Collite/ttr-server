// SPDX-License-Identifier: Apache-2.0
package org.tatrman.resolver

import org.tatrman.fuzzy.v1.BatchMatchRequest
import org.tatrman.fuzzy.v1.BatchMatchResponse
import org.tatrman.fuzzy.v1.FuzzyStatusResponse
import org.tatrman.resolver.client.FuzzyClient
import org.tatrman.resolver.concept.ConceptModelSource
import org.tatrman.resolver.model.ResolverRegistry
import org.tatrman.resolver.model.ResolverThresholds
import org.tatrman.resolver.concept.ConceptModels
import org.tatrman.resolver.concept.ConceptSearch
import org.tatrman.resolver.mcp.ConceptDoor
import org.tatrman.resolver.mcp.ConceptDoorHandler
import org.tatrman.resolver.token.ResumeTokenCodec
import org.tatrman.mcp.identity.IdentityPolicy
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * CN-P5 S5.1 — the cn doors over the shared fixture estate ([CnEstate]): the model read from `model/` the way a
 * deployed resolver reads its mounted tree, the vocabulary from the estate's archive compiled with the model's real
 * content id ([CnEstate.pinnedArchive]), and [CnEstate.Matcher] standing in for lex-matcher.
 */
object CnDoors {
    /** The day a frame that names none is evaluated on — fixed, so every seed is deterministic. */
    val CLOCK: Clock = Clock.fixed(Instant.parse("2026-10-22T00:00:00Z"), ZoneOffset.UTC)

    val codec: ResumeTokenCodec = ResumeTokenCodec(mapOf("k1" to ByteArray(32) { 7 }), "k1", maxAgeSeconds = 3600)

    fun models(
        archive: Path = CnEstate.pinnedArchive(),
        modelPath: Path? = CnEstate.root.resolve("model"),
    ): ConceptModels {
        val registry = CnEstate.registry(archive)
        return ConceptModels(modelPath?.let { ConceptModelSource(it) }, registry::current, CLOCK)
    }

    fun door(
        archive: Path = CnEstate.pinnedArchive(),
        modelPath: Path? = CnEstate.root.resolve("model"),
    ): ConceptDoor {
        val registry = CnEstate.registry(archive)
        val matcher = CnEstate.Matcher(CnEstate.lexiconOf(archive), CnEstate.MEMBERS)
        return ConceptDoor(
            ConceptModels(modelPath?.let { ConceptModelSource(it) }, registry::current, CLOCK),
            ConceptSearch(matcher, registry::current),
            codec,
        )
    }

    /** A golden world's root (`shared/testdata/cn-worlds/<name>`). */
    fun worldRoot(name: String): Path =
        Path.of(System.getProperty("cnWorlds") ?: error("cnWorlds not set")).resolve(name)

    /**
     * The doors over golden world [name]: no lexicon archive goes with it, so the registry names no model and the doors
     * pin the model to its own content id; nothing answers a lookup.
     */
    fun world(name: String): ConceptDoor {
        val registry = ResolverRegistry(emptyList(), emptyList(), ResolverThresholds.LIVE, "stub:$name")
        val none =
            object : FuzzyClient {
                override suspend fun batchMatch(request: BatchMatchRequest): BatchMatchResponse =
                    BatchMatchResponse.getDefaultInstance()

                override suspend fun getStatus(): FuzzyStatusResponse = FuzzyStatusResponse.getDefaultInstance()
            }
        return ConceptDoor(
            ConceptModels(ConceptModelSource(worldRoot(name)), { registry }, CLOCK),
            ConceptSearch(none) { registry },
            codec,
        )
    }

    fun handler(
        requireIdentity: Boolean = false,
        policy: IdentityPolicy = IdentityPolicy.PERMISSIVE,
    ): ConceptDoorHandler = ConceptDoorHandler(door(), requireIdentity, policy)
}
