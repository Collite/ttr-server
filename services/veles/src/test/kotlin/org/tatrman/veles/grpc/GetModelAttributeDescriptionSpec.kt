// SPDX-License-Identifier: Apache-2.0
package org.tatrman.veles.grpc

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import org.tatrman.meta.v1.GetModelRequest
import org.tatrman.ttr.metadata.graph.ModelGraph
import org.tatrman.ttr.metadata.model.ModelDescriptor
import org.tatrman.ttr.metadata.reconcile.ModelReconciler
import org.tatrman.ttr.metadata.registry.MetadataRegistry
import org.tatrman.ttr.metadata.source.FileBasedSource
import org.tatrman.ttr.metadata.source.LocalFsStorage
import java.nio.file.Path

/**
 * NLS-P11 T5 — the attribute-description chain, end to end.
 *
 * kantheon's `{{ entities }}` roster (GX contracts §6a) renders one line per attribute with
 * its description, and it reads them off `ModelBundleAttribute.object_descriptor.description`
 * — a field that has been on the wire since Stage 04 but that nothing downstream ever
 * consumed. "It is on the wire" and "it arrives populated" are different claims, and only the
 * second one makes the roster useful, so this spec makes the second one.
 *
 * It runs against the BUNDLED `model-ttr/` tree rather than a synthetic fixture on purpose:
 * a synthetic model proves the mapping compiles, while real estate content proves authors'
 * descriptions actually survive parse → metadata → bundle.
 */
class GetModelAttributeDescriptionSpec :
    StringSpec({

        val fixtureRoot: Path =
            Path
                .of(checkNotNull(this::class.java.classLoader.getResource("model-ttr/ucetnictvi")).toURI())
                .parent

        fun service(): MetadataServiceImpl {
            val source =
                FileBasedSource(
                    sourceId = "ucetnictvi",
                    priority = 100,
                    storage = LocalFsStorage(id = "ucetnictvi", rootPath = fixtureRoot),
                )
            val reconciler =
                ModelReconciler(ModelDescriptor(id = "test", name = "test", description = "ucetnictvi fixture"))
            val snapshot = source.load()
            val result = reconciler.reconcile(listOf(snapshot))
            val registry = MetadataRegistry()
            registry.swap(result.model, ModelGraph.build(result.model), result.warnings + result.errors)
            return MetadataServiceImpl(registry, packageIndex = servedIndex(snapshot))
        }

        suspend fun bundle() =
            service()
                .getModel(GetModelRequest.newBuilder().addPackages("ucetnictvi").build())
                .model

        "every bundled entity carries its attributes, each with a populated ObjectDescriptor" {
            val entities = bundle().entitiesList
            entities.isEmpty() shouldBe false
            // Every attribute is addressable: a local name is what the roster prints and what a
            // FREE_SQL plan then names in a projection.
            entities.all { e -> e.attributesList.all { it.objectDescriptor.localName.isNotEmpty() } } shouldBe true
            entities.any { it.attributesList.isNotEmpty() } shouldBe true
        }

        "authored attribute descriptions arrive populated (the P11 roster's whole input)" {
            val described =
                bundle()
                    .entitiesList
                    .flatMap { it.attributesList }
                    .filter { it.objectDescriptor.description.isNotEmpty() }
            // The ucetnictvi package describes essentially every attribute it declares; the
            // assertion is deliberately a floor, not a count, so estate edits do not break it.
            (described.size >= 20) shouldBe true
            described.all { it.objectDescriptor.localName.isNotEmpty() } shouldBe true
        }

        "the description that arrives is the AUTHORED text, verbatim" {
            // Named, not counted. A floor of "≥20 non-empty" survives a mapping that fills
            // every description with the local name, or the entity's, or a placeholder — all
            // of which would render a plausible-looking roster made of the wrong words. One
            // exact pair, straight out of `model-ttr/ucetnictvi/er.ttr`, cannot.
            val attrs =
                bundle()
                    .entitiesList
                    .single { it.objectDescriptor.localName == "hodnoty_manažerského_účetnictví" }
                    .attributesList
                    .associateBy { it.objectDescriptor.localName }

            attrs.getValue("id_hodnoty").objectDescriptor.description shouldBe
                "Unikátní identifikátor záznamu hodnoty"
            attrs.getValue("plán").objectDescriptor.description shouldBe "Plánovaná hodnota za období"
            // …and it is not any of the things a broken mapping would substitute.
            attrs.getValue("plán").objectDescriptor.localName shouldBe "plán"
        }

        // ⚑ "an attribute the author did not describe arrives EMPTY" is NOT asserted here:
        // `ucetnictvi` describes all 52 of its attributes, so any such case against this
        // fixture is vacuous by construction. It is pinned where an undescribed attribute
        // actually exists — `GetModelLocalizedDescriptionSpec`, "step 5", over the
        // `model-locale/localized` matrix.

        "AttributeDetail carries the three facts the roster prints (type / key / nullable)" {
            val attrs = bundle().entitiesList.flatMap { it.attributesList }
            attrs.all { it.detail.type.isNotEmpty() } shouldBe true
            attrs.any { it.detail.isKey } shouldBe true
        }
    })
