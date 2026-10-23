// SPDX-License-Identifier: Apache-2.0
package org.tatrman.health.content

import io.ktor.client.HttpClient
import io.ktor.client.engine.apache.Apache
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyStore
import java.security.cert.CertificateFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory

/**
 * Read-only GETs against the Kubernetes API from inside the cluster, as the pod's ServiceAccount (its
 * token and the cluster CA are mounted under [serviceAccountDir]). What it may read is the health
 * chart's ClusterRole (`rbac.enabled`): the status of ExternalSecrets and Certificates, nothing else.
 */
class KubernetesReader(
    private val server: String = defaultServer(),
    private val serviceAccountDir: Path = Path.of("/var/run/secrets/kubernetes.io/serviceaccount"),
) {
    private var client: HttpClient? = null

    fun replaceClient(newClient: HttpClient) {
        client?.close()
        client = newClient
    }

    val available: Boolean get() = Files.isReadable(serviceAccountDir.resolve("token"))

    suspend fun get(path: String): HttpResponse {
        check(available) { "not running in a cluster (no ServiceAccount token)" }
        // Projected tokens rotate: read it for every request.
        val token = Files.readString(serviceAccountDir.resolve("token")).trim()
        return http().get(server + path) { bearerAuth(token) }
    }

    private fun http(): HttpClient =
        client ?: HttpClient(Apache) {
            engine {
                connectTimeout = 5_000
                socketTimeout = 10_000
                sslContext = clusterTrust(serviceAccountDir.resolve("ca.crt"))
            }
        }.also { client = it }

    companion object {
        fun defaultServer(): String {
            val host = System.getenv("KUBERNETES_SERVICE_HOST") ?: return "https://kubernetes.default.svc"
            val port = System.getenv("KUBERNETES_SERVICE_PORT") ?: "443"
            return if (':' in host) "https://[$host]:$port" else "https://$host:$port"
        }

        private fun clusterTrust(caFile: Path): SSLContext {
            val certificates =
                Files
                    .newInputStream(
                        caFile,
                    ).use { CertificateFactory.getInstance("X.509").generateCertificates(it) }
            val store =
                KeyStore.getInstance(KeyStore.getDefaultType()).apply {
                    load(null)
                    certificates.forEachIndexed { i, certificate -> setCertificateEntry("cluster-ca-$i", certificate) }
                }
            val trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(store) }
            return SSLContext.getInstance("TLS").apply { init(null, trust.trustManagers, null) }
        }
    }
}
