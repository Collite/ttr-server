// SPDX-License-Identifier: Apache-2.0
// translate-snapshot — the ONE adapter from a Veles `ModelSnapshot` to the translator's
// `ModelHandle` (MV-T1, 2026-09-24; moved out of services/translate). Two readers use it:
// the translate service (query time — every ER query an agent runs) and Veles'
// `ListMemberVocabularies` (index time — the `read_sql` a member vocabulary is loaded by).
// One adapter is the point: the entity's population must be decided by the same code at
// both times, or the index and the query drift apart.
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
    api(project(":shared:proto"))
    api(libs.tatrman.ttr.translator)

    testImplementation(libs.bundles.kotest)
}
