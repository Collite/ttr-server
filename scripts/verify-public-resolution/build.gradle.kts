// SPDX-License-Identifier: Apache-2.0
// SV-P1 S4 T6 — the phase-DONE proof: every published spine artifact resolves
// ANONYMOUSLY from Maven Central. Run on a machine/container with NO ~/.gradle
// credentials:
//
//   gradle -PspineVersion=0.9.4 verifyPublicResolution --refresh-dependencies
//   # (or: gradle -PspineVersion=0.9.4 dependencies --configuration runtimeClasspath --refresh-dependencies)
//
// The ONLY repository is mavenCentral() — no GitHub Packages, no credentials.
// If resolution succeeds, the org.tatrman:* coordinates are genuinely public.
//
// NOTE: this cannot pass until S4 T4/T5 have actually published to Central, and
// Central search/CDN sync lags a release by ~15-120 min (don't flag a failure
// before ~2 h). Reruns at every future gate + at SV-P6.
//
// Versions: the read spine did not publish under one uniform version — pass the
// version(s) being verified. `spineVersion` (default 0.9.4) covers most; the
// ttr-metadata pair defaults to `metadataVersion`. Override either on the
// command line as the Central line evolves.
//
// `java-library` (not `base`): resolving these deps means resolving variant-aware
// modules like Guava, which publishes separate android/jre variants and needs the
// consumer to carry `TargetJvmEnvironment=standard-jvm` to disambiguate. The java
// plugin supplies that attribute (and the rest of the JVM ecosystem attributes) on
// `runtimeClasspath`; `base` alone does not, so Guava fails with "no matching
// variant". We compile nothing — the plugin is here purely for correct resolution.
plugins {
    `java-library`
}

repositories {
    mavenCentral() // NO GitHub Packages, NO credentials — the whole point of the proof.
}

val spineVersion = (findProperty("spineVersion") as String?) ?: "0.9.4"
val metadataVersion = (findProperty("metadataVersion") as String?) ?: spineVersion

dependencies {
    // tatrman toolchain (Collite/ttr-core)
    implementation("org.tatrman:ttr-parser:$spineVersion")
    implementation("org.tatrman:ttr-writer:$spineVersion")
    implementation("org.tatrman:ttr-semantics:$spineVersion")
    implementation("org.tatrman:ttr-metadata:$metadataVersion")
    implementation("org.tatrman:ttr-metadata-git:$metadataVersion")
    implementation("org.tatrman:ttr-plan-proto:$spineVersion")
    implementation("org.tatrman:ttr-translator:$spineVersion")
    // tatrman-server libs (Collite/ttr-server)
    implementation("org.tatrman:server-proto:$spineVersion")
    implementation("org.tatrman:otel-config:$spineVersion")
    implementation("org.tatrman:logging-config:$spineVersion")
    implementation("org.tatrman:ktor-configurator:$spineVersion")
    implementation("org.tatrman:db-common:$spineVersion")
    implementation("org.tatrman:data-formatter:$spineVersion")
    implementation("org.tatrman:fuzzy-common:$spineVersion")
    implementation("org.tatrman:whois-common:$spineVersion")
    implementation("org.tatrman:keycloak-auth:$spineVersion")
    implementation("org.tatrman:meta-client:$spineVersion")
    implementation("org.tatrman:llm-client:$spineVersion")
}

// The 18 org.tatrman:* coordinates the spine must expose on Central.
val expectedSpine = listOf(
    "ttr-parser", "ttr-writer", "ttr-semantics", "ttr-metadata", "ttr-metadata-git",
    "ttr-plan-proto", "ttr-translator", "server-proto", "otel-config", "logging-config",
    "ktor-configurator", "db-common", "data-formatter", "fuzzy-common", "whois-common",
    "keycloak-auth", "meta-client", "llm-client",
)

tasks.register("verifyPublicResolution") {
    val runtimeClasspath = configurations.named("runtimeClasspath")
    val versions = "spine=$spineVersion, metadata=$metadataVersion"
    doLast {
        val rc = runtimeClasspath.get()
        require(rc.files.isNotEmpty()) {
            "no artifacts resolved — Central publish incomplete or not yet synced ($versions)"
        }
        // Identify the org.tatrman:* components by GROUP (accurate), not filename.
        val tatrman = rc.incoming.resolutionResult.allComponents
            .mapNotNull { it.id as? org.gradle.api.artifacts.component.ModuleComponentIdentifier }
            .filter { it.group == "org.tatrman" }
            .associate { it.module to it.version }
        val missing = expectedSpine.filter { it !in tatrman }
        require(missing.isEmpty()) {
            "resolved from Central, but these org.tatrman:* coordinates are MISSING at the given version(s): $missing ($versions)"
        }
        println("✅ resolved ${rc.files.size} files anonymously from Maven Central ($versions)")
        println("   all ${expectedSpine.size} org.tatrman:* spine artifacts present:")
        expectedSpine.sorted().forEach { println("     org.tatrman:$it:${tatrman[it]}") }
    }
}
