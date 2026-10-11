// SPDX-License-Identifier: Apache-2.0
package org.tatrman.identity.client

import com.typesafe.config.ConfigFactory
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import shared.libs.db.common.DatabaseConnection
import java.sql.DriverManager

/**
 * Unit spec: the ERP user source reads the ERP through the SQL its deployment configures (the image
 * ships none) and maps each query's contract columns. An in-memory H2 schema stands in for "some
 * ERP": its names are unlike the contract's, so only the configured queries can bridge them.
 */
class ErpClientSpec :
    StringSpec({

        val url = "jdbc:h2:mem:erp-client-spec;DB_CLOSE_DELAY=-1"
        val schema =
            listOf(
                "CREATE TABLE app_user (uid VARCHAR(10), login VARCHAR(20), name VARCHAR(40), " +
                    "mail VARCHAR(40), blocked INT)",
                "INSERT INTO app_user VALUES ('u1', 'ada', 'Ada Lovelace', 'ada@example.org', 0), " +
                    "('u2', 'bob', 'Bob', NULL, 0), ('u3', 'eve', 'Eve Spy', 'eve@example.org', 1)",
                "CREATE TABLE app_group (gid VARCHAR(10), parent_gid VARCHAR(10), label VARCHAR(20), note VARCHAR(40))",
                "INSERT INTO app_group VALUES ('g1', 'g1', 'admin', 'Administrators'), ('g2', 'g1', 'viewer', NULL)",
                "CREATE TABLE app_membership (uid VARCHAR(10), gid VARCHAR(10))",
                "INSERT INTO app_membership VALUES ('u1', 'g1'), ('u2', 'g2')",
            )
        DriverManager.getConnection(url).use { conn ->
            conn.createStatement().use { st -> schema.forEach { st.execute(it) } }
        }

        val queries =
            ErpQueries(
                users =
                    "SELECT uid AS user_id, login AS username, name AS fullname, mail AS email " +
                        "FROM app_user WHERE blocked = 0",
                roles = "SELECT gid AS id, label AS code, note AS description FROM app_group",
                userRoles = "SELECT uid AS user_id, gid AS role_id FROM app_membership",
                roleHierarchy = "SELECT parent_gid AS parent_role, gid AS child_role FROM app_group",
            )

        fun db(): DatabaseConnection =
            mockk {
                every { query<Any?>(any()) } answers { firstArg<() -> Any?>().invoke() }
                every { getConnection() } answers { DriverManager.getConnection(url) }
            }

        "the shipped config carries no ERP schema, so the ERP source is skipped" {
            val config = ConfigFactory.load()
            ErpQueries.missingKeys(config, "erp-database") shouldContainExactly
                ErpQueries.KEYS.map { "erp-database.queries.$it" }
            ErpQueries.fromConfig(config, "erp-database").shouldBeNull()
            ErpClient.fromConfig(config, "erp-database").shouldBeNull()
        }

        "a partly configured source names the queries it still needs" {
            val config =
                ConfigFactory
                    .parseString("""erp-database.queries { users = "SELECT 1", roles = "  " }""")
                    .withFallback(ConfigFactory.load())
            ErpQueries.missingKeys(config, "erp-database") shouldContainExactly
                listOf("roles", "user-roles", "role-hierarchy").map { "erp-database.queries.$it" }
            ErpClient.fromConfig(config, "erp-database").shouldBeNull()
        }

        "configured queries are read back verbatim" {
            val config =
                ConfigFactory.parseMap(
                    mapOf(
                        "erp-database.queries.users" to queries.users,
                        "erp-database.queries.roles" to queries.roles,
                        "erp-database.queries.user-roles" to queries.userRoles,
                        "erp-database.queries.role-hierarchy" to queries.roleHierarchy,
                    ),
                )
            ErpQueries.fromConfig(config, "erp-database") shouldBe queries
        }

        "users come from the configured query; a blank email takes the fallback domain" {
            val users = runBlocking { ErpClient(db(), queries, fallbackEmailDomain = "erp.example").fetchUsers() }
            users.map { it.id } shouldContainExactly listOf("u1", "u2")
            users.first { it.id == "u1" }.let {
                it.username shouldBe "ada"
                it.email shouldBe "ada@example.org"
                it.firstName shouldBe "Ada"
                it.lastName shouldBe "Lovelace"
            }
            users.first { it.id == "u2" }.email shouldBe "bob@erp.example"
        }

        "without a fallback domain a user with no email is skipped, not merged on a blank email" {
            val users = runBlocking { ErpClient(db(), queries).fetchUsers() }
            users.map { it.id } shouldContainExactly listOf("u1")
        }

        "roles, user-role mappings and the hierarchy come from their queries" {
            val client = ErpClient(db(), queries)
            val roles = runBlocking { client.fetchRoles() }
            roles.map { it.id to it.code } shouldContainExactlyInAnyOrder listOf("g1" to "admin", "g2" to "viewer")
            val mappings = runBlocking { client.fetchUserRoleMappings() }
            mappings shouldContainExactlyInAnyOrder listOf("u1" to "g1", "u2" to "g2")
            // A group that is its own parent is a root.
            val hierarchy = runBlocking { client.fetchRoleHierarchy() }
            hierarchy shouldContainExactlyInAnyOrder listOf("" to "g1", "g1" to "g2")
        }

        "emailFor keeps a real email and builds one only when it can" {
            ErpClient.emailFor("a@b.c", "a", "x.example") shouldBe "a@b.c"
            ErpClient.emailFor(" ", "a", "x.example") shouldBe "a@x.example"
            ErpClient.emailFor(null, "a", "").shouldBeNull()
            ErpClient.emailFor(null, null, "x.example").shouldBeNull()
        }
    })
