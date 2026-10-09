// SPDX-License-Identifier: Apache-2.0
import org.apache.tools.ant.taskdefs.condition.Os

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.ktor)
    alias(libs.plugins.ktlint)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.jib)
}

application {
    mainClass.set("org.tatrman.fuzzy.mcp.ApplicationKt")
}

val osArch = System.getProperty("os.arch").lowercase()
val isArm64 = osArch.contains("aarch64") || osArch.contains("arm64")
val isCi = System.getenv("CI") != null

jib {
    from {
        image = "ghcr.io/collite/eclipse-temurin:21-jre" // mirror of the Docker official image (mirror-base-images.yml)
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
        image = "fuzzy-mcp:latest"
    }
    container {
        mainClass = "org.tatrman.fuzzy.mcp.ApplicationKt"
        ports = listOf("7267")
    }
    dockerClient {
        executable = "docker"
        val targetSocket =
            System.getenv("DOCKER_HOST") ?: if (Os.isFamily(Os.FAMILY_MAC)) {
                "unix://${System.getProperty("user.home")}/.rd/docker.sock"
            } else {
                "npipe:////./pipe/docker_engine"
            }
        environment = mapOf("DOCKER_HOST" to targetSocket)
    }
}

dependencies {
    api(libs.otel.logback.appender)
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.cio)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.server.cors)
    implementation(libs.ktor.server.call.logging)
    implementation(libs.ktor.serialization.kotlinx.json)

    implementation(libs.kotlin.mcp.sdk)
    implementation(libs.typesafe.config)
    implementation(libs.logback.classic)
    implementation(libs.logstash.logback.encoder)

    // Stage 2.2 T6 — load the manifest YAMLs for capabilities-mcp
    // registration. We don't depend on `:tools:capabilities-mcp` (they're
    // peers) so we parse the manifests directly with Jackson (same as
    // veles-mcp).
    implementation(libs.jackson.databind)
    implementation(libs.jackson.module.kotlin)
    implementation(libs.jackson.dataformat.yaml)

    // Forked shared libs (Phase 1.3 — in-repo project deps).
    implementation(project(":shared:libs:kotlin:ktor-configurator"))
    implementation(project(":shared:libs:kotlin:otel-config"))
    implementation(project(":shared:libs:kotlin:logging-config"))
    implementation(project(":shared:libs:kotlin:capabilities-client"))
    implementation(project(":shared:libs:kotlin:fuzzy-common"))

    // gRPC client to the fuzzy service.
    implementation(project(":shared:proto"))
    implementation(project(":services:lex-matcher"))
    implementation(libs.grpc.kotlin.stub)
    implementation(libs.grpc.netty.shaded)

    implementation(libs.ktor.opentelemetry)
    implementation(libs.micrometer.registry.prometheus)

    testImplementation(libs.kotest.runner.junit5)
    testImplementation(libs.kotest.assertions.core)
    testImplementation(libs.mockk)
}

tasks.test {
    useJUnitPlatform()
}
