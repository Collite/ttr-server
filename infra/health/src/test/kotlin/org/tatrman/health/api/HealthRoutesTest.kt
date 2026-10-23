// SPDX-License-Identifier: Apache-2.0
package org.tatrman.health.api

import org.tatrman.health.config.FieldConfig
import org.tatrman.health.config.HealthConfig
import org.tatrman.health.config.StatusConfig
import org.tatrman.health.config.TechnologyConfig
import org.tatrman.health.content.ContentService
import org.tatrman.health.service.HealthCheckService
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class HealthRoutesTest :
    StringSpec({

        // `veles` reports its content; `postgres` has none. Both fail their health check (nothing
        // listens on port 1), which is beside the point here.
        val config =
            HealthConfig(
                technologies =
                    mapOf(
                        "veles" to
                            TechnologyConfig(
                                type = "tcp",
                                host = "127.0.0.1",
                                port = 1,
                                timeout = 500,
                                status =
                                    StatusConfig(
                                        url = "http://veles/status",
                                        commit = "commit",
                                        summary = listOf(FieldConfig("objects", "object_count", "count")),
                                    ),
                            ),
                        "postgres" to TechnologyConfig(type = "tcp", host = "127.0.0.1", port = 1, timeout = 500),
                    ),
            )

        fun content(): ContentService =
            ContentService(config.technologies.mapNotNull { (k, t) -> t.status?.let { k to it } }.toMap()).also {
                it.replaceClient(
                    HttpClient(
                        MockEngine {
                            respond(
                                """{"commit":"a0dfb2a","object_count":214}""",
                                HttpStatusCode.OK,
                                headersOf(HttpHeaders.ContentType, "application/json"),
                            )
                        },
                    ),
                )
            }

        "the detailed snapshot carries each technology's content and the commit summary" {
            testApplication {
                application {
                    this.install(ContentNegotiation) { json() }
                    healthRoutes(HealthCheckService(config), content())
                }

                val body = Json.parseToJsonElement(client.get("/health/all/detailed").bodyAsText()).jsonObject
                val byName =
                    body.getValue("technologies").jsonArray.associateBy {
                        it.jsonObject
                            .getValue("name")
                            .jsonPrimitive.content
                    }

                val veles =
                    byName
                        .getValue("veles")
                        .jsonObject
                        .getValue("content")
                        .jsonObject
                veles.getValue("commit").jsonPrimitive.content shouldBe "a0dfb2a"
                veles
                    .getValue("summary")
                    .jsonArray[0]
                    .jsonObject
                    .getValue("v")
                    .jsonPrimitive.content shouldBe "214"
                (byName.getValue("postgres").jsonObject["content"] ?: JsonNull) shouldBe JsonNull
                body
                    .getValue("content")
                    .jsonObject
                    .getValue("commit")
                    .jsonPrimitive.content shouldBe "a0dfb2a"
            }
        }

        "/status/all answers only the content" {
            testApplication {
                application {
                    this.install(ContentNegotiation) { json() }
                    healthRoutes(HealthCheckService(config), content())
                }

                val response = client.get("/status/all")
                response.status shouldBe HttpStatusCode.OK
                val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
                body.getValue("technologies").jsonObject.keys shouldBe setOf("veles")
                body
                    .getValue("summary")
                    .jsonObject
                    .getValue("agree")
                    .jsonPrimitive.content shouldBe "true"
            }
        }

        "without any status block the detailed snapshot is the health answer alone" {
            testApplication {
                application {
                    this.install(ContentNegotiation) { json() }
                    healthRoutes(HealthCheckService(HealthConfig()), ContentService(emptyMap()))
                }

                val body = Json.parseToJsonElement(client.get("/health/all/detailed").bodyAsText()).jsonObject
                (body["content"] ?: JsonNull) shouldBe JsonNull
            }
        }
    })
