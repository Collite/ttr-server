// SPDX-License-Identifier: Apache-2.0
package org.tatrman.query.flight

import org.apache.arrow.flight.CallHeaders
import org.apache.arrow.flight.CallInfo
import org.apache.arrow.flight.CallStatus
import org.apache.arrow.flight.FlightServerMiddleware
import org.apache.arrow.flight.RequestContext
import org.apache.arrow.flight.auth2.Auth2Constants
import org.tatrman.query.world.Worlds

/**
 * BI (contracts C-2, C-3) — every call's caller and world, decided before the producer sees the call.
 *
 * The factory runs [authenticator] on the call's headers (no credential, no call) and resolves the connection's
 * world: the `world` header (a JDBC URL's unknown properties and ADBC's `rpc.call_header.world` both travel as
 * headers), else the world the connection chose with the JDBC `catalog` property. The driver sets `catalog` once,
 * by `SetSessionOptions`; the edge answers with a `ttr_world` cookie the driver sends back on every call after — so
 * the world needs no session state on this pod, and survives its restart. A world this door does not serve is
 * refused here, at connect, with `unknown_world`.
 *
 * The producer reads the result back by [KEY] ([SESSIONS]); a password login's bearer goes out in the response
 * headers.
 */
class IdentityWorldMiddleware internal constructor(
    val session: EdgeSession,
    private val auth: EdgeAuthResult,
    private val worlds: Worlds,
    /** The `world` header's value, when the call sent one: it outranks the cookie. */
    private val headerWorld: String = "",
) : FlightServerMiddleware {
    @Volatile private var keptWorld: String? = null

    /**
     * The connection asks to keep [world] (`SetSessionOptions(catalog)`; empty = forget it): it goes back as the world
     * cookie. Null when it may; else the refusal, `"<code>: <sentence>"` — a world this door does not serve
     * (`unknown_world`), or one the connection's `world` header contradicts (`world_mismatch`).
     */
    fun keepWorld(world: String): String? {
        world.takeIf { it.isNotEmpty() }?.let(worlds::refusal)?.let { (code, message) ->
            return "$code: $message"
        }
        return when {
            headerWorld.isNotEmpty() && world.isNotEmpty() && world != headerWorld ->
                "${TtrFlightSqlProducer.WORLD_MISMATCH}: this connection's world header says '$headerWorld'; " +
                    "catalog '$world' contradicts it."
            else -> {
                keptWorld = world
                null
            }
        }
    }

    override fun onBeforeSendingHeaders(outgoingHeaders: CallHeaders) {
        auth.appendToOutgoingHeaders(outgoingHeaders)
        keptWorld?.let { world ->
            outgoingHeaders.insert(
                SET_COOKIE_HEADER,
                if (world.isEmpty()) "$WORLD_COOKIE=; Max-Age=0" else "$WORLD_COOKIE=$world",
            )
        }
    }

    override fun onCallCompleted(status: CallStatus) = Unit

    override fun onCallErrored(err: Throwable) = Unit

    class Factory(
        private val authenticator: FlightAuthenticator,
        private val worlds: Worlds = Worlds.NONE,
    ) : FlightServerMiddleware.Factory<IdentityWorldMiddleware> {
        override fun onCallStarted(
            info: CallInfo,
            incomingHeaders: CallHeaders,
            context: RequestContext,
        ): IdentityWorldMiddleware {
            val auth = authenticator.authenticate(incomingHeaders)
            val headerWorld = incomingHeaders.get(WORLD_HEADER)?.trim().orEmpty()
            val world = headerWorld.ifEmpty { cookie(incomingHeaders, WORLD_COOKIE).orEmpty() }
            world.takeIf { it.isNotEmpty() }?.let(worlds::refusal)?.let { (code, message) ->
                throw FlightAuthenticator.refused(
                    code,
                    message,
                    CallStatus.INVALID_ARGUMENT,
                )
            }
            context.put(Auth2Constants.PEER_IDENTITY_KEY, auth.peerIdentity)
            val session =
                EdgeSession(
                    userId = auth.identity.id,
                    roles = auth.identity.roles.toList(),
                    world = world,
                    client = clientOf(incomingHeaders),
                )
            return IdentityWorldMiddleware(session, auth, worlds, headerWorld)
        }
    }

    companion object {
        val KEY: FlightServerMiddleware.Key<IdentityWorldMiddleware> =
            FlightServerMiddleware.Key.of(
                "ttr-identity-world",
            )

        /** The producer's view of a call: the session this middleware resolved, or null (the call is refused). */
        val SESSIONS = SessionResolver { context -> context.getMiddleware(KEY)?.session }

        fun binding(factory: Factory): MiddlewareBinding<IdentityWorldMiddleware> = MiddlewareBinding(KEY, factory)

        const val WORLD_HEADER = "world"
        const val WORLD_COOKIE = "ttr_world"

        /** What a client may say it is (the `tatrman://` Superset dialect says `superset`). */
        const val CLIENT_HEADER = "x-ttr-client"

        private const val SET_COOKIE_HEADER = "Set-Cookie"
        private const val COOKIE_HEADER = "Cookie"
        private val CLIENTS =
            setOf(
                EdgeSession.CLIENT_SUPERSET,
                EdgeSession.CLIENT_JDBC,
                EdgeSession.CLIENT_ADBC,
                EdgeSession.CLIENT_OTHER,
            )

        /**
         * The client kind for receipts and metrics (C-6): `x-ttr-client` when it names one, else the gRPC user agent —
         * the Flight SQL JDBC driver is grpc-java, ADBC's Flight SQL driver is grpc-go.
         */
        internal fun clientOf(headers: CallHeaders): String {
            headers
                .get(CLIENT_HEADER)
                ?.trim()
                ?.lowercase()
                ?.takeIf { it in CLIENTS }
                ?.let { return it }
            val agent = headers.get("user-agent").orEmpty().lowercase()
            return when {
                "superset" in agent -> EdgeSession.CLIENT_SUPERSET
                "adbc" in agent || "grpc-go" in agent -> EdgeSession.CLIENT_ADBC
                "jdbc" in agent || "grpc-java" in agent -> EdgeSession.CLIENT_JDBC
                else -> EdgeSession.CLIENT_OTHER
            }
        }

        /** [name]'s value in the call's `Cookie` header(s); null when absent or cleared. */
        internal fun cookie(
            headers: CallHeaders,
            name: String,
        ): String? =
            headers
                .getAll(COOKIE_HEADER)
                ?.toList()
                .orEmpty()
                .flatMap { it.split(';') }
                .map { it.trim() }
                .firstOrNull { it.substringBefore('=').trim() == name }
                ?.substringAfter('=')
                ?.trim()
                ?.removeSurrounding("\"")
                ?.ifEmpty { null }
    }
}
