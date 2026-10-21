// SPDX-License-Identifier: Apache-2.0
package org.tatrman.query.flight

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import org.apache.arrow.flight.FlightCallHeaders
import org.apache.arrow.flight.FlightRuntimeException
import org.apache.arrow.flight.FlightStatusCode
import org.apache.arrow.memory.RootAllocator
import org.tatrman.query.flight.AuthTestKit.KeycloakStub
import org.tatrman.query.flight.FlightTestKit.RunningEdge
import org.tatrman.query.flight.FlightTestKit.ScriptedDoor
import org.tatrman.query.flight.FlightTestKit.dataBatch
import org.tatrman.query.flight.FlightTestKit.ipc
import org.tatrman.query.flight.FlightTestKit.readAll
import org.tatrman.query.flight.FlightTestKit.tail
import org.tatrman.mcp.identity.IdentitySource
import java.time.Instant
import java.util.Base64

/**
 * BI (contracts C-2) — who may call the edge. A bearer the realm signed is the identity — `preferred_username` and
 * `realm_access.roles`, nothing else; a user name and password become one through Keycloak's direct-access grant
 * on client `jdbc`, and that bearer goes back in the handshake for the client to reuse. No credential, no call.
 */
class FlightAuthenticatorSpec :
    StringSpec({
        val allocator = RootAllocator()
        afterSpec { allocator.close() }

        // The wire's headers are gRPC metadata (case-insensitive); a bare FlightCallHeaders is not, so the specs
        // spell the header as Flight's Auth2Constants does.
        fun headers(authorization: String?) =
            FlightCallHeaders().apply { authorization?.let { insert("Authorization", it) } }

        fun basic(
            user: String,
            password: String,
        ) = "Basic " + Base64.getEncoder().encodeToString("$user:$password".toByteArray())

        fun refusal(block: () -> Unit): FlightRuntimeException = shouldThrow<FlightRuntimeException> { block() }

        "a bearer the realm signed is the identity: preferred_username and realm_access.roles" {
            val result =
                FlightAuthenticator(
                    AuthTestKit.verifier,
                ).authenticate(headers("Bearer ${AuthTestKit.token()}"))
            result.peerIdentity shouldBe "petr"
            result.identity.roles.toList() shouldContainExactly listOf("kantheon-area-hartland", "kantheon-scope-dc-5")
            result.identity.source shouldBe IdentitySource.TOKEN
            // A bearer the client sent is not sent back.
            FlightCallHeaders().also { result.appendToOutgoingHeaders(it) }.keys() shouldBe emptySet()
        }

        "an expired bearer is UNAUTHENTICATED, and says it expired" {
            val e =
                refusal {
                    FlightAuthenticator(AuthTestKit.verifier)
                        .authenticate(
                            headers("Bearer ${AuthTestKit.token(expiresAt = Instant.now().minusSeconds(600))}"),
                        )
                }
            e.status().code() shouldBe FlightStatusCode.UNAUTHENTICATED
            e.message shouldBe "invalid_token: The bearer token was not accepted (expired)."
        }

        "a bearer signed with any other key is refused; its claims are never read" {
            val forged = AuthTestKit.token(roles = listOf("query-platform-admin"), pair = AuthTestKit.otherRealm)
            val e = refusal { FlightAuthenticator(AuthTestKit.verifier).authenticate(headers("Bearer $forged")) }
            e.status().code() shouldBe FlightStatusCode.UNAUTHENTICATED
            e.message shouldBe "invalid_token: The bearer token was not accepted (signature)."
        }

        "no Authorization header is UNAUTHENTICATED, naming what to send" {
            val e = refusal { FlightAuthenticator(AuthTestKit.verifier).authenticate(headers(null)) }
            e.status().code() shouldBe FlightStatusCode.UNAUTHENTICATED
            e.message shouldStartWith "missing_credentials:"
            e.message shouldContain "bearer token"
            refusal { FlightAuthenticator(AuthTestKit.verifier).authenticate(headers("Negotiate abc")) }
                .message shouldStartWith "missing_credentials:"
        }

        "Basic is Keycloak's password grant on client jdbc; the bearer it returns goes back to the client" {
            KeycloakStub().use { keycloak ->
                val authenticator =
                    FlightAuthenticator(AuthTestKit.verifier, KeycloakPasswordGrant(keycloak.tokenUrl, "jdbc"))
                val result = authenticator.authenticate(headers(basic("petr", "petr-secret")))
                result.peerIdentity shouldBe "petr"
                result.identity.roles.toList() shouldContainExactly listOf("kantheon-area-hartland")
                val form = keycloak.forms.single()
                form["grant_type"] shouldBe "password"
                form["client_id"] shouldBe "jdbc"
                form["username"] shouldBe "petr"
                form.containsKey("client_secret") shouldBe false
                val outgoing = FlightCallHeaders().also { result.appendToOutgoingHeaders(it) }
                outgoing.get("Authorization") shouldStartWith "Bearer "
                AuthTestKit.verifier.verify(outgoing.get("Authorization").removePrefix("Bearer ")) shouldBe
                    org.tatrman.mcp.identity.Verification.Valid
            }
        }

        "a wrong password is UNAUTHENTICATED: Keycloak refused the password grant (401)" {
            KeycloakStub().use { keycloak ->
                val authenticator =
                    FlightAuthenticator(AuthTestKit.verifier, KeycloakPasswordGrant(keycloak.tokenUrl, "jdbc"))
                val e = refusal { authenticator.authenticate(headers(basic("petr", "wrong"))) }
                e.status().code() shouldBe FlightStatusCode.UNAUTHENTICATED
                e.message shouldBe "password_refused: Keycloak refused the password grant (401 invalid_grant)."
            }
        }

        "Keycloak unreachable is UNAVAILABLE (retryable), not a wrong password" {
            val authenticator =
                FlightAuthenticator(
                    AuthTestKit.verifier,
                    KeycloakPasswordGrant("http://127.0.0.1:1/realms/kantheon/protocol/openid-connect/token", "jdbc"),
                )
            val e = refusal { authenticator.authenticate(headers(basic("petr", "petr-secret"))) }
            e.status().code() shouldBe FlightStatusCode.UNAVAILABLE
            e.message shouldStartWith "identity_unavailable:"
        }

        "an edge with no password login answers Basic with what it does accept" {
            val e = refusal { FlightAuthenticator(AuthTestKit.verifier).authenticate(headers(basic("petr", "x"))) }
            e.status().code() shouldBe FlightStatusCode.UNAUTHENTICATED
            e.message shouldBe "password_login_off: this edge takes a bearer token only (the token property)."
        }

        "over the wire: the handshake answers Basic with the bearer, and the next call runs as its user" {
            KeycloakStub().use { keycloak ->
                val door = ScriptedDoor { listOf(dataBatch(0, ipc(allocator, listOf(1 to "a")), 1), tail(1)) }
                val identity =
                    IdentityWorldMiddleware.Factory(
                        FlightAuthenticator(AuthTestKit.verifier, KeycloakPasswordGrant(keycloak.tokenUrl, "jdbc")),
                    )
                RunningEdge(door, identity = identity).use { edge ->
                    val bearer = edge.client.authenticateBasicToken("petr", "petr-secret").get()
                    val info = edge.sql.execute("SELECT id, name FROM er.entity.warehouse", bearer)
                    edge.sql.getStream(info.endpoints.single().ticket, bearer).use { it.readAll() }
                    val request = door.requests.single()
                    request.context.userId shouldBe "petr"
                    request.context.authRolesList shouldContainExactly listOf("kantheon-area-hartland")
                    keycloak.forms.size shouldBe 1
                    // Without any credential the same edge refuses.
                    val e = refusal { edge.sql.execute("SELECT 1") }
                    e.status().code() shouldBe FlightStatusCode.UNAUTHENTICATED
                }
            }
        }
    })
