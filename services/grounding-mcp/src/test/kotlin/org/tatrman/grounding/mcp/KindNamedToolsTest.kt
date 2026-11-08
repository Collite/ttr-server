// SPDX-License-Identifier: Apache-2.0
package org.tatrman.grounding.mcp

import io.grpc.Status
import io.grpc.StatusException
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.Runs
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.tatrman.grounding.mcp.client.GroundingClient
import shared.ktor.mcp.safeMcpTool

/**
 * RG-P3.S2.T5 — the kind-named grounding tools. The three MCP tools delegate to Ground(kind=…) on
 * the generic proto (already pinned by ToolsTest); this pins the RS-28 registry side: the kind-named
 * capability ids `grounding.time|geo|money:v1` are registered, every callback is wrapped in
 * `safeMcpTool` (a timeout ⇒ a well-formed `CallToolResult(isError=true)`), and the geo tool honours
 * the geo capability posture (a dark/erroring geocoder surfaces UNAVAILABLE as a clean tool error).
 */
class KindNamedToolsTest :
    StringSpec({

        fun request(args: JsonObject?): CallToolRequest {
            val r = mockk<CallToolRequest>()
            every { r.arguments } returns args
            return r
        }

        "the kind-named capability ids are registered from the manifests, Scan's with them (TS-P0 S0.3)" {
            val ids = ManifestLoader().loadAll().map { it.tool.capabilityId }
            ids shouldContainExactlyInAnyOrder
                listOf("grounding.time:v1", "grounding.geo:v1", "grounding.money:v1", "grounding.time.scan:v1")
            ManifestLoader().loadAll().all { it.tool.category == "grounding" } shouldBe true
        }

        "safeMcpTool turns a callback timeout into a well-formed CallToolResult(isError=true)" {
            val wrapped =
                safeMcpTool("grounding.geo:v1", timeoutMs = 20) {
                    delay(1_000)
                    CallToolResult(content = listOf(TextContent(text = "unreached")), isError = false)
                }
            val result = wrapped(request(buildJsonObject { put("spanText", "within 20 km of Brno") }))
            result.isError shouldBe true
        }

        "the geo tool honours the capability posture: a dark geocoder (UNAVAILABLE) → clean tool error" {
            val geo =
                mockk<GroundingClient>().also {
                    every { it.serviceName } returns "geo"
                    every { it.close() } just Runs
                    coEvery { it.ground(any()) } throws
                        StatusException(Status.UNAVAILABLE.withDescription("geo place resolution unavailable"))
                }
            val tools = Tools(chrono = mockk(relaxed = true), geo = geo, money = mockk(relaxed = true))

            val result =
                tools.groundGeoCallback(
                    request(buildJsonObject { put("spanText", "within 20 km of Brno") }),
                )
            result.isError shouldBe true
            (result.content.first() as TextContent).text.contains("geo") shouldBe true
        }
    })
