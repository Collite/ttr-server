// SPDX-License-Identifier: Apache-2.0
package org.tatrman.automation.writer

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import org.tatrman.automation.api.AutomationException
import org.tatrman.automation.api.ErrorCode
import org.tatrman.automation.bundle.BundleFixture
import org.tatrman.automation.identity.Caller
import org.tatrman.automation.identity.RunAsRef
import org.tatrman.automation.identity.ServiceTokens
import org.tatrman.automation.identity.TokenKind
import org.tatrman.automation.ledger.RunNames
import java.util.UUID

/**
 * S2.3's interim deferred context (⚑ Found 2026-11-12): without the pinned executor, a held commit or a revert rebuilds
 * its apply context only from the run's own served bundle and only as a declared identity with a configured credential.
 */
class DeferredContextsSpec :
    StringSpec({
        val bundle = BundleFixture.load()
        val ops = Caller("sub-ops", "olga", emptySet(), TokenKind.PERSON, "t", groups = setOf("hartland-ops"))
        val tokens = ServiceTokens(mapOf("AUTOMATION_DECIDER" to "svc-hartland-automation:s"), fetch = { "" })

        fun names(
            bundleId: String? = bundle.id,
            runAs: String = "svc-hartland-automation",
        ) = RunNames("release_order", "release_on_placement", "release_native_event", bundleId, runAs)

        "the run's own bundle and its run_as identity rebuild the context, acting with the identity's credential" {
            val context = BundleDeferredContexts({ bundle }, tokens).resolve(UUID.randomUUID(), names(), ops)
            context.world shouldBe "demo"
            context.identity.runAs shouldBe RunAsRef.Named("automation_decider")
            context.identity.principal shouldBe "svc-hartland-automation"
            context.identity.credential shouldBe "TTR_CONN_AUTOMATION_DECIDER"
            context.modelFp shouldBe bundle.modelFp
        }

        "another served bundle, a caller run_as or an unmapped credential is 409 close-not-ready" {
            for ((n, t) in listOf(
                names(bundleId = "served:master@other") to tokens,
                names(runAs = "maya") to tokens,
                names() to ServiceTokens(emptyMap(), fetch = { "" }),
            )) {
                shouldThrow<AutomationException> {
                    BundleDeferredContexts({ bundle }, t).resolve(UUID.randomUUID(), n, ops)
                }.code shouldBe ErrorCode.CLOSE_NOT_READY
            }
        }
    })
