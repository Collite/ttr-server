// SPDX-License-Identifier: Apache-2.0
package org.tatrman.health.content

import org.tatrman.health.config.StatusConfig
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.nio.file.Files

class KubernetesReaderTest :
    StringSpec({

        "a k8s: status is read from the API server with the ServiceAccount token, re-read per request" {
            val serviceAccount = Files.createTempDirectory("sa")
            val token = serviceAccount.resolve("token")
            Files.writeString(token, "token-1\n")
            val seen = mutableListOf<Pair<String, String?>>()
            val kubernetes = KubernetesReader(server = "https://api.test", serviceAccountDir = serviceAccount)
            kubernetes.replaceClient(
                HttpClient(
                    MockEngine { request ->
                        seen += request.url.toString() to request.headers[HttpHeaders.Authorization]
                        respond(
                            """{"items":[]}""",
                            HttpStatusCode.OK,
                            headersOf(HttpHeaders.ContentType, "application/json"),
                        )
                    },
                ),
            )
            val config =
                StatusConfig(url = "k8s:/apis/external-secrets.io/v1/externalsecrets", adapter = "k8s-externalsecrets")
            val content = ContentService(mapOf("external-secrets" to config), kubernetes = kubernetes)

            content.read(config).ok shouldBe true
            Files.writeString(token, "token-2")
            content.read(config)

            seen shouldBe
                listOf(
                    "https://api.test/apis/external-secrets.io/v1/externalsecrets" to "Bearer token-1",
                    "https://api.test/apis/external-secrets.io/v1/externalsecrets" to "Bearer token-2",
                )
        }

        "outside a cluster the content says so instead of failing" {
            val kubernetes = KubernetesReader(serviceAccountDir = Files.createTempDirectory("no-sa"))
            val config =
                StatusConfig(url = "k8s:/apis/external-secrets.io/v1/externalsecrets", adapter = "k8s-externalsecrets")

            ContentService(mapOf("external-secrets" to config), kubernetes = kubernetes).read(config) shouldBe
                Content.failed("not running in a cluster")
        }
    })
