// SPDX-License-Identifier: Apache-2.0
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.ktlint)
    `java-library`
}
kotlin { jvmToolchain(21) }
tasks.test { useJUnitPlatform() }
dependencies {
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.bundles.kotest)
}
