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
    mainClass.set("org.tatrman.worker.postgres.ApplicationKt")
}

kotlin {
    jvmToolchain(21)
}

tasks.test {
    useJUnitPlatform()
    // Apache Arrow needs explicit module access on JDK 16+ to use sun.misc.Unsafe.
    jvmArgs(
        "--add-opens=java.base/java.nio=ALL-UNNAMED",
        "--add-opens=java.base/sun.nio.ch=ALL-UNNAMED",
    )
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
        image = "postgres:dev"
    }
    container {
        mainClass = "org.tatrman.worker.postgres.ApplicationKt"
        ports = listOf("7302", "7303")
        // Arrow needs these on JDK 21.
        jvmFlags =
            listOf(
                "--add-opens=java.base/java.nio=ALL-UNNAMED",
                "--add-opens=java.base/sun.nio.ch=ALL-UNNAMED",
            )
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
    implementation(project(":shared:proto"))
    // ES-P0 — the one ExecutionReceipt builder (caps, masking, the never-fail-a-run guard),
    // shared with worker-mssql and ttr-query.
    implementation(project(":shared:libs:kotlin:execution-receipt"))

    implementation(libs.grpc.kotlin.stub)
    implementation(libs.grpc.netty.shaded)
    implementation(libs.grpc.services)

    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)

    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.typesafe.config)
    implementation(libs.slf4j.api)
    implementation(libs.logback.classic)
    implementation(libs.logstash.logback.encoder)
    implementation(libs.ktor.opentelemetry)
    api(libs.otel.logback.appender)

    // Postgres flavour — Postgres is Mssql adapted to PostgreSQL (architecture §2).
    implementation(libs.postgresql)
    implementation(libs.hikaricp)
    implementation(libs.arrow.vector)
    implementation(libs.arrow.memory.netty)

    testImplementation(libs.bundles.kotest)
    testImplementation(libs.mockk)

    // Component tier (testing arc Stage 1.2) — real Postgres via Testcontainers. The convention's
    // `componentTest` suite already brings kotest + the base testcontainers + `project()` (postgres
    // main, exposing the pg driver on the runtime classpath). These add what the specs compile
    // against directly: the testkit (`Containers.postgres()`), the protos, the Arrow reader for
    // result deserialization, coroutines for the flow, and the pg driver for the seed connection.
    "componentTestImplementation"(project(":shared:libs:kotlin:component-testkit"))
    "componentTestImplementation"(project(":shared:proto"))
    "componentTestImplementation"(project(":shared:libs:kotlin:execution-receipt"))
    "componentTestImplementation"(libs.kotlinx.coroutines.core)
    "componentTestImplementation"(libs.arrow.vector)
    "componentTestImplementation"(libs.arrow.memory.netty)
    "componentTestImplementation"(libs.postgresql)
}

// Arrow's Netty allocator needs these JDK-module opens on the componentTest JVM, exactly as the
// `test` task above (the specs deserialize Arrow IPC results from the real-Postgres round-trip).
tasks.named<Test>("componentTest") {
    jvmArgs(
        "--add-opens=java.base/java.nio=ALL-UNNAMED",
        "--add-opens=java.base/sun.nio.ch=ALL-UNNAMED",
    )
}
