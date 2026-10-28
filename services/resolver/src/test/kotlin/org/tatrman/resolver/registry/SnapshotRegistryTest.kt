// SPDX-License-Identifier: Apache-2.0
package org.tatrman.resolver.registry

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import org.tatrman.resolver.model.ResolverThresholds

class SnapshotRegistryTest :
    StringSpec({
        "projection pins archive id, vocabulary and model hash to the captured pass" {
            val vocabulary =
                DeclaredVocabulary(
                    listOf(DeclaredVocabularyEntry("old", "er.entity.old", listOf(DeclaredValue("old", "old")))),
                )
            val source =
                object : RegistrySource {
                    override fun hash() = "h2"

                    override fun modelSnapshotHash() = "m2"

                    override suspend fun fetch() = DeclaredVocabulary()

                    override fun capture(): RegistrySource =
                        object : RegistrySource {
                            override fun hash() = "h1"

                            override fun modelSnapshotHash() = "m1"

                            override suspend fun fetch() = vocabulary
                        }
                }
            val registry = SnapshotRegistry(source, ResolverThresholds.LIVE)
            val current = registry.current()
            current.snapshotHash shouldBe "h1"
            current.modelSnapshotHash shouldBe "m1"
            current.entityTypes.single().anchors shouldBe listOf("old")
            registry.current() shouldBe current
        }
    })
