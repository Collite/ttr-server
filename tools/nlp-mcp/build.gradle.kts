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
    mainClass.set("org.tatrman.nlp.mcp.ApplicationKt")
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
        image = "nlp-mcp:latest"
    }
    container {
        mainClass = "org.tatrman.nlp.mcp.ApplicationKt"
        ports = listOf("7272")
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
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.server.cors)
    implementation(libs.ktor.server.call.logging)
    implementation(libs.ktor.serialization.kotlinx.json)

    implementation(libs.kotlin.mcp.sdk)
    implementation(libs.typesafe.config)
    implementation(libs.logback.classic)
    implementation(libs.logstash.logback.encoder)

    // Load the manifest YAMLs for capabilities-mcp registration. We don't
    // depend on `:tools:capabilities-mcp` (peers), so we parse the manifests
    // directly with Jackson (same as veles-mcp / fuzzy-mcp).
    implementation(libs.jackson.databind)
    implementation(libs.jackson.module.kotlin)
    implementation(libs.jackson.dataformat.yaml)

    // Forked shared libs (Phase 1.3 — in-repo project deps).
    implementation(project(":shared:libs:kotlin:ktor-configurator"))
    implementation(project(":shared:libs:kotlin:otel-config"))
    implementation(project(":shared:libs:kotlin:logging-config"))
    implementation(project(":shared:libs:kotlin:capabilities-client"))

    // Nlp speaks gRPC at v1 (RG-P1.S1 — gRPC is the service contract; REST is a
    // dev/health mirror). The generated `org.tatrman.nlp.v1` Kotlin stubs come
    // from `:shared:proto` (the `.proto` is the single source; the Python front
    // owns its own generated stubs). No `:services:nlp` dep — that's Python.
    implementation(project(":shared:proto"))
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
