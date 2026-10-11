// SPDX-License-Identifier: Apache-2.0
package org.tatrman.veles.mcp

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequestParams
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.tatrman.meta.v1.ResolveAreaResponse
import org.tatrman.common.v1.ResponseMessage
import org.tatrman.common.v1.Severity

/**
 * Golem P4 S4.2 — `resolve_area` MCP-tool layer test.
 *
 * Verifies the zero-logic wrapper contract:
 *  - argument forwarding (`area`)
 *  - shape of the structured response (`packages[]`, `description`, `tags[]`, `found`)
 *  - veles's Rule-6 warnings (e.g. `area_not_found`) surfaced in the text payload
 *  - the missing-area guard short-circuits before the gRPC call
 */
class ResolveAreaToolSpec :
    StringSpec({

        fun request(args: JsonObject = JsonObject(emptyMap())): CallToolRequest =
            CallToolRequest(CallToolRequestParams(name = "resolve_area", arguments = args))

        fun stubGrpc(): org.tatrman.veles.client.MetadataGrpcClient {
            val grpc = mockk<org.tatrman.veles.client.MetadataGrpcClient>(relaxed = true)
            coEvery { grpc.resolveArea(any()) } returns ResolveAreaResponse.getDefaultInstance()
            return grpc
        }

        fun tools(grpc: org.tatrman.veles.client.MetadataGrpcClient): Tools = Tools(grpc)

        "resolve_area forwards area and returns packages/description/tags/found" {
            val grpc = stubGrpc()
            coEvery { grpc.resolveArea("hartland") } returns
                ResolveAreaResponse
                    .newBuilder()
                    .addPackages("sales")
                    .addPackages("returns")
                    .setDescription("Sales, returns, inventory and customer analytics for Hartland Stores")
                    .addTags("reporting")
                    .addTags("demo")
                    .setFound(true)
                    .build()

            val res =
                tools(grpc).resolveAreaCallback(
                    request(buildJsonObject { put("area", JsonPrimitive("hartland")) }),
                )
            (res.isError == true) shouldBe false
            coVerify(exactly = 1) { grpc.resolveArea("hartland") }
            val structured = res.structuredContent as JsonObject
            (structured["found"] as JsonPrimitive).content shouldBe "true"
            (structured["description"] as JsonPrimitive).content shouldBe
                "Sales, returns, inventory and customer analytics for Hartland Stores"
            val pkgs = (structured["packages"] as JsonArray).map { (it as JsonPrimitive).content }
            pkgs shouldBe listOf("sales", "returns")
            val tags = (structured["tags"] as JsonArray).map { (it as JsonPrimitive).content }
            tags shouldBe listOf("reporting", "demo")
        }

        "missing area returns an error result without calling gRPC" {
            val grpc = stubGrpc()
            val res = tools(grpc).resolveAreaCallback(request(JsonObject(emptyMap())))
            res.isError shouldBe true
            coVerify(exactly = 0) { grpc.resolveArea(any()) }
        }

        "area_not_found: found=false and the warning is surfaced in the text payload" {
            val grpc = mockk<org.tatrman.veles.client.MetadataGrpcClient>(relaxed = true)
            coEvery { grpc.resolveArea("nonexistent") } returns
                ResolveAreaResponse
                    .newBuilder()
                    .setFound(false)
                    .addMessages(
                        ResponseMessage
                            .newBuilder()
                            .setSeverity(Severity.WARNING)
                            .setCode("area_not_found")
                            .setHumanMessage("No area named 'nonexistent'")
                            .build(),
                    ).build()

            val res =
                tools(grpc).resolveAreaCallback(
                    request(buildJsonObject { put("area", JsonPrimitive("nonexistent")) }),
                )
            (res.isError == true) shouldBe false
            val structured = res.structuredContent as JsonObject
            (structured["found"] as JsonPrimitive).content shouldBe "false"
            val text = res.content.first().toString()
            text.contains("area_not_found") shouldBe true
        }
    })
