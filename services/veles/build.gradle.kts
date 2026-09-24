// SPDX-License-Identifier: Apache-2.0
plugins {
    base
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.ktor)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ktlint)
    alias(libs.plugins.jib)
}

application {
    mainClass.set("org.tatrman.veles.ApplicationKt")
}

jib {
    from {
        image = "eclipse-temurin:21-jre"
    }
    to {
        image = "veles:dev"
    }
    container {
        mainClass = "org.tatrman.veles.ApplicationKt"
        ports = listOf("7260", "7261")
    }
}

kotlin {
    jvmToolchain(21)
}

tasks.test {
    useJUnitPlatform()
}

// VelesModelLoadComponentSpec reconciles the real bundled model tree through the live
// ttr-metadata reconciler (LocalFsStorage → a real directory). The suite carries Veles on its
// runtime classpath as a JAR, so the spec cannot resolve the model dir from a classpath resource
// URI (that would be a `jar:` URI → FileSystemNotFoundException). Hand it the authored resources
// dir on disk instead.
tasks.named<Test>("componentTest") {
    systemProperty(
        "veles.modelRoot",
        layout.projectDirectory
            .dir("src/main/resources/model-ttr")
            .asFile.absolutePath,
    )
}

// (yamlToTtr task removed: TTR-only fork — model is now sourced from `org.tatrman.ttr.*`
// modeler artifacts and the bundled `model-ttr` resource tree. The `just yaml-to-ttr`
// recipe no longer applies; the legacy YAML dir stays in tree as a one-time reference.)

dependencies {
    // TTR metadata library (third-party, Collite/ttr-core) — Veles runs on this
    // after the M4.1 swap (MD2): typed model, sources, reconcile, resolve, graph,
    // search, registry, refresher mechanism, world resolution. It re-exports
    // ttr-parser/writer/semantics as `api`, so those three are NOT declared here
    // (MetadataExportRoutes still imports them — they arrive transitively).
    implementation(libs.tatrman.ttr.metadata)
    // GitArchiveStorage (jgit) behind the ModelStorage SPI (MD3) — jgit +
    // commons-compress ride this transitively; Veles must not re-declare them.
    implementation(libs.tatrman.ttr.metadata.git)
    // `erp-sql-metadata` was a declared-but-unused dep in the ai-platform build (no `shared.erp.*`
    // imports in any source). Dropped from the kantheon fork.
    implementation(project(":shared:libs:kotlin:ktor-configurator"))
    implementation(project(":shared:libs:kotlin:otel-config"))
    implementation(project(":shared:libs:kotlin:logging-config"))
    // Section F query-parse worker parses stored queries in-process against the model (SQL → PlanNode).
    implementation(libs.tatrman.ttr.translator)
    // MV-T1 — member vocabularies render through the query path's own adapter + the RG-* registry.
    implementation(project(":shared:libs:kotlin:translate-snapshot"))
    implementation(project(":shared:libs:kotlin:diagnostics"))
    implementation(project(":shared:proto"))

    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.grpc.kotlin.stub)
    implementation(libs.grpc.netty.shaded)
    implementation(libs.grpc.services)

    // jgrapht (ModelGraph) rides ttr-metadata transitively; jgit/commons-compress
    // ride ttr-metadata-git (MD3). Veles no longer declares any of them directly.

    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.ktor.opentelemetry)

    // commons-compress: used DIRECTLY by the kept MetadataExportRoutes (tar bundle);
    // it also rides ttr-metadata-git transitively (runtime), but the export routes
    // need it at compile time, so it stays a direct dep.
    implementation(libs.apache.commons.compress)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.typesafe.config)
    // Prometheus `/metrics` endpoint.
    implementation(libs.micrometer.registry.prometheus)
    implementation(libs.slf4j.api)
    implementation(libs.logback.classic)
    implementation(libs.logstash.logback.encoder)
    api(libs.otel.logback.appender)

    testImplementation(libs.bundles.kotest)
    testImplementation(libs.mockk)
    // In-process gRPC server (review-004 R3.2 — live GetPrompts round-trip).
    testImplementation(libs.grpc.inprocess)
    // Ktor testApplication host — drives VelesReadRoutes over HTTP (the llm-gateway pattern).
    testImplementation(libs.ktor.server.test.host)

    // Component tier (WS-C1 T4) — loads the **real bundled `model-ttr/` tree** (not a synthetic
    // fixture) through the live ttr-metadata reconciler and asserts the shipped TPC-DS curated
    // queries + areas resolve. Needs the metadata library (FileBasedSource / reconciler / registry /
    // graph) and the protos on the componentTest compile classpath (both `implementation` in main,
    // so not visible to the suite transitively).
    "componentTestImplementation"(libs.tatrman.ttr.metadata)
    "componentTestImplementation"(project(":shared:proto"))
}
