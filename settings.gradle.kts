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
        mavenCentral()
        // TEMPORARY (SV-P0/P1 interim): consume the tatrman `org.tatrman:*`
        // artifacts (ttr-metadata, ttr-plan-proto, …) at `0.0.1-LOCAL` from
        // Maven Local while the fork settles. Removed once the SV-P1 publish
        // gates land the 0.9.x line on the public registry (plan §SV-P1).
        mavenLocal()
        // TTR toolchain (org.tatrman:ttr-{parser,writer,semantics,metadata,…}),
        // published by the `tatrman` repo to GitHub Packages under `Collite/ttr-core`.
        // These are NOT on Maven Central yet; the same per-user `gpr.*` PAT that
        // kantheon uses authenticates here. `includeGroup("org.tatrman")` keeps the
        // repo scoped to that group only.
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
