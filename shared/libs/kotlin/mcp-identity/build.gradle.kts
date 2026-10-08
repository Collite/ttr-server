// SPDX-License-Identifier: Apache-2.0
// Shared OBO identity gate for MCP door edges (RG-P6.S1, Decision B). Lifted from
// tools/query-mcp so the fail-closed bearer/OBO gate is single-sourced across
// every MCP door (query-mcp + the resolver resolve door). Pure JVM leaf — no
// Ktor, no MCP SDK: it decides over already-extracted headers/args so it stays
// transport-agnostic and unit-testable.
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ktlint)
    `java-library`
}

kotlin {
    jvmToolchain(21)
}

tasks.test {
    useJUnitPlatform()
}

dependencies {
    implementation(libs.kotlinx.serialization.json)
    // LR G2b — RS256 verification of the bearer (BearerVerifier); the JWKS key source logs fetch failures.
    implementation(libs.auth0.java.jwt)
    implementation(libs.slf4j.api)
    testImplementation(libs.bundles.kotest)
}
