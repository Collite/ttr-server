// SPDX-License-Identifier: Apache-2.0
package org.tatrman.automation.bundle

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.shouldBe
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.authenticate
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory
import org.tatrman.automation.api.installAutomationErrors
import org.tatrman.automation.api.openRoutes
import org.tatrman.automation.apply.PreviewObligationStorage
import org.tatrman.automation.identity.*
import org.tatrman.automation.ledger.Claim
import org.tatrman.automation.runner.Open
import org.tatrman.mcp.identity.HttpJwksKeySource
import org.tatrman.mcp.identity.JwksBearerVerifier
import java.nio.file.Path
import java.time.Duration
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.readText
import org.tatrman.ttr.metadata.model.RunAsRef as ModelRunAs

/** Real producer evidence and checked actions; failure injection replaces only the placement boundary. */
class PlacementIsolationSpec :
    StringSpec({
        val original =
            BundleFixture.load(
                ApprovalFixture.copyTo(tempdir().toPath().resolve("branch"), fulfilment = true),
            )
        val realm = TestRealm()
        afterSpec { realm.close() }

        "unexpected warm-up failure is cached and isolated from insert-only Open with no fulfilment claim" {
            val attempts = AtomicInteger()
            val failure = IllegalStateException("injected placement failure")
            val insert =
                RecipeManifest.JSON
                    .decodeFromString<RecipeManifest>(
                        Path
                            .of(
                                "src/test/resources/approval-evidence/obligations/release_order.bundle/manifest.json",
                            ).readText(),
                    ).let { manifest ->
                        val recipe = manifest.recipe!!.copy(name = "insert_order")
                        LoadedRecipe("insert_order", manifest.copy(recipe = recipe), recipe, emptyMap())
                    }
            val selected =
                original.copy(
                    recipes = original.recipes + (insert.name to insert),
                    programs =
                        original.programs + (
                            "morning_release" to
                                original.programs.getValue("morning_release").copy(recipe = insert.name)
                        ),
                    deployments =
                        original.deployments.mapValues { (_, deployment) ->
                            deployment.copy(runAs = ModelRunAs.Caller)
                        },
                    preparation =
                        BundlePreparation(original.model, original.packages, original.loadedAt) { _, _ ->
                            attempts.incrementAndGet()
                            throw failure
                        },
                )
            val logger = LoggerFactory.getLogger(ApprovalDependencies::class.java) as Logger
            val messages =
                ListAppender<ILoggingEvent>().also {
                    it.start()
                    logger.addAppender(it)
                }
            try {
                val ready = ApprovalDependencies.prepare(selected)
                ApprovalDependencies.prepare(ready)
                ready.recipes
                    .getValue("release_order")
                    .coverage.refusal shouldBe null
                ready.recipes
                    .getValue("insert_order")
                    .coverage.refusal shouldBe null
                ready.recipes
                    .getValue("insert_order")
                    .coverage.actions
                    .getValue("expect")
                    .transition shouldBe null
                attempts.get() shouldBe 1
                val warnings = messages.list.filter { it.formattedMessage.contains("obligation placement") }
                warnings.size shouldBe 1
                warnings.single().formattedMessage.contains("demo") shouldBe true
                repeat(2) {
                    (
                        shouldThrow<IllegalStateException> {
                            PreviewObligationStorage.resolve(ready, "demo")
                        } === failure
                    ) shouldBe true
                }
                var claims = 0
                val open =
                    Open(
                        { ready },
                        ApprovalReader { _, _, _ -> true },
                        ServiceTokens(emptyMap(), { error("no exchange") }),
                        {
                            claims++
                            Claim.Claimed(UUID.randomUUID(), startedAt = BundleFixture.clock.instant())
                        },
                        { null },
                        { false },
                        Duration.ofSeconds(60),
                        Duration.ofMinutes(30),
                        BundleFixture.clock,
                    )
                testApplication {
                    application {
                        install(ContentNegotiation) { json() }
                        install(StatusPages) { installAutomationErrors() }
                        install(Authentication) {
                            AutomationBearerProvider.register(
                                this,
                                CallerVerifier(
                                    JwksBearerVerifier(
                                        HttpJwksKeySource(realm.jwksUrl),
                                        TestRealm.ISSUER,
                                        TestRealm.AUDIENCE,
                                    ),
                                ),
                            )
                        }
                        routing { authenticate(AUTOMATION_AUTH) { openRoutes(open::open) } }
                    }

                    suspend fun post(
                        recipe: String,
                        deployment: String,
                    ) = client.post("/automation/recipes/$recipe/runs") {
                        bearerAuth(realm.person())
                        contentType(ContentType.Application.Json)
                        val port =
                            if (deployment ==
                                "release_native_morning"
                            ) {
                                """{"none":true}"""
                            } else {
                                """{"key":"4711"}"""
                            }
                        setBody("""{"deployment":"$deployment","port":$port}""")
                    }
                    repeat(2) {
                        val response = post("release_order", "release_native_event")
                        response.status shouldBe HttpStatusCode.InternalServerError
                        val body = response.bodyAsText()
                        Json
                            .parseToJsonElement(body)
                            .jsonObject
                            .getValue("error")
                            .jsonObject
                            .getValue("code")
                            .jsonPrimitive.content shouldBe "internal"
                        body.contains(failure.message!!) shouldBe false
                        claims shouldBe 0
                    }
                    post("insert_order", "release_native_morning").status shouldBe HttpStatusCode.Created
                    claims shouldBe 1
                }
                attempts.get() shouldBe 1
            } finally {
                logger.detachAppender(messages)
                messages.stop()
            }
        }

        "fatal placement errors are not cached or swallowed by warm-up" {
            val failure = AssertionError("fatal placement")
            val selected =
                original.copy(
                    preparation =
                        BundlePreparation(
                            original.model,
                            original.packages,
                            original.loadedAt,
                        ) { _, _ -> throw failure },
                )
            (shouldThrow<AssertionError> { ApprovalDependencies.prepare(selected) } === failure) shouldBe true
            selected.prepared.obligationPlacements.isEmpty() shouldBe true
        }
    })
