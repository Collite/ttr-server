// SPDX-License-Identifier: Apache-2.0
package org.tatrman.automation.identity

import com.auth0.jwt.JWT
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.tatrman.keycloak.auth.KeycloakTokenProvider
import org.tatrman.keycloak.auth.TokenProvider
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/** A named identity's client credentials: `TTR_CONN_<NAME>` = `<client id>:<client secret>`. */
data class ClientCredential(
    val clientId: String,
    val secret: String,
) {
    override fun toString(): String = "ClientCredential(clientId=$clientId, secret=***)"

    companion object {
        /** Null for a value that is not `id:secret` — e.g. a database URL left under an identity's name. */
        fun parse(raw: String): ClientCredential? {
            val colon = raw.indexOf(':')
            if (colon <= 0 || colon == raw.length - 1) return null
            val id = raw.substring(0, colon)
            if (id.contains('/') || raw.substring(colon + 1).startsWith("//")) return null
            return ClientCredential(id, raw.substring(colon + 1))
        }
    }
}

/**
 * The service principals' tokens (§7): a credential name (`TTR_CONN_AUTOMATION` or `AUTOMATION`) → its
 * client-credentials token, cached until [early] before the token's own `exp` (a token without one is not
 * cached). [connections] are `automation.connections` — the `TTR_CONN_*` env merged over HOCON.
 */
class ServiceTokens(
    private val connections: Map<String, String>,
    private val fetch: suspend (ClientCredential) -> String,
    private val clock: Clock = Clock.systemUTC(),
    private val early: Duration = Duration.ofSeconds(30),
) {
    private class Held(
        val token: String,
        val until: Instant,
    )

    private val held = ConcurrentHashMap<String, Held>()
    private val locks = ConcurrentHashMap<String, Mutex>()

    /** The credential behind [name], or null when it is unset or not `id:secret` — `403 identity · unmapped`. */
    fun credential(name: String): ClientCredential? = connections[key(name)]?.let { ClientCredential.parse(it) }

    suspend fun token(name: String): String {
        val key = key(name)
        held[key]?.takeIf { clock.instant() < it.until }?.let { return it.token }
        return locks.computeIfAbsent(key) { Mutex() }.withLock {
            held[key]?.takeIf { clock.instant() < it.until }?.let { return@withLock it.token }
            val credential = checkNotNull(credential(key)) { "credential $key is not mapped" }
            val token = fetch(credential)
            JWT.decode(token).expiresAtAsInstant?.let { held[key] = Held(token, it - early) }
            token
        }
    }

    private fun key(name: String) = name.removePrefix(CONNECTION_ENV_PREFIX)

    companion object {
        private const val CONNECTION_ENV_PREFIX = "TTR_CONN_"

        /**
         * Client credentials at the realm's token endpoint (Keycloak). One provider — one HTTP client — per
         * credential, built on its first fetch and kept: a provider per fetch would leak a client every expiry.
         */
        fun keycloak(
            connections: Map<String, String>,
            tokenUrl: String,
            provider: (
                ClientCredential,
            ) -> TokenProvider = { KeycloakTokenProvider.create(tokenUrl, it.clientId, it.secret) },
        ): ServiceTokens {
            val providers = ConcurrentHashMap<ClientCredential, TokenProvider>()
            return ServiceTokens(connections, { c -> providers.computeIfAbsent(c, provider).getToken() })
        }
    }
}
