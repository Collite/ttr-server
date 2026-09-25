// SPDX-License-Identifier: Apache-2.0
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.ktor)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ktlint)
    alias(libs.plugins.jib)
}

application {
    mainClass.set("org.tatrman.fuzzy.ApplicationKt")
}

tasks.test {
    useJUnitPlatform()
    // Forward the FZ perf-harness switches to the forked test JVM (Gradle does not propagate
    // -D system properties across the fork by default): -DregenGoldens=true rewrites the parity
    // goldens (GoldenCaptureTest), -DincludePerf=true enables the opt-in BenchmarkSpec.
    listOf("regenGoldens", "includePerf").forEach { key ->
        System.getProperty(key)?.let { systemProperty(key, it) }
    }
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
        image = "fuzzy:dev"
    }
    container {
        mainClass = "org.tatrman.fuzzy.ApplicationKt"
        ports = listOf("7265", "7266")
    }
    dockerClient {
        executable = "docker"
    }
}

dependencies {
    implementation(project(":shared:libs:kotlin:ktor-configurator"))
    api(libs.otel.logback.appender)
    implementation(project(":shared:libs:kotlin:logging-config"))

    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.server.cors)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.ktor.server.call.logging)
    implementation(libs.logback.classic)
    implementation(libs.logstash.logback.encoder)

    // Stage 2.2 lean: no SQL backend at v1 (the catalog is an in-repo JSON).
    // exposed / hikaricp / mssql / pgsql are dropped from the kantheon fuzzy
    // fork per the lean carve-out. Re-add when the metadata-driven loader
    // (veles + SQL warehouse) is wired in a later stage.
    implementation(libs.typesafe.config)

    implementation(libs.java.string.similarity)
    // FZ-P3 — the extracted pure fuzzy engine (org.tatrman.fuzzy.core.*). The service keeps
    // StringRepository/loaders/api/telemetry and composes the engine over them.
    // RV-P1.4 T3 — the compiled lexicon archive: ttr-snapshot untars it, ttr-lexicon decodes it.
    // What the a3 ruling buys is that the COMPILER stays out of the serving artifact; ttr-metadata
    // and ttr-parser still arrive transitively, because ttr-snapshot `api`s ttr-metadata (its
    // SnapshotArchiveStorage implements that module's ModelStorage SPI). Unavoidable, and cheap
    // next to dragging in the compiler and its stdlib resources.
    implementation(libs.tatrman.ttr.snapshot)
    implementation(libs.tatrman.ttr.lexicon)
    implementation(project(":shared:libs:kotlin:lex-matcher-core"))
    // MV-T2 — RG-FUZ-00x wording comes from the one registry, not from string literals here.
    implementation(project(":shared:libs:kotlin:diagnostics"))
    implementation(project(":shared:libs:kotlin:fuzzy-common"))
    // RG-P0.S3 — fold() now comes from the shared S-2 lib (was inline here).
    implementation(project(":shared:libs:kotlin:text"))

    // Nlp (Phase 2.3) Czech lemmatisation client — kept but disabled at v1
    // (see application.conf `fuzzy.nlp.enabled = false`). When the lib is
    // unused (NoopLemmatizer path) the engine dependency tree stays small.
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)

    // OpenTelemetry
    implementation(libs.ktor.opentelemetry)
    implementation(project(":shared:libs:kotlin:otel-config"))
    implementation(libs.micrometer.registry.prometheus)

    // gRPC — :shared:proto generates the FuzzyService coroutine base class
    // and the VelesService stub the metadata loader calls.
    implementation(project(":shared:proto"))
    implementation(libs.grpc.kotlin.stub)
    implementation(libs.grpc.netty.shaded)
    implementation(libs.grpc.services)
    implementation(libs.kotlinx.serialization.json)

    // SQL backend for the `metadata` loader source (Veles → SELECT pk,col → DB
    // → catalog). Off the path for the `static` (in-repo JSON catalog) source;
    // DatabaseFactory.connect runs only when fuzzy.loader.source = "metadata".
    implementation(libs.exposed.core)
    implementation(libs.exposed.jdbc)
    implementation(libs.hikaricp)
    implementation(libs.postgresql)
    implementation(libs.mssql.jdbc)

    // TEST SCOPE ONLY — the reader is exercised against the REAL packer, so a layout change in
    // the producer breaks this test rather than silently diverging. The serving artifact still
    // ships neither the compiler nor its ttr-parser/ttr-metadata closure.
    testImplementation(libs.tatrman.ttr.lexicon.compile)
    testImplementation(libs.bundles.kotest)
    testImplementation(libs.mockk)
    testImplementation(libs.grpc.inprocess)
    // MV-T2 — the member loader's read plans run through the real JDBC row mapping (PostgreSQL mode).
    testImplementation(libs.h2)
    testImplementation(libs.ktor.client.mock)
    testImplementation(libs.ktor.server.test.host)
}
