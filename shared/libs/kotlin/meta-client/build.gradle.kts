// SPDX-License-Identifier: Apache-2.0
// metadata-client — the shared gRPC client for the Metadata model graph service
// (GetModel / GetPrompts / list*). Extracted from tools/meta-mcp (2026-06-18,
// Golem Stage 2.2) so Golem's PackageContext + PromptStore and Pythia consume one
// client, not per-module copies. Depends only on :shared:proto (which generates the
// grpc + grpckt stubs) + the netty transport — NOT on :services:metadata (the server).
plugins {
    base
    alias(libs.plugins.kotlin.jvm)
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
    // Metadata protos + generated grpc/grpckt coroutine stubs (org.tatrman.meta.v1).
    api(project(":shared:proto"))
    implementation(libs.grpc.netty.shaded)
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.bundles.kotest)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.mockk)
}
