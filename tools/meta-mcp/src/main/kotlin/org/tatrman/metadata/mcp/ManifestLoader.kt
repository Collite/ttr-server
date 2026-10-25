// SPDX-License-Identifier: Apache-2.0
package org.tatrman.veles.mcp

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
 * Review-004 R5.1 — load the six `ToolCapability` manifests under
 * `src/main/resources/manifests/tools/` and build one [Capability] per
 * manifest.
 *
 * The shared `ManifestYamlLoader` lives in `:tools:capabilities-mcp` — a
 * peer module that veles-mcp must not depend on (and vice-versa), so
 * we parse the manifests directly. The shapes are simple: a top-level
 * `ToolCapability` with `capability_id` + `category` + `version` +
 * `description` + `service_endpoint` + `search_tags` + `cost_hints`.
 *
 * Review-004 (F4) — the previous single-capability shim impersonated
 * `meta.get_model:v1` and folded the other tools into `search_tags`,
 * so the registry only saw 1 capability. This loader publishes every
 * authored manifest so each tool is discoverable by its own `capability_id`.
 */
internal class ManifestLoader {
    private val log = LoggerFactory.getLogger(ManifestLoader::class.java)
    private val mapper =
        com.fasterxml.jackson.databind.ObjectMapper(YAMLFactory()).apply {
            registerModule(KotlinModule.Builder().build())
            // The manifests use snake_case keys (matching the predecessor platform's
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

    /**
     * Load all tool manifests from the given classpath-relative base
     * directory. The base defaults to `manifests/tools` (matching the
     * veles-mcp's `src/main/resources/manifests/tools/` tree).
     */
    fun loadAll(classpathBase: String = "manifests/tools"): List<Capability> {
        // `javaClass.classLoader.getResource` works for classpath resources;
        // `javaClass.getResource` returns null for some reason in this
        // class-loader layout (Gradle's test classpath includes the
        // main resources but `getResource` on the class doesn't resolve
        // the prefix when the class itself is in a sub-package). The
        // explicit classloader call is the reliable path.
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
