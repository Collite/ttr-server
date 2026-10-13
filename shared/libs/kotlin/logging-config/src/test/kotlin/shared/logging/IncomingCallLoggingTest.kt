// SPDX-License-Identifier: Apache-2.0
package shared.logging

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.grpc.ManagedChannel
import io.grpc.MethodDescriptor
import io.grpc.Server
import io.grpc.ServerServiceDefinition
import io.grpc.Status
import io.grpc.StatusException
import io.grpc.inprocess.InProcessChannelBuilder
import io.grpc.inprocess.InProcessServerBuilder
import io.grpc.kotlin.ClientCalls
import io.grpc.kotlin.ServerCalls
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldEndWith
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import org.slf4j.LoggerFactory
import java.io.InputStream
import java.util.concurrent.TimeUnit
import kotlin.coroutines.EmptyCoroutineContext

/**
 * ttr-server#197 — a server call through [IncomingCallLoggingInterceptor], end to end over an
 * in-process channel, the way the resolver serves `Resolve`.
 *
 * The resolver closed `Resolve` as `UNKNOWN` with an empty message on two kinds of turn (a Czech
 * question opening with a numeral, and a place right after a restart). Both had answered a
 * clarification, so the response carried a resume token several kilobytes long. With the call logger
 * at DEBUG, the interceptor rendered that response for its log line, and masking the token walked it
 * with a regex that recursed once per character: a `StackOverflowError` inside the interceptor's
 * `sendMessage`. The handler had already answered; the logger failed the call. grpc-kotlin then closes
 * a thrown call with `Status.fromThrowable`, which is `UNKNOWN` with no description, and the caller
 * read `UNKNOWN: ` and nothing else.
 */
class IncomingCallLoggingTest :
    StringSpec({

        "#197 a response holding a long resume token closes OK with the call logger at DEBUG, logged masked" {
            val token = "T".repeat(200_000)
            val (answer, events) =
                serve(Level.DEBUG) { """awaiting { resume_token: "$token" } trace_id: "t"""" }

            answer.getOrThrow() shouldContain token
            val logged = events.single { it.formattedMessage.startsWith("⇠ $METHOD response=") }.formattedMessage
            logged shouldContain "resume_token: \"<redacted>\""
            logged shouldNotContain token
            events.single { it.formattedMessage.startsWith("⇣ ") }.formattedMessage shouldBe "⇣ $METHOD closed OK"
        }

        "#197 a handler that throws closes with a status naming the exception and its message" {
            val (answer, _) = serve(Level.INFO) { throw IllegalStateException("span for Memphis DC overlaps") }

            val status = (answer.exceptionOrNull() as StatusException).status
            status.code shouldBe Status.Code.UNKNOWN
            status.description shouldBe "java.lang.IllegalStateException: span for Memphis DC overlaps"
        }

        "#197 the WARN line still names only the class and the site — the message reaches the caller, not the log" {
            val (_, events) = serve(Level.INFO) { throw IllegalStateException("span for Memphis DC overlaps") }

            val warn = events.single { it.level == Level.WARN }.formattedMessage
            warn shouldStartWith "⇣ $METHOD closed UNKNOWN:  — java.lang.IllegalStateException at "
            warn shouldNotContain "Memphis"
        }

        "#197 an exception with no message is named by its class alone" {
            val (answer, _) = serve(Level.INFO) { throw IndexOutOfBoundsException() }

            (answer.exceptionOrNull() as StatusException).status.description shouldBe
                "java.lang.IndexOutOfBoundsException"
        }

        "#197 the message is cut to its first line and its secrets masked" {
            val long = "first line names token: \"abc.def\" and " + "x".repeat(500) + "\nsecond line"
            val (answer, _) = serve(Level.INFO) { throw IllegalArgumentException(long) }

            val description = (answer.exceptionOrNull() as StatusException).status.description!!
            description shouldStartWith
                "java.lang.IllegalArgumentException: first line names token: \"<redacted>\" and xxx"
            description shouldNotContain "abc.def"
            description shouldNotContain "second line"
            // 200 characters of message, then the ellipsis
            description.length shouldBe "java.lang.IllegalArgumentException: ".length + 200 + 1
            description shouldEndWith "x…"
        }

        "a status the handler chose is passed on as it was" {
            val (answer, _) =
                serve(Level.INFO) {
                    throw StatusException(Status.NOT_FOUND.withDescription("no such turn"))
                }

            val status = (answer.exceptionOrNull() as StatusException).status
            status.code shouldBe Status.Code.NOT_FOUND
            status.description shouldBe "no such turn"
        }

        "#197 a payload that cannot be rendered is logged as such and never fails the call" {
            val unrenderable =
                object {
                    override fun toString(): String = throw IllegalStateException("cannot render")
                }

            formatPayload(unrenderable) shouldBe "<unrenderable payload: java.lang.IllegalStateException>"
        }
    }) {
    private companion object {
        const val METHOD = "test.Logged/Answer"

        /** A plain-string wire: the interceptor renders a non-protobuf payload with `toString()`. */
        val UTF8 =
            object : MethodDescriptor.Marshaller<String> {
                override fun stream(value: String): InputStream = value.toByteArray().inputStream()

                override fun parse(stream: InputStream): String = stream.readBytes().decodeToString()
            }

        val ANSWER: MethodDescriptor<String, String> =
            MethodDescriptor
                .newBuilder(UTF8, UTF8)
                .setType(MethodDescriptor.MethodType.UNARY)
                .setFullMethodName(METHOD)
                .build()

        /**
         * One call to a server whose handler answers with [handler], through the interceptor, with
         * `grpc.in` at [level]. The caller's result, and what the interceptor logged.
         */
        suspend fun serve(
            level: Level,
            handler: (String) -> String,
        ): Pair<Result<String>, List<ILoggingEvent>> {
            val name = InProcessServerBuilder.generateName()
            val server: Server =
                InProcessServerBuilder
                    .forName(name)
                    .directExecutor()
                    .intercept(IncomingCallLoggingInterceptor())
                    .addService(
                        ServerServiceDefinition
                            .builder("test.Logged")
                            .addMethod(
                                ServerCalls.unaryServerMethodDefinition(EmptyCoroutineContext, ANSWER) { handler(it) },
                            ).build(),
                    ).build()
                    .start()
            val channel: ManagedChannel = InProcessChannelBuilder.forName(name).directExecutor().build()

            val logger = LoggerFactory.getLogger("grpc.in") as Logger
            val previous = logger.level
            val appender = ListAppender<ILoggingEvent>().also { it.start() }
            logger.addAppender(appender)
            logger.level = level
            try {
                val answer = runCatching { ClientCalls.unaryRpc(channel, ANSWER, "question") }
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
