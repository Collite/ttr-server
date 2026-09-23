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

// ES-P0·S0.2 — the ONE `worker.v1.ExecutionReceipt` builder, shared by every hop that fills a
// half of it: the two JVM workers (statement half) and ttr-query (plan half). worker-polars is
// Python and carries its own twin (ES plan §ES-P0·S0.2).
//
// A module of its own rather than a fold into component-testkit or db-common: the testkit is
// test-support (it `api`s Testcontainers, which has no business on a worker's runtime classpath)
// and db-common is JDBC plumbing with no proto dependency. This is the `transfer-core` shape —
// a thin main-scope lib over `:shared:proto`. Not in `publishableLibs`: no consumer outside
// this repo.
dependencies {
    // ExecutionReceipt / BoundParameter / StatementKind / RlsOutcome, and the plan.v1 +
    // validate.v1 types the two halves carry. `api` — they are this lib's whole surface.
    api(project(":shared:proto"))

    // A receipt never fails a run: the guard logs a WARN and returns absent (ES architecture §7).
    implementation(libs.slf4j.api)

    testImplementation(libs.bundles.kotest)
}
