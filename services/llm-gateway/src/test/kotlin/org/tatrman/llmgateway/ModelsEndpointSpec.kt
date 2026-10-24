// SPDX-License-Identifier: Apache-2.0
package org.tatrman.llmgateway

import com.typesafe.config.ConfigFactory
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * LG-P1·S2·T5 — `/v1/models` (contracts §1.5): OpenAI list shape from the catalog, one entry per model,
 * **aliases NOT listed**, `tags` + `created` extensions present, and the endpoint is virtual-key gated.
 */
class ModelsEndpointSpec :
    StringSpec({

        "GET /v1/models with a seeded key → OpenAI list; one per model, aliases not listed, tags+created present" {
            testApplication {
                environment { config = MapApplicationConfig() }
                application { module(ConfigFactory.load(), TestSupport.seededGateway()) }

                val res =
                    client.get("/v1/models") {
                        header(HttpHeaders.Authorization, "Bearer ${TestSupport.SEEDED_KEY}")
                    }
                res.status shouldBe HttpStatusCode.OK

                val body = Json.parseToJsonElement(res.bodyAsText()).jsonObject
                body["object"]!!.jsonPrimitive.content shouldBe "list"
                val data = body["data"]!!.jsonArray
                data.size shouldBe 9 // one per concrete catalog model

                val ids = data.map { it.jsonObject["id"]!!.jsonPrimitive.content }
                ids shouldContain "gpt-4o"
                ids shouldContain "claude-haiku-4-5"
                // tier/rules.conf aliases are routing names, NOT models
                ids shouldContain "gpt-6.1-sol"
                ids shouldContain "text-embedding-3-small"
                ids.none { it in setOf("haiku", "sonnet", "gpt-4", "fast", "claude-haiku", "smart") } shouldBe true

                val first = data.first().jsonObject
                first.containsKey("tags") shouldBe true
                first.containsKey("created") shouldBe true
                first["owned_by"]!!.jsonPrimitive.content.isNotBlank() shouldBe true
            }
        }

        "GET /v1/models without a key → 401 invalid_api_key (OpenAI-shaped)" {
            testApplication {
                environment { config = MapApplicationConfig() }
                application { module(ConfigFactory.load(), TestSupport.seededGateway()) }

                val res = client.get("/v1/models")
                res.status shouldBe HttpStatusCode.Unauthorized
                res.bodyAsText() shouldContain "invalid_api_key"
            }
        }

        "GET /v1/models with an unknown key → 401" {
            testApplication {
                environment { config = MapApplicationConfig() }
                application { module(ConfigFactory.load(), TestSupport.seededGateway()) }

                val res =
                    client.get("/v1/models") {
                        header(HttpHeaders.Authorization, "Bearer ttrk-not-a-real-key")
                    }
                res.status shouldBe HttpStatusCode.Unauthorized
            }
        }
    })
