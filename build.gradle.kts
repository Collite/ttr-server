// SPDX-License-Identifier: Apache-2.0
plugins {
    base
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.ktlint) apply false
    // SV-P1 S4 — vanniktech on the root classpath (`apply false`) so the shared
    // MavenCentralBuildService registers once for the whole build; each published
    // lib applies it for real in the publishing convention below.
    alias(libs.plugins.maven.publish.vanniktech) apply false
}

allprojects {
    // The open-spine group id: plain `org.tatrman` (kantheon's own group carries
    // an extra segment; server artifacts do not). The license/ownership boundary
    // is physical — server artifacts live under org.tatrman:* the same as the
    // tatrman toolchain (contracts §1, §7).
    group = "org.tatrman"
    version = providers.gradleProperty("tatrman-server.version").orNull ?: "0.0.0-SNAPSHOT"
}

subprojects {
    plugins.withId("org.jetbrains.kotlin.jvm") {
        extensions.configure<org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension> {
            jvmToolchain(21)
        }

        // ── Component test tier (mirrors kantheon's testing arc) ─────────────
        // A dedicated `componentTest` source set (`src/componentTest/kotlin`)
        // for **real-dependency** Testcontainers specs, physically separate from
        // `src/test` so the `test` PR gate stays mocked-only. Kotest ships its
        // own JUnit-Platform engine; isolation from the mocked `test` gate is the
        // source-set split plus the classpath guard below.
        extensions.configure<org.gradle.testing.base.TestingExtension> {
            suites.register("componentTest", org.gradle.api.plugins.jvm.JvmTestSuite::class.java) {
                dependencies {
                    implementation(project())
                    implementation(libs.kotest.runner.junit5)
                    implementation(libs.kotest.assertions.core)
                    implementation(libs.kotest.property)
                    implementation(libs.testcontainers)
                }
                targets.configureEach {
                    testTask.configure {
                        useJUnitPlatform()
                        shouldRunAfter(tasks.named("test"))
                    }
                }
            }

            // ── Integration test tier (cluster-bound; skips without -Pcontext) ─
            suites.register("integrationTest", org.gradle.api.plugins.jvm.JvmTestSuite::class.java) {
                dependencies {
                    implementation(project())
                    implementation(libs.kotest.runner.junit5)
                    implementation(libs.kotest.assertions.core)
                    implementation(libs.kotest.property)
                }
                targets.configureEach {
                    testTask.configure {
                        useJUnitPlatform()
                        shouldRunAfter(tasks.named("test"))
                        onlyIf { providers.gradleProperty("context").isPresent }
                        providers.gradleProperty("context").orNull?.let { systemProperty("context", it) }
                        providers.gradleProperty("namespace").orNull?.let { systemProperty("namespace", it) }
                    }
                }
            }
        }

        // The unit `test` gate must never run a component/integration spec.
        // Fail fast if either higher tier's output leaks onto the unit classpath.
        val higherTierClassesDirs =
            extensions.getByType<org.gradle.api.tasks.SourceSetContainer>().let { sets ->
                sets["componentTest"].output.classesDirs + sets["integrationTest"].output.classesDirs
            }
        tasks.named<org.gradle.api.tasks.testing.Test>("test") {
            doFirst {
                val higherTierFiles = higherTierClassesDirs.files
                val leaked = classpath.files.filter { it in higherTierFiles }
                require(leaked.isEmpty()) {
                    "Higher-tier (component/integration) classes on the unit `test` classpath: $leaked. " +
                        "test-all must stay mocked-only."
                }
            }
        }
    }

    plugins.withId("org.jlleitschuh.gradle.ktlint") {
        // ktlint config picked up from .editorconfig at repo root.
    }
}

// ── SV-P0 interim publishing (S5 wrinkle) ────────────────────────────────────
// The shared libs + proto stubs moved from kantheon, but kantheon's staying
// agents/tools/services still consume them (otel-config, logging-config,
// ktor-configurator, the client libs, the spine proto stubs, …). They publish
// `0.0.1-LOCAL` to Maven Local so kantheon resolves them as `org.tatrman:*`
// artifacts — the SAME mechanism kantheon already uses for `ttr-metadata`
// (standing facts). Flips to the 0.9.x public line at SV-P1 gate 3. Test-support
// libs (component-testkit, integration-harness) are server-internal → not published.
val publishableLibs =
    setOf(
        ":shared:proto",
        ":shared:libs:kotlin:otel-config",
        ":shared:libs:kotlin:logging-config",
        ":shared:libs:kotlin:ktor-configurator",
        ":shared:libs:kotlin:db-common",
        ":shared:libs:kotlin:data-formatter",
        // FZ-P3 — the extracted fuzzy engine, consumed by ai-platform (NX-B pattern).
        ":shared:libs:kotlin:lex-matcher-core",
        // text (RG-P0.S3 — the one S-2 fold) is now published: lex-matcher-core depends on it, so
        // ai-platform (consuming lex-matcher-core) needs the fold resolvable — exactly the cross-repo
        // consumer the RG-P0.S3 note anticipated. diagnostics still has no external consumer.
        ":shared:libs:kotlin:text",
        ":shared:libs:kotlin:fuzzy-common",
        ":shared:libs:kotlin:whois-common",
        ":shared:libs:kotlin:keycloak-auth",
        ":shared:libs:kotlin:meta-client",
        ":shared:libs:kotlin:llm-client",
        // CH-D5 — the Charon transfer seam, consumed by tatrman-platform's radegast
        // (CH-P2 cutover) behind the unchanged TransferMover in-process seam.
        ":shared:libs:kotlin:transfer-core",
        // capabilities-client TRIMMED at SV-P1 S2 (2026-07-11): no external consumer
        // (kantheon keeps its own copy; nothing published depends on it). Publishing it
        // would be a maintenance promise with no taker — re-add here if a consumer appears.
    )
// SV-P1 S4 — Maven Central coordinates need a per-artifact name + description
// (the GH Packages POM did not). artifactId = project.name except `:shared:proto`
// → `server-proto` (a bare "proto" coordinate is too generic).
val pomMeta: Map<String, Triple<String, String, String>> =
    mapOf(
        // path to Triple(artifactId, POM name, POM description)
        ":shared:proto" to Triple("server-proto", "TTR Server Proto", "gRPC/protobuf wire contracts for the TTR read-spine services (meta.v1, query.v1, translate.v1, validate.v1, dispatch.v1, worker.v1, transfer.v1, metis.v1, …)."),
        ":shared:libs:kotlin:otel-config" to Triple("otel-config", "TTR Server OTel Config", "OpenTelemetry bootstrap shared by the TTR read-spine services."),
        ":shared:libs:kotlin:logging-config" to Triple("logging-config", "TTR Server Logging Config", "Logback/structured-logging bootstrap shared by the TTR read-spine services."),
        ":shared:libs:kotlin:ktor-configurator" to Triple("ktor-configurator", "TTR Server Ktor Configurator", "Shared Ktor server bootstrap (routing, health, OTel, error handling) for the TTR read-spine services."),
        ":shared:libs:kotlin:db-common" to Triple("db-common", "TTR Server DB Common", "Shared JDBC/connection-pool helpers for the TTR worker services."),
        ":shared:libs:kotlin:data-formatter" to Triple("data-formatter", "TTR Server Data Formatter", "Shared result/value formatting for the TTR read-spine services."),
        ":shared:libs:kotlin:lex-matcher-core" to Triple("lex-matcher-core", "TTR Lex Matcher Core", "The embedded fuzzy-matching engine (token index + vocabulary, index-first retrieval, cascade) for the TTR services."),
        ":shared:libs:kotlin:text" to Triple("text", "TTR Text", "The one S-2 text fold (lowercase → NFD → strip marks) every TTR matcher shares."),
        ":shared:libs:kotlin:fuzzy-common" to Triple("fuzzy-common", "TTR Server Fuzzy Common", "Shared fuzzy-matching types for the TTR services."),
        ":shared:libs:kotlin:whois-common" to Triple("whois-common", "TTR Server Whois Common", "Shared identity/role-source (roleSource: bearer|whois) types for the TTR services."),
        ":shared:libs:kotlin:keycloak-auth" to Triple("keycloak-auth", "TTR Server Keycloak Auth", "Shared Keycloak/OBO bearer-auth helpers for the TTR services."),
        ":shared:libs:kotlin:meta-client" to Triple("meta-client", "TTR Meta Client", "gRPC client for the Veles metadata service (meta.v1)."),
        ":shared:libs:kotlin:llm-client" to Triple("llm-client", "TTR LLM Client", "Client for the llm-gateway service (llm.v1)."),
        ":shared:libs:kotlin:transfer-core" to Triple("transfer-core", "TTR Transfer Core", "The Charon transfer seam (MoveExecutor + Plan + CharonError over transfer.v1) for in-process embedding behind a TransferMover."),
    )

subprojects {
    if (path !in publishableLibs) return@subprojects
    // vanniktech owns the publication (adds the sources + javadoc jars Central
    // requires) and the Central Portal target; the GH Packages block below stays
    // the pre-release staging lane (RO-17: Central is the public registry).
    apply(plugin = "com.vanniktech.maven.publish")
    val (artifact, pomName, pomDescription) =
        pomMeta[path] ?: error("publishableLibs entry $path has no pomMeta (name/description) — Central requires both")
    extensions.configure<com.vanniktech.maven.publish.MavenPublishBaseExtension> {
        publishToMavenCentral()
        // Central requires signatures; the Central CI lane supplies the key. Local
        // builds + the GH Packages staging lane don't sign — gate on the key so
        // signAllPublications() doesn't hard-fail those (it fails a non-SNAPSHOT
        // version when unkeyed).
        if (providers.environmentVariable("ORG_GRADLE_PROJECT_signingInMemoryKey").isPresent ||
            providers.gradleProperty("signingInMemoryKey").isPresent
        ) {
            signAllPublications()
        }
        coordinates("org.tatrman", artifact, version.toString())
        pom {
            name.set(pomName)
            description.set(pomDescription)
            inceptionYear.set("2025")
            url.set("https://github.com/Collite/ttr-server")
            licenses {
                license {
                    name.set("The Apache License, Version 2.0")
                    url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                    distribution.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                }
            }
            developers {
                developer {
                    id.set("collite")
                    name.set("Collite")
                    url.set("https://github.com/Collite")
                }
            }
            scm {
                connection.set("scm:git:https://github.com/Collite/ttr-server.git")
                developerConnection.set("scm:git:git@github.com:Collite/ttr-server.git")
                url.set("https://github.com/Collite/ttr-server")
            }
        }
    }
    extensions.configure<org.gradle.api.publish.PublishingExtension> {
        repositories {
            maven {
                name = "GitHubPackages"
                url = uri("https://maven.pkg.github.com/Collite/ttr-server")
                credentials {
                    username = providers.gradleProperty("gpr.user").orNull ?: System.getenv("GITHUB_ACTOR")
                    password = providers.gradleProperty("gpr.token").orNull ?: System.getenv("GITHUB_TOKEN")
                }
            }
        }
    }
}
