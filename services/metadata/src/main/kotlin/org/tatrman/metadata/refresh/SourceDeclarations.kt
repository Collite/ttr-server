// SPDX-License-Identifier: Apache-2.0
package org.tatrman.metadata.refresh

import com.typesafe.config.Config
import com.typesafe.config.ConfigValueType
import java.nio.file.Path

/** One normalization path for content slots, fallback lookup and the independent CN promotion checkout. */
internal data class SourceDeclaration(
    val key: String,
    val id: String,
    val config: Config,
)

internal fun sourceDeclarations(config: Config): List<SourceDeclaration> {
    if (!config.hasPath("metadata.sources")) return emptyList()
    val value = config.getValue("metadata.sources")
    val entries =
        when (value.valueType()) {
            ConfigValueType.LIST -> {
                require(config.getList("metadata.sources").all { it.valueType() == ConfigValueType.OBJECT }) {
                    "invalid_source_config: sources list entries must be objects"
                }
                config.getConfigList("metadata.sources").map { sub ->
                    require(sub.hasPath("id")) { "invalid_source_config: sources list entry requires id" }
                    val id = sub.getString("id")
                    require(id.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]*")) && id !in RESERVED_SOURCE_IDS) {
                        "invalid_source_config: invalid or reserved source id"
                    }
                    SourceDeclaration(id, id, sub)
                }
            }
            ConfigValueType.OBJECT -> {
                val sources = config.getConfig("metadata.sources")
                sources.root().keys.filter { it != "builtin-stock" }.sorted().map { key ->
                    val sub = sources.getConfig(key)
                    SourceDeclaration(key, if (sub.hasPath("id")) sub.getString("id") else key, sub)
                }
            }
            else -> throw IllegalArgumentException("invalid_source_config: sources must be a list or keyed object")
        }
    require(entries.map { it.id }.distinct().size == entries.size) { "invalid_source_config: duplicate source id" }
    require(entries.none { it.id in RESERVED_SOURCE_IDS }) { "invalid_source_config: reserved source id" }
    require(entries.all { entry -> entries.none { it !== entry && it.key == entry.id } }) {
        "invalid_source_config: source id collides with another block key"
    }
    entries.forEach { entry ->
        if (entry.config.hasPath("subdirectory")) sourceSubdirectory(entry.config)
    }
    return entries
}

/** Subdirectories are roots inside a source, never a way to escape its tree. */
internal fun sourceSubdirectory(config: Config): String {
    val value = if (config.hasPath("subdirectory")) config.getString("subdirectory") else ""
    require(
        !value.contains('\\') && !Path.of(value.ifEmpty { "." }).isAbsolute && value.split('/').none { it == ".." },
    ) {
        "invalid_source_config: subdirectory must be relative without parent traversal"
    }
    return value.split('/').filter { it.isNotEmpty() && it != "." }.joinToString("/")
}

private val RESERVED_SOURCE_IDS = setOf("builtin-stock", "builtin-stock-roles", "cn-vocabularies")
