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
    mainClass.set("org.tatrman.identity.ApplicationKt")
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
        image = "whois:dev"
    }
    container {
        mainClass = "org.tatrman.identity.ApplicationKt"
        ports = listOf("7110")
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
    implementation(project(":shared:libs:kotlin:whois-common"))
    implementation(project(":shared:libs:kotlin:keycloak-auth"))
    implementation(project(":shared:libs:kotlin:db-common"))
    implementation(project(":shared:libs:kotlin:ktor-configurator"))
    implementation(project(":shared:libs:kotlin:otel-config"))

    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.cio)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)

    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.apache)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.client.auth)

    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.typesafe.config)
    implementation(libs.slf4j.api)
    implementation(libs.logback.classic)
    implementation(libs.logstash.logback.encoder)
    api(libs.otel.logback.appender)
    implementation(libs.ktor.opentelemetry)

    implementation(libs.exposed.core)
    implementation(libs.postgresql)
    implementation(libs.flyway.core)
    implementation(libs.flyway.pgsql)

    implementation(libs.caffeine)
    implementation(libs.apache.commons.compress)

    testImplementation(libs.bundles.kotest)
    testImplementation(libs.mockk)
    testImplementation(libs.wiremock)
    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.h2)
}
