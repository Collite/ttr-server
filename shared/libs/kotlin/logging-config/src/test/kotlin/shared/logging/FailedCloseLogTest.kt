// SPDX-License-Identifier: Apache-2.0
package shared.logging

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.grpc.Status
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.slf4j.LoggerFactory

/**
 * The close line of a failed server call (ttr-server#197). A handler that throws closes `UNKNOWN`
 * with an empty description; the exception rides only as the status's `cause`, so this line is the
 * only place it can be seen — and its message can quote the caller's words, which stay at DEBUG.
 */
class FailedCloseLogTest :
    StringSpec({

        fun capture(
            level: Level,
            block: (org.slf4j.Logger) -> Unit,
        ): List<ILoggingEvent> {
            val logger = LoggerFactory.getLogger("grpc.in.test.FailedClose/Call") as Logger
            val appender = ListAppender<ILoggingEvent>().also { it.start() }
            logger.addAppender(appender)
            logger.level = level
            try {
                block(logger)
            } finally {
                logger.detachAppender(appender)
            }
            return appender.list
        }

        val thrown = IllegalStateException("span for Memphis DC overlaps")
        val crashed = Status.UNKNOWN.withCause(thrown)

        "a thrown handler's WARN line names the exception and where it was thrown, not its message" {
            val warn = capture(Level.WARN) { logFailedClose(it, "test.FailedClose/Call", crashed) }.single()

            warn.level shouldBe Level.WARN
            warn.formattedMessage shouldContain "closed UNKNOWN"
            warn.formattedMessage shouldContain "java.lang.IllegalStateException at "
            warn.formattedMessage shouldContain "FailedCloseLogTest"
            warn.formattedMessage shouldNotContain "Memphis"
            warn.throwableProxy shouldBe null
        }

        "at DEBUG the stack follows, message and all" {
            val events = capture(Level.DEBUG) { logFailedClose(it, "test.FailedClose/Call", crashed) }

            events.size shouldBe 2
            events[1].level shouldBe Level.DEBUG
            events[1].throwableProxy.message shouldBe "span for Memphis DC overlaps"
        }

        "a status with no cause logs the line it always did" {
            val warn =
                capture(Level.DEBUG) {
                    logFailedClose(it, "test.FailedClose/Call", Status.NOT_FOUND.withDescription("no such turn"))
                }.single()

            warn.formattedMessage shouldBe "⇣ test.FailedClose/Call closed NOT_FOUND: no such turn"
        }
    })
