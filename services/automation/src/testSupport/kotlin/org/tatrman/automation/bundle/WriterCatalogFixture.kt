// SPDX-License-Identifier: Apache-2.0
package org.tatrman.automation.bundle

import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.Path

/** The P1 fixture predates generated ER catalogs for three recipes; complete its typed DDL test input. */
fun completeWriterCatalogs(root: Path) {
    java.nio.file.Files.list(root.resolve("automation")).use { paths ->
        paths.forEach { recipe ->
            val directory = recipe.resolve("${recipe.fileName}.bundle")
            val manifest = directory.resolve("manifest.json")
            if (java.nio.file.Files
                    .exists(manifest) &&
                recipe.fileName.toString() != "release_order"
            ) {
                val model =
                    RecipeManifest
                        .parse(
                            java.nio.file.Files
                                .readString(manifest),
                        ).recipe!!
                val schema = model.verdictSchema!!

                fun catalog(
                    name: String,
                    columns: List<Pair<String, String>>,
                    keys: Set<String>,
                ) = "def entity $name { attributes: [" +
                    columns.joinToString(",") { (column, type) ->
                        "def attribute $column { type: $type${if (column in keys) ", isKey: true" else ""} }"
                    } + "] }"
                val keyNames = model.key.map { it.substringAfterLast('.') }.toSet()
                val columns =
                    schema.columns.map {
                        it.getValue("name").jsonPrimitive.content to it.getValue("type").jsonPrimitive.content
                    }
                val entities =
                    listOf(
                        catalog(schema.entity, columns, keyNames + setOf("run", "route")),
                        catalog(schema.current!!, columns, keyNames + "route"),
                        catalog(
                            schema.detail!!,
                            listOf("run" to "text") + columns.filter { it.first in keyNames } +
                                listOf("route" to "text", "context" to "text", "row_no" to "int"),
                            emptySet(),
                        ),
                    )
                java.nio.file.Files.writeString(
                    directory.resolve("catalog/writer-test-entities.ttrm"),
                    "package hartland.automation.generated\nmodel er\n" + entities.joinToString("\n"),
                )
            }
        }
    }
}
