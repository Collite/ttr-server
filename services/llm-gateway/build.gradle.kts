// SPDX-License-Identifier: Apache-2.0
import org.apache.tools.ant.taskdefs.condition.Os

// LLM Gateway 2.0 — Ktor 3 (CIO) in-place rewrite of the Spring 1.x "prometheus" gateway
// (LG effort; design.md). De-Spring complete (FI-1); REST + SSE only, no gRPC (FI-5). The
// pre-rewrite Spring tree is preserved at tag `llm-gateway/pre-2.0` (porting reference: rule
// engine, cache, prompt-log scheme, pricing — never the ai-gateway experiment, FI-6).

plugins {
    base
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.ktor)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ktlint)
    alias(libs.plugins.jib)
}

group = "org.tatrman.llmgateway"
version = "2.0.0"

application {
    mainClass.set("org.tatrman.llmgateway.ApplicationKt")
}

kotlin {
    jvmToolchain(21)
}

tasks.test {
    useJUnitPlatform()
}

val osArch = System.getProperty("os.arch").lowercase()
val isArm64 = osArch.contains("aarch64") || osArch.contains("arm64")
val isCi = System.getenv("CI") != null

jib {
    from {
        image = "eclipse-temurin:21-jre"
        platforms {
            if (isCi) {
                platform {
                    architecture = "arm64"
                    os = "linux"
                }
                platform {
                    architecture = "amd64"
                    os = "linux"
                }
            } else {
                platform {
                    architecture = if (isArm64) "arm64" else "amd64"
                    os = "linux"
                }
            }
        }
    }
    to {
        image = "llm-gateway:dev"
    }
    container {
        mainClass = "org.tatrman.llmgateway.ApplicationKt"
        ports = listOf("7280")
    }
    dockerClient {
        executable = "docker"
        val targetSocket =
            System.getenv("DOCKER_HOST")
                ?: if (Os.isFamily(Os.FAMILY_MAC)) {
                    "unix://${System.getProperty("user.home")}/.rd/docker.sock"
                } else {
                    "npipe:////./pipe/docker_engine"
                }
        environment = mapOf("DOCKER_HOST" to targetSocket)
    }
}

dependencies {
    // Shared libs (reuse — do not hand-roll; LG-P1·S1 pre-flight)
    implementation(project(":shared:libs:kotlin:ktor-configurator"))
    implementation(project(":shared:libs:kotlin:otel-config"))
    implementation(project(":shared:libs:kotlin:logging-config"))
    implementation(project(":shared:libs:kotlin:db-common"))

    // Ktor 3 server (CIO) + SSE + client
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.cio)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.server.sse)
    implementation(libs.ktor.server.status.pages)
    implementation(libs.ktor.server.call.logging)
    implementation(libs.ktor.server.cors)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.client.content.negotiation)

    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kaml) // catalog.yaml / governance.yaml (contracts §2)
    implementation(libs.typesafe.config) // providers.conf + app config (HOCON)

    // Persistence + cache (LG-P1·S1·T4)
    implementation(libs.exposed.core)
    implementation(libs.exposed.jdbc)
    implementation(libs.exposed.java.time)
    implementation(libs.exposed.json)
    implementation(libs.hikaricp)
    runtimeOnly(libs.postgresql)
    implementation(libs.flyway.core)
    implementation(libs.flyway.pgsql)
    implementation(libs.lettuce.core)
    implementation(libs.jtokkit) // BPE token estimator — flagged last-resort budget usage (LG-P4·S2, D-4)
    implementation(libs.auth0.java.jwt) // RS256 verification for the Keycloak admin API (LG-P4·S3)

    // Observability
    implementation(libs.slf4j.api)
    implementation(libs.logback.classic)
    implementation(libs.logstash.logback.encoder)
    implementation(libs.otel.logback.appender)
    implementation(libs.ktor.opentelemetry)
    implementation(libs.micrometer.registry.prometheus)

    // Proto — the LLM gateway wire contract (org.tatrman.llm.v1) is its own module;
    // this service is its only consumer (implements LlmGatewayService). It uses no
    // other shared proto, so it does NOT depend on :shared:proto.
    implementation(project(":shared:proto-llm"))

    // Testing — mocked unit tier (component tier = separate componentTest source set)
    testImplementation(libs.bundles.kotest)
    testImplementation(libs.mockk)
    testImplementation(libs.wiremock)
    testImplementation(libs.testcontainers)
    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.ktor.client.mock)
    testImplementation(libs.kotlinx.coroutines.test) // virtual-time retry/backoff specs (LG-P3·S2)
    testImplementation(libs.auth0.java.jwt) // sign test admin JWTs with generated RS256 keys (LG-P4·S3)

    // Component tier (Testcontainers) — the root suite already carries project()/kotest/testcontainers;
    // these are main `implementation` deps the BootComponentSpec references directly (not transitive on
    // the componentTest compile classpath).
    "componentTestImplementation"(libs.testcontainers.postgresql)
    "componentTestImplementation"(libs.ktor.server.test.host)
    "componentTestImplementation"(libs.typesafe.config)
    "componentTestImplementation"(libs.postgresql)
    // governance repos are constructed over DatabaseConnection in the LG-P4·S1 component specs
    "componentTestImplementation"(project(":shared:libs:kotlin:db-common"))
    // rate-limit component specs assert Redis state (TTL) via RedisConn.sync() (LG-P4·S2)
    "componentTestImplementation"(libs.lettuce.core)
    "componentTestImplementation"(libs.wiremock)
    "componentTestImplementation"(libs.kotlinx.serialization.json) // SSE tool-call reassembly (S2 conformance)
    // sign test admin JWTs for the admin-API component specs (LG-P4·S3)
    "componentTestImplementation"(libs.auth0.java.jwt)
    // LC-P0·S0.2 — PromptLogSpec reads the writer's drop counter off a SimpleMeterRegistry.
    "componentTestImplementation"(libs.micrometer.registry.prometheus)
    // OTel span assertions via an in-memory exporter (LG-P5·S2 TraceSpec)
    "componentTestImplementation"(libs.opentelemetry.sdk)
    "componentTestImplementation"(libs.opentelemetry.sdk.testing)
}
