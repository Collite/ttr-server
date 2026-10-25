// SPDX-License-Identifier: Apache-2.0
package org.tatrman.metadata.cn

import com.typesafe.config.ConfigFactory
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.tatrman.concept.candidate.Proposal
import org.tatrman.mcp.identity.Verification

/**
 * CN-P5 S5.3 T7 (A-CN-91) — who may do what, and when the store is on at all: the caller is the verified bearer; a
 * producer id is spoken for by the principals the deployment maps to it; the store never runs without a database AND
 * a token issuer.
 */
class CnPolicySpec :
    FunSpec({
        test("METADATA_CN_PRODUCERS: producer=principal[|principal…], comma-separated") {
            CnPolicy.parsePrincipals(
                " kleio=kleio , au:synthesizer=automation|au-bot,clinic:evidence=ev, junk ,=x,y=",
            ) shouldBe
                mapOf(
                    "kleio" to setOf("kleio"),
                    "au:synthesizer" to setOf("automation", "au-bot"),
                    "clinic:evidence" to setOf("ev"),
                )
        }

        test("the map registers its deployments, and drops what the registry would never admit") {
            val policy =
                CnPolicy(
                    producerPrincipals =
                        mapOf(
                            "clinic:evidence" to setOf("ev"),
                            "import:icd" to setOf("importer"),
                            "manual:someone" to setOf("someone"),
                            "kleio:evidence" to setOf("k"), // a built-in's name is not a deployment
                            "other" to setOf("o"),
                        ),
                )
            policy.registry.deployments shouldBe setOf("clinic")
            policy.producerPrincipals.keys shouldBe setOf("clinic:evidence")
        }

        test("the caller is the token: a service account's client id, a person's username, the realm roles") {
            val callers = CnCallers { Verification.Valid }
            callers.check("Bearer ${CnTestKit.token("clinic-evidence", service = true)}") shouldBe
                CallerCheck.Verified(CnCaller("clinic-evidence", person = false, roles = emptySet()))
            callers.check("Bearer ${CnTestKit.STEWARD}") shouldBe
                CallerCheck.Verified(CnCaller("a.steward", person = true, roles = setOf("cn-steward")))
            callers.check(null) shouldBe CallerCheck.Refused("missing")
            callers.check("Basic abc") shouldBe CallerCheck.Refused("missing")
            CnCallers { Verification.Invalid("expired") }.check("Bearer ${CnTestKit.STEWARD}") shouldBe
                CallerCheck.Refused("expired")
        }

        test("manual:<p> is the steward p, in person") {
            val policy = CnTestKit.policy
            val term = Proposal.Term("cn.Transfer", null, "cs", listOf("překlad"))
            policy.admit(CnCaller("a.steward", true, setOf("cn-steward")), "manual:a.steward", term).shouldBeNull()
            policy.admit(CnCaller("a.steward", true, emptySet()), "manual:a.steward", term)!!.code shouldBe
                CnPolicy.CN_PRODUCER_NOT_CALLER
            policy.admit(CnCaller("b.steward", true, setOf("cn-steward")), "manual:a.steward", term)!!.code shouldBe
                CnPolicy.CN_PRODUCER_NOT_CALLER
        }

        test("a bare principal is a service account, user:<name> a person — a username is never a client id (F5)") {
            val policy =
                CnPolicy(
                    producerPrincipals =
                        mapOf(
                            "clinic:evidence" to setOf("clinic-evidence", "user:a.miner"),
                            "clinic:span-catalogue" to setOf("clinic-catalogue"),
                        ),
                )
            val term = Proposal.Term("cn.Transfer", null, "cs", listOf("překlad"))
            val service = CnCaller("clinic-evidence", person = false, roles = emptySet())
            val impostor = CnCaller("clinic-evidence", person = true, roles = emptySet())
            val miner = CnCaller("a.miner", person = true, roles = emptySet())
            val minerService = CnCaller("a.miner", person = false, roles = emptySet())
            policy.admit(service, "clinic:evidence", term).shouldBeNull()
            policy.admit(impostor, "clinic:evidence", term)!!.code shouldBe CnPolicy.CN_PRODUCER_NOT_CALLER
            policy.admit(miner, "clinic:evidence", term).shouldBeNull()
            policy.admit(minerService, "clinic:evidence", term)!!.code shouldBe CnPolicy.CN_PRODUCER_NOT_CALLER
            policy.mayRead(service) shouldBe true
            policy.mayRead(miner) shouldBe true
            policy.mayRead(impostor) shouldBe false
            policy.mayRead(minerService) shouldBe false
        }

        test("off without a database, off without a token issuer — and it says which") {
            fun setup(conf: String) = CnSetup.fromConfig(ConfigFactory.parseString(conf)) { null }
            setup("").surface.shouldBeInstanceOf<CnSurface.Off>().reason shouldContain "METADATA_CN_DB_HOST"
            setup("""metadata.cn.db { host = "pg", port = "5432", database = "m", user = "m", password = "" }""")
                .surface
                .shouldBeInstanceOf<CnSurface.Off>()
                .reason shouldContain "METADATA_CN_DB_PASSWORD"
            setup("""metadata.cn.db { host = "pg", port = "5432", database = "m", user = "m", password = "p" }""")
                .surface
                .shouldBeInstanceOf<CnSurface.Off>()
                .reason shouldContain "METADATA_CN_AUTH_ISSUER"
            val on =
                setup(
                    """
                    metadata.cn {
                        db { host = "pg", port = "5432", database = "m", user = "m", password = "p" }
                        auth { issuer = "https://kc/realms/r", jwks-uri = "https://kc/realms/r/certs" }
                        producers = "kleio=kleio"
                        promotion.source = "github-model"
                    }
                    """.trimIndent(),
                )
            on.surface
                .shouldBeInstanceOf<CnSurface.On>()
                .service.promotionEnabled shouldBe false
            (on.vocabularySource != null) shouldBe true
            on.promotionSourceId.shouldBeNull() // no `github-model` block here: promotion off
            on.store?.close()
        }
    })
