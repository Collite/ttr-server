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
    mainClass.set("org.tatrman.translate.ApplicationKt")
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
        image = "translate:dev"
    }
    container {
        mainClass = "org.tatrman.translate.ApplicationKt"
        ports = listOf("7275", "7276")
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
    // MV-T1 — SnapshotModelHandle moved here so Veles renders member vocabularies through it too.
    implementation(project(":shared:libs:kotlin:translate-snapshot"))
    // InMemoryModelHandle (the ModelHandle SPI test double) ships in the
    // ttr-translator test-fixtures jar — used by TpcdsUnparseSpec (WS-T2 T4).
    testImplementation(testFixtures(libs.tatrman.ttr.translator))
    implementation(project(":shared:libs:kotlin:ktor-configurator"))
    implementation(project(":shared:libs:kotlin:otel-config"))
    implementation(project(":shared:libs:kotlin:logging-config"))
    implementation(project(":shared:proto"))

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

    testImplementation(libs.bundles.kotest)
    testImplementation(libs.mockk)

    // Component tier (WS-C1 T2) — the golden-SQL PostgreSQL unparse matrix over the four TPC-DS
    // curated shapes. No container: it drives the **real** ttr-translator parse→RelNode→unparse
    // path (not a mock) end-to-end and freezes the emitted PostgreSQL to golden files, so it lives
    // out of the mocked `test` gate. Needs the translator + its `InMemoryModelHandle` test double
    // (the same fixture the unit `TpcdsUnparseSpec` uses) plus the protos on the compile classpath.
    "componentTestImplementation"(libs.tatrman.ttr.translator)
    "componentTestImplementation"(testFixtures(libs.tatrman.ttr.translator))
    "componentTestImplementation"(project(":shared:proto"))
}
