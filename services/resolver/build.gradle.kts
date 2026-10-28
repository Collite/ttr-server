// SPDX-License-Identifier: Apache-2.0
// RG-P5 — resolver: the deterministic resolver core (workstream E).
//
// The ONE rule of this module: ZERO LLM. There is deliberately NO dependency on
// `:shared:libs:kotlin:llm-client` (or any llm-gateway stub) — the LLM
// escalation ladder is the kantheon Resolving Agent's job (RS-23). NoLlmDependencyTest
// asserts this mechanically; keeping the dep out of this file is the first line.
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.ktor)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ktlint)
    alias(libs.plugins.jib)
}

application {
    mainClass.set("org.tatrman.resolver.ApplicationKt")
}

tasks.test {
    useJUnitPlatform()
    // CN-P4 S4.4 — the conceptual tier's shared fixture estate, compiled at test time by the real producer
    // (`CnEstate`). Declared as an input so an edit to it re-runs the tests that read it.
    val cnEstate = rootProject.file("shared/testdata/cn-estate")
    inputs.dir(cnEstate).withPropertyName("cnEstate")
    systemProperty("cnEstate", cnEstate.absolutePath)
    // CN-P5 S5.1 — two of ttr-concept's golden worlds, the cn doors' seed estates (`shared/testdata/cn-worlds/README.md`).
    val cnWorlds = rootProject.file("shared/testdata/cn-worlds")
    inputs.dir(cnWorlds).withPropertyName("cnWorlds")
    systemProperty("cnWorlds", cnWorlds.absolutePath)
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
        image = "resolver:dev"
    }
    container {
        mainClass = "org.tatrman.resolver.ApplicationKt"
        ports = listOf("7275", "7276")
    }
    dockerClient {
        executable = "docker"
    }
}

dependencies {
    implementation(project(":shared:libs:kotlin:lexicon-transport"))
    implementation(project(":shared:libs:kotlin:ktor-configurator"))
    api(libs.otel.logback.appender)
    implementation(project(":shared:libs:kotlin:logging-config"))

    // S-2 — the one normalization spec (`fold`). Span proposal folds declared
    // anchor words the same way lex-matcher/the grounding kernel do; determinism
    // and cross-service parity require the byte-identical fold. Dependency-free leaf.
    implementation(project(":shared:libs:kotlin:text"))

    // RG-* diagnostics registry (RG-RES-001 degrade / RG-RES-002 bad resume token).
    implementation(project(":shared:libs:kotlin:diagnostics"))

    // RG-P6.S1 — the `resolve.bind:v1` MCP door (RS-27: the door exposes the
    // deterministic core only). MCP SDK + a CIO surface for streamable HTTP; the
    // shared fail-closed OBO identity gate (Decision B); protobuf JSON for faithful
    // ResolveResponse→structuredContent marshalling. NONE of these is an LLM client
    // (verifyNoLlmDependency stays green).
    implementation(project(":shared:libs:kotlin:mcp-identity"))
    implementation(libs.kotlin.mcp.sdk)
    implementation(libs.ktor.server.cio)
    implementation(libs.ktor.server.cors)
    implementation(libs.protobuf.java.util)

    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.ktor.server.call.logging)
    implementation(libs.logback.classic)
    implementation(libs.logstash.logback.encoder)
    implementation(libs.typesafe.config)

    // OpenTelemetry
    implementation(libs.ktor.opentelemetry)
    implementation(project(":shared:libs:kotlin:otel-config"))
    implementation(libs.micrometer.registry.prometheus)

    // gRPC — :shared:proto generates the ResolverService coroutine base + the
    // FuzzyService / NlpService / GroundingService client stubs the deterministic core calls.
    // The grounding stub needs no new coordinate: one proto module, all three contracts.
    implementation(project(":shared:proto"))
    implementation(libs.grpc.kotlin.stub)
    implementation(libs.grpc.netty.shaded)
    implementation(libs.grpc.services)
    implementation(libs.kotlinx.serialization.json)

    // RV-P1.6.T6 (RV-42) — the CLOSED `ground:` kind vocabulary, taken from the artifact that
    // defines it (`LexiconValidator.GROUNDING_KINDS`) rather than mirrored here. The core asks
    // lex-matcher which grounding kernel claims a span, and the set of kernels it may ask about is
    // the producer's to state; a copy in this repo would be a second rule free to drift from the
    // first. Reading it costs the artifact model only — ttr-lexicon is a leaf (kotlinx-json +
    // snakeyaml), and NOT the compiler, which is what the P1.2 (a3) ruling buys.
    implementation(libs.tatrman.ttr.lexicon)
    // ✅ Q-7 (Bora, 2026-08-14) — the declared-vocabulary channel is the compiled lexicon
    // archive, the same file lex-matcher mounts (RS-24: one channel, two consumers, one
    // snapshot identity). ttr-snapshot untars it; ttr-lexicon above decodes it. The COMPILER
    // stays out of the serving artifact, which is what the P1.2 (a3) ruling buys — and neither
    // is an LLM client, so the zero-LLM classpath guard below is unaffected.
    implementation(libs.tatrman.ttr.snapshot)
    // CN-P5 S5.1 — the cn doors (`statement.*` · `assertion.*` · `concept.search` · `definition.render`, cn contracts
    // §4) wrap ttr-concept's pure functions. It brings the typed model, the TTR-P frontend and the translator (the
    // er renderer's dialects, `ErModelHandle`); none is an LLM client, so the zero-LLM guard below holds.
    implementation(libs.tatrman.ttr.concept)

    testImplementation(libs.bundles.kotest)
    testImplementation(libs.mockk)
    testImplementation(libs.grpc.inprocess)
    // LP-P0·S3 T4 — the hero verdict diff serves each hero estate from a REAL lex-matcher
    // (GrpcService + FuzzyMatcher, in-process) under fuzzy.match v1 and v2. Test scope only: the
    // resolver's runtimeClasspath (and its zero-LLM guard) never sees it.
    testImplementation(project(":services:lex-matcher"))
    testImplementation(project(":shared:libs:kotlin:lex-matcher-core"))
    // RV-P2.1.T5 — the Q-15 frame-role fixture corpus is authored YAML and is re-run
    // in-process against the ported rules (FrameRolesFixtureTest). Test-only.
    testImplementation(libs.jackson.dataformat.yaml)
    testImplementation(libs.jackson.databind)
    // Q-7 — the archive fixture is packed by the REAL producer, test scope only, exactly as
    // lex-matcher's `LexiconArchiveSourceTest` does. Two readers conformant by contract are
    // held together by both being held to the same packer.
    testImplementation(libs.tatrman.ttr.lexicon.compile)
    // CN-P5 S5.1 — the cn doors pin their model to the lexicon's `modelSnapshotHash`, which ttr-lexicon-cli's
    // `ModelContentId` computes; the doors hash the tree by the same rule, and a test holds the copy to the producer's.
    testImplementation(libs.tatrman.ttr.lexicon.cli)
    // MS-P3.S4 — the frame-role corpus derives each mention's `objectKind` through the REAL
    // `MentionKinds` table (contracts §8.5) instead of the fixture stating one. There is exactly
    // ONE implementation of that mapping in the ecosystem and it lives in ttr-semantics; a copy of
    // it in this repo's test tree would be a second.
    //
    // ⛑ Declared at TEST scope to say where it belongs — but that scope is a signal, NOT a guard,
    // and the first version of this comment claimed otherwise (review-084 F2). `ttr-semantics` is
    // already on this module's `runtimeClasspath`, transitively via `ttr-metadata`, so production
    // code here can import `MentionKinds` and compile. What actually holds the line is
    // `verifyNoKindDerivation` below.
    testImplementation(libs.tatrman.ttr.semantics)
}

// RG-P5 — structural ZERO-LLM guard (RS-23). Fail the build if the resolver's
// runtime classpath resolves ANY LLM client: the in-house llm-gateway client
// module (`:shared:libs:kotlin:llm-client`) or a known external LLM SDK.
// This enforces the invariant at the dependency-graph level; NoLlmDependencyTest
// is the runtime backstop. (The generated `org.tatrman.llm.v1` gRPC stub was split
// into its own `:shared:proto-llm` module, so it is no longer on this classpath —
// NoLlmDependencyTest asserts its absence as a hard forbidden class.)
val forbiddenLlmCoordinates =
    listOf("llm-client", "openai", "anthropic", "langchain4j", "langchain", "theokanning")

val verifyNoLlmDependency by tasks.registering {
    val runtimeClasspath = configurations.named("runtimeClasspath")
    doLast {
        val hits =
            runtimeClasspath
                .get()
                .resolvedConfiguration.resolvedArtifacts
                .map { it.moduleVersion.id }
                .filter { id ->
                    forbiddenLlmCoordinates.any { bad ->
                        id.name.contains(bad, ignoreCase = true) || id.group.contains(bad, ignoreCase = true)
                    }
                }.map { "${it.group}:${it.name}:${it.version}" }
                .distinct()
        require(hits.isEmpty()) {
            "ZERO-LLM violation (RS-23): resolver runtimeClasspath resolves LLM client artifact(s): $hits"
        }
    }
}

// MS (review-084 F2) — structural NO-KIND-DERIVATION guard. Fail the build if anything in this
// module's MAIN source set reaches for the derivation table.
//
// `FrameRoles.isMeasure`'s ⛔ comment forbids deriving an `objectKind` anywhere in this service:
// the model decides, through exactly one table, upstream, and a second rule here would be free to
// drift from the model's own. That was enforced by prose, and prose was assumed to be backed by
// the `testImplementation` scope above — which backs nothing, because `ttr-metadata` puts
// `ttr-semantics` on the runtime classpath regardless. This is the guard that comment describes:
// a `MentionKinds` import under `src/main` fails the build, and the failure names the rule.
//
// The token is the PACKAGE, not the type name, and that is deliberate: Kotlin can only reach
// `MentionKinds` through an import or a fully-qualified use, and both spell the package — so one
// token is necessary and sufficient.
//
// Comments are stripped before the search, because the main-source KDocs that *name* the chain
// (`FrameRoles`, `ResolverRegistry`, `RegistrySource`, `LexiconArchiveRegistrySource`) must stay
// legal: explaining where a kind comes from is the opposite of deriving one, and a guard that
// punished the explanation would delete the only documentation of the chain. The strip is
// deliberately naive — a `//` inside a string literal truncates that line — which can only ever
// cost a false NEGATIVE on a line that mixes a string with a fully-qualified use. An `import` line
// contains no strings, so the shape that actually enables the local fix is always caught.
//
// Test sources are exempt on purpose — deriving the kind from declared facts is exactly what the
// frame-role corpus must do (contracts §8.5), and doing it through the real table is the point.
val forbiddenKindDerivationImport = "org.tatrman.ttr.semantics"

val verifyNoKindDerivation by tasks.registering {
    val mainSources = fileTree("src/main") { include("**/*.kt") }
    inputs.files(mainSources).withPathSensitivity(PathSensitivity.RELATIVE)
    doLast {
        val blockComment = Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL)
        val lineComment = Regex("""(?m)//.*$""")
        val hits =
            mainSources
                .filter { file ->
                    file
                        .readText()
                        .replace(blockComment, "")
                        .replace(lineComment, "")
                        .contains(forbiddenKindDerivationImport)
                }.map { it.relativeTo(projectDir).path }
        require(hits.isEmpty()) {
            "NO-KIND-DERIVATION violation (MS contracts §5, FrameRoles.isMeasure ⛔): " +
                "$hits import `$forbiddenKindDerivationImport` from MAIN sources. A kind is DECLARED " +
                "by the model and copied verbatim through the archive — it is never derived in this " +
                "service. Naming the table in a comment is fine; reaching for it is not."
        }
    }
}

// ttr-server#209 (review of #244) — structural DECLARED-LAYER TWIN guard. Fail the build if the two
// copies of `DeclaredLayer.kt` differ in anything but their `package` line.
//
// The resolver and lex-matcher both serve the declared lexicon layer, and RS-24 forbids either from
// importing the other until the RO-13 shared lib exists — so the multi-archive merge (the compiler's
// precedence restated, the per-ref fact union, which archives are served) lives in each service as a
// verbatim copy. Two copies kept aligned by prose is exactly how the two readers drifted before
// (the empty-vocabulary defect), so the alignment is checked here, on every `check`. Edit one copy,
// copy it to the other. The producer-side fix — one public merge in ttr-lexicon — is asked for in
// Collite/ttr-core#190.
val declaredLayerTwins =
    listOf(
        file("src/main/kotlin/org/tatrman/resolver/registry/DeclaredLayer.kt"),
        rootProject.file("services/lex-matcher/src/main/kotlin/org/tatrman/fuzzy/loader/DeclaredLayer.kt"),
    )

val verifyDeclaredLayerTwin by tasks.registering {
    val twins = declaredLayerTwins
    val root = rootDir
    inputs.files(twins).withPathSensitivity(PathSensitivity.RELATIVE)
    doLast {
        val packageLine = Regex("""(?m)^package [\w.]+$""")
        val (ours, theirs) = twins.map { it.readText().replaceFirst(packageLine, "package <twin>").lines() }
        val at = (0 until maxOf(ours.size, theirs.size)).firstOrNull { ours.getOrNull(it) != theirs.getOrNull(it) }
        if (at != null) {
            throw GradleException(
                "DECLARED-LAYER TWIN drift (RS-24, ttr-server#209): ${twins.map { it.relativeTo(root).path }} " +
                    "differ at line ${at + 1}:\n" +
                    "  resolver:    ${ours.getOrNull(at) ?: "<end of file>"}\n" +
                    "  lex-matcher: ${theirs.getOrNull(at) ?: "<end of file>"}\n" +
                    "The two copies must be the SAME code but for their `package` line: edit one, copy it to the other.",
            )
        }
    }
}

// CN-P5 S5.1 T4 — structural NO-DB-CLIENT guard (cn contracts §2.3: cn never executes a write; A-CN-50). The cn doors
// co-host here, and `assertion.lower:v1` returns a row payload: nothing on this classpath may be able to store it. Fail the
// build if the runtime classpath resolves a database driver, a JDBC pool or an ADBC / Arrow Flight SQL client. Calcite and
// Avatica are allowed and are the reason this is a list rather than "no java.sql": they are the translator's relational
// algebra (ttr-concept's er renderer and `ErModelHandle`), and reach no database without a driver from this list.
// `CnDoorPurityTest` is the other half: no door class references JDBC at all.
val forbiddenDbCoordinates =
    listOf(
        "postgresql",
        "mssql-jdbc",
        "mysql-connector",
        "mariadb-java-client",
        "ojdbc",
        "sqlite-jdbc",
        "duckdb_jdbc",
        "h2database",
        "hsqldb",
        "derby",
        "HikariCP",
        "r2dbc",
        "adbc",
        "flight-sql",
    )

val verifyNoDbClient by tasks.registering {
    val runtimeClasspath = configurations.named("runtimeClasspath")
    doLast {
        val hits =
            runtimeClasspath
                .get()
                .resolvedConfiguration.resolvedArtifacts
                .map { it.moduleVersion.id }
                .filter { id ->
                    forbiddenDbCoordinates.any { bad ->
                        id.name.contains(bad, ignoreCase = true) || id.group.contains(bad, ignoreCase = true)
                    }
                }.map { "${it.group}:${it.name}:${it.version}" }
                .distinct()
        require(hits.isEmpty()) {
            "NO-DB-CLIENT violation (cn contracts §2.3, A-CN-50): resolver runtimeClasspath resolves database client " +
                "artifact(s): $hits — the cn doors return rows, they never write them."
        }
    }
}

tasks.named("check") {
    dependsOn(verifyNoLlmDependency, verifyNoKindDerivation, verifyDeclaredLayerTwin, verifyNoDbClient)
}
