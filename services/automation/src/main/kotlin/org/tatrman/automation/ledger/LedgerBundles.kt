// SPDX-License-Identifier: Apache-2.0
package org.tatrman.automation.ledger

import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import org.jetbrains.exposed.v1.jdbc.upsert
import org.tatrman.automation.bundle.ApprovalBook
import org.tatrman.automation.bundle.ApprovalFps
import org.tatrman.automation.bundle.ApprovalReader
import org.tatrman.automation.bundle.ApprovalRecord
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import java.sql.Connection
import java.util.UUID
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
    ApprovalReader,
    ApprovalBook {
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
            Approvals
                .select(Approvals.id)
                .where {
                    (Approvals.kind eq kind) and (Approvals.obj eq obj) and (fpColumn(kind) eq fp) and
                        (Approvals.status eq "approved")
                }.limit(1)
                .any()
        }

    override fun latestApproved(
        kind: String,
        obj: String,
    ): String? =
        transaction {
            Approvals
                .select(fpColumn(kind))
                .where { (Approvals.kind eq kind) and (Approvals.obj eq obj) and (Approvals.status eq "approved") }
                .orderBy(Approvals.at to SortOrder.DESC, Approvals.id to SortOrder.DESC)
                .limit(1)
                .firstOrNull()
                ?.get(fpColumn(kind))
        }

    override fun latestToolchain(
        kind: String,
        obj: String,
    ): String? =
        transaction {
            Approvals
                .select(Approvals.toolchain)
                .where { (Approvals.kind eq kind) and (Approvals.obj eq obj) and (Approvals.status eq "approved") }
                .orderBy(Approvals.at to SortOrder.DESC, Approvals.id to SortOrder.DESC)
                .limit(1)
                .firstOrNull()
                ?.get(Approvals.toolchain)
        }

    /** One query: every approved row of [objects], each under the fingerprint its kind is judged by. */
    override fun snapshot(objects: Collection<String>): ApprovalReader {
        if (objects.isEmpty()) return ApprovalReader { _, _, _ -> false }
        val rows =
            transaction {
                Approvals
                    .select(
                        Approvals.kind,
                        Approvals.obj,
                        Approvals.fpText,
                        Approvals.fpValues,
                        Approvals.fp,
                        Approvals.toolchain,
                    ).where { (Approvals.status eq "approved") and (Approvals.obj inList objects.distinct()) }
                    .orderBy(Approvals.at to SortOrder.DESC, Approvals.id to SortOrder.DESC)
                    .mapNotNull { row ->
                        val kind = row[Approvals.kind]
                        row[fpColumn(kind)]?.let { Triple(kind, row[Approvals.obj], it) to row[Approvals.toolchain] }
                    }
            }
        val approved = rows.map { it.first }
        return object : ApprovalReader {
            override fun latestToolchain(
                kind: String,
                obj: String,
            ): String? = rows.firstOrNull { it.first.first == kind && it.first.second == obj }?.second

            override fun approved(
                kind: String,
                obj: String,
                fp: String,
            ): Boolean = Triple(kind, obj, fp) in approved

            override fun latestApproved(
                kind: String,
                obj: String,
            ): String? = approved.firstOrNull { it.first == kind && it.second == obj }?.third
        }
    }

    /**
     * S2.3 (D-60): one transaction, serialized per (kind, object) by an advisory lock. An `approved` row under the same
     * fingerprint is answered as is (`created = false`); otherwise the row is inserted and the other `approved` rows of
     * that kind and object become `superseded`. Rows are never updated otherwise and never deleted.
     */
    override fun approve(record: ApprovalRecord): ApprovalRecord =
        transaction(transactionIsolation = Connection.TRANSACTION_READ_COMMITTED) {
            exec(
                "SELECT pg_advisory_xact_lock(hashtext(?))",
                listOf(
                    org.jetbrains.exposed.v1.core
                        .TextColumnType() to
                        "automation/approvals/${record.kind}/${record.obj}",
                ),
            ) {}
            val fp =
                requireNotNull(
                    when (record.kind) {
                        "recipe" -> record.fps.text
                        "values" -> record.fps.values
                        else -> record.fps.fp
                    },
                ) { "an approval names its fingerprint" }
            val column = fpColumn(record.kind)
            val existing =
                Approvals
                    .selectAll()
                    .where {
                        (Approvals.kind eq record.kind) and (Approvals.obj eq record.obj) and (column eq fp) and
                            (Approvals.status eq "approved")
                    }.orderBy(Approvals.at to SortOrder.ASC, Approvals.id to SortOrder.ASC)
                    .firstOrNull()
            if (existing != null) {
                return@transaction ApprovalRecord(
                    existing[Approvals.id].toString(),
                    existing[Approvals.objectKind],
                    existing[Approvals.obj],
                    existing[Approvals.kind],
                    ApprovalFps(existing[Approvals.fpText], existing[Approvals.fpValues], existing[Approvals.fp]),
                    existing[Approvals.approver],
                    existing[Approvals.at].toInstant().toString(),
                    existing[Approvals.note],
                    created = false,
                    toolchain = existing[Approvals.toolchain],
                )
            }
            Approvals.update({
                (Approvals.kind eq record.kind) and (Approvals.obj eq record.obj) and
                    (Approvals.status eq "approved") and (column neq fp)
            }) { it[status] = "superseded" }
            Approvals.insert {
                it[id] = UUID.fromString(record.id)
                it[objectKind] = record.objectKind
                it[obj] = record.obj
                it[fpText] = record.fps.text
                it[fpValues] = record.fps.values
                it[Approvals.fp] = record.fps.fp
                it[kind] = record.kind
                it[approver] = record.approver
                it[at] = Instant.parse(record.at).atOffset(ZoneOffset.UTC)
                it[note] = record.note
                it[status] = "approved"
                it[toolchain] = record.toolchain
            }
            record
        }

    private fun fpColumn(kind: String) =
        when (kind) {
            "recipe" -> Approvals.fpText
            "values" -> Approvals.fpValues
            else -> Approvals.fp
        }
}
