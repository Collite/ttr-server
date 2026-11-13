// SPDX-License-Identifier: Apache-2.0
package org.tatrman.resolver

import org.tatrman.grounding.v1.GroundingContext
import org.tatrman.grounding.v1.ScanResponse
import org.tatrman.resolver.client.GroundingClient

/** Existing grounding-only fixtures advertise no additional time spans. Scan replay lives in ScanSeamTest. */
interface GroundOnlyKernel : GroundingClient {
    override suspend fun scan(
        text: String,
        context: GroundingContext,
        pkg: String,
    ): ScanResponse = ScanResponse.getDefaultInstance()
}
