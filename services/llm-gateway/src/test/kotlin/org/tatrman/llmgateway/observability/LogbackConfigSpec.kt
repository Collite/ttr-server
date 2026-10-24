// SPDX-License-Identifier: Apache-2.0
package org.tatrman.llmgateway.observability

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.joran.JoranConfigurator
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import org.slf4j.Logger

/**
 * The shipped `logback.xml`, loaded into a fresh context: INFO unless LLM_GATEWAY_LOG_LEVEL says otherwise, and
 * Exposed (whose DEBUG lines are SQL statements with their values, prompt_logs' prompt and response text
 * included) at INFO whatever the root says.
 */
class LogbackConfigSpec :
    StringSpec({
        fun context(level: String? = null): LoggerContext {
            val key = "LLM_GATEWAY_LOG_LEVEL"
            val before = System.getProperty(key)
            if (level != null) System.setProperty(key, level) else System.clearProperty(key)
            try {
                return LoggerContext().also { ctx ->
                    JoranConfigurator()
                        .apply {
                            setContext(
                                ctx,
                            )
                        }.doConfigure(LogbackConfigSpec::class.java.getResource("/logback.xml"))
                }
            } finally {
                if (before != null) System.setProperty(key, before) else System.clearProperty(key)
            }
        }

        "the root logs at INFO" {
            context().getLogger(Logger.ROOT_LOGGER_NAME).level shouldBe Level.INFO
            context().getLogger("io.lettuce.core.protocol.CommandHandler").effectiveLevel shouldBe Level.INFO
        }

        "LLM_GATEWAY_LOG_LEVEL=DEBUG moves the root, and Exposed's SQL stays out" {
            val ctx = context("DEBUG")
            ctx.getLogger(Logger.ROOT_LOGGER_NAME).level shouldBe Level.DEBUG
            ctx.getLogger("Exposed").effectiveLevel shouldBe Level.INFO
        }
    })
