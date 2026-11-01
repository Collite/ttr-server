// SPDX-License-Identifier: Apache-2.0
package org.tatrman.metadata.cn

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import org.tatrman.metadata.refresh.GuardedRefresher
import org.tatrman.ttr.metadata.model.CnSchema

/** Keep database work off the publication thread; acknowledge only a completed observation. */
internal fun cnPromotionListener(
    refresher: GuardedRefresher,
    sourceId: String?,
    scope: CoroutineScope,
    observe: (CnSchema, String) -> Unit,
) {
    val log = LoggerFactory.getLogger("org.tatrman.metadata.cn.CnPromotionListener")
    refresher.addPublicationListener(sourceId) { outcome, complete ->
        val version = refresher.promotionCommit(sourceId, outcome)
        val schema =
            outcome.snapshot
                ?.model
                ?.schemas
                ?.get("cn") as? CnSchema
        if (version == null || schema == null) {
            complete(true)
            return@addPublicationListener
        }
        scope.launch(Dispatchers.IO) {
            var ok = false
            try {
                observe(schema, version)
                ok = true
            } catch (failure: Exception) {
                log.warn("cn candidates: merge check failed: {}", failure.message)
            } finally {
                complete(ok)
            }
        }
    }
}
