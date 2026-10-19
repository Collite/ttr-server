// SPDX-License-Identifier: Apache-2.0
package org.tatrman.automation.api

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldMatch
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File

/**
 * The error envelope (AU contracts §1) and the code catalogue's sweep: a code of a delivered stage is raised
 * (an `AutomationException(ErrorCode.X, …)` is built) in exactly one place of the main sources; a code of a
 * stage still to come is raised nowhere. Add a stage to [DELIVERED] as it lands.
 */
class ErrorEnvelopeSpec :
    StringSpec({
        "every code is kebab-case, unique, and names a plan stage" {
            ErrorCode.entries.forEach {
                it.code shouldMatch Regex("[a-z]+(-[a-z]+)*")
                it.stage shouldMatch Regex("S[0-4]\\.[1-3]")
            }
            ErrorCode.entries
                .map { it.code }
                .toSet()
                .size shouldBe ErrorCode.entries.size
        }

        "a delivered stage's code is raised in exactly one place; a later stage's in none" {
            val raised =
                File("src/main/kotlin")
                    .walkTopDown()
                    .filter { it.isFile && it.extension == "kt" }
                    .flatMap { file -> RAISE.findAll(file.readText()).map { it.groupValues[1] to file.name } }
                    .groupBy({ it.first }, { it.second })
            val wrong =
                ErrorCode.entries.mapNotNull { code ->
                    val sites = raised[code.name].orEmpty()
                    val expected = if (code.stage in DELIVERED) 1 else 0
                    if (sites.size ==
                        expected
                    ) {
                        null
                    } else {
                        "${code.name} (${code.stage}): raised at $sites, expected $expected"
                    }
                }
            wrong.shouldBeEmpty()
            (raised.keys - ErrorCode.entries.map { it.name }.toSet()).shouldBeEmpty()
        }

        "an AutomationException answers its status and the envelope, detail included" {
            testApplication {
                application {
                    install(ContentNegotiation) { json() }
                    install(StatusPages) { installAutomationErrors() }
                    routing {
                        get("/stale") {
                            throw AutomationException(
                                ErrorCode.RECIPE_STALE,
                                "the recipe changed since it was approved",
                                buildJsonObject { put("approved", "fp-a") },
                            )
                        }
                    }
                }
                val response = client.get("/stale")
                response.status shouldBe HttpStatusCode.Conflict
                val error = Json.parseToJsonElement(response.bodyAsText()).jsonObject["error"]!!.jsonObject
                error.keys shouldContainExactly setOf("code", "message", "detail")
                error["code"] shouldBe JsonPrimitive("recipe-stale")
                error["message"] shouldBe JsonPrimitive("the recipe changed since it was approved")
                error["detail"]!!.jsonObject["approved"]!!.jsonPrimitive.content shouldBe "fp-a"
            }
        }

        "anything unexpected answers 500 `internal` in the same envelope, without the exception's text" {
            testApplication {
                application {
                    install(ContentNegotiation) { json() }
                    install(StatusPages) { installAutomationErrors() }
                    routing { get("/crash") { error("password=hunter2 leaked into a message") } }
                }
                val response = client.get("/crash")
                response.status shouldBe HttpStatusCode.InternalServerError
                val body = response.bodyAsText()
                val error = Json.parseToJsonElement(body).jsonObject["error"]!!.jsonObject
                error["code"] shouldBe JsonPrimitive("internal")
                (body.contains("hunter2")) shouldBe false
            }
        }
    }) {
    companion object {
        val DELIVERED = setOf("S0.1", "S0.2", "S0.3")
        private val RAISE = Regex("""AutomationException\(\s*ErrorCode\.([A-Z_]+)""")
    }
}
