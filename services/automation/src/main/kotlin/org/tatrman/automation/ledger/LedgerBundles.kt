// SPDX-License-Identifier: Apache-2.0
package org.tatrman.automation.ledger

import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import org.jetbrains.exposed.v1.jdbc.upsert
import org.tatrman.automation.bundle.ApprovalReader
import org.tatrman.automation.bundle.Bundle
import org.tatrman.automation.bundle.BundleBook
import java.time.Instant
import java.time.ZoneOffset

/**
 * The ledger's side of the bundle registry: the `bundles` rows (one per loaded bundle — a reload of one commit
 * updates its row) and the `approvals` reads that make a deployment stale (D-60).
 */
class LedgerBundles :
    BundleBook,
    ApprovalReader {
    override fun record(bundle: Bundle) {
        transaction {
            Bundles.upsert {
                it[id] = bundle.id
                it[kind] = bundle.kind.wire
                it[branch] = bundle.branch
                it[commit] = bundle.commit
                it[modelFp] = bundle.modelFp
                it[loadedAt] = bundle.loadedAt.atOffset(ZoneOffset.UTC)
                it[lastUsedAt] = bundle.loadedAt.atOffset(ZoneOffset.UTC)
            }
        }
    }

    override fun touch(
        id: String,
        at: Instant,
    ) {
        transaction { Bundles.update({ Bundles.id eq id }) { it[lastUsedAt] = at.atOffset(ZoneOffset.UTC) } }
    }

    override fun approved(
        kind: String,
        obj: String,
        fp: String,
    ): Boolean =
        transaction {
            val fpColumn =
                when (kind) {
                    "recipe" -> Approvals.fpText
                    "values" -> Approvals.fpValues
                    else -> Approvals.fp
                }
            Approvals
                .select(Approvals.id)
                .where {
                    (Approvals.kind eq kind) and (Approvals.obj eq obj) and (fpColumn eq fp) and
                        (Approvals.status eq "approved")
                }.limit(1)
                .any()
        }
}
