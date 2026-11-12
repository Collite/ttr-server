<!-- SPDX-License-Identifier: Apache-2.0 -->
# Publishing — tatrman-server release lanes

Every artifact this repo ships is **tag-driven**. Pushing a tag is the release
action; no workflow publishes anything from a branch.

## Lanes

| Tag | Artifact | Registry | Workflow | Gate |
|---|---|---|---|---|
| `server-libs/v<x.y.z>` | the `org.tatrman:*` Kotlin library set | GitHub Packages | `publish.yml` | every tag |
| `server-libs/v<x.y.z>-RELEASE` | ↑ same set | **+ Maven Central** | `publish.yml` | `-RELEASE` marker |
| `python-nlp/v<x.y.z>` | `ttr-nlp` wheel | *(build only — nothing published)* | `publish-python.yml` | every tag |
| `python-nlp/v<x.y.z>-RELEASE` | `ttr-nlp` wheel | **PyPI** | `publish-python.yml` | `-RELEASE` marker + `pypi` env |
| `<module>/v<x.y.z>` | one container image | `ghcr.io/collite/<module>` | `release-image.yml` | every tag |
| *(manual dispatch only)* | the four `nlp-<backend>` images | `ghcr.io/collite/nlp-*` | `release-nlp-backends.yml` | human dispatch + FI-4 legal gate |
| `superset/v<superset>-ttr<N>` | the operated Superset image (official + `ttr-sqlalchemy`) | `ghcr.io/collite/superset` | `release-image.yml` | every tag; the version must be `<x.y.z>-ttr<N>` |
| `python-sqlalchemy/v<x.y.z>[-RELEASE]` | `ttr-sqlalchemy` wheel | PyPI on `-RELEASE` | `publish-python.yml` | as `python-nlp` |

## The `-RELEASE` marker

A public registry has no internal staging equivalent and, for Maven Central, a
monthly quota that fast-iteration publishing would exhaust. So reaching a public
registry requires an **explicit** `-RELEASE` marker on the tag rather than being
inferred from the absence of a prerelease suffix (the 2026-07-16 polarity flip,
synced across the four repos).

The marker is a **tag-level** signal only: it is stripped before the version
reaches any registry, so `python-nlp/v0.1.0-RELEASE` publishes as plain `0.1.0`.

Cutting a bare tag is a genuinely useful thing to do — it runs the whole build
and all its content checks without spending a public version number.

### Which lane a consumer reads (2026-09-24)

Maven Central is an **output** of this ecosystem, never an input. Every internal
consumer — this repo, `kantheon`, `tatrman-platform`, a downstream platform — resolves
`org.tatrman:*` from **GitHub Packages alone**: each one's `settings.gradle.kts`
carries `mavenCentral { content { excludeGroup("org.tatrman") } }` beside the
`Collite/ttr-{core,server}` package repos, so the two lanes cannot race and no
internal build can quietly come to depend on a public release.

That exclusion is what makes the bare-tag cadence work. A `-RELEASE` is for
people outside the ecosystem; nothing inside it waits on one, and a `server-libs`
cut is consumable by kantheon minutes after `publish.yml` finishes rather than
after a Central sync and a quota decision.

The one deliberate exception is
[`scripts/verify-public-resolution`](./scripts/verify-public-resolution/) — a
standalone build whose only repository is `mavenCentral()`. Its entire job is to
prove the *public* lane really is anonymously resolvable, so it must not be
"fixed" to use GitHub Packages.

## `python-nlp/v*` — the `ttr-nlp` wheel

Package `shared/libs/python/ttr-nlp` → PyPI project [`ttr-nlp`], via **PyPI
Trusted Publishing** (OIDC). There is no API token anywhere in this repo:
PyPI mints a short-lived one per run for the `publish` job.

**Version comes from the tag.** The repo tree permanently keeps
`version = "0.0.0"` in `pyproject.toml`; the workflow injects the tag's version
at build time and verifies the injection took. Never hand-edit that version to
make a release — the tag is the only place a version is decided.

### ⚑ The name coupling

The trusted-publisher registration on PyPI names three things, and **all three
must keep matching** or the OIDC handshake fails with an opaque 403 at upload:

| Registration field | Value |
|---|---|
| Repository | `Collite/ttr-server` |
| Workflow filename | `publish-python.yml` |
| Environment | `pypi` |

Renaming the workflow file or the GitHub environment silently breaks publishing.
Re-register on PyPI if either has to change.

Setting both up is a one-time account-level action that only the PyPI project
owner can perform (tracked as **P-BP T1/T2**). Until it is done, `-RELEASE` tags
will build and then fail at the upload step; bare tags are unaffected.

### Cutting a release

```bash
git tag python-nlp/v0.1.0-RELEASE && git push origin python-nlp/v0.1.0-RELEASE
```

There is no `just publish` lane for the wheel yet — `just publish` currently
covers the `server-libs` bundle and the container-image modules only. Adding the
Python lane belongs with the first real publish (NLS-P4.T7).

[`ttr-nlp`]: https://pypi.org/p/ttr-nlp

## TS-P0 internal release — 2026-11-08

Source: master `8d506b7208fbcaf5a0370641eab9fb67a2e99fd7` (S0.3,
[ttr-server#380](https://github.com/Collite/ttr-server/pull/380), review-230 re-review 2 PASS).
Versions are selected by the tags below; `gradle.properties` keeps its local-development default.
The library cut advances the 0.11 line from `server-libs/v0.11.35` to `server-libs/v0.11.36`.

`server-libs/v0.12.1`–`v0.12.4` (MW-P1, 2026-11-01) are numbered higher but were
cut from earlier source (`v0.12.4` at `ae1ca313`, an ancestor of `8d506b72`) and have
no `Scan`. Version 0.11.36 contains everything in 0.12.4 plus `Scan`, but Gradle's
highest-version resolution selects 0.12.4 when the dependency graph also requests
that version, losing `Scan`. Per ✅D-MW-71, the next shared `server-libs` cut is
`v0.12.5+` from master, carrying `Scan` and restoring version-number order; MW owns
that cut after TC-ap-P2. A consumer whose graph may contain 0.12.x waits for that
cut rather than forcing 0.11.36; a consumer with no 0.12.x in its graph may pin
0.11.36 meanwhile. TS makes no additional library cut (✅R239-A).

The images replace chrono and grounding-mcp 0.11.30, built by the
[`server-libs/v0.11.30` run 40215659266](https://github.com/Collite/ttr-server/actions/runs/40215659266)
from `d0f56fc5`; 0.11.31 is the next free version on GHCR, despite the gaps in the
per-service Git tags.

| Tag | Published artifact | Release CI |
|---|---|---|
| `server-libs/v0.11.36` | `org.tatrman:server-proto:0.11.36` and the standard server library set, GitHub Packages | [42505408371](https://github.com/Collite/ttr-server/actions/runs/42505408371) |
| `chrono/v0.11.31` | `ghcr.io/collite/chrono:0.11.31` | [42505408429](https://github.com/Collite/ttr-server/actions/runs/42505408429) |
| `grounding-mcp/v0.11.31` | `ghcr.io/collite/grounding-mcp:0.11.31` | [42505408428](https://github.com/Collite/ttr-server/actions/runs/42505408428) |

Both images contain `linux/amd64` and `linux/arm64` manifests:

| Image | Manifest-list digest |
|---|---|
| `chrono:0.11.31` | `sha256:fa6d431c3f28fedf5f1f620b59e46e521ef4796300b3eacfd1f711e76ad029f6` |
| `grounding-mcp:0.11.31` | `sha256:ac2b8907fe4367f1273a3c126aafd926db2d656f14a38b2465c63a7bf5f4d1a9` |

This cut ships `GroundingService.Scan`, its request/response/span messages, chrono's
reviewed time recognition and span scanner, and grounding-mcp's `scan_time` tool.
`Ground` and `GetStatus` keep their existing wire shape. Consumer adoption and cluster
rolls belong to later TS stages. The historical consumer source for this cut is
`chrono/v0.11.31` at the source SHA above, superseded by `chrono/v0.11.32`
([release evidence below](#ts-s06-internal-year-range-release--chrono-01132-2026-11-12)).

### Clean-resolution check

Verified on `bp-dsk` in a disposable pod with an empty Gradle/Maven cache and a
fresh Docker daemon (no preloaded images):

- Gradle 9.3.0 / JDK 21, standalone consumer depending on
  `org.tatrman:server-proto:0.11.36`: `gradle --no-daemon --max-workers=2 run resolvedArtifacts`
  passed; 22 dependency artifacts resolved from GitHub Packages plus Maven Central
  (with `org.tatrman` excluded from Central). The Java probe compiled and ran
  `ScanRequest` / `ScanResponse` / `TimeSpan` construction and checked
  `GroundingServiceGrpc.getScanMethod()` against `org.tatrman.grounding.v1.GroundingService/Scan`.
- `docker pull ghcr.io/collite/chrono:0.11.31` and
  `docker pull ghcr.io/collite/grounding-mcp:0.11.31` both passed anonymously,
  returning the manifest-list digests above. Both lists advertise amd64 and arm64;
  these pulls verified the bp-dsk host's amd64 images.

The resolved `server-proto` JAR SHA-256 is
`6cdb13c3f300e3a19a8fac0eea340e33454e1cfc8620779f953af5e358181652`.
It brings `org.tatrman:ttr-plan-proto:0.14.1`, inherited from master. Consumer pin
adoption belongs to the later consumer stages.

## TS S0.6 internal year-range release — chrono 0.11.32, 2026-11-12

The immutable tag `chrono/v0.11.32` points to the exact reviewed S0.5 source
`cc62a65989744f6e5503c56ad47eff1e44c9ac62` (source tree identical to repair
`f40233ea`, [#389](https://github.com/Collite/ttr-server/pull/389), review-248
re-review 1 PASS), reachable from master through merge
`5e061fa4b7cb8c967411c97e1844577bf32d7e60`. The tag deliberately excludes later
unrelated implementation changes. Source CI
[43209232305](https://github.com/Collite/ttr-server/actions/runs/43209232305) is green;
bp-dsk and the independent reviewer ran chrono 822 / grounding-mcp 17 tests and
eval 23 checks, all passing. Version 0.11.32 was absent from both remote tags and
GHCR before this cut.

| Tag | Published artifact | Release CI |
|---|---|---|
| `chrono/v0.11.32` | `ghcr.io/collite/chrono:0.11.32` | [43257949667](https://github.com/Collite/ttr-server/actions/runs/43257949667), green on `cc62a659` |

Manifest-list digest:
`sha256:4bf66802cc0fb49fcb6ded5a08de60b9742dd178d4b046979350d676d338c15b`.
The list contains `linux/amd64` and `linux/arm64`; the clean pull below verified
amd64. This release ships calendar-year ranges and the review-248 S1–S6 repairs,
as described in CHANGELOG's versioned 0.11.32 section.

No `grounding-mcp/v0.11.32` companion is required:
`git diff grounding-mcp/v0.11.31 chrono/v0.11.32 -- services/grounding-mcp shared/`
is empty, including MCP tests and shared code, from the 0.11.31 build source
`8d506b72` to `cc62a659`. S0.5 changed chrono implementation, tests and fixtures;
eval tooling, tests and fixtures; conformance corpus checksums; and documentation.
The MCP image remains 0.11.31. There is no shared server-libs publication,
dependency pin change or cluster roll in S0.6; the earlier S0.4 library evidence and version-resolution
warning above still apply.

### Clean-resolution and tagged-corpus checks

On `bp-dsk`, a disposable `docker:27-dind` pod, `ttr-server/ts-s06-clean-pull`,
started with an empty `/var/lib/docker` volume and no registry credentials.
Commands used explicit `kubectl --context bp-dsk -n ttr-server exec`:

- `test -z "$(docker image ls -aq)"` and
  `test ! -e /root/.docker/config.json` passed before the pull.
- `docker pull ghcr.io/collite/chrono:0.11.32` resolved anonymously to the digest
  above; `docker image inspect` confirmed that digest and `amd64`.
- `docker manifest inspect ghcr.io/collite/chrono:0.11.32` independently confirmed
  both platform entries. `docker create` followed by
  `docker cp released-chrono:/app/classes/org/tatrman/chrono/recognize/YearRanges.class`
  confirmed the range implementation class is present without starting the service.
- A `git archive chrono/v0.11.32` of the tagged chrono and eval fixtures was
  transferred into the pod and checked with Python: Ground 138 rows, planner Scan 109 rows, eval 228
  cases. All 11 successful year-range Ground rows assert `intervalEnd: 2026-01-01`.
  Eval SHA-256 is
  `3f10d9773215b0af0cb52860c45c46a47ee2ad4d438594c705d530560d2d52b9`,
  matching the source's conformance manifest.

The disposable pod was deleted after verification. No deployed workload changed.
This verified tag supersedes 0.11.31 as the source for later consumer stages.
