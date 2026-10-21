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
    mainClass.set("org.tatrman.query.ApplicationKt")
}

kotlin {
    jvmToolchain(21)
}

// BI (C-1) — Arrow (the Flight SQL edge's vectors and allocator) needs module access on JDK 16+.
val arrowJvmFlags =
    listOf(
        "--add-opens=java.base/java.nio=ALL-UNNAMED",
        "--add-opens=java.base/sun.nio.ch=ALL-UNNAMED",
    )

application {
    applicationDefaultJvmArgs = arrowJvmFlags
}

tasks.test {
    useJUnitPlatform()
    jvmArgs(arrowJvmFlags)
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
        image = "query:dev"
    }
    container {
        mainClass = "org.tatrman.query.ApplicationKt"
        ports = listOf("7305", "7306", "7308")
        jvmFlags = arrowJvmFlags
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

    // ES-P0·S0.3 — the shared `worker.v1.ExecutionReceipt` builder: the query service fills the plan half
    // (dispatched plan · security_applied · compile facts) with the same rules the workers use
    // for the statement half.
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

    implementation(libs.caffeine)

    // BI-P1 — the Arrow Flight SQL edge (contracts C-1…C-8). flight-core's own grpc-netty is built on an
    // older grpc than the repo's; pinning it here keeps grpc-core and its netty transport in lockstep.
    implementation(libs.arrow.flight.core)
    implementation(libs.arrow.flight.sql)
    implementation(libs.arrow.vector)
    implementation(libs.arrow.memory.netty)
    implementation(libs.grpc.netty)

    testImplementation(libs.bundles.kotest)
    testImplementation(libs.mockk)
    // The stock Flight SQL JDBC driver (shaded) — the round-trip test makes DBeaver's metadata calls with it.
    testImplementation(libs.arrow.flight.sql.jdbc.driver)
}
