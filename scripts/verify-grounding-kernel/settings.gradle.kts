// SPDX-License-Identifier: Apache-2.0
// OL W3·S1 T3 (OL-C27) — standalone, NOT part of the tatrman-server build: a throwaway consumer of
// the published grounding kernel. It resolves `org.tatrman:grounding-core` and `grounding-lexicon`
// as ARTEFACTS (never `project()` edges) and compiles one class against each — the way the time
// service in ttr-platform consumes them (OL-C28).
//
// Two lanes, chosen with `-PkernelLane=`:
//   local    (default) — the set's own coordinates from Maven Local, after `publishToMavenLocal`
//                        (CI's `build` job; version `0.0.1-LOCAL` exists in no registry)
//   packages           — the set's own coordinates from GitHub Packages `Collite/ttr-server` ONLY,
//                        no Maven Local at all (the release proof, `-PkernelVersion=<released>`)
// The ttr-core toolchain the kernel depends on (ttr-plan-proto, ttr-lexicon, ttr-snapshot) comes
// from `Collite/ttr-core` in both lanes, and nothing `org.tatrman` from Maven Central — the same
// rule as the repo's own settings.
rootProject.name = "verify-grounding-kernel"

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

val lane = providers.gradleProperty("kernelLane").orElse("local").get()
require(lane == "local" || lane == "packages") { "kernelLane must be local or packages, got '$lane'" }

// The artefacts this repo publishes (publishableLibs + pomMeta in the root build.gradle.kts) that
// the kernel's closure reaches; everything else `org.tatrman` here is ttr-core's.
val serverSet = listOf("grounding-core", "grounding-lexicon", "text", "server-proto")

fun RepositoryHandler.githubPackages(repo: String) =
    maven {
        name = repo
        url = uri("https://maven.pkg.github.com/Collite/$repo")
        credentials {
            username = providers.gradleProperty("gpr.user").orNull ?: System.getenv("GITHUB_ACTOR")
            password = providers.gradleProperty("gpr.token").orNull ?: System.getenv("GITHUB_TOKEN")
        }
    }

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral {
            content { excludeGroup("org.tatrman") }
        }
        // The set's modules are EXCLUSIVE to the lane's repository: no other repository is asked
        // for them, so a hit proves the lane serves them.
        exclusiveContent {
            forRepository { if (lane == "packages") githubPackages("ttr-server") else mavenLocal() }
            filter { serverSet.forEach { includeModule("org.tatrman", it) } }
        }
        githubPackages("ttr-core").content { includeGroup("org.tatrman") }
    }
    versionCatalogs {
        // The repo's own catalog: the consumer compiles with the Kotlin the libraries were built with.
        create("libs") { from(files("../../gradle/libs.versions.toml")) }
    }
}
