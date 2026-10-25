// SPDX-License-Identifier: Apache-2.0
package org.tatrman.veles.mcp

import com.typesafe.config.ConfigFactory
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

/**
 * Phase 2.1 review-004 R2.3 — config-driven gRPC target.
 *
 * Exercises the **production** [buildGrpcClient] helper (the same function
 * `main()` calls) so the test cannot drift from the wiring it claims to
 * cover. The previous bug: `main()` read `System.getenv("METADATA_GRPC_HOST")`,
 * which is always empty because the k8s manifest sets `VELES_GRPC_HOST`
 * instead — so the pod never connected. The fix reads `metadata.host` /
 * `metadata.port` from `application.conf` (where HOCON resolves the
 * `VELES_GRPC_*` overrides).
 *
 * No real network is opened: `ManagedChannelBuilder` constructs the channel
 * lazily and does not connect until the first RPC; the test closes whatever
 * it builds.
 */
class GrpcTargetConfigSpec :
    StringSpec({

        "buildGrpcClient builds a client when metadata.host is set" {
            val config =
                ConfigFactory.parseString(
                    """
                    metadata {
                        host = "veles.test.example"
                        port = "7261"
                    }
                    """.trimIndent(),
                )
            val client = buildGrpcClient(config)
            client shouldNotBe null
            client?.close()
        }

        "buildGrpcClient returns null (warn-and-continue) when metadata.host is blank" {
            val config =
                ConfigFactory.parseString(
                    """
                    metadata {
                        host = ""
                        port = "7261"
                    }
                    """.trimIndent(),
                )
            buildGrpcClient(config) shouldBe null
        }

        "buildGrpcClient defaults the port to 7261 when only the host is set" {
            // Mirrors a minimal cluster config that relies on the conf default;
            // the call must not throw on a missing metadata.port.
            val config = ConfigFactory.parseString("""metadata { host = "meta" }""")
            val client = buildGrpcClient(config)
            client shouldNotBe null
            client?.close()
        }
    })
