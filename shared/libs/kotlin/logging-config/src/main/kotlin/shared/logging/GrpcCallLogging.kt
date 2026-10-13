// SPDX-License-Identifier: Apache-2.0
package shared.logging

import com.google.protobuf.MessageOrBuilder
import com.google.protobuf.TextFormat
import io.grpc.CallOptions
import io.grpc.Channel
import io.grpc.ClientCall
import io.grpc.ClientInterceptor
import io.grpc.ForwardingClientCall
import io.grpc.ForwardingClientCallListener
import io.grpc.ForwardingServerCall
import io.grpc.ForwardingServerCallListener
import io.grpc.Metadata
import io.grpc.MethodDescriptor
import io.grpc.ServerCall
import io.grpc.ServerCallHandler
import io.grpc.ServerInterceptor
import io.grpc.Status
import org.slf4j.LoggerFactory

/**
 * Server-side interceptor: log every incoming gRPC call at INFO with its full request
 * payload, plus a close-status line so success/failure is visible in the same logger.
 *
 * Activates per-method loggers named `grpc.in.<fully.qualified.MethodName>` so operators
 * can tune verbosity of one RPC without touching the whole service.
 *
 * Payload formatter uses `TextFormat.shortDebugString` — a one-line protobuf rendering that
 * mirrors what the rest of the platform uses for diagnostic dumps. Non-protobuf payloads
 * fall back to `toString()`.
 */
class IncomingCallLoggingInterceptor : ServerInterceptor {
    override fun <ReqT : Any, RespT : Any> interceptCall(
        call: ServerCall<ReqT, RespT>,
        headers: Metadata,
        next: ServerCallHandler<ReqT, RespT>,
    ): ServerCall.Listener<ReqT> {
        val method = call.methodDescriptor.fullMethodName
        val log = LoggerFactory.getLogger("grpc.in.$method")

        val wrappedCall =
            object : ForwardingServerCall.SimpleForwardingServerCall<ReqT, RespT>(call) {
                override fun sendMessage(message: RespT) {
                    // Log the response CONTENT, not just the close status. Many
                    // RPCs carry their errors *in-band* (a status-OK response
                    // whose `messages` field holds the failure), so "closed OK"
                    // alone hides them. DEBUG + truncated to keep large/streaming
                    // payloads (e.g. ResultBatch arrow_ipc) from flooding logs.
                    if (log.isDebugEnabled) log.debug("⇠ {} response={}", method, formatPayload(message))
                    super.sendMessage(message)
                }

                override fun close(
                    status: Status,
                    trailers: Metadata,
                ) {
                    if (status.isOk) {
                        log.info("⇣ {} closed OK", method)
                    } else {
                        // the status as the handler left it: the exception's message stays off WARN
                        logFailedClose(log, method, status)
                    }
                    super.close(describedFailure(status), trailers)
                }
            }

        val listener = next.startCall(wrappedCall, headers)
        return object : ForwardingServerCallListener.SimpleForwardingServerCallListener<ReqT>(listener) {
            override fun onMessage(message: ReqT) {
                // The full request payload can carry secrets (e.g. a resume-token bearer
                // string) — keep it OFF the INFO line and only at DEBUG, and even then
                // route it through the field redactor (RG-P6 review B).
                log.info("⇢ {} received", method)
                if (log.isDebugEnabled) log.debug("⇢ {} payload={}", method, formatPayload(message))
                super.onMessage(message)
            }

            override fun onCancel() {
                log.info("⇣ {} cancelled by client", method)
                super.onCancel()
            }
        }
    }
}

/**
 * The close line of a failed call. A handler that THROWS closes `UNKNOWN` with an empty description
 * and the exception only as the status's `cause` — which never crosses the wire, so this line is the
 * one place it can be seen (ttr-server#197: two resolver crashes that logged nothing but
 * `closed UNKNOWN: `). The WARN line names the exception's class and where it was thrown; the
 * message and the stack go to DEBUG, because an exception message can quote the caller's words and
 * those stay off the INFO/WARN lines.
 */
internal fun logFailedClose(
    log: org.slf4j.Logger,
    method: String,
    status: Status,
) {
    val cause = status.cause
    if (cause == null) {
        log.warn("⇣ {} closed {}: {}", method, status.code, status.description ?: "")
        return
    }
    log.warn(
        "⇣ {} closed {}: {} — {} at {}",
        method,
        status.code,
        status.description ?: "",
        cause.javaClass.name,
        cause.stackTrace.firstOrNull() ?: "<no frame>",
    )
    if (log.isDebugEnabled) log.debug("⇣ {} failed", method, cause)
}

/** How much of a failure's message a status description carries (ttr-server#197). */
internal const val FAILURE_MESSAGE_MAX_CHARS = 200

/**
 * The status a failed call closes with, as the caller reads it. A handler that THROWS closes with
 * `Status.fromThrowable`: `UNKNOWN`, the exception as the `cause`, and no description. The cause
 * never crosses the wire, so the caller read `UNKNOWN: ` and nothing else (ttr-server#197). Such a
 * status is given a description: the exception's class, and the first line of its message with its
 * secrets masked, cut at [FAILURE_MESSAGE_MAX_CHARS]. The message goes back to the caller whose call
 * it was; the WARN line ([logFailedClose]) still names only the class and the throw site.
 *
 * A status that already has a description is passed on as it was, and so is one with no cause.
 */
internal fun describedFailure(status: Status): Status {
    if (status.isOk || !status.description.isNullOrBlank()) return status
    val cause = status.cause ?: return status
    val message =
        cause.message
            ?.lineSequence()
            ?.firstOrNull()
            ?.trim()
            .orEmpty()
    if (message.isEmpty()) return status.withDescription(cause.javaClass.name)
    val masked = redactSecrets(message)
    val short = if (masked.length > FAILURE_MESSAGE_MAX_CHARS) masked.take(FAILURE_MESSAGE_MAX_CHARS) + "…" else masked
    return status.withDescription("${cause.javaClass.name}: $short")
}

/**
 * Client-side interceptor: log every outgoing gRPC call at DEBUG, both pre-flight (before
 * the request hits the wire) and post-flight (response received and call closed). Uses
 * per-method loggers named `grpc.out.<fully.qualified.MethodName>`.
 *
 * DEBUG-only by design so production runs at INFO see no client traffic; flip the level
 * (via the `LOG_LEVEL` env var on the consuming service) to trace what your service is
 * sending downstream.
 */
class OutgoingCallLoggingInterceptor : ClientInterceptor {
    override fun <ReqT : Any, RespT : Any> interceptCall(
        method: MethodDescriptor<ReqT, RespT>,
        callOptions: CallOptions,
        next: Channel,
    ): ClientCall<ReqT, RespT> {
        val name = method.fullMethodName
        val log = LoggerFactory.getLogger("grpc.out.$name")

        return object : ForwardingClientCall.SimpleForwardingClientCall<ReqT, RespT>(
            next.newCall(method, callOptions),
        ) {
            override fun sendMessage(message: ReqT) {
                if (log.isDebugEnabled) log.debug("→ {} payload={}", name, formatPayload(message))
                super.sendMessage(message)
            }

            override fun start(
                responseListener: Listener<RespT>,
                headers: Metadata,
            ) {
                if (log.isDebugEnabled) log.debug("→ {} starting (headers={})", name, headers)
                val wrapped =
                    object : ForwardingClientCallListener.SimpleForwardingClientCallListener<RespT>(responseListener) {
                        override fun onMessage(message: RespT) {
                            if (log.isDebugEnabled) log.debug("← {} response={}", name, formatPayload(message))
                            super.onMessage(message)
                        }

                        override fun onClose(
                            status: Status,
                            trailers: Metadata,
                        ) {
                            if (status.isOk) {
                                if (log.isDebugEnabled) log.debug("✓ {} closed OK", name)
                            } else {
                                // status != OK is rare enough to surface above DEBUG so an
                                // operator at INFO sees it without flipping flags.
                                log.warn("✗ {} closed {}: {}", name, status.code, status.description ?: "")
                            }
                            super.onClose(status, trailers)
                        }
                    }
                super.start(wrapped, headers)
            }
        }
    }
}

/** Default cap on a single logged payload — bounds large/streaming messages (e.g. ResultBatch arrow_ipc). */
internal const val DEFAULT_MAX_PAYLOAD_CHARS = 4000

/**
 * Resolve the payload cap, overridable per deployment with `GRPC_LOG_PAYLOAD_MAX_CHARS`.
 *
 * **Why an override exists (2026-08-14).** 4000 is right for the case it was written for — a
 * `ResultBatch` carrying `arrow_ipc` would otherwise flood the log with megabytes of base64. It is
 * wrong for the case that motivated turning DEBUG on in the first place: a resolver
 * `ResolveResponse` runs to ~8 000 chars, and the cap lands **exactly** in the middle, keeping the
 * parse (tokens, lemmas, feats) and cutting the `resolution { bindings … mentions … gaps }` block —
 * the only part anyone reads a lattice dump for. Two live captures lost 4202 and 4705 chars each,
 * and a truncated dump reads exactly like a short one unless you notice the ellipsis.
 *
 * Rejected alternatives, so nobody re-opens them: raising the constant (it protects a real case),
 * and per-service constants (the cap is a property of the *deployment's* log budget, not of the
 * code). An unparseable or non-positive value falls back to the default rather than failing a
 * service at boot over a logging knob.
 */
internal fun payloadCapFrom(raw: String?): Int {
    val parsed =
        raw
            ?.trim()
            ?.toIntOrNull()
            ?.takeIf { it > 0 }
    return parsed ?: DEFAULT_MAX_PAYLOAD_CHARS
}

private val MAX_PAYLOAD_CHARS: Int = payloadCapFrom(System.getenv("GRPC_LOG_PAYLOAD_MAX_CHARS"))

/**
 * Proto field names whose string value is a secret and must never reach a log sink
 * (RG-P6 review B). `TextFormat.shortDebugString` renders these as `token: "…"`; the
 * [SECRET_FIELD_REGEX] masks the quoted value in place while keeping the field
 * visible so a payload dump stays diagnostically useful.
 */
private val SECRET_FIELD_NAMES =
    listOf("token", "resume_token", "authorization", "api_key", "apikey", "secret", "password")

/**
 * ⚠ The quoted value is walked with a POSSESSIVE loop (`*+`), which `java.util.regex` runs
 * iteratively. The greedy `(?:\\.|[^"\\])*` it replaces recursed once per character, so a long value
 * overflowed the stack of the thread logging it (ttr-server#197). A resolver clarification's resume
 * token runs to kilobytes: 20 options came to 8 450 characters, and on a 1 MB thread stack the old
 * walk died past ~1 000 characters while the JVM was cold and past ~4 100 once it was warm. The two
 * alternatives cannot both match at one position, so giving up backtracking changes no match.
 */
private val SECRET_FIELD_REGEX =
    Regex("(?i)\\b(${SECRET_FIELD_NAMES.joinToString("|")})\\b(\\s*:\\s*)\"(?:[^\"\\\\]|\\\\.)*+\"")

/**
 * `ttrk-` virtual-gateway keys (LG-P4·S1, D-1) are masked by VALUE, not field name: a leaked key can
 * appear bare in an exception message ("insert failed for ttrk-…"), which the field-name pass above would
 * miss. The `ttrk-` prefix is kept so a log stays diagnostic ("it was a virtual key") without the secret.
 */
private val TTRK_KEY_REGEX = Regex("ttrk-[A-Za-z0-9_-]+")

/** Mask secret-bearing field values AND bare `ttrk-` keys in a rendered payload / message string. */
internal fun redactSecrets(rendered: String): String {
    val fieldsMasked =
        SECRET_FIELD_REGEX.replace(
            rendered,
        ) { m -> "${m.groupValues[1]}${m.groupValues[2]}\"<redacted>\"" }
    return TTRK_KEY_REGEX.replace(fieldsMasked) { "ttrk-<redacted>" }
}

/**
 * A payload as its log line shows it. Rendering runs inside the call (`sendMessage`, `onMessage`), so
 * it must never throw: whatever it throws would fail a call whose handler had already answered, which
 * is how ttr-server#197 closed `Resolve` as `UNKNOWN`. A payload that cannot be rendered is logged as
 * such.
 */
internal fun formatPayload(message: Any?): String {
    val rendered =
        try {
            when (message) {
                null -> "<null>"
                is MessageOrBuilder -> redactSecrets(TextFormat.shortDebugString(message))
                else -> redactSecrets(message.toString())
            }
        } catch (e: RuntimeException) {
            return "<unrenderable payload: ${e.javaClass.name}>"
        } catch (e: StackOverflowError) {
            return "<unrenderable payload: ${e.javaClass.name}>"
        }
    return if (rendered.length > MAX_PAYLOAD_CHARS) {
        rendered.take(MAX_PAYLOAD_CHARS) + "…(+${rendered.length - MAX_PAYLOAD_CHARS} chars truncated)"
    } else {
        rendered
    }
}
