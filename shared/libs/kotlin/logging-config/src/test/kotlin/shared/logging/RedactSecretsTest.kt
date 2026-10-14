// SPDX-License-Identifier: Apache-2.0
package shared.logging

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

/** [block] on a fresh thread with a [bytes]-sized stack — what it returned, or what it threw. */
private fun <T> onStack(
    bytes: Long,
    block: () -> T,
): Result<T> {
    var result: Result<T>? = null
    val thread = Thread(null, { result = runCatching(block) }, "redact-small-stack", bytes)
    // runCatching takes Throwable, so a StackOverflowError comes back as the failure
    thread.start()
    thread.join()
    return checkNotNull(result)
}

/**
 * RG-P6 review B — secret-bearing fields must never reach a log sink. The gRPC call
 * interceptor renders payloads with `TextFormat.shortDebugString`, which emits
 * `token: "…"`; [redactSecrets] masks those values in place while keeping the field
 * name visible for diagnostics.
 */
class RedactSecretsTest :
    StringSpec({

        "a resume token in a rendered payload is masked" {
            val rendered = """conversation_id: "c-1" resume { token: "abc.def.ghi" selected_option_id: "M:x" }"""
            val out = redactSecrets(rendered)
            out shouldNotContain "abc.def.ghi"
            out shouldContain "token: \"<redacted>\""
            out shouldContain "selected_option_id: \"M:x\"" // non-secret fields survive
        }

        "resume_token, authorization, secret and password are all masked (case-insensitive)" {
            val rendered = """resume_token: "T" Authorization: "Bearer z" secret: "s" password: "p" api_key: "k""""
            val out = redactSecrets(rendered)
            listOf("\"T\"", "\"Bearer z\"", "\"s\"", "\"p\"", "\"k\"").forEach { out shouldNotContain it }
        }

        // ttr-server#197 — the mask used to walk a quoted value one character per recursion
        // (`(?:\\.|[^"\\])*`), so a long value overflowed the stack of the thread logging it. A
        // clarification's resume token signs every option it offers: 20 options came to 8 450
        // characters, and on a 1 MB thread stack (the Linux default) the walk died past ~1 000
        // characters while the JVM was cold and past ~4 100 once it was warm.
        "#197 a resume token as long as a 20-option clarification's is masked on a 1 MB stack" {
            val token = "eyJjb252ZXJzYXRpb25JZCI6".padEnd(8450, 'Q')
            val rendered = """awaiting { options { id: "V:er.entity.item" } resume_token: "$token" } trace_id: "t""""

            val out = onStack(1L shl 20) { redactSecrets(rendered) }.getOrThrow()

            out shouldNotContain token
            out shouldContain "resume_token: \"<redacted>\""
            out shouldContain "trace_id: \"t\""
        }

        "#197 the mask walks a value of any length, plain or escape-heavy, without recursing" {
            val plain = "A".repeat(1_000_000)
            // TextFormat writes non-ASCII as octal escapes: every character of the value is an escape
            val escaped = "\\303\\241".repeat(125_000)

            val out =
                onStack(128L shl 10) {
                    redactSecrets("""resume_token: "$plain" text: "kept" token: "$escaped" locale: "cs"""")
                }.getOrThrow()

            out shouldBe """resume_token: "<redacted>" text: "kept" token: "<redacted>" locale: "cs""""
        }

        "#197 an escaped quote stays inside the value, and an unterminated value is left as it was" {
            redactSecrets("""token: "a\"b" other: "c"""") shouldBe """token: "<redacted>" other: "c""""
            val open = "token: \"" + "A".repeat(10_000)
            onStack(128L shl 10) { redactSecrets(open) }.getOrThrow() shouldBe open
        }

        "a payload with no secret fields is unchanged" {
            val rendered = """conversation_id: "c-1" fresh { text: "kolik za DF" locale: "cs" }"""
            redactSecrets(rendered) shouldBe rendered
        }

        // LG-P4·S1·T5 — a ttrk- virtual key is masked by value, even bare in an exception message (D-1).
        "a ttrk- virtual key is masked by value in a bare (non-field) message" {
            val plaintext = "ttrk-" + "aB3_dE6-hI9".repeat(3) + "xyz1234"
            val rendered = "key issuance insert failed for $plaintext at team golem"
            val out = redactSecrets(rendered)
            out shouldNotContain plaintext
            out shouldContain "ttrk-<redacted>"
            out shouldContain "team golem" // surrounding diagnostics survive
        }
    })
