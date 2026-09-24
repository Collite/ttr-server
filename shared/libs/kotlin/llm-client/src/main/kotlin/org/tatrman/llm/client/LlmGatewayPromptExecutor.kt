// SPDX-License-Identifier: Apache-2.0
package org.tatrman.llm.client

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.prompt.dsl.ModerationResult
import ai.koog.prompt.dsl.Prompt
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.ContentPart
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.ResponseMetaInfo
import ai.koog.prompt.streaming.StreamFrame
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import org.slf4j.LoggerFactory
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * Koog [PromptExecutor] that bridges to [LlmGatewayClient] — the single LLM access
 * point for Koog-based agents (Themis nodes, Golem's PlanComposer).
 *
 * Scope:
 *  - `execute(prompt, model, tools)` — extracts text from System + User messages,
 *    maps Koog's [LLModel] id to a gateway tier key (`"haiku"` / `"sonnet"` /
 *    `"opus"`), returns a single [Message.Assistant] wrapping the gateway reply.
 *  - `executeStreaming` / `moderate` — the gateway exposes neither; both throw
 *    [UnsupportedOperationException].
 *  - `tools` ignored — agents call MCP tools directly, not via Koog tool-routing.
 *  - `close()` is a no-op — the gateway is owned by the caller.
 *  - [onCompletion] — the side channel for what Koog's return type cannot carry (LC contracts §1):
 *    every SUCCESSFUL completion's [LlmCompletion] (the prompt-log row id above all) is handed to it
 *    before `execute` returns. It is a constructor callback rather than a "last completion" slot so
 *    that concurrent turns sharing one executor never read each other's; and it is `suspend` so the
 *    collector can find the CALLING turn on its coroutine context. A failed call never reaches it
 *    (a gateway error is a failure, not an empty completion — review-100 F2), and a callback that
 *    throws is logged, never allowed to fail a call the gateway already served.
 */
class LlmGatewayPromptExecutor(
    private val gateway: LlmGatewayClient,
    private val now: () -> Instant = { Clock.System.now() },
    private val onCompletion: suspend (LlmCompletion) -> Unit = {},
) : PromptExecutor() {
    private val logger = LoggerFactory.getLogger(LlmGatewayPromptExecutor::class.java)

    override suspend fun execute(
        prompt: Prompt,
        model: LLModel,
        tools: List<ToolDescriptor>,
    ): List<Message.Response> {
        val systemContent = prompt.textOf<Message.System>()
        val userContent = prompt.textOf<Message.User>()
        val temperature = prompt.params.temperature ?: 0.0

        val completion =
            gateway
                .completeWithMeta(
                    prompt = userContent,
                    systemPrompt = systemContent,
                    model = mapModelToGatewayKey(model),
                    temperature = temperature,
                ).getOrThrow()
        // The call is served, billed and logged by now: a collector that throws must not turn it into a
        // failure Koog might retry (and pay for twice) — review-100 F18.
        try {
            onCompletion(completion)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn(
                "onCompletion failed for call {} — the completion is returned regardless",
                completion.callRef,
                e,
            )
        }

        return listOf(
            Message.Assistant(
                content = completion.content,
                metaInfo = ResponseMetaInfo(timestamp = now()),
            ),
        )
    }

    override fun executeStreaming(
        prompt: Prompt,
        model: LLModel,
        tools: List<ToolDescriptor>,
    ): Flow<StreamFrame> =
        flow {
            throw UnsupportedOperationException(
                "LlmGatewayClient does not expose streaming. Switch to a streaming-capable executor for this Koog feature.",
            )
        }

    override suspend fun moderate(
        prompt: Prompt,
        model: LLModel,
    ): ModerationResult =
        throw UnsupportedOperationException(
            "LlmGatewayClient does not expose moderation. Compose a separate moderation executor if needed.",
        )

    override fun close() {
        // gateway lifecycle is managed by the caller — do not close it here.
    }

    companion object {
        /**
         * Map a Koog [LLModel] to one of the gateway's GENERIC tier keys — `"deep"` (smart),
         * `"fast"` (mid), `"mini"` (cheap). These are provider-agnostic: the gateway catalog
         * maps them to concrete models per deployment (baseline: all → one Azure gpt-4.1). Unknown
         * ids fall through to `"mini"` (cheapest). Consumers (Golem PlanComposer, Themis nodes)
         * therefore request tiers, not vendor names — decoupling the caller from the routed model.
         */
        fun mapModelToGatewayKey(model: LLModel): String =
            when {
                model.id.contains("opus", ignoreCase = true) -> "deep"
                model.id.contains("sonnet", ignoreCase = true) -> "fast"
                model.id.contains("haiku", ignoreCase = true) -> "mini"
                else -> "mini"
            }

        private inline fun <reified T : Message> Prompt.textOf(): String =
            messages
                .filterIsInstance<T>()
                .flatMap { it.parts }
                .filterIsInstance<ContentPart.Text>()
                .joinToString("\n") { it.text }
    }
}
