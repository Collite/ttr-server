// SPDX-License-Identifier: Apache-2.0
package org.tatrman.identity.client

import com.typesafe.config.Config
import org.tatrman.identity.domain.UserSource
import org.slf4j.LoggerFactory
import shared.libs.db.common.DatabaseConnection

/**
 * The ERP user source (identity `db` mode). An ERP's user schema is its own, so nothing schema-specific ships in the
 * image: the four reads are SQL from configuration ([ErpQueries], `erp-database.queries`), each returning the columns
 * its contract names. [fromConfig] returns null — the source is skipped — while any of them is unset.
 *
 * A user whose email column is blank is given `<username>@<fallbackEmailDomain>`; with no domain configured such a
 * user is skipped, because users are matched across sources by email.
 */
class ErpClient(
    private val dbConnection: DatabaseConnection,
    private val queries: ErpQueries,
    private val fallbackEmailDomain: String = "",
) : UserSourceClient {
    private val logger = LoggerFactory.getLogger("ErpClient")

    override val source: UserSource = UserSource.ERP

    override suspend fun fetchUsers(): List<GenericUser> =
        dbConnection.query {
            dbConnection.getConnection().use { conn ->
                conn
                    .prepareStatement(queries.users)
                    .use { statement ->
                        val resultSet = statement.executeQuery()
                        val users = mutableListOf<GenericUser>()
                        var withoutEmail = 0
                        while (resultSet.next()) {
                            val fullname = resultSet.getString("fullname") ?: ""
                            val (first, last) =
                                fullname.split(" ", limit = 2).let {
                                    if (it.size == 2) it[0] to it[1] else it[0] to ""
                                }
                            val username = resultSet.getString("username")
                            val email = emailFor(resultSet.getString("email"), username, fallbackEmailDomain)
                            if (email == null) {
                                withoutEmail++
                                continue
                            }
                            users.add(
                                GenericUser(
                                    id = resultSet.getString("user_id"),
                                    username = username,
                                    firstName = first,
                                    lastName = last,
                                    email = email,
                                ),
                            )
                        }
                        if (withoutEmail > 0) {
                            logger.warn(
                                "Skipped {} ERP users with no email " +
                                    "(set erp-database.fallback-email-domain to keep them)",
                                withoutEmail,
                            )
                        }
                        logger.info("Fetched {} users from ERP", users.size)
                        users
                    }
            }
        }

    override suspend fun fetchRoles(): List<GenericRole> =
        dbConnection.query {
            dbConnection.getConnection().use { conn ->
                conn
                    .prepareStatement(queries.roles)
                    .use { statement ->
                        val resultSet = statement.executeQuery()
                        val roles = mutableListOf<GenericRole>()
                        while (resultSet.next()) {
                            roles.add(
                                GenericRole(
                                    id = resultSet.getString("id"),
                                    code = resultSet.getString("code"),
                                    description = resultSet.getString("description"),
                                    source = UserSource.ERP,
                                ),
                            )
                        }
                        logger.info("Fetched {} roles from ERP", roles.size)
                        roles
                    }
            }
        }

    override suspend fun fetchUserRoleMappings(): List<Pair<String, String>> =
        dbConnection.query {
            dbConnection.getConnection().use { conn ->
                conn
                    .prepareStatement(queries.userRoles)
                    .use { statement ->
                        val resultSet = statement.executeQuery()
                        val mappings = mutableListOf<Pair<String, String>>()
                        while (resultSet.next()) {
                            val userId = resultSet.getString("user_id")
                            val roleId = resultSet.getString("role_id")
                            if (userId != null && roleId != null) {
                                mappings.add(userId to roleId)
                            }
                        }
                        logger.info("Fetched {} role mappings from ERP", mappings.size)
                        mappings
                    }
            }
        }

    override suspend fun fetchRoleHierarchy(): List<Pair<String, String>> =
        dbConnection.query {
            dbConnection.getConnection().use { conn ->
                conn
                    .prepareStatement(queries.roleHierarchy)
                    .use { statement ->
                        val resultSet = statement.executeQuery()
                        val mappings = mutableListOf<Pair<String, String>>()
                        while (resultSet.next()) {
                            var parentRole = resultSet.getString("parent_role") ?: ""
                            val childRole = resultSet.getString("child_role")
                            if (parentRole == childRole) {
                                parentRole = ""
                            }
                            if (childRole != null) {
                                mappings.add(parentRole to childRole)
                            }
                        }
                        logger.info("Fetched {} role hierarchy entries from ERP", mappings.size)
                        mappings
                    }
            }
        }

    companion object {
        private val log = LoggerFactory.getLogger(ErpClient::class.java)

        /**
         * The ERP source from `<path>` (connection, `queries`, `fallback-email-domain`), or null — with a WARN
         * naming the missing keys, and no connection opened — while any of the four queries is unset.
         */
        fun fromConfig(
            config: Config,
            path: String = "erp-database",
        ): ErpClient? {
            val missing = ErpQueries.missingKeys(config, path)
            if (missing.isNotEmpty()) {
                log.warn(
                    "ERP user source skipped: {} not set (the ERP's own SQL, see infra/identity/README.md)",
                    missing.joinToString(),
                )
                return null
            }
            val dbConnection = DatabaseConnection.fromConfig(config, path)
            dbConnection.init()
            val domainPath = "$path.fallback-email-domain"
            val domain = if (config.hasPath(domainPath)) config.getString(domainPath).trim() else ""
            return ErpClient(dbConnection, checkNotNull(ErpQueries.fromConfig(config, path)), domain)
        }

        /** The ERP's email, else `<username>@<fallbackEmailDomain>`; null when neither can be had. */
        internal fun emailFor(
            email: String?,
            username: String?,
            fallbackEmailDomain: String,
        ): String? =
            when {
                !email.isNullOrBlank() -> email
                !username.isNullOrBlank() && fallbackEmailDomain.isNotBlank() -> "$username@$fallbackEmailDomain"
                else -> null
            }
    }
}

/**
 * The ERP source's four reads, from `<path>.queries`. Each must return the columns named here:
 *
 * - `users` — `user_id`, `username`, `fullname` (`"First Last"`), `email`; the users to sync (active ones only).
 * - `roles` — `id`, `code`, `description`.
 * - `user-roles` — `user_id`, `role_id`.
 * - `role-hierarchy` — `parent_role`, `child_role`; a row whose parent is itself is a root.
 */
data class ErpQueries(
    val users: String,
    val roles: String,
    val userRoles: String,
    val roleHierarchy: String,
) {
    companion object {
        val KEYS = listOf("users", "roles", "user-roles", "role-hierarchy")

        /** The `<path>.queries.*` keys that are unset or blank, fully qualified. */
        fun missingKeys(
            config: Config,
            path: String,
        ): List<String> =
            KEYS
                .map { "$path.queries.$it" }
                .filter { !config.hasPath(it) || config.getString(it).isBlank() }

        fun fromConfig(
            config: Config,
            path: String,
        ): ErpQueries? {
            if (missingKeys(config, path).isNotEmpty()) return null

            fun q(key: String) = config.getString("$path.queries.$key").trim()
            return ErpQueries(
                users = q("users"),
                roles = q("roles"),
                userRoles = q("user-roles"),
                roleHierarchy = q("role-hierarchy"),
            )
        }
    }
}
