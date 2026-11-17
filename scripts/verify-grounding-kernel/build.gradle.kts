// SPDX-License-Identifier: Apache-2.0
// OL W3·S1 T3 — see settings.gradle.kts. `./gradlew -p scripts/verify-grounding-kernel build`
// compiles the two consumer classes; `dependencies --configuration compileClasspath` shows where
// each artefact came from.
plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(21)
}

val kernelVersion = (findProperty("kernelVersion") as String?) ?: "0.0.1-LOCAL"

dependencies {
    implementation("org.tatrman:grounding-core:$kernelVersion")
    implementation("org.tatrman:grounding-lexicon:$kernelVersion")
}
