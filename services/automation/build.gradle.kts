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
    mainClass.set("org.tatrman.automation.ApplicationKt")
}

kotlin {
    jvmToolchain(21)
}

tasks.test {
    useJUnitPlatform()
    // ErrorEnvelopeSpec sweeps the main sources for raising sites, and PgmqVendorSpec reads the vendored
    // migration: inputs the test task does not see on its own, so a change to them must rerun it.
    inputs.dir("src/main/kotlin")
    inputs.dir("src/main/resources")
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
        image = "automation:dev"
    }
    container {
        mainClass = "org.tatrman.automation.ApplicationKt"
        ports = listOf("7131")
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
    implementation(project(":shared:libs:kotlin:ktor-configurator"))
    implementation(project(":shared:libs:kotlin:otel-config"))
    implementation(project(":shared:libs:kotlin:logging-config"))

    // The ledger — the shared DatabaseConnection (Hikari + Exposed), Flyway for the schema and the
    // vendored pgmq (D-130: SQL-only, no extension), the Exposed json/java-time column types.
    implementation(project(":shared:libs:kotlin:db-common"))
    implementation(libs.exposed.core)
    implementation(libs.exposed.jdbc)
    implementation(libs.exposed.json)
    implementation(libs.exposed.java.time)
    implementation(libs.postgresql)
    implementation(libs.flyway.core)
    implementation(libs.flyway.pgsql)

    // Identity (S0.3): the shared LR G2b bearer verifier (RS256 over the realm's JWKS) and the Keycloak
    // client-credentials provider for the named identities; java-jwt reads the verified token's claims.
    implementation(project(":shared:libs:kotlin:mcp-identity"))
    implementation(project(":shared:libs:kotlin:keycloak-auth"))
    implementation(libs.auth0.java.jwt)
    implementation(libs.ktor.server.auth)

    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.server.status.pages)
    implementation(libs.ktor.serialization.kotlinx.json)

    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.typesafe.config)
    implementation(libs.slf4j.api)
    implementation(libs.logback.classic)
    implementation(libs.logstash.logback.encoder)
    implementation(libs.ktor.opentelemetry)
    api(libs.otel.logback.appender)

    testImplementation(libs.bundles.kotest)
    testImplementation(libs.mockk)
    testImplementation(libs.ktor.server.test.host)
    // InMemoryMetricReader for the AutomationTelemetry assertions.
    testImplementation(libs.opentelemetry.sdk.testing)

    // Component tier — the ledger over a REAL Postgres (Testcontainers): Flyway migrates clean, pgmq
    // round-trips, the idempotency claim races. `src/componentTest`, so the unit gate stays Docker-free.
    "componentTestImplementation"(libs.testcontainers.postgresql)
    "componentTestImplementation"(project(":shared:libs:kotlin:db-common"))
    "componentTestImplementation"(libs.exposed.core)
    "componentTestImplementation"(libs.exposed.jdbc)
    "componentTestImplementation"(libs.exposed.json)
    "componentTestImplementation"(libs.exposed.java.time)
    "componentTestImplementation"(libs.postgresql)
    "componentTestImplementation"(libs.flyway.core)
    "componentTestImplementation"(libs.flyway.pgsql)
    "componentTestImplementation"(libs.typesafe.config)
    "componentTestImplementation"(libs.kotlinx.serialization.json)
}
