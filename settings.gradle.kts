// SPDX-License-Identifier: Apache-2.0
rootProject.name = "tatrman-server"

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        // ⚠ `org.tatrman:*` is EXCLUDED from Central, deliberately (2026-09-24). Maven Central is
        // an OUTPUT of this ecosystem, never an input: a `-RELEASE`-marked tag publishes there for
        // EXTERNAL consumers, and Central's free tier (~7 releases per namespace per month on a
        // 3-month rolling average) cannot absorb an internal cadence of several cuts per week —
        // see tatrman's PUBLISHING.md § Release lanes. EVERY tag, bare or RELEASE, reaches GitHub
        // Packages, so the internal chain reads that lane and only that lane.
        //
        // Without the exclusion the two lanes race: whichever registry happens to hold the pinned
        // version wins, and an internal build silently starts depending on a public release nobody
        // meant to make. That is not hypothetical — kantheon drifted onto Central, and by
        // 2026-09-24 was pinned three server-libs versions back waiting for a Central cut that the
        // internal lane had already published.
        mavenCentral {
            content { excludeGroup("org.tatrman") }
        }
        // The TTR toolchain (org.tatrman:ttr-{parser,writer,semantics,metadata,snapshot,lexicon,
        // lexicon-compile,plan-proto,translator,…}), published by the `tatrman` repo under
        // Collite/ttr-core on EVERY `grammar/v*` / `translator/v*` tag.
        //
        // GitHub Packages requires authentication even for a PUBLIC repository's packages, so the
        // credentials are not optional: `gpr.user`/`gpr.token` in ~/.gradle/gradle.properties
        // locally (a classic PAT with `read:packages`), the auto-provisioned GITHUB_TOKEN in
        // Actions (the workflow needs `permissions: packages: read`).
        maven {
            name = "Tatrman"
            url = uri("https://maven.pkg.github.com/Collite/ttr-core")
            credentials {
                username = providers.gradleProperty("gpr.user").orNull
                    ?: System.getenv("GITHUB_ACTOR")
                password = providers.gradleProperty("gpr.token").orNull
                    ?: System.getenv("GITHUB_TOKEN")
            }
            content {
                includeGroup("org.tatrman")
            }
        }
        // LAST on purpose. A `publishToMavenLocal` copy is for iterating on an UNPUBLISHED version
        // (the SV-P0 `0.0.1-LOCAL` habit); it must never shadow a version that a registry already
        // serves, because a stale ~/.m2 jar carrying the same coordinate as a real cut is one of
        // the hardest skews in this ecosystem to see. Ordered here, a local copy is reached only
        // when neither lane above has that version at all.
        mavenLocal()
    }
}

// Toolchain exercise module (S1) — deleted once the moved modules are green.
include(":tools:_smoke-test")

// ── SV-P0 S3 move set — spine transplanted from kantheon@355c68d, renamed on
// arrival (ledger §3). Package/proto internals are swept in S4; the build is
// intentionally RED until then (S3+S4 = one change window).
// Python modules (services/nlp, workers/worker-polars) and the non-Gradle
// infra/backstage are built out-of-band, not included here.

// Shared wire protos + Kotlin libs
include(":shared:proto")
// LLM gateway wire contract — split out of :shared:proto so its gRPC stub does
// not reach zero-LLM services (RS-23). Only the llm-gateway server depends on it.
include(":shared:proto-llm")
include(":shared:libs:kotlin:otel-config")
include(":shared:libs:kotlin:logging-config")
include(":shared:libs:kotlin:ktor-configurator")
include(":shared:libs:kotlin:mcp-identity")
include(":shared:libs:kotlin:db-common")
include(":shared:libs:kotlin:data-formatter")
// RG-P0.S3 — S-2 shared normalization (fold) + RG-* diagnostics registry.
include(":shared:libs:kotlin:text")
include(":shared:libs:kotlin:diagnostics")
// RG-P3 grounding kernel (workstream D) — consolidated PlanExpr/SqlRenderer/RecipeBuilder scaffolding.
include(":shared:libs:kotlin:grounding-core")
include(":shared:libs:kotlin:grounding-lexicon")
// FZ-P3 — the pure fuzzy engine, extracted so ai-platform consumes it instead of a copy.
include(":shared:libs:kotlin:lex-matcher-core")
include(":shared:libs:kotlin:fuzzy-common")
include(":shared:libs:kotlin:whois-common")
include(":shared:libs:kotlin:keycloak-auth")
include(":shared:libs:kotlin:meta-client")
// MV-T1 — the Veles-snapshot → translator ModelHandle adapter, shared by translate + Veles.
include(":shared:libs:kotlin:translate-snapshot")
include(":shared:libs:kotlin:llm-client")
// CH-D5 — the published Charon transfer seam (MoveExecutor + Plan + Either +
// CharonError + MoveRpc), embedded in-process by radegast behind TransferMover.
include(":shared:libs:kotlin:transfer-core")
// Grafted from kantheon per Bora's decision (S4) — capability-registration client
// (4 MCP tools) + the component/integration test-tier harness libs.
// ES-P0 — the one worker.v1.ExecutionReceipt builder, shared by the two JVM workers
// (statement half) and the query service (plan half).
include(":shared:libs:kotlin:execution-receipt")
include(":shared:libs:kotlin:capabilities-client")
include(":shared:libs:kotlin:component-testkit")
include(":shared:libs:kotlin:integration-harness")

// Spine services
include(":services:veles")
include(":services:query")
include(":services:translate")
include(":services:validate")
include(":services:dispatch")
include(":services:lex-matcher")
include(":services:llm-gateway")
// RG-P5 — the deterministic resolver core (workstream E). ZERO LLM by arch test.
include(":services:resolver")
// RG-P3 grounding services (workstream D) — moved from ai-platform, J-v2 renamed.
include(":services:chrono")
include(":services:money")
include(":services:geo")
// CH — Charon (Arrow data mover, strangler ④): the one open Charon, unified from the
// kantheon/platform siblings (arc CH; base = platform PL-P3 copy). transfer.v1 → :shared:proto.
include(":services:charon")
include(":services:grounding-mcp")

// Engine workers (JVM; the Polars worker is Python — out of the Gradle build)
include(":workers:worker-postgres")
include(":workers:worker-mssql")

// MCP tools
include(":tools:meta-mcp")
include(":tools:query-mcp")
include(":tools:lex-matcher-mcp")
include(":tools:nlp-mcp")

// Infra (RO-22: health + backstage ride the server repo)
include(":infra:identity")
include(":infra:health")
