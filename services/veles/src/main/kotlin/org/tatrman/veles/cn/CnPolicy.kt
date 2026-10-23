// SPDX-License-Identifier: Apache-2.0
package org.tatrman.veles.cn

import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.JsonObject
import org.tatrman.concept.candidate.Admission
import org.tatrman.concept.candidate.ProducerRegistry
import org.tatrman.concept.candidate.Proposal
import org.tatrman.mcp.identity.BearerVerifier
import org.tatrman.mcp.identity.IdentityPolicy
import org.tatrman.mcp.identity.IdentityResolver
import org.tatrman.mcp.identity.Verification

/**
 * The caller of the candidate API = the verified bearer (A-CN-91, the automation service's D-94 rule): never a header
 * or a body field, because the store writes who submitted, rejected and promoted. [principal] is a person's
 * `preferred_username`, or a service account's client id (Keycloak names its user `service-account-<client id>`);
 * [roles] are the realm roles.
 */
data class CnCaller(
    val principal: String,
    val person: Boolean,
    val roles: Set<String>,
)

sealed interface CallerCheck {
    data class Verified(
        val caller: CnCaller,
    ) : CallerCheck

    /** [reason] is the verifier's category (`missing`, `signature`, `expired`, …) — safe to return. */
    data class Refused(
        val reason: String,
    ) : CallerCheck
}

/** `Authorization: Bearer` → [CnCaller]: verified by [verifier] (RS256 against the realm's JWKS), then read. */
class CnCallers(
    private val verifier: BearerVerifier,
) {
    fun check(authorization: String?): CallerCheck {
        val token = IdentityResolver.bearerTokenOf(authorization) ?: return CallerCheck.Refused("missing")
        val verdict = verifier.verify(token)
        if (verdict is Verification.Invalid) return CallerCheck.Refused(verdict.reason)
        val identity =
            IdentityResolver.resolve("Bearer $token", null, null, IdentityPolicy.TOKEN_ONLY)
                ?: return CallerCheck.Refused("missing_sub")
        val service = identity.id.startsWith(SERVICE_ACCOUNT_PREFIX)
        return CallerCheck.Verified(
            CnCaller(identity.id.removePrefix(SERVICE_ACCOUNT_PREFIX), !service, identity.roles),
        )
    }

    private companion object {
        const val SERVICE_ACCOUNT_PREFIX = "service-account-"
    }
}

/** A refusal the API answers with [status] and `{"error": <message>, "code": <code>, …[detail]}`. */
class CnRefused(
    val status: HttpStatusCode,
    val code: String,
    message: String,
    val detail: JsonObject? = null,
) : RuntimeException(message)

/**
 * Who may do what (cn contracts §6 + §8, A-CN-91):
 * - **submit** — a producer the [ProducerRegistry] admits (A-CN-83) AND that the caller may speak for: `manual:<p>` only
 *   the person `p` holding [stewardRole]; any other producer only the principals [producerPrincipals] maps it to. The
 *   registry's deployments are the ones the map names (`clinic:evidence` registers `clinic`).
 * - **reject / promote** — [stewardRole] (promotion authority: stewards promote concepts and vocabulary, AGEL K §5).
 * - **read the queue** — a steward or a mapped producer principal.
 * - **register a vocabulary version** — [importerRole] (Q15-5: importers register versions, never propose content).
 */
class CnPolicy(
    val stewardRole: String = "cn-steward",
    val importerRole: String = "cn-importer",
    producerPrincipals: Map<String, Set<String>> = emptyMap(),
) {
    val registry: ProducerRegistry = ProducerRegistry(deploymentsOf(producerPrincipals.keys))

    /** The map minus what the registry would never admit (an importer, an unregistered or a `manual:` id). */
    val producerPrincipals: Map<String, Set<String>> =
        producerPrincipals.filterKeys { p ->
            !p.startsWith("manual:") && registry.admit(p, ANY_TERM) is Admission.Admitted
        }

    private val principals: Set<String> =
        this.producerPrincipals.values
            .flatten()
            .toSet()

    fun isSteward(c: CnCaller): Boolean = stewardRole in c.roles

    fun isImporter(c: CnCaller): Boolean = importerRole in c.roles

    fun mayRead(c: CnCaller): Boolean = isSteward(c) || c.principal in principals

    fun requireSteward(
        c: CnCaller,
        what: String,
    ) {
        if (!isSteward(c)) throw CnRefused(HttpStatusCode.Forbidden, CN_FORBIDDEN, "$what needs the $stewardRole role")
    }

    /** Null when [caller] may submit [proposal] as [producer]; else the refusal. */
    fun admit(
        caller: CnCaller,
        producer: String,
        proposal: Proposal,
    ): CnRefused? {
        when (val a = registry.admit(producer, proposal)) {
            is Admission.Refused -> return CnRefused(HttpStatusCode.Forbidden, a.code, a.reason)
            Admission.Admitted -> Unit
        }
        val speaksFor =
            if (producer.startsWith("manual:")) {
                caller.person && caller.principal == producer.removePrefix("manual:") && isSteward(caller)
            } else {
                caller.principal in producerPrincipals[producer].orEmpty()
            }
        if (speaksFor) return null
        return CnRefused(
            HttpStatusCode.Forbidden,
            CN_PRODUCER_NOT_CALLER,
            if (producer.startsWith("manual:")) {
                "'$producer' is submitted by that steward, signed in as themselves with the $stewardRole role"
            } else {
                "'${caller.principal}' does not submit as '$producer'"
            },
        )
    }

    companion object {
        const val CN_FORBIDDEN = "CN_FORBIDDEN"
        const val CN_PRODUCER_NOT_CALLER = "CN_PRODUCER_NOT_CALLER"

        private val ANY_TERM = Proposal.Term("cn.X", null, "en", listOf("x"))
        private val DEPLOYMENT_ROLE = Regex("^([a-z][a-z0-9-]*):(evidence|span-catalogue)$")

        /** The deployments the map names, minus any the registry refuses to register (a built-in's name, `import`). */
        private fun deploymentsOf(producers: Set<String>): Set<String> =
            producers
                .mapNotNull { DEPLOYMENT_ROLE.find(it)?.groupValues?.get(1) }
                .filter { runCatching { ProducerRegistry(setOf(it)) }.isSuccess }
                .toSet()

        /**
         * `METADATA_CN_PRODUCERS`: `producer=principal[|principal…]`, comma-separated —
         * `kleio=kleio,au:synthesizer=automation,clinic:evidence=clinic-evidence`.
         */
        fun parsePrincipals(text: String): Map<String, Set<String>> =
            text
                .split(',')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .mapNotNull { pair ->
                    val producer = pair.substringBefore('=').trim()
                    val who =
                        pair
                            .substringAfter('=', "")
                            .split('|')
                            .map { it.trim() }
                            .filter { it.isNotEmpty() }
                    if (producer.isEmpty() || who.isEmpty()) null else producer to who.toSet()
                }.groupBy({ it.first }, { it.second })
                .mapValues { (_, sets) -> sets.flatten().toSet() }
    }
}
