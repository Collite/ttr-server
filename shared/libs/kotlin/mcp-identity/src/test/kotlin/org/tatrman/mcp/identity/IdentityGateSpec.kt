// SPDX-License-Identifier: Apache-2.0
package org.tatrman.mcp.identity

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import java.util.Base64

/**
 * The shared OBO identity gate (RG-P6.S1, lifted from the query-mcp edge;
 * kantheon-security §2 / §2.1). Agents must call with the user's OBO token;
 * the gate fails closed on missing identity and on token-vs-arg spoofing.
 */
class IdentityGateSpec :
    StringSpec({

        fun makeJwt(payload: String): String {
            val header =
                Base64.getUrlEncoder().withoutPadding().encodeToString("""{"alg":"none"}""".toByteArray())
            val body = Base64.getUrlEncoder().withoutPadding().encodeToString(payload.toByteArray())
            return "$header.$body.signature-ignored"
        }

        // (a) valid user bearer → identity with user_id + auth_roles from realm_access.roles
        "valid user bearer is allowed with user_id and realm roles" {
            val token = makeJwt("""{"preferred_username":"alice","realm_access":{"roles":["analyst","viewer"]}}""")
            val decision = IdentityGate.decide("Bearer $token", null, null, requireIdentity = true)
            val allow = decision.shouldBeInstanceOf<IdentityGate.Decision.Allow>()
            val identity = allow.identity.shouldBeInstanceOf<UserIdentity>()
            identity.id shouldBe "alice"
            identity.source shouldBe IdentitySource.TOKEN
            identity.roles shouldContain "analyst"
            identity.roles shouldContain "viewer"
        }

        // (b) no token (and identity required) → fail closed, Rule-6 missing_user_identity
        "no identity supplied is rejected fail-closed when identity is required" {
            val decision = IdentityGate.decide(null, null, null, requireIdentity = true)
            val reject = decision.shouldBeInstanceOf<IdentityGate.Decision.Reject>()
            reject.code shouldBe "missing_user_identity"
        }

        // (c) service-account-shaped token (no user claim) → resolves to nothing → rejected.
        // Agents must call with the user's OBO token, never service identity.
        "service-account token with no user claim is rejected (never service identity)" {
            // A client-credentials/service token shaped with no preferred_username and no sub.
            val svcToken = makeJwt("""{"clientId":"svc-pythia","typ":"Bearer","azp":"svc-pythia"}""")
            val decision = IdentityGate.decide("Bearer $svcToken", null, null, requireIdentity = true)
            val reject = decision.shouldBeInstanceOf<IdentityGate.Decision.Reject>()
            reject.code shouldBe "missing_user_identity"
        }

        // Spoof guard: a valid token plus a conflicting user_id arg is rejected.
        "token identity conflicting with a user_id arg is rejected" {
            val token = makeJwt("""{"preferred_username":"alice","realm_access":{"roles":["analyst"]}}""")
            val decision = IdentityGate.decide("Bearer $token", null, argUserId = "bob", requireIdentity = true)
            val reject = decision.shouldBeInstanceOf<IdentityGate.Decision.Reject>()
            reject.code shouldBe "identity_conflict"
        }

        // Dev-network path: identity not required + none supplied → allowed with null identity.
        "no identity is allowed (null) when identity is not required (dev-network path)" {
            val decision = IdentityGate.decide(null, null, null, requireIdentity = false)
            val allow = decision.shouldBeInstanceOf<IdentityGate.Decision.Allow>()
            allow.identity shouldBe null
        }

        // --- policy (RG-P6 review A): TOKEN_ONLY ignores caller-controlled sources ---

        // A client-supplied user_id arg must NOT satisfy require-identity under TOKEN_ONLY.
        "user_id arg does not satisfy require-identity under TOKEN_ONLY" {
            val decision =
                IdentityGate.decide(
                    null,
                    null,
                    argUserId = "admin:evil",
                    requireIdentity = true,
                    policy = IdentityPolicy.TOKEN_ONLY,
                )
            val reject = decision.shouldBeInstanceOf<IdentityGate.Decision.Reject>()
            reject.code shouldBe "missing_user_identity"
        }

        // The X-User-Id header is likewise ignored under TOKEN_ONLY.
        "X-User-Id header does not satisfy require-identity under TOKEN_ONLY" {
            val decision =
                IdentityGate.decide(
                    null,
                    userIdHeader = "admin:evil",
                    argUserId = null,
                    requireIdentity = true,
                    policy = IdentityPolicy.TOKEN_ONLY,
                )
            decision.shouldBeInstanceOf<IdentityGate.Decision.Reject>()
        }

        // The `admin:` prefix cannot self-grant admin from an arg under TOKEN_ONLY — and the
        // PERMISSIVE default still does (the dev-network behavior is preserved).
        "the admin: convention self-grants under PERMISSIVE but never under TOKEN_ONLY" {
            val permissive =
                IdentityGate.decide(
                    null,
                    null,
                    argUserId = "admin:root",
                    requireIdentity = true,
                    policy = IdentityPolicy.PERMISSIVE,
                )
            permissive
                .shouldBeInstanceOf<IdentityGate.Decision.Allow>()
                .identity!!
                .roles shouldContain "query-platform-admin"

            val tokenOnly =
                IdentityGate.decide(
                    null,
                    null,
                    argUserId = "admin:root",
                    requireIdentity = true,
                    policy = IdentityPolicy.TOKEN_ONLY,
                )
            tokenOnly.shouldBeInstanceOf<IdentityGate.Decision.Reject>()
        }

        // An OBO Bearer token still authenticates under TOKEN_ONLY (roles from realm_access only).
        "a Bearer token authenticates under TOKEN_ONLY with realm roles, not the admin: prefix" {
            val token = makeJwt("""{"preferred_username":"admin:ops","realm_access":{"roles":["analyst"]}}""")
            val decision =
                IdentityGate.decide(
                    "Bearer $token",
                    null,
                    null,
                    requireIdentity = true,
                    policy = IdentityPolicy.TOKEN_ONLY,
                )
            val identity =
                decision
                    .shouldBeInstanceOf<IdentityGate.Decision.Allow>()
                    .identity
                    .shouldBeInstanceOf<UserIdentity>()
            identity.id shouldBe "admin:ops"
            identity.roles shouldContain "analyst"
            identity.isAdmin shouldBe false // the admin: prefix did NOT grant admin under TOKEN_ONLY
        }

        // ── LR G2b — a verifying door ────────────────────────────────────────────────────────────
        val realm = rsaPair()
        val verifier =
            JwksBearerVerifier(
                { kid -> (realm.public as java.security.interfaces.RSAPublicKey).takeIf { kid == "k1" } },
                issuer = "https://keycloak.example/realms/kantheon",
            )

        "a verified bearer is allowed, with the claims it carries" {
            val token = signedToken(realm, roles = listOf("kantheon-area-hartland", "kantheon-scope-dc-5"))
            val decision =
                IdentityGate.decide(
                    "Bearer $token",
                    null,
                    null,
                    requireIdentity = true,
                    policy = IdentityPolicy.TOKEN_ONLY,
                    verifier = verifier,
                )
            val identity =
                decision
                    .shouldBeInstanceOf<IdentityGate.Decision.Allow>()
                    .identity
                    .shouldBeInstanceOf<UserIdentity>()
            identity.id shouldBe "petr"
            identity.roles shouldContain "kantheon-scope-dc-5"
        }

        "a forged bearer is refused — the role it claims never reaches a policy" {
            // Same claims, our own key: exactly what a decode-only door used to accept.
            val forged = signedToken(rsaPair(), roles = listOf("query-platform-admin"))
            val reject =
                IdentityGate
                    .decide("Bearer $forged", null, null, requireIdentity = true, verifier = verifier)
                    .shouldBeInstanceOf<IdentityGate.Decision.Reject>()
            reject.code shouldBe "invalid_token"
            reject.message shouldContain "signature"
        }

        "a forged bearer does not fall through to the header or the arg, even on a permissive door" {
            val forged = makeJwt("""{"preferred_username":"petr","realm_access":{"roles":["query-platform-admin"]}}""")
            val decision =
                IdentityGate.decide(
                    "Bearer $forged",
                    userIdHeader = "admin:ops",
                    argUserId = "admin:ops",
                    requireIdentity = true,
                    policy = IdentityPolicy.PERMISSIVE,
                    verifier = verifier,
                )
            decision.shouldBeInstanceOf<IdentityGate.Decision.Reject>().code shouldBe "invalid_token"
        }

        "an expired bearer is refused as invalid, not as missing" {
            val stale =
                signedToken(
                    realm,
                    expiresAt =
                        java.time.Instant
                            .now()
                            .minusSeconds(600),
                )
            val reject =
                IdentityGate
                    .decide("Bearer $stale", null, null, requireIdentity = true, verifier = verifier)
                    .shouldBeInstanceOf<IdentityGate.Decision.Reject>()
            reject.code shouldBe "invalid_token"
            reject.message shouldContain "expired"
        }

        "no bearer at all is still missing_user_identity under TOKEN_ONLY — the header is not an identity" {
            val decision =
                IdentityGate.decide(
                    null,
                    userIdHeader = "admin:ops",
                    argUserId = null,
                    requireIdentity = true,
                    policy = IdentityPolicy.TOKEN_ONLY,
                    verifier = verifier,
                )
            decision.shouldBeInstanceOf<IdentityGate.Decision.Reject>().code shouldBe "missing_user_identity"
        }
    })
