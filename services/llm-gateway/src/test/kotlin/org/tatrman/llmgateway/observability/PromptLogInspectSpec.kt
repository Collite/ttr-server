// SPDX-License-Identifier: Apache-2.0
package org.tatrman.llmgateway.observability

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.time.OffsetDateTime
import java.time.ZoneOffset

/**
 * Unit tier for the prompt-log inspect surface (PT contracts §5) — the parts
 * that need no database. Route + query behaviour against real Postgres lives in
 * the componentTest `PromptLogsRoutesSpec`, matching how the admin routes are
 * tested in this service.
 */
class PromptLogInspectSpec :
    StringSpec({

        "limit clamps: absent -> default, over ceiling -> max, non-positive -> 1" {
            clampPromptLogLimit(null) shouldBe PROMPT_LOGS_DEFAULT_LIMIT
            clampPromptLogLimit(10) shouldBe 10
            clampPromptLogLimit(PROMPT_LOGS_MAX_LIMIT) shouldBe PROMPT_LOGS_MAX_LIMIT
            // The ceiling is what stops an inspect tool becoming a bulk export of
            // every prompt and completion the estate has produced.
            clampPromptLogLimit(10_000) shouldBe PROMPT_LOGS_MAX_LIMIT
            clampPromptLogLimit(0) shouldBe 1
            clampPromptLogLimit(-5) shouldBe 1
        }

        "row serializes to the contracts §5 camelCase shape, nulls preserved as null" {
            val row =
                PromptLogRow(
                    id = 771,
                    turnRef = "turn-1",
                    traceId = "0af7651916cd43dd8448eb211c80319c",
                    requestedModel = "claude-opus-5",
                    servedModel = "claude-opus-5",
                    servedProvider = "azure",
                    fallbackFrom = null,
                    cached = false,
                    tokensPrompt = 1204,
                    tokensCompletion = 88,
                    durationMs = 910,
                    ttfbMs = 310,
                    costUsd = 0.0123,
                    status = "SUCCESS",
                    createdAt = OffsetDateTime.of(2026, 7, 30, 9, 0, 2, 0, ZoneOffset.UTC),
                    promptText = "the prompt",
                    responseText = "the completion",
                )

            val j = row.toJson()

            j.keys shouldBe
                setOf(
                    "id",
                    "turnRef",
                    "traceId",
                    "requestedModel",
                    "servedModel",
                    "servedProvider",
                    "fallbackFrom",
                    "cached",
                    "tokensPrompt",
                    "tokensCompletion",
                    "durationMs",
                    "ttfbMs",
                    "costUsd",
                    "status",
                    "createdAt",
                    "promptText",
                    "responseText",
                    "purpose", // LC §2.3
                    "agentId", // LC §2.3
                )
            // id is a BIGSERIAL — rendered as a string so a JS consumer cannot lose
            // precision on it the way it would with a bare number past 2^53.
            j["id"]!!.jsonPrimitive.content shouldBe "771"
            j["turnRef"]!!.jsonPrimitive.content shouldBe "turn-1"
            j["cached"]!!.jsonPrimitive.content shouldBe "false"

            // An absent fallback is JSON null, not the string "null" and not omitted:
            // the consumer distinguishes "no fallback happened" from "not reported".
            j["fallbackFrom"]!!.jsonPrimitive.isString shouldBe false
            j["fallbackFrom"]!!.jsonPrimitive.content shouldBe "null"
        }

        // LC §2.3 — purpose and agentId always (null on a pre-V5 row); endUserSubject ONLY for a role-holder:
        // a subject reading their own rows does not need to be told who they are.
        "the LC fields: purpose + agentId always, endUserSubject only when asked for a role-holder" {
            val row =
                PromptLogRow(
                    id = 9,
                    turnRef = "t",
                    traceId = null,
                    requestedModel = "fast",
                    servedModel = "gpt-5-mini",
                    servedProvider = "azure",
                    fallbackFrom = null,
                    cached = false,
                    tokensPrompt = 1,
                    tokensCompletion = 1,
                    durationMs = 1,
                    ttfbMs = null,
                    costUsd = null,
                    status = "SUCCESS",
                    createdAt = null,
                    promptText = null,
                    responseText = null,
                    purpose = "compose-plan",
                    endUserSubject = "sub-dan",
                    agentId = "golem-hartland",
                )
            val own = row.toJson()
            own["purpose"]!!.jsonPrimitive.content shouldBe "compose-plan"
            own["agentId"]!!.jsonPrimitive.content shouldBe "golem-hartland"
            own.containsKey("endUserSubject") shouldBe false

            row.toJson(includeSubject = true)["endUserSubject"]!!.jsonPrimitive.content shouldBe "sub-dan"

            // A pre-V5 row: the keys are there, as JSON null — "not recorded", distinguishable from "".
            val legacy = row.copy(purpose = null, agentId = null).toJson()
            legacy["purpose"]!!.jsonPrimitive.contentOrNull shouldBe null
            legacy["agentId"]!!.jsonPrimitive.contentOrNull shouldBe null
        }

        "bodies are returned RAW — the gateway must not pre-digest them" {
            // Redaction is the consumer's job (PT-20/21) because only it knows the
            // reader's profile. A gateway that digested here would also make the
            // consumer's redaction floor unauditable: it could no longer see what it
            // was supposed to remove.
            val secretish = "Authorization: Bearer eyJhbGciOiJIUzI1NiJ9.body.sig"
            val row =
                PromptLogRow(
                    id = 1,
                    turnRef = "t",
                    traceId = null,
                    requestedModel = null,
                    servedModel = null,
                    servedProvider = null,
                    fallbackFrom = null,
                    cached = false,
                    tokensPrompt = null,
                    tokensCompletion = null,
                    durationMs = null,
                    ttfbMs = null,
                    costUsd = null,
                    status = null,
                    createdAt = OffsetDateTime.now(ZoneOffset.UTC),
                    promptText = secretish,
                    responseText = null,
                )

            row.toJson()["promptText"]!!.jsonPrimitive.content shouldBe secretish
        }

        // review-079 R12. `created_at` is DEFAULT, not NOT NULL — a row carrying an
        // explicit NULL must serialize as null rather than take the page to a 500.
        "a null created_at serializes as null, not as the string \"null\"" {
            val row =
                PromptLogRow(
                    id = 3,
                    turnRef = "t",
                    traceId = null,
                    requestedModel = null,
                    servedModel = null,
                    servedProvider = null,
                    fallbackFrom = null,
                    cached = false,
                    tokensPrompt = null,
                    tokensCompletion = null,
                    durationMs = null,
                    ttfbMs = null,
                    costUsd = null,
                    status = null,
                    createdAt = null,
                    promptText = null,
                    responseText = null,
                )

            row.toJson()["createdAt"]!!.jsonPrimitive.contentOrNull shouldBe null
        }
    })
