// SPDX-License-Identifier: Apache-2.0
plugins {
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
    // The one S-2 fold (RG-P0.S3): `TextNormalizer` delegates to `org.tatrman.text.Normalization`
    // so every matcher folds byte-identically. `api` so consumers (a downstream platform) get it transitively.
    api(project(":shared:libs:kotlin:text"))

    // Levenshtein (bounded + unbounded) — the only heavy engine dependency.
    api(libs.java.string.similarity)

    // FuzzyMatcher fans spans out with structured concurrency; the matcher/repository seam logs.
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.slf4j.api)

    testImplementation(libs.bundles.kotest)
    testImplementation(libs.kotlinx.coroutines.core)
}
