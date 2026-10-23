// SPDX-License-Identifier: Apache-2.0
package org.tatrman.resolver.concept

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.matchers.types.shouldBeSameInstanceAs
import org.tatrman.resolver.CnDoors
import org.tatrman.resolver.CnEstate
import org.tatrman.resolver.model.ResolverRegistry
import org.tatrman.resolver.model.ResolverThresholds
import org.tatrman.ttr.lexicon.cli.ModelContentId
import org.tatrman.ttr.metadata.model.CnSchema
import org.tatrman.ttr.snapshot.SnapshotManifest
import org.tatrman.ttr.snapshot.SnapshotWriter
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.appendText
import kotlin.io.path.readText
import kotlin.io.path.relativeTo
import kotlin.io.path.writeBytes

/**
 * CN-P5 S5.1 T2 — where the cn doors read their model from ([ConceptModelSource]) and what pins it to the resolver's
 * snapshot ([ConceptModels]).
 */
class ConceptModelSourceTest :
    StringSpec({
        val shopModel = CnEstate.root.resolve("model")

        fun concepts(tree: ModelTree): Int = (tree.model.schemas["cn"] as CnSchema).concepts.size

        /** [root]'s model files packed as a snapshot archive, its `docs/` the same tree. */
        fun archiveOf(
            root: Path,
            extra: Map<String, String> = emptyMap(),
        ): Path {
            val docs =
                Files.walk(root).use { s ->
                    s
                        .filter { Files.isRegularFile(it) }
                        .toList()
                        .associate { it.relativeTo(root).toString() to it.readText() }
                }
            val bytes = SnapshotWriter.write(SnapshotManifest(kind = "models", producedBy = "test"), docs + extra)
            return Files.createTempDirectory("cn-archive").resolve("model.tar.zst").also { it.writeBytes(bytes) }
        }

        "the content id is the lexicon build's own rule (ttr-lexicon-cli ModelContentId)" {
            for (root in listOf(shopModel, CnDoors.worldRoot("sales"), CnDoors.worldRoot("clinic"))) {
                ConceptModelSource.contentId(root) shouldBe ModelContentId.of(root)
            }
        }

        "a directory and a snapshot archive of it load the same model, with the same id" {
            val fromDir = ConceptModelSource(shopModel).current().shouldNotBeNull()
            val fromArchive =
                ConceptModelSource(
                    archiveOf(shopModel),
                    Files.createTempDirectory("cn-work"),
                ).current().shouldNotBeNull()
            fromArchive.contentId shouldBe fromDir.contentId
            concepts(fromArchive) shouldBe concepts(fromDir)
            concepts(fromDir) shouldBe 12
            (fromDir.md != null) shouldBe true
        }

        "the same files are the same tree; an edited file reloads" {
            val copy = CnEstate.variant { }.resolve("model")
            val source = ConceptModelSource(copy)
            val first = source.current().shouldNotBeNull()
            source.current() shouldBeSameInstanceAs first
            copy.resolve("cn/shop.ttrm").appendText("\n// an edit\n")
            val second = source.current().shouldNotBeNull()
            second.contentId shouldNotBe first.contentId
        }

        "an unreadable path loads nothing; an archive entry that leaves docs/ refuses the archive" {
            ConceptModelSource(Path.of("/nonexistent/cn-model")).current().shouldBeNull()
            val evil = archiveOf(shopModel, mapOf("../escape.ttrm" to "model cn\n"))
            ConceptModelSource(evil, Files.createTempDirectory("cn-work")).current().shouldBeNull()
        }

        "the pin: a model the lexicon names is served as the resolver's snapshot" {
            val archive = CnEstate.pinnedArchive()
            val registry = CnEstate.registry(archive)
            val state = CnDoors.models(archive).current().shouldBeInstanceOf<ConceptModels.State.Ready>()
            state.model.resolved.snapshotHash shouldBe registry.current().snapshotHash
            state.model.modelId shouldBe ModelContentId.of(shopModel)
        }

        "the pin: a model the lexicon does not name is refused as skewed" {
            // The golden archive's header names a placeholder model, not this one.
            val state = CnDoors.models(CnEstate.pack()).current().shouldBeInstanceOf<ConceptModels.State.Unavailable>()
            state.code shouldBe ConceptModels.CN_MODEL_SKEW
        }

        "the pin: with no archive naming a model, the model's own content id is the snapshot" {
            val registry = ResolverRegistry(emptyList(), emptyList(), ResolverThresholds.LIVE, "stub")
            val state =
                ConceptModels(ConceptModelSource(shopModel), { registry })
                    .current()
                    .shouldBeInstanceOf<ConceptModels.State.Ready>()
            state.model.resolved.snapshotHash shouldBe ModelContentId.of(shopModel)
        }

        "no model configured is CN_MODEL_UNAVAILABLE" {
            val registry = ResolverRegistry(emptyList(), emptyList(), ResolverThresholds.LIVE, "stub")
            val state =
                ConceptModels(
                    null,
                    { registry },
                ).current().shouldBeInstanceOf<ConceptModels.State.Unavailable>()
            state.code shouldBe ConceptModels.CN_MODEL_UNAVAILABLE
        }

        "one model per snapshot: the same inputs reuse the built model" {
            val models = CnDoors.models()
            val a = models.current().shouldBeInstanceOf<ConceptModels.State.Ready>()
            val b = models.current().shouldBeInstanceOf<ConceptModels.State.Ready>()
            b.model shouldBeSameInstanceAs a.model
        }
    })
