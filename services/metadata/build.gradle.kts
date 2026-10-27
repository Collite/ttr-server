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
    mainClass.set("org.tatrman.metadata.ApplicationKt")
}

jib {
    from {
        image = "ghcr.io/collite/eclipse-temurin:21-jre" // mirror of the Docker official image (mirror-base-images.yml)
    }
    to {
        image = "metadata:dev"
    }
    container {
        mainClass = "org.tatrman.metadata.ApplicationKt"
        ports = listOf("7260", "7261")
    }
}

kotlin {
    jvmToolchain(21)
}

tasks.test {
    useJUnitPlatform()
    // CN-P4 S4.2 — the conceptual tier's shared fixture estate (`shared/testdata/cn-estate`, one copy for metadata,
    // lex-matcher and the resolver). Declared as an input too: a system property naming a directory is not one.
    val cnEstate = rootProject.file("shared/testdata/cn-estate")
    inputs.dir(cnEstate).withPropertyName("cnEstate")
    systemProperty("cnEstate", cnEstate.absolutePath)
    // CN-P5 S5.3 — the candidate store promotes into ttr-concept's clinic golden world (`shared/testdata/cn-worlds`).
    val cnWorlds = rootProject.file("shared/testdata/cn-worlds")
    inputs.dir(cnWorlds).withPropertyName("cnWorlds")
    systemProperty("cnWorlds", cnWorlds.absolutePath)
}

// MetadataModelLoadComponentSpec reconciles the `model-seed` and `model-tpcds` fixture trees through
// the live ttr-metadata reconciler (LocalFsStorage → a real directory), so hand it their parent
// directory on disk.
// Metadata bundles no model (ttr-server#180): the model comes from the configured git source.
tasks.named<Test>("componentTest") {
    systemProperty(
        "metadata.modelRoot",
        layout.projectDirectory
            .dir("src/test/resources")
            .asFile.absolutePath,
    )
}

dependencies {
    // TTR metadata library (third-party, Collite/ttr-core) — Metadata runs on this
    // after the M4.1 swap (MD2): typed model, sources, reconcile, resolve, graph,
    // search, registry, refresher mechanism, world resolution. It re-exports
    // ttr-parser/writer/semantics as `api`, so those three are NOT declared here
    // (MetadataExportRoutes still imports them — they arrive transitively).
    implementation(libs.tatrman.ttr.metadata)
    implementation(libs.tomlj)
    // #272: YAML validity and JSON projection only; no report renderer dependency.
    implementation(libs.jackson.databind)
    implementation(libs.jackson.dataformat.yaml)
    // Direct blob reads at the served SHA in GitTemplateSource, without a second fetch/checkout.
    implementation(libs.jgit)
    // GitArchiveStorage behind the ModelStorage SPI (MD3).
    implementation(libs.tatrman.ttr.metadata.git)
    // `erp-sql-metadata` was a declared-but-unused dep in the predecessor platform's build (no `shared.erp.*`
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
    // CN-P5 S5.3 — the candidate store (cn contracts §6): ttr-concept's candidate reader, producer registry and
    // promotion patch (S5.2), and its definition renderer for a phase's pull request; the verified bearer
    // (mcp-identity's JWKS verifier); the store's Postgres (the BI receipts pattern: Hikari, Flyway on first use).
    implementation(libs.tatrman.ttr.concept)
    implementation(project(":shared:libs:kotlin:mcp-identity"))
    implementation(libs.hikaricp)
    implementation(libs.postgresql)
    implementation(libs.flyway.core)
    implementation(libs.flyway.pgsql)

    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.grpc.kotlin.stub)
    implementation(libs.grpc.netty.shaded)
    implementation(libs.grpc.services)

    // jgrapht (ModelGraph) rides ttr-metadata transitively.

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
    // VL-P2 — the layout routes encode their bodies with kotlinx (LayoutRoutes.kt: encodeDefaults + explicitNulls)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.typesafe.config)
    // Prometheus `/metrics` endpoint.
    implementation(libs.micrometer.registry.prometheus)
    implementation(libs.slf4j.api)
    implementation(libs.logback.classic)
    implementation(libs.logstash.logback.encoder)
    api(libs.otel.logback.appender)

    testImplementation(libs.bundles.kotest)
    testImplementation(libs.tatrman.ttr.metadata.cli)
    testImplementation(libs.mockk)
    // In-process gRPC server (review-004 R3.2 — live GetPrompts round-trip).
    testImplementation(libs.grpc.inprocess)
    // Ktor testApplication host — drives MetadataReadRoutes over HTTP (the llm-gateway pattern).
    testImplementation(libs.ktor.server.test.host)

    // Component tier (WS-C1 T4) — loads the whole `model-tpcds/` and `model-seed/` fixture trees
    // (TPC-DS, and the Hartland returns slice, each with its area) through the live ttr-metadata
    // reconciler and asserts the TPC-DS curated queries + areas resolve. Needs the metadata library (FileBasedSource / reconciler / registry /
    // graph) and the protos on the componentTest compile classpath (both `implementation` in main,
    // so not visible to the suite transitively).
    "componentTestImplementation"(libs.tatrman.ttr.metadata)
    "componentTestImplementation"(project(":shared:proto"))
    // CN-P5 S5.3 — the candidate store on a real Postgres (Testcontainers; `src/componentTest`, so the unit gate stays
    // Docker-free).
    "componentTestImplementation"(libs.tatrman.ttr.concept)
    "componentTestImplementation"(libs.testcontainers.postgresql)
    "componentTestImplementation"(libs.postgresql)
}
