// SPDX-License-Identifier: Apache-2.0
import org.apache.tools.ant.taskdefs.condition.Os

plugins {
    base
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.ktor)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ktlint)
    alias(libs.plugins.jib)
}

application {
    mainClass.set("org.tatrman.validate.ApplicationKt")
}

tasks.test {
    useJUnitPlatform()
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
        image = "validate:dev"
    }
    container {
        mainClass = "org.tatrman.validate.ApplicationKt"
        ports = listOf("7285", "7286")
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
    implementation(libs.tatrman.ttr.translator)
    implementation(project(":shared:libs:kotlin:ktor-configurator"))
    implementation(project(":shared:libs:kotlin:otel-config"))
    implementation(project(":shared:libs:kotlin:logging-config"))
    // Fork Stage 5.3 — optional whois role-enrichment source (UserRecord shapes + GET /whois client).
    implementation(project(":shared:libs:kotlin:whois-common"))
    implementation(libs.caffeine)
    implementation(project(":shared:proto"))

    implementation(libs.grpc.kotlin.stub)
    implementation(libs.grpc.netty.shaded)
    implementation(libs.grpc.services)

    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.client.content.negotiation)

    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.typesafe.config)
    implementation(libs.slf4j.api)
    implementation(libs.logback.classic)
    implementation(libs.logstash.logback.encoder)
    implementation(libs.ktor.opentelemetry)
    api(libs.otel.logback.appender)

    testImplementation(libs.bundles.kotest)
    testImplementation(libs.mockk)
    testImplementation(libs.wiremock)

    // Component tier (WS-C1 T3) — proves Validate's tenant-isolation policy decision **enforces** on a
    // real Postgres: the predicate the PolicyEngine emits, applied as a WHERE clause, denies
    // cross-tenant rows (and a same-tenant query returns rows). Needs the testkit (Containers.postgres),
    // the protos (security/plan v1), coroutines (evaluatePolicies is `suspend`), and the pg driver.
    "componentTestImplementation"(project(":shared:libs:kotlin:component-testkit"))
    "componentTestImplementation"(project(":shared:proto"))
    "componentTestImplementation"(libs.kotlinx.coroutines.core)
    "componentTestImplementation"(libs.postgresql)
}
