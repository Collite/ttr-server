// SPDX-License-Identifier: Apache-2.0
package org.tatrman.automation.bundle

import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.Path

/** Catalog detail columns follow RecipeCatalogEmitter: context columns are text, keys retain their types. */
fun writerDetailColumns(manifest: RecipeManifest): List<Pair<String, String>> {
    val model = requireNotNull(manifest.recipe)
    val schema = requireNotNull(model.verdictSchema)
    val keys = model.key.map { it.substringAfterLast('.') }.toSet()
    val contexts =
        model.roles
            ?.routes
            .orEmpty()
            .flatMap { it.detail }
            .toSet()
    val published =
        manifest.islands
            .filter {
                it.name in contexts
            }.flatMap { it.outputs }
            .flatMap { it.columns }
            .map { it.name }
    // Three legacy manifests predate output schemas; these are their SQL projection names, not a new resolver.
    val legacy =
        when (model.name) {
            "release_order" ->
                listOf(
                    "line_no",
                    "item",
                    "quantity",
                    "on_hand",
                    "outage",
                    "short",
                    "unit_price",
                    "list_price",
                    "invoice_id",
                    "due",
                    "open_amount",
                )
            "monitor_inbound" -> listOf("qty_ok", "qty_bad", "last_received")
            "settle" -> listOf("obligation_id", "kind", "due")
            else -> emptyList()
        }
    return (
        listOf("run" to "text") +
            schema.columns
                .map {
                    it.getValue("name").jsonPrimitive.content to it.getValue("type").jsonPrimitive.content
                }.filter { it.first in keys } + listOf("route" to "text", "context" to "text", "row_no" to "int") +
            (
                if (published.isEmpty() &&
                    contexts.isNotEmpty()
                ) {
                    legacy
                } else {
                    published
                }
            ).filter { it !in keys }.distinct().map { it to "text" } +
            // ttr-core#382: the pending producer catalog column, deliberately mirrored only in copied test fixtures.
            listOf("truncated" to "bool")
    ).distinctBy { it.first }
}

/** Complete the old fixture catalogs on a disposable copy; never alter the released producer fixture. */
fun completeWriterCatalogs(root: Path) {
    Files.list(root.resolve("automation")).use { paths ->
        paths.forEach { recipe ->
            val directory = recipe.resolve("${recipe.fileName}.bundle")
            val path = directory.resolve("manifest.json")
            if (!Files.exists(path)) return@forEach
            val manifest = RecipeManifest.parse(Files.readString(path))
            val model = requireNotNull(manifest.recipe)
            val schema = requireNotNull(model.verdictSchema)

            fun attributes(
                columns: List<Pair<String, String>>,
                keys: Set<String>,
            ) = columns.joinToString(",") { (column, type) ->
                "def attribute $column { type: $type${if (column in keys) ", isKey: true" else ""} }"
            }

            fun catalog(
                name: String,
                columns: List<Pair<String, String>>,
                keys: Set<String>,
            ) = "def entity $name { attributes: [${attributes(columns, keys)}] }"
            val columns =
                schema.columns.map {
                    it.getValue("name").jsonPrimitive.content to
                        it.getValue("type").jsonPrimitive.content
                }
            val keys = model.key.map { it.substringAfterLast('.') }.toSet()
            val detail = writerDetailColumns(manifest)
            val existing = directory.resolve("catalog/${schema.entity}.ttrm")
            if (Files.exists(existing)) {
                val text = Files.readString(existing)
                val pattern = Regex("(def entity ${schema.detail} \\{[\\s\\S]*?attributes: \\[)[\\s\\S]*?(\\]\\s*})")
                Files.writeString(
                    existing,
                    pattern.replace(text) {
                        it.groupValues[1] + attributes(detail, emptySet()) +
                            it.groupValues[2]
                    },
                )
            } else {
                Files.createDirectories(directory.resolve("catalog"))
                Files.writeString(
                    directory.resolve("catalog/writer-test-entities.ttrm"),
                    "package hartland.automation.generated\nmodel er\n" +
                        listOf(
                            catalog(schema.entity, columns, keys + setOf("run", "route")),
                            catalog(requireNotNull(schema.current), columns, keys + "route"),
                            catalog(requireNotNull(schema.detail), detail, emptySet()),
                        ).joinToString("\n"),
                )
            }
        }
    }
}
