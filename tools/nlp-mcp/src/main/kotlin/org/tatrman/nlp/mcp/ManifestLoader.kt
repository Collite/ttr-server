// SPDX-License-Identifier: Apache-2.0
package org.tatrman.nlp.mcp

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.fasterxml.jackson.module.kotlin.readValue
import org.slf4j.LoggerFactory
import org.tatrman.capabilities.v1.Capability
import org.tatrman.capabilities.v1.CostHints
import org.tatrman.capabilities.v1.ToolCapability
import java.io.File

/**
 * Load the `ToolCapability` manifests under `src/main/resources/manifests/tools/`
 * and build one [Capability] per manifest. Same pattern as meta-mcp / fuzzy-mcp.
 *
 * The shared `ManifestYamlLoader` lives in `:tools:capabilities-mcp` — a peer
 * module that nlp-mcp must not depend on — so we parse the manifests directly.
 * For Stage 2.3 there's a single manifest: `analyze.yaml` → capability_id
 * `nlp.analyze:v1` (the NLP ops are tool args, not separate tools, per
 * contracts §2, so one capability covers the whole `analyze` surface).
 */
class ManifestLoader {
    private val log = LoggerFactory.getLogger(ManifestLoader::class.java)
    private val mapper =
        com.fasterxml.jackson.databind.ObjectMapper(YAMLFactory()).apply {
            registerModule(KotlinModule.Builder().build())
            // The manifests use snake_case keys (matching the
            // `tools/capabilities-mcp` convention); map them to the
            // camelCase Kotlin data-class properties.
            propertyNamingStrategy =
                com.fasterxml.jackson.databind.PropertyNamingStrategies.SNAKE_CASE
            configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
        }

    data class ToolManifest(
        val capabilityId: String? = null,
        val category: String? = null,
        val version: String? = null,
        val description: String? = null,
        val serviceEndpoint: String? = null,
        val searchTags: List<String>? = null,
        val costHints: CostHintsManifest? = null,
    )

    data class CostHintsManifest(
        val typicalLatencyMs: Double? = null,
        val typicalCostUsd: Double? = null,
        val isIdempotent: Boolean? = null,
        val maxConcurrent: Int? = null,
    )

    fun loadAll(classpathBase: String = "manifests/tools"): List<Capability> {
        val url =
            ManifestLoader::class.java.classLoader.getResource(classpathBase) ?: run {
                log.warn("No manifests at classpath:{}; no capabilities to register", classpathBase)
                return emptyList()
            }
        val files: List<File> =
            when (url.protocol) {
                "file" ->
                    File(url.toURI())
                        .listFiles()
                        .orEmpty()
                        .filter { it.isFile && it.name.endsWith(".yaml") }
                        .sortedBy { it.name }
                else -> {
                    log.warn("Unsupported manifests URL protocol '{}'; skipping", url.protocol)
                    return emptyList()
                }
            }
        val caps = mutableListOf<Capability>()
        for (file in files) {
            try {
                val manifest = mapper.readValue<ToolManifest>(file)
                val cap = manifest.toCapability(file.path) ?: continue
                caps.add(cap)
            } catch (e: Exception) {
                log.warn("Failed to load manifest {}: {}", file, e.message)
            }
        }
        log.info("Loaded {} tool capabilities from classpath:{}", caps.size, classpathBase)
        return caps
    }

    private fun ToolManifest.toCapability(sourcePath: String): Capability? {
        if (capabilityId.isNullOrBlank()) {
            log.warn("Manifest {} has blank capability_id; skipping", sourcePath)
            return null
        }
        val builder =
            ToolCapability
                .newBuilder()
                .setCapabilityId(capabilityId)
                .setCategory(category.orEmpty())
                .setVersion(version.orEmpty())
                .setDescription(description.orEmpty())
                .setServiceEndpoint(serviceEndpoint.orEmpty())
                .addAllSearchTags(searchTags.orEmpty())
        costHints?.let { ch ->
            builder.costHints =
                CostHints
                    .newBuilder()
                    .setTypicalLatencyMs(ch.typicalLatencyMs ?: 0.0)
                    .setTypicalCostUsd(ch.typicalCostUsd ?: 0.0)
                    .setIsIdempotent(ch.isIdempotent ?: false)
                    .setMaxConcurrent(ch.maxConcurrent ?: 0)
                    .build()
        }
        return Capability.newBuilder().setTool(builder.build()).build()
    }
}
