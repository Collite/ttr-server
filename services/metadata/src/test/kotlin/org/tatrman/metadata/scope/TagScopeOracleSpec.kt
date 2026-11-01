// SPDX-License-Identifier: Apache-2.0
package org.tatrman.metadata.scope

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.tatrman.ttr.metadata.model.ModelDescriptor
import org.tatrman.ttr.metadata.reconcile.ModelReconciler
import org.tatrman.ttr.metadata.source.FileBasedSource
import org.tatrman.ttr.metadata.source.LocalFsStorage
import java.nio.file.Files
import java.nio.file.Path

/** Opt-in external-content oracle. Keep both the archived main tree and validator output outside tracked files. */
class TagScopeOracleSpec :
    StringSpec({
        val root = System.getenv("MW_TAG_ORACLE_ROOT")
        "default closure equals the validator objects on the same archived main SHA".config(enabled = root != null) {
            val tree = Path.of(checkNotNull(root))
            val source =
                FileBasedSource("oracle", 100, LocalFsStorage("oracle", tree.resolve("ai-models/generated/ttr")))
            val snapshot = source.load()
            val result = ModelReconciler(ModelDescriptor("oracle", "oracle", "oracle")).reconcile(listOf(snapshot))
            val validator = Json.parseToJsonElement(Files.readString(tree.resolve("default-validator.json"))).jsonObject
            validator["loaded"]!!.jsonPrimitive.content shouldBe "true"
            val agents = validator["agents"]!!.jsonArray
            agents.isNotEmpty() shouldBe true
            val reports =
                agents.map { agent ->
                    val record = agent.jsonObject
                    val tags = record["tags"]!!.jsonArray.map { it.jsonPrimitive.content }.toSet()
                    val expected = record["objects"]!!.jsonArray.map { it.jsonPrimitive.content }.toSortedSet()
                    val actual =
                        TagScope
                            .resolve(
                                result.model,
                                tags,
                                checkQueries = false,
                            ).objects
                            .map { it.qname.dotted() }
                            .toSortedSet()
                    val missing = expected - actual
                    val extra = actual - expected
                    "${record["agent_id"]!!.jsonPrimitive.content}: expected=${expected.size} actual=${actual.size}\nmissing=${missing.joinToString()}\nextra=${extra.joinToString()}"
                }
            val loaded =
                result.model
                    .objectByQname()
                    .keys
                    .map { it.dotted() }
                    .sorted()
            val summary =
                "source_errors=${snapshot.errors.size} " +
                    "dropped_files=${snapshot.droppedFiles.size} reconcile_errors=${result.errors.size}\n"
            val queryIdentities =
                result.model.queries.keys
                    .map { it.dotted() }
                    .sorted()
                    .joinToString("\n")
            Files.writeString(tree.resolve("comparison.txt"), summary + reports.joinToString("\n\n"))
            Files.writeString(tree.resolve("loaded-identities.txt"), loaded.joinToString("\n"))
            Files.writeString(tree.resolve("query-identities.txt"), queryIdentities)
            // Exact equality, without hiding renderer losses or normalizing object identities.
            reports.all {
                it.substringAfter("missing=").substringBefore('\n').isEmpty() &&
                    it.substringAfter("extra=").isEmpty()
            } shouldBe
                true
        }
    })
