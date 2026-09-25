// SPDX-License-Identifier: Apache-2.0
package org.tatrman.fuzzy.db

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.jetbrains.exposed.v1.jdbc.Database
import org.tatrman.fuzzy.config.DatabaseConfig
import org.tatrman.fuzzy.config.MssqlConfig
import org.tatrman.fuzzy.config.PostgresConfig
import javax.sql.DataSource

/**
 * Opens the Exposed connection the `metadata` loader runs member vocabularies' read plans
 * against. Only invoked when `fuzzy.loader.source = "metadata"`; the `static`
 * (in-repo JSON catalog) source never touches the DB.
 *
 * Forked from ai-platform `fuzzy-matcher` (lean carve-out re-added 2026-06-14):
 * package + config types moved to `org.tatrman.fuzzy.*`.
 */
object DatabaseFactory {
    fun connect(config: DatabaseConfig) {
        val pool = hikari(config)
        Database.connect(pool)
    }

    private fun hikari(config: DatabaseConfig): DataSource {
        val hikariConfig =
            HikariConfig().apply {
                when (config) {
                    is PostgresConfig -> {
                        driverClassName = "org.postgresql.Driver"
                        jdbcUrl = "jdbc:postgresql://${config.host}:${config.port}/${config.database}"
                    }
                    is MssqlConfig -> {
                        driverClassName = "com.microsoft.sqlserver.jdbc.SQLServerDriver"
                        jdbcUrl =
                            "jdbc:sqlserver://${config.host}:${config.port};" +
                            "databaseName=${config.database};encrypt=true;trustServerCertificate=true;"
                    }
                }
                username = config.user
                password = config.pass
                maximumPoolSize = 3
                isAutoCommit = false
                transactionIsolation = "TRANSACTION_REPEATABLE_READ"
                validate()
            }
        return HikariDataSource(hikariConfig)
    }
}
