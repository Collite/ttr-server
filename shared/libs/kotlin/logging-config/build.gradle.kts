// SPDX-License-Identifier: Apache-2.0
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
    api(libs.ktor.server.core)
    api(libs.ktor.client.core)
    api(libs.ktor.client.apache)
    api(libs.ktor.client.content.negotiation)
    api(libs.ktor.serialization.kotlinx.json)
    api(libs.logback.classic)
    api(libs.logstash.logback.encoder)
    api(libs.otel.logback.appender)
    api(libs.opentelemetry.sdk.extension.autoconfigure)
    api(libs.opentelemetry.exporter.otlp)
    api(libs.opentelemetry.semconv)
    api(libs.slf4j.api)
    api(libs.kotlinx.serialization.json)

    implementation(libs.typesafe.config)

    // gRPC interceptors live here for cross-service reuse. compileOnly so non-gRPC
    // consumers (HTTP-only Ktor services) don't pay for the transitive deps; every gRPC
    // service already declares these themselves.
    compileOnly(libs.grpc.kotlin.stub)
    compileOnly(libs.protobuf.java)
    // W3C trace-context propagation over gRPC metadata (GrpcTracing.kt). `extension-kotlin`
    // supplies Context.asContextElement() — the server half contributes the extracted context to
    // the HANDLER's coroutine context, because a thread-local would not survive a suspension.
    compileOnly(libs.opentelemetry.extension.kotlin)

    testImplementation(libs.bundles.kotest)
    testImplementation(libs.wiremock)
    testImplementation(libs.mockk)
    testImplementation(libs.grpc.kotlin.stub)
    testImplementation(libs.grpc.inprocess)
    testImplementation(libs.protobuf.java)
    testImplementation(libs.opentelemetry.extension.kotlin)
    testImplementation(libs.opentelemetry.sdk)
}
