// SPDX-License-Identifier: Apache-2.0
package org.tatrman.chrono

import org.tatrman.grounding.v1.EntityKind
import org.tatrman.grounding.v1.GetStatusRequest
import org.tatrman.grounding.v1.GroundRequest
import org.tatrman.grounding.v1.GroundResponse
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import org.tatrman.chrono.discover.EmptySemanticDiscovery
import org.tatrman.chrono.grpc.ChronoGroundingService

/**
 * A8.2 scaffold DoD — the service boots, GetStatus answers, and the not-yet-implemented
 * Ground path degrades to UNGROUNDABLE (never throws). The recognizer specs (A8.3+) supersede
 * the Ground assertion here as the corpus turns green.
 */
class ChronoScaffoldSpec :
    StringSpec({
        val svc = ChronoGroundingService(EmptySemanticDiscovery, llmFallback = null)

        "GetStatus reports service=chrono, ready, and capability flags" {
            val s = svc.getStatus(GetStatusRequest.getDefaultInstance())
            s.service shouldBe "chrono"
            s.ready shouldBe true
            s.capabilitiesMap["metadata"] shouldBe "ok"
            s.capabilitiesMap["llm_fallback"] shouldBe "false"
        }

        "OL-C1: GetStatus declares scan=true — this chrono implements Scan" {
            svc.getStatus(GetStatusRequest.getDefaultInstance()).capabilitiesMap["scan"] shouldBe "true"
        }

        "Ground on an unrecognized span degrades to UNGROUNDABLE (scaffold)" {
            val r =
                svc.ground(
                    GroundRequest
                        .newBuilder()
                        .setSpanText("nothing recognizable yet")
                        .setKind(EntityKind.DATE_TIME)
                        .build(),
                )
            r.status shouldBe GroundResponse.Status.UNGROUNDABLE
        }
    })
