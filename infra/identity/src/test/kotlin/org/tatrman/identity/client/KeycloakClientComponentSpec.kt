// SPDX-License-Identifier: Apache-2.0
package org.tatrman.identity.client

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.get
import com.github.tomakehurst.wiremock.client.WireMock.okJson
import com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo
import com.github.tomakehurst.wiremock.core.WireMockConfiguration
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import org.tatrman.keycloak.auth.TokenProvider

/**
 * Component-tier spec: the KeycloakClient against a mocked Keycloak admin API (WireMock).
 * Asserts the realm-admin REST shapes parse into the generic domain types. True e2e against a
 * live Keycloak is deferred to the separate integration-test suite (planning-conventions §4).
 */
class KeycloakClientComponentSpec :
    StringSpec({

        val realm = "tatrman"
        val staticToken =
            object : TokenProvider {
                override suspend fun getToken(): String = "test-token"
            }

        fun startWiremock(): WireMockServer =
            WireMockServer(WireMockConfiguration.options().dynamicPort()).also { it.start() }

        fun client(wm: WireMockServer): KeycloakClient =
            KeycloakClient(baseUrl = "http://localhost:${wm.port()}", realm = realm, tokenProvider = staticToken)

        "fetchUsers parses the realm users into GenericUser" {
            val wm = startWiremock()
            try {
                wm.stubFor(
                    get(urlPathEqualTo("/admin/realms/$realm/users")).willReturn(
                        okJson(
                            """
                            [
                              { "id": "kc-1", "username": "ada", "email": "ada@x.cz",
                                "firstName": "Ada", "lastName": "L", "enabled": true },
                              { "id": "kc-2", "username": "bob", "email": "bob@x.cz", "enabled": false }
                            ]
                            """.trimIndent(),
                        ),
                    ),
                )
                val users = runBlocking { client(wm).fetchUsers() }
                users.map { it.id } shouldContainExactlyInAnyOrder listOf("kc-1", "kc-2")
                users.first { it.id == "kc-2" }.enabled shouldBe false
            } finally {
                wm.stop()
            }
        }

        "fetchRoles maps realm roles into GenericRole(KEYCLOAK)" {
            val wm = startWiremock()
            try {
                wm.stubFor(
                    get(urlPathEqualTo("/admin/realms/$realm/roles")).willReturn(
                        okJson(
                            """[ { "id": "r-1", "name": "admin", "description": "Admins" },
                                 { "id": "r-2", "name": "viewer" } ]""",
                        ),
                    ),
                )
                val roles = runBlocking { client(wm).fetchRoles() }
                roles.map { it.code } shouldContainExactlyInAnyOrder listOf("admin", "viewer")
                roles.forEach { it.source shouldBe org.tatrman.identity.domain.UserSource.KEYCLOAK }
            } finally {
                wm.stop()
            }
        }
    })
