// SPDX-License-Identifier: Apache-2.0
package org.tatrman.resolver

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.tatrman.mcp.identity.IdentityPolicy
import org.tatrman.nlp.v1.AnalyzeResponse
import org.tatrman.resolver.mcp.ResolveDoor
import org.tatrman.resolver.mcp.ResolveDoorHandler
import org.tatrman.resolver.v1.AwaitingClarification
import org.tatrman.resolver.v1.Capabilities
import org.tatrman.resolver.v1.Option
import org.tatrman.resolver.v1.ResolveRequest
import org.tatrman.resolver.v1.ResolveResponse
import org.tatrman.resolver.v1.Resolution
import java.util.Base64

/**
 * RG-P6.S1.T1 — the `resolve.bind:v1` door contract, driven at the handler seam
 * with a fake `resolve` fn (real proto responses, ZERO LLM / upstreams). Asserts:
 * a fresh question returns bindings + the `parse` passthrough (F-T1) + the
 * `capabilities` echo (F-T3); a clarification carries options + an opaque
 * resume_token that round-trips through a SECOND tool call and marshals into a
 * resume request; and H-2 fail-closed — a call with no identity is refused before
 * any resolution work, and a token/user_id spoof is refused.
 */
class ResolveDoorTest :
    StringSpec({

        // A fake core: records requests and answers by shape (fresh vs resume;
        // an ambiguous "QT" text yields a clarification). No signing — the door
        // only passes the opaque token through; token crypto is ResumeTokenTest's job.
        class FakeCore {
            val requests = mutableListOf<ResolveRequest>()

            suspend fun resolve(request: ResolveRequest): ResolveResponse {
                requests += request
                return when {
                    request.hasResume() ->
                        ResolveResponse
                            .newBuilder()
                            .setResolution(
                                Resolution.newBuilder().setConfidence(1.0).setRationale("resumed via signed pin"),
                            ).setTraceId("t-resume")
                            .build()
                    request.fresh.text.contains("QT") ->
                        ResolveResponse
                            .newBuilder()
                            .setAwaiting(
                                AwaitingClarification
                                    .newBuilder()
                                    .addOptions(Option.newBuilder().setId("M:qt-orlak").setLabel("QT ORLAK"))
                                    .addOptions(Option.newBuilder().setId("M:qt-belus").setLabel("QT BELUS"))
                                    .setResumeToken("tok-abc"),
                            ).setCapabilities(Capabilities.newBuilder().setFuzzyReady(true))
                            .build()
                    else ->
                        ResolveResponse
                            .newBuilder()
                            .setParse(AnalyzeResponse.newBuilder().setLanguage("cs"))
                            .setResolution(
                                Resolution.newBuilder().setConfidence(0.9).setRationale("deterministic bind: 1 domain"),
                            ).setCapabilities(Capabilities.newBuilder().setLanguage("cs").setFuzzyReady(true))
                            .setTraceId("t-fresh")
                            .build()
                }
            }
        }

        fun unsignedJwt(username: String): String {
            val enc = Base64.getUrlEncoder().withoutPadding()
            val header = enc.encodeToString("""{"alg":"none","typ":"JWT"}""".toByteArray())
            val payload = enc.encodeToString("""{"preferred_username":"$username"}""".toByteArray())
            return "$header.$payload.sig"
        }

        fun args(vararg pairs: Pair<String, String>): JsonObject =
            buildJsonObject { pairs.forEach { (k, v) -> put(k, v) } }

        fun JsonObject.child(key: String): JsonObject = this[key]!!.jsonObject

        fun JsonObject.string(key: String): String = this[key]!!.jsonPrimitive.content

        "fresh question → resolution with the parse passthrough (F-T1) and capabilities echo (F-T3)" {
            val core = FakeCore()
            val handler = ResolveDoorHandler(ResolveDoor(core::resolve), requireIdentity = false)

            val result = runBlocking { handler.handle(args("conversation_id" to "c-1", "text" to "kolik"), null, null) }

            result.isError shouldBe false
            val sc = result.structuredContent.shouldNotBeNull()
            sc.containsKey("resolution").shouldBeTrue()
            sc.containsKey("parse").shouldBeTrue() // E-T1 / F-T1 passthrough
            sc.containsKey("capabilities").shouldBeTrue() // F-T3 honest echo
            sc.child("resolution").string("rationale") shouldBe "deterministic bind: 1 domain"
            core.requests
                .single()
                .fresh.text shouldBe "kolik"
        }

        "missing conversation_id is an INVALID_ARGUMENT error (never a resolve)" {
            val core = FakeCore()
            val handler = ResolveDoorHandler(ResolveDoor(core::resolve), requireIdentity = false)

            val result = runBlocking { handler.handle(args("text" to "kolik"), null, null) }

            result.isError shouldBe true
            result.structuredContent.shouldNotBeNull().string("errorCode") shouldBe "INVALID_ARGUMENT"
            core.requests.isEmpty().shouldBeTrue()
        }

        "clarification carries options + an opaque resume_token that round-trips through a second call" {
            val core = FakeCore()
            val handler = ResolveDoorHandler(ResolveDoor(core::resolve), requireIdentity = false)

            // Call 1: an ambiguous span → AwaitingClarification with a resume token.
            val first = runBlocking { handler.handle(args("conversation_id" to "c-1", "text" to "za QT"), null, null) }
            first.isError shouldBe false
            val awaiting = first.structuredContent.shouldNotBeNull().child("awaiting")
            awaiting.string("resumeToken") shouldBe "tok-abc"
            val token = awaiting.string("resumeToken")

            // Call 2: resume with the opaque token + a chosen option id → pin binding.
            val second =
                runBlocking {
                    handler.handle(
                        args("conversation_id" to "c-1", "resume_token" to token, "selected_option_id" to "M:qt-orlak"),
                        null,
                        null,
                    )
                }
            second.isError shouldBe false
            second.structuredContent
                .shouldNotBeNull()
                .child("resolution")
                .string("rationale") shouldBe
                "resumed via signed pin"
            // The door marshalled the resume oneof faithfully.
            val resumeReq = core.requests.last()
            resumeReq.hasResume().shouldBeTrue()
            resumeReq.resume.token shouldBe "tok-abc"
            resumeReq.resume.selectedOptionId shouldBe "M:qt-orlak"
        }

        "resume without selected_option_id is an INVALID_ARGUMENT error" {
            val core = FakeCore()
            val handler = ResolveDoorHandler(ResolveDoor(core::resolve), requireIdentity = false)

            val result =
                runBlocking {
                    handler.handle(
                        args("conversation_id" to "c-1", "resume_token" to "tok-abc"),
                        null,
                        null,
                    )
                }

            result.isError shouldBe true
            result.structuredContent.shouldNotBeNull().string("errorCode") shouldBe "INVALID_ARGUMENT"
            core.requests.isEmpty().shouldBeTrue()
        }

        "H-2 fail-closed: a call with no identity is refused before any resolution work" {
            val core = FakeCore()
            val handler = ResolveDoorHandler(ResolveDoor(core::resolve), requireIdentity = true)

            val result = runBlocking { handler.handle(args("conversation_id" to "c-1", "text" to "kolik"), null, null) }

            result.isError shouldBe true
            result.structuredContent.shouldNotBeNull().string("errorCode") shouldBe "missing_user_identity"
            core.requests.isEmpty().shouldBeTrue() // never reached the core
        }

        "a valid OBO bearer is allowed through the secured door" {
            val core = FakeCore()
            val handler = ResolveDoorHandler(ResolveDoor(core::resolve), requireIdentity = true)

            val result =
                runBlocking {
                    handler.handle(
                        args("conversation_id" to "c-1", "text" to "kolik"),
                        "Bearer ${unsignedJwt("alice")}",
                        null,
                    )
                }

            result.isError shouldBe false
            result.structuredContent
                .shouldNotBeNull()
                .containsKey("resolution")
                .shouldBeTrue()
        }

        "a token/user_id spoof is refused (identity_conflict), never resolved" {
            val core = FakeCore()
            val handler = ResolveDoorHandler(ResolveDoor(core::resolve), requireIdentity = true)

            val result =
                runBlocking {
                    handler.handle(
                        args("conversation_id" to "c-1", "text" to "kolik", "user_id" to "bob"),
                        "Bearer ${unsignedJwt("alice")}",
                        null,
                    )
                }

            result.isError shouldBe true
            result.structuredContent.shouldNotBeNull().string("errorCode") shouldBe "identity_conflict"
            core.requests.isEmpty().shouldBeTrue()
        }

        // RG-P6 review A: under TOKEN_ONLY a caller-supplied user_id (even `admin:`) is
        // NOT an identity — it can neither satisfy require-identity nor reach the core.
        "TOKEN_ONLY door: a user_id-only call is refused and never reaches the core" {
            val core = FakeCore()
            val handler =
                ResolveDoorHandler(
                    ResolveDoor(core::resolve),
                    requireIdentity = true,
                    policy = IdentityPolicy.TOKEN_ONLY,
                )

            val result =
                runBlocking {
                    handler.handle(
                        args("conversation_id" to "c-1", "text" to "kolik", "user_id" to "admin:root"),
                        null,
                        null,
                    )
                }

            result.isError shouldBe true
            result.structuredContent.shouldNotBeNull().string("errorCode") shouldBe "missing_user_identity"
            core.requests.isEmpty().shouldBeTrue()
        }

        // RG-P6 review C (wiring): the gate's resolved subject is stamped on the request
        // so the pipeline can sign+re-check it on a resume token.
        "the resolved OBO subject is threaded into the request as caller_subject" {
            val core = FakeCore()
            val handler = ResolveDoorHandler(ResolveDoor(core::resolve), requireIdentity = true)

            runBlocking {
                handler.handle(
                    args("conversation_id" to "c-1", "text" to "kolik"),
                    "Bearer ${unsignedJwt("alice")}",
                    null,
                )
            }

            core.requests.single().callerSubject shouldBe "alice"
        }
    })
