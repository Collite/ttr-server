// SPDX-License-Identifier: Apache-2.0
package org.tatrman.grounding.mcp

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.PropertyNamingStrategies
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.fasterxml.jackson.module.kotlin.readValue
import com.typesafe.config.Config
import org.slf4j.LoggerFactory
import org.tatrman.capabilities.client.CapabilitiesClient
import org.tatrman.capabilities.v1.Capability
import org.tatrman.capabilities.v1.CostHints
import org.tatrman.capabilities.v1.ToolCapability
import java.io.File

private val log = LoggerFactory.getLogger("grounding-mcp.capabilities")

/**
 * RG-P3.S2.T6 — load grounding-mcp's `ToolCapability` manifests (the
 * `src/main/resources/manifests/tools` YAML dir) and register each with
 * capabilities-mcp (warn-and-continue). The tool vocabulary forks per contracts §2:
 * the MCP tool names stay `ground_time` / `ground_geo` / `ground_money`; the registry
 * capability ids are the kind-named `grounding.time:v1` / `grounding.geo:v1` /
 * `grounding.money:v1` (RS-28 — ergonomic outside, each delegating to Ground(kind=…)
 * on the generic proto inside). Mirrors the query-mcp / meta-mcp pattern.
 */
internal class ManifestLoader {
    private val mapper =
        ObjectMapper(YAMLFactory()).apply {
            registerModule(KotlinModule.Builder().build())
            propertyNamingStrategy = PropertyNamingStrategies.SNAKE_CASE
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
                val cap = mapper.readValue<ToolManifest>(file).toCapability(file.path) ?: continue
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

/**
 * Register grounding-mcp's capabilities with capabilities-mcp at startup
 * (warn-and-continue). When the registry is unreachable the call still returns; the
 * background retry loop re-attempts with backoff. Opt in with
 * `CAPABILITIES_MCP_URL=http://capabilities-mcp:7501` (or `capabilities-mcp.url`).
 */
internal fun registerWithCapabilities(config: Config) {
    val endpoint =
        System.getenv("CAPABILITIES_MCP_URL")
            ?: if (config.hasPath("capabilities-mcp.url")) config.getString("capabilities-mcp.url") else ""
    val capabilities = ManifestLoader().loadAll()
    if (capabilities.isEmpty()) {
        log.info("No grounding-mcp capabilities to register (manifests dir empty or missing).")
        return
    }
    if (endpoint.isBlank()) {
        log.info(
            "CAPABILITIES_MCP_URL not set — {} grounding-mcp capabilities are not registered.",
            capabilities.size,
        )
        return
    }
    var registered = 0
    for (cap in capabilities) {
        val id = cap.tool.capabilityId
        val handle =
            CapabilitiesClient.startupRegister(
                capability = cap,
                endpoint = endpoint,
                heartbeatIntervalMs = 30_000,
            )
        if (handle.registrationId != null) {
            registered++
            log.info("grounding-mcp registered '{}' with capabilities-mcp at {}", id, endpoint)
        } else {
            log.warn(
                "grounding-mcp startup register for '{}' at {} not yet complete; background retry will continue.",
                id,
                endpoint,
            )
        }
    }
    log.info("grounding-mcp: {}/{} capabilities registered", registered, capabilities.size)
}
