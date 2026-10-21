// SPDX-License-Identifier: Apache-2.0
package org.tatrman.query.flight

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.apache.arrow.flight.CallHeaders
import org.apache.arrow.flight.CallStatus
import org.apache.arrow.flight.ErrorFlightMetadata
import org.apache.arrow.flight.FlightRuntimeException
import org.apache.arrow.flight.auth2.Auth2Constants
import org.apache.arrow.flight.auth2.CallHeaderAuthenticator
import org.slf4j.LoggerFactory
import org.tatrman.mcp.identity.BearerVerifier
import org.tatrman.mcp.identity.IdentityPolicy
import org.tatrman.mcp.identity.IdentityResolver
import org.tatrman.mcp.identity.UserIdentity
import org.tatrman.mcp.identity.Verification
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.Base64

/**
 * BI (contracts C-2) — the edge's authenticator: query-mcp's identity path, behind Flight's header authentication.
 *
 * `Authorization: Bearer <JWT>` is verified by [verifier] (mcp-identity's `JwksBearerVerifier`: RS256 against the
 * realm's keys, issuer, expiry) and only then read by `IdentityResolver` under [IdentityPolicy.TOKEN_ONLY]: the user
 * is `preferred_username`, the roles `realm_access.roles`. `Authorization: Basic` is a user name and password, turned
 * into a bearer by [passwords] (Keycloak's direct-access grant on client `jdbc`); the bearer is verified like any
 * other and handed back in the response headers, where the Flight clients pick it up and send it from then on. The
 * edge keeps no password and no token.
 *
 * Every refusal is `UNAUTHENTICATED` with `"<code>: <one sentence>"`, except an identity provider that does not
 * answer, which is `UNAVAILABLE`: retrying is the right reaction to that one. The code leads the description because
 * that is all a client sees: Flight closes a call its middleware refused with empty trailers, so the `x-ttr-code`
 * metadata set here does not travel (it does for the producer's own refusals).
 */
class FlightAuthenticator(
    private val verifier: BearerVerifier,
    private val passwords: PasswordGrant? = null,
) : CallHeaderAuthenticator {
    override fun authenticate(incomingHeaders: CallHeaders): EdgeAuthResult {
        val header = incomingHeaders.get(Auth2Constants.AUTHORIZATION_HEADER)?.trim().orEmpty()
        val bearer = IdentityResolver.bearerTokenOf(header)
        return when {
            bearer != null -> EdgeAuthResult(identityOf(bearer), issuedBearer = null)
            header.startsWith(Auth2Constants.BASIC_PREFIX, ignoreCase = true) -> {
                val grant =
                    passwords ?: throw refused(
                        "password_login_off",
                        "this edge takes a bearer token only (the token property).",
                    )
                val (user, password) = basicCredentials(header.substring(Auth2Constants.BASIC_PREFIX.length))
                val token = grant.tokenFor(user, password)
                EdgeAuthResult(identityOf(token), issuedBearer = token)
            }
            else ->
                throw refused(
                    "missing_credentials",
                    "send a bearer token (the token property) or a user name and password.",
                )
        }
    }

    private fun identityOf(token: String): UserIdentity {
        val verdict = verifier.verify(token)
        if (verdict is Verification.Invalid) {
            log.warn("Flight edge: bearer refused ({})", verdict.reason)
            throw refused("invalid_token", "The bearer token was not accepted (${verdict.reason}).")
        }
        return IdentityResolver.resolve("Bearer $token", null, null, IdentityPolicy.TOKEN_ONLY)
            ?: throw refused("missing_user_identity", "the token names no user (preferred_username or sub).")
    }

    private fun basicCredentials(encoded: String): Pair<String, String> {
        val decoded =
            runCatching { String(Base64.getDecoder().decode(encoded.trim()), Charsets.UTF_8) }.getOrNull()
                ?: throw refused("missing_credentials", "the Basic credentials are not base64.")
        val user = decoded.substringBefore(':', "")
        if (user.isEmpty()) throw refused("missing_credentials", "the Basic credentials name no user.")
        return user to decoded.substringAfter(':')
    }

    companion object {
        private val log = LoggerFactory.getLogger(FlightAuthenticator::class.java)

        internal fun refused(
            code: String,
            sentence: String,
            status: CallStatus = CallStatus.UNAUTHENTICATED,
        ): FlightRuntimeException =
            status
                .withDescription("$code: $sentence")
                .withMetadata(ErrorFlightMetadata().apply { insert(TtrFlightSqlProducer.CODE_METADATA_KEY, code) })
                .toRuntimeException()
    }
}

/** A verified caller; for a password login, also the bearer the edge obtained, which goes back to the client. */
class EdgeAuthResult(
    val identity: UserIdentity,
    private val issuedBearer: String?,
) : CallHeaderAuthenticator.AuthResult {
    override fun getPeerIdentity(): String = identity.id

    override fun appendToOutgoingHeaders(outgoingHeaders: CallHeaders) {
        issuedBearer?.let {
            outgoingHeaders.insert(
                Auth2Constants.AUTHORIZATION_HEADER,
                Auth2Constants.BEARER_PREFIX + it,
            )
        }
    }
}

/** A user name and password turned into the realm's access token; refusals are Flight errors. */
fun interface PasswordGrant {
    fun tokenFor(
        user: String,
        password: String,
    ): String
}

/**
 * Keycloak's direct-access grant (`grant_type=password`) on [clientId] — a public client with direct access
 * enabled (`jdbc`); [clientSecret] only when the realm made it confidential. The password is sent once, to the
 * realm, and dropped.
 */
class KeycloakPasswordGrant(
    private val tokenUrl: String,
    private val clientId: String,
    private val clientSecret: String = "",
    private val http: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build(),
) : PasswordGrant {
    private val json = Json { ignoreUnknownKeys = true }

    override fun tokenFor(
        user: String,
        password: String,
    ): String {
        val form =
            buildList {
                add("grant_type" to "password")
                add("client_id" to clientId)
                if (clientSecret.isNotEmpty()) add("client_secret" to clientSecret)
                add("username" to user)
                add("password" to password)
            }.joinToString("&") { (k, v) -> "${encode(k)}=${encode(v)}" }
        val request =
            HttpRequest
                .newBuilder(URI.create(tokenUrl))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form))
                .build()
        val response =
            try {
                http.send(request, HttpResponse.BodyHandlers.ofString())
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw unavailable(e)
            } catch (e: Exception) {
                throw unavailable(e)
            }
        val body = runCatching { json.parseToJsonElement(response.body()).jsonObject }.getOrNull()
        val status = response.statusCode()
        if (status in 200..299) {
            return body?.get("access_token")?.jsonPrimitive?.contentOrNull
                ?: throw FlightAuthenticator.refused(
                    "identity_unavailable",
                    "Keycloak answered the password grant without a token.",
                    CallStatus.UNAVAILABLE,
                )
        }
        val error =
            body
                ?.get("error")
                ?.jsonPrimitive
                ?.contentOrNull
                ?.let { " $it" }
                .orEmpty()
        log.info("Flight edge: password grant for {} refused ({}{})", user, status, error)
        if (status >= 500) {
            throw FlightAuthenticator.refused(
                "identity_unavailable",
                "Keycloak failed the password grant ($status$error).",
                CallStatus.UNAVAILABLE,
            )
        }
        throw FlightAuthenticator.refused("password_refused", "Keycloak refused the password grant ($status$error).")
    }

    private fun unavailable(e: Exception): FlightRuntimeException {
        log.warn("Flight edge: Keycloak at {} did not answer: {}", tokenUrl, e.toString())
        return FlightAuthenticator.refused(
            "identity_unavailable",
            "Keycloak did not answer the password grant (${e.javaClass.simpleName}).",
            CallStatus.UNAVAILABLE,
        )
    }

    private fun encode(value: String): String = URLEncoder.encode(value, Charsets.UTF_8)

    companion object {
        private val log = LoggerFactory.getLogger(KeycloakPasswordGrant::class.java)
    }
}
