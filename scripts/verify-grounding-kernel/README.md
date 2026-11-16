# verify-grounding-kernel — the grounding kernel as a consumer sees it (OL W3·S1)

A standalone Gradle project, **not** part of the tatrman-server build. It resolves
`org.tatrman:grounding-core` and `org.tatrman:grounding-lexicon` as published
artefacts, never as `project()` edges, and compiles one class against each, which is
how the time service in `ttr-platform` consumes them (OL-C27, OL-C28).

## Lanes

| `-PkernelLane=` | Where the set's modules come from | Used by |
|---|---|---|
| `local` (default) | Maven Local only (after `publishToMavenLocal`) | CI's `build` job, on every PR |
| `packages` | GitHub Packages `Collite/ttr-server` only, never Maven Local | the release proof |

"The set's modules" means the two libraries and the members of this repo's set
that their POMs name (`text`, `server-proto`). Each is *exclusive* to the lane's
repository, so a successful resolution shows that lane served it. In both lanes, the
ttr-core toolchain they depend on (`ttr-plan-proto`, `ttr-lexicon`, `ttr-snapshot`)
comes from `Collite/ttr-core`, and no `org.tatrman` module comes from Maven Central.
Both GitHub Packages repositories need `gpr.user`/`gpr.token` or
`GITHUB_ACTOR`/`GITHUB_TOKEN`.

## Run

```bash
# local lane: what CI runs
./gradlew --no-configuration-cache :shared:proto:publishToMavenLocal \
  :shared:libs:kotlin:text:publishToMavenLocal \
  :shared:libs:kotlin:grounding-core:publishToMavenLocal \
  :shared:libs:kotlin:grounding-lexicon:publishToMavenLocal
./gradlew -p scripts/verify-grounding-kernel build

# release proof: a released server-libs version, from GitHub Packages alone
./gradlew -p scripts/verify-grounding-kernel -PkernelLane=packages -PkernelVersion=<x.y.z> \
  dependencies --configuration compileClasspath --refresh-dependencies
```
