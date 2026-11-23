// SPDX-License-Identifier: Apache-2.0
package org.tatrman.automation.bundle

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.tatrman.ttr.metadata.model.World
import org.tatrman.ttr.parser.model.PropertyValue
import org.w3c.dom.Element
import org.xml.sax.helpers.DefaultHandler
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.io.path.name

data class DeployedProcess(
    val engineUrl: String,
    val deploymentName: String,
    val deploymentSource: String,
    val key: String,
    val resource: String,
    val fingerprint: String,
)

/**
 * The authored process set deployed by ttr-demo's automation-export.sh. Each top-level process
 * names its served deployment in ttr:contract. Embedded subprocesses do not have their own
 * definition in this set, and the separate exports/ tree contains the underlying skeletons.
 */
internal object DeployedProcesses {
    private const val BPMN = "http://www.omg.org/spec/BPMN/20100524/MODEL"
    private const val TTR = "https://tatrman.org/schema/automation-export/1"
    private val setName = Regex("[a-zA-Z0-9_-]+")

    fun read(
        root: Path,
        worlds: Collection<World>,
    ): Map<String, DeployedProcess> {
        val processes = root.resolve("processes")
        val setsFile = processes.resolve("sets.json")
        if (!Files.isDirectory(processes, NOFOLLOW_LINKS) ||
            !Files.isRegularFile(setsFile, NOFOLLOW_LINKS)
        ) {
            return emptyMap()
        }
        // This metadata is optional. Refusing the whole served bundle would break older detail reads.
        return runCatching {
            val sets = Json.parseToJsonElement(Files.readString(setsFile)) as? JsonObject ?: return emptyMap()
            val parser =
                DocumentBuilderFactory
                    .newDefaultNSInstance()
                    .apply {
                        setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
                        setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "")
                        setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "")
                    }.newDocumentBuilder()
                    .apply { setErrorHandler(DefaultHandler()) }
            val bindings = mutableListOf<Pair<String, DeployedProcess>>()
            for ((set, metadata) in sets) {
                if (!setName.matches(set) || metadata !is JsonObject) continue
                val deployments =
                    (metadata["deployments"] as? JsonObject)
                        ?.values
                        .orEmpty()
                        .mapNotNull { it.string() }
                        .toSet()
                if (deployments.isEmpty()) continue
                val setDir = processes.resolve(set)
                if (!Files.isDirectory(setDir, NOFOLLOW_LINKS)) return emptyMap()
                Files.newDirectoryStream(setDir, "*.bpmn").use { files ->
                    for (file in files) {
                        if (!Files.isRegularFile(file, NOFOLLOW_LINKS)) continue
                        val bytes = Files.readAllBytes(file)
                        val definition = parser.parse(bytes.inputStream()).documentElement
                        if (definition.namespaceURI != BPMN || definition.localName != "definitions") continue
                        val fingerprint = Fingerprints.sha256(bytes)
                        for (process in definition.children(BPMN, "process")) {
                            val deployment = deploymentOf(process)?.takeIf { it in deployments } ?: continue
                            val key = process.getAttribute("id").takeIf { it.isNotBlank() } ?: continue
                            val engineUrl = engineUrl(worlds, deployment) ?: continue
                            bindings += deployment to
                                DeployedProcess(
                                    engineUrl,
                                    "hartland-processes-$set",
                                    "hartland:processes/$set",
                                    key,
                                    file.name,
                                    fingerprint,
                                )
                        }
                    }
                }
            }
            bindings
                .groupBy({ it.first }, { it.second })
                .mapNotNull { (d, candidates) ->
                    candidates.singleOrNull()?.let { d to it }
                }.toMap()
        }.getOrDefault(emptyMap())
    }

    private fun deploymentOf(process: Element): String? {
        if (process.getAttribute("isExecutable") != "true") return null
        val contract =
            process
                .children(BPMN, "extensionElements")
                .singleOrNull()
                ?.children(TTR, "contract")
                ?.singleOrNull() ?: return null
        val body = Json.parseToJsonElement(contract.textContent) as? JsonObject ?: return null
        return body["deployment"].string()?.takeIf { body["target"].string() == "process_engine" }
    }

    private fun engineUrl(
        worlds: Collection<World>,
        deployment: String,
    ): String? {
        val world = worlds.singleOrNull { w -> w.deployments.any { it.qname.name == deployment } } ?: return null
        val target = world.deployments.single { it.qname.name == deployment }.target
        val engine =
            world.executors.singleOrNull { it.qname.name == target && it.extendsRef == "process_engine" } ?: return null
        return when (val url = engine.manifest["url"]) {
            is PropertyValue.StringValue -> url.raw
            is PropertyValue.TripleStringValue -> url.raw
            else -> null
        }
    }

    private fun Element.children(
        namespace: String,
        name: String,
    ): List<Element> =
        (0 until childNodes.length)
            .mapNotNull { childNodes.item(it) as? Element }
            .filter { it.namespaceURI == namespace && it.localName == name }
}

private fun JsonElement?.string(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.content
