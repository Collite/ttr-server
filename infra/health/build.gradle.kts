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
    mainClass.set("org.tatrman.health.ApplicationKt")
}

tasks.test {
    useJUnitPlatform()
}

// FO-P5.S2 (FO-28): filter the single release-train version into build.properties so the open-tier
// status page can report the running Server version. Scoped to build.properties only — application.conf
// / logback.xml carry HOCON ${?ENV} placeholders that must NOT be expanded.
tasks.processResources {
    val serverVersion = project.version.toString()
    inputs.property("serverVersion", serverVersion)
    filesMatching("build.properties") {
        expand("version" to serverVersion)
    }
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
        image = "health:dev"
    }
    container {
        mainClass = "org.tatrman.health.ApplicationKt"
        ports = listOf("7000")
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
    implementation(project(":shared:libs:kotlin:otel-config"))
    implementation(project(":shared:libs:kotlin:ktor-configurator"))

    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.cio)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)

    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.apache)
    implementation(libs.ktor.client.content.negotiation)

    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.typesafe.config)
    implementation(libs.slf4j.api)
    implementation(libs.logback.classic)
    implementation(libs.logstash.logback.encoder)
    api(libs.otel.logback.appender)
    implementation(libs.ktor.opentelemetry)

    implementation(libs.caffeine)

    testImplementation(libs.bundles.kotest)
    testImplementation(libs.mockk)
    testImplementation(libs.wiremock)
    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.ktor.client.mock)
}
