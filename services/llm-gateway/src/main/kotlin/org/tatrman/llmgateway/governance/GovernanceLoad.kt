// SPDX-License-Identifier: Apache-2.0
package org.tatrman.llmgateway.governance

import org.slf4j.LoggerFactory
import org.tatrman.llmgateway.auth.KeyValidator
import org.tatrman.llmgateway.auth.PgKeyValidator
import org.tatrman.llmgateway.config.GovernanceConfig
import shared.libs.db.common.DatabaseConnection

/** The governance layer a booted, PG-backed gateway exposes (T6): repos, issuance, and the real validator. */
data class Governance(
    val teams: TeamRepo,
    val keys: VirtualKeyRepo,
    val keyService: KeyService,
    val validator: KeyValidator,
)

/**
 * Startup governance load (T6): upsert config teams (FK integrity) then import the `governance.yaml`
 * seeded keys into `virtual_keys` (G-3) — idempotent, so re-boots no-op. Seeded-hash validity (64-hex) is
 * already enforced by the LG-P1 `ConfigValidator` at load, so a bad hash fails boot before we get here.
 * Returns the wired [Governance] whose [Governance.validator] is the PG-backed production path.
 */
object GovernanceLoad {
    private val log = LoggerFactory.getLogger(GovernanceLoad::class.java)

    fun apply(
        db: DatabaseConnection,
        governance: GovernanceConfig,
    ): Governance {
        val teams = TeamRepo(db)
        val keys = VirtualKeyRepo(db)

        teams.upsertAll(governance.teams)
        governance.keys.forEach { k -> keys.upsertSeeded(k.team, k.name, k.sha256.lowercase()) }
        log.info(
            "governance loaded: {} teams upserted, {} seeded keys imported",
            governance.teams.size,
            governance.keys.size,
        )

        val teamIds = governance.teams.mapTo(HashSet()) { it.id }
        return Governance(teams, keys, KeyService(keys, teams), PgKeyValidator(keys, teamIds))
    }
}
