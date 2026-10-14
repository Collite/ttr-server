// SPDX-License-Identifier: Apache-2.0
package org.tatrman.resolver

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.grpc.Status
import io.grpc.StatusException
import io.grpc.inprocess.InProcessChannelBuilder
import io.grpc.inprocess.InProcessServerBuilder
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.mockk.coEvery
import io.mockk.mockk
import org.slf4j.LoggerFactory
import org.tatrman.resolver.grpc.ResolverGrpcService
import org.tatrman.resolver.pipeline.ResolverPipeline
import org.tatrman.resolver.v1.AwaitingClarification
import org.tatrman.resolver.v1.Option
import org.tatrman.resolver.v1.FreshQuestion
import org.tatrman.resolver.v1.ResolveRequest
import org.tatrman.resolver.v1.ResolveResponse
import org.tatrman.resolver.v1.ResolverServiceGrpcKt
import shared.logging.IncomingCallLoggingInterceptor
import java.util.concurrent.TimeUnit

/**
 * ttr-server#197 — `Resolve` closed `UNKNOWN` with an empty message, served the way `Application.kt`
 * serves it: [ResolverGrpcService] behind [IncomingCallLoggingInterceptor].
 *
 * hartland runs the call logger at DEBUG. Both failing kinds of turn had answered a clarification
 * („10 nejprodávanějších produktů na tržišti v roce 2025“ asked about `roce` with 20 product names;
 * "Marketplace revenue in 2025 for Memphis DC" asked which `DC`), so the response carried a resume token
 * that signs every option: 8 450 characters for the 20. Masking it for the response's log line walked
 * it with a regex that recursed once per character, and the `StackOverflowError` (logged since #202 as
 * `java.lang.StackOverflowError at java.base/java.util.regex.Pattern$Branch.match`) failed the call
 * after the pipeline had answered. How long a token overflowed depended on the stack and on how warm the
 * JVM was (~1 000 characters cold, ~4 100 warm on a 1 MB stack), which is why the place question failed
 * only in the minutes after a restart.
 *
 * The token here is 100 000 characters, past what the old walk survives on any default stack, so the
 * test does not depend on the JVM's warmth. The realistic length is pinned on a 1 MB stack in
 * logging-config's `RedactSecretsTest`.
 */
class ResolveCallLoggingTest :
    StringSpec({

        "#197 a clarification with a long resume token closes OK with the call logger at DEBUG" {
            val token = "eyJjb252ZXJzYXRpb25JZCI6".padEnd(100_000, 'Q')
            val clarification =
                ResolveResponse
                    .newBuilder()
                    .setAwaiting(
                        AwaitingClarification
                            .newBuilder()
                            .addOptions(Option.newBuilder().setId("V:er.entity.date_dim.year").setLabel("za rok"))
                            .addOptions(Option.newBuilder().setId("V:er.entity.item.product_name").setLabel("produkt"))
                            .setResumeToken(token),
                    ).setTraceId("t-197")
                    .build()
            val pipeline = mockk<ResolverPipeline>()
            coEvery { pipeline.resolve(any()) } returns clarification

            val (answer, events) = resolve(pipeline, Level.DEBUG)

            answer.getOrThrow().awaiting.resumeToken shouldBe token
            val logged = events.single { it.formattedMessage.startsWith("⇠ $RESOLVE response=") }.formattedMessage
            logged shouldContain "resume_token: \"<redacted>\""
            logged shouldNotContain token
            events.single { it.formattedMessage.startsWith("⇣ ") }.formattedMessage shouldBe "⇣ $RESOLVE closed OK"
        }

        "#197 a resolve that throws closes UNKNOWN with the exception's class and message, never empty" {
            val pipeline = mockk<ResolverPipeline>()
            coEvery { pipeline.resolve(any()) } throws IndexOutOfBoundsException("Index 0 out of bounds for length 0")

            val (answer, events) = resolve(pipeline, Level.INFO)

            val status = (answer.exceptionOrNull() as StatusException).status
            status.code shouldBe Status.Code.UNKNOWN
            status.description shouldBe "java.lang.IndexOutOfBoundsException: Index 0 out of bounds for length 0"
            events.single { it.level == Level.WARN }.formattedMessage shouldContain
                "closed UNKNOWN:  — java.lang.IndexOutOfBoundsException at "
        }
    }) {
    private companion object {
        const val RESOLVE = "org.tatrman.resolver.v1.ResolverService/Resolve"

        suspend fun resolve(
            pipeline: ResolverPipeline,
            level: Level,
        ): Pair<Result<ResolveResponse>, List<ILoggingEvent>> {
            val name = InProcessServerBuilder.generateName()
            val server =
                InProcessServerBuilder
                    .forName(name)
                    .directExecutor()
                    .intercept(IncomingCallLoggingInterceptor())
                    .addService(ResolverGrpcService(pipeline))
                    .build()
                    .start()
            val channel = InProcessChannelBuilder.forName(name).directExecutor().build()
            val logger = LoggerFactory.getLogger("grpc.in") as Logger
            val previous = logger.level
            val appender = ListAppender<ILoggingEvent>().also { it.start() }
            logger.addAppender(appender)
            logger.level = level
            try {
                val request =
                    ResolveRequest
                        .newBuilder()
                        .setConversationId("c-197")
                        .setFresh(FreshQuestion.newBuilder().setText("10 nejprodávanějších produktů").setLocale("cs"))
                        .build()
                val answer =
                    runCatching { ResolverServiceGrpcKt.ResolverServiceCoroutineStub(channel).resolve(request) }
                return answer to appender.list.toList()
            } finally {
                logger.detachAppender(appender)
                logger.level = previous
                channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS)
                server.shutdownNow().awaitTermination(5, TimeUnit.SECONDS)
            }
        }
    }
}
