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
| `n8n-nodes-tatrman/v<x.y.z>` | `@collite/n8n-nodes-tatrman` + the derived n8n image `n8n-tatrman:<n8n>-<x.y.z>` | GitHub Packages (npm) + `ghcr.io/collite/n8n-tatrman` | `n8n-nodes.yml` | every tag; the version must equal `package.json` |
| *(manual dispatch only)* | the n8n base mirror `n8n:<n8n>` | `ghcr.io/collite/n8n` | `n8n-base-mirror.yml` | the requested version must equal the Dockerfile pin |

### The `server-libs` set

One `server-libs/v<x.y.z>` tag publishes all sixteen `org.tatrman:*` libraries at the
tag's version. They are listed in `publishableLibs` and `pomMeta` in the root
`build.gradle.kts`; `publish.yml`'s header carries the same list.

| Artifact | What it is |
|---|---|
| `server-proto` | the gRPC/protobuf wire contracts (`:shared:proto`) |
| `otel-config` · `logging-config` · `ktor-configurator` | the services' shared bootstrap |
| `db-common` · `data-formatter` | JDBC helpers and result formatting |
| `lex-matcher-core` · `text` · `fuzzy-common` | the fuzzy engine, the S-2 text fold, fuzzy types |
| `whois-common` · `keycloak-auth` | identity and bearer auth |
| `meta-client` · `llm-client` | clients for `meta.v1` and `llm.v1` |
| `transfer-core` | the Charon transfer seam (`transfer.v1`) |
| `grounding-core` · `grounding-lexicon` | the grounding kernel: plan.v1 recipe scaffolding and the `ground:<kind>` lexicon slice (OL W3·S1, OL-C27) |

The grounding kernel joined the set so the time service in `ttr-platform` can build
on published artefacts rather than `project()` edges (OL-C28). CI's `build` job
checks both from a consumer's side on every PR: it publishes them to Maven Local,
and [`scripts/verify-grounding-kernel`](./scripts/verify-grounding-kernel/) compiles
against them as artefacts.

## `n8n-nodes-tatrman/v*` — the n8n nodes

`connectors/n8n-nodes-tatrman` moved here from `kantheon` in OL W6·S1 (OL-C37); the package name,
the image name and the version line did not change, and versions up to `0.1.0` were published from
`kantheon`. The tag publishes two artefacts from one checked tarball: the npm package
`@collite/n8n-nodes-tatrman` on GitHub Packages and `ghcr.io/collite/n8n-tatrman:<n8n>-<x.y.z>`,
where `<n8n>` is the base pinned in the folder's `Dockerfile`. npmjs is not a lane until a public
release is called; the tag carries no `-RELEASE` marker.

- **Cutting a release:** bump `version` in the folder's `package.json` in a PR; after the merge,
  from a clean current master, `just publish n8n-nodes-tatrman set <x.y.z>`. The workflow refuses
  a tag that differs from the package version before any registry write.
- **Access:** both writes use the job's `GITHUB_TOKEN` (`packages: write`); the PR and release jobs
  pull `ghcr.io/collite/n8n` with it. The three packages were created by `kantheon`'s workflows, so
  each needs `Collite/ttr-server` in its *Manage Actions access* list: `n8n` (Read; Write for
  `n8n-base-mirror.yml`), `n8n-tatrman` (Write), npm `n8n-nodes-tatrman` (Write).
- **Recovery:** if the image was pushed but npm publication failed, `gh workflow run n8n-nodes.yml
  --repo Collite/ttr-server --ref master -f release_tag=n8n-nodes-tatrman/v<x.y.z>` republishes the
  npm package only. Never move a released tag.
- **A new n8n base:** dispatch `n8n-base-mirror.yml` with the new version on a ref whose
  `Dockerfile` pins it, then release.

### Released from this repository

| Version | npm package | Image | Digest |
|---|---|---|---|
| 0.1.1 (tag `n8n-nodes-tatrman/v0.1.1` on `0af5fba8`, 2026-11-18, run [44356569760](https://github.com/Collite/ttr-server/actions/runs/44356569760)) | `@collite/n8n-nodes-tatrman@0.1.1` | `ghcr.io/collite/n8n-tatrman:2.44.6-0.1.1` | `sha256:632b5f44ce45d9948b2ad4f6cf25dc71d9f43d932937cb3823a08935f71824e7` |

## `golem-py/v*` — the golem-py CLI image

The first internal cut is `golem-py/v0.11.0` (OL W1c·S3b). After independent source review
and merge, tag the reviewed merge SHA and push that tag to run `release-image.yml`. Verify
the workflow's tag and checkout SHA, and compare the push digest with the GHCR digest for
`ghcr.io/collite/golem-py:0.11.0`. Use the verified digest for the
[certification Job](services/golem-py/README.md#ol-w1c-certification).
The [certification PR](https://github.com/Collite/ttr-server/pull/463) records the source SHA,
tag, release CI run and digest together with the live verification.

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

## OL W1a·S1 internal release — the `scan` capability, 2026-11-16

The three immutable tags point to merge `91543b66d3d15236ecdcfdd61218530de7fd2e07`
of [#419](https://github.com/Collite/ttr-server/pull/419) (review-315, repair re-review
PASS; PR CI [43888468009](https://github.com/Collite/ttr-server/actions/runs/43888468009)
green on head `864fdb9b`). The next free versions were checked against the remote tags and GHCR before
the cut: chrono and the resolver had reached 0.11.37 (the resolver's 0.11.33–0.11.37 exist on GHCR only,
from dispatch builds), and grounding-mcp 0.11.31.

| Tag | Published artifact | Release CI |
|---|---|---|
| `chrono/v0.11.38` | `ghcr.io/collite/chrono:0.11.38` | [43899729726](https://github.com/Collite/ttr-server/actions/runs/43899729726), green on `91543b66` |
| `resolver/v0.11.38` | `ghcr.io/collite/resolver:0.11.38` | [43899735548](https://github.com/Collite/ttr-server/actions/runs/43899735548), green on `91543b66` |
| `grounding-mcp/v0.11.32` | `ghcr.io/collite/grounding-mcp:0.11.32` | [43899741417](https://github.com/Collite/ttr-server/actions/runs/43899741417), green on `91543b66` |

Manifest-list digests (each lists `linux/amd64` and `linux/arm64`):

| Image | Digest |
|---|---|
| `chrono:0.11.38` | `sha256:36cfde368261a81995f2c5e90575e47d2a10d10a492be6cd162af9fcc3f3e848` |
| `resolver:0.11.38` | `sha256:1298ee2742582a7e1cc974bd23d416ab9ba76bd546100a9ed4cea2eeb4b1b481` |
| `grounding-mcp:0.11.32` | `sha256:e97a65b6334ab3b9b17d1312701444fa9a15dc61506b6cf21d4b1b980cc7a599` |

chrono's `GetStatus` declares `capabilities["scan"] = "true"` (OL-C1). The resolver calls
`Scan` only when its grounder declares that capability, and grounding-mcp's `scan_time`
answers `not_available` when the grounder does not (OL-C2, OL-C4). Roll order (OL-C5): roll chrono
before or together with the resolver and grounding-mcp. A reader that rolls first sees no `scan`
key in the older chrono's status, so it stops calling Scan until chrono rolls; `Ground` is
unaffected. No library cut, no pin change and no cluster roll in this stage.

### Released-image check

The chrono image was pulled by digest on a developer Mac (`linux/arm64`) and started with
`GRPC_REFLECTION_ENABLED=true` and no metadata service. Then
`grpcurl -plaintext -d '{}' localhost:17220 org.tatrman.grounding.v1.GroundingService/GetStatus`
answered:

```json
{
  "service": "chrono",
  "capabilities": {
    "lexicon_slice": "",
    "lexicon_slice_terms": "0",
    "llm_fallback": "false",
    "metadata": "down",
    "scan": "true"
  }
}
```

The probe container was removed afterwards. No deployed workload changed.

## OL W3·S1 internal release — the grounding kernel, `server-libs` 0.12.6, 2026-11-17

The tag `server-libs/v0.12.6` points to merge `906c9ef45c6ff55b32e3731aa1ffed4ade866eff`
of [#426](https://github.com/Collite/ttr-server/pull/426) (review-326 PASS; PR CI
[43920455982](https://github.com/Collite/ttr-server/actions/runs/43920455982) green on head `13648419`).
The 0.12 line is deliberate (D-42): `server-libs/v0.12.5` already exists and `ttr-platform`'s engine line
pins `tatrman-server-engine = 0.12.5` (OL-C44), so a 0.11.x cut would be numbered below the version its
consumer reads. 0.12.6 was the next free patch, checked against the remote tags and GitHub Packages before
the cut. Bare tag: GitHub Packages only, no Maven Central.

| Tag | Published artifacts | Release CI |
|---|---|---|
| `server-libs/v0.12.6` | the 16-library set at `0.12.6`, GitHub Packages `Collite/ttr-server`, including the first `org.tatrman:grounding-core:0.12.6` and `org.tatrman:grounding-lexicon:0.12.6` | [44023204274](https://github.com/Collite/ttr-server/actions/runs/44023204274), `gate` + `publish` green on `906c9ef4` |

The grounding kernel's POMs depend on `server-proto` and `text` at 0.12.6, and `grounding-lexicon` also on
ttr-core's `ttr-lexicon` / `ttr-snapshot` 0.22.3. No code, API or pin changed, and no image was released.

### Consumer-resolution check

[`scripts/verify-grounding-kernel`](./scripts/verify-grounding-kernel/) on the `packages` lane, where the
set's modules are exclusive to GitHub Packages `Collite/ttr-server` and Maven Local is not asked:

```bash
./gradlew -p scripts/verify-grounding-kernel -PkernelLane=packages -PkernelVersion=0.12.6 \
  dependencies --configuration compileClasspath --refresh-dependencies
```

```
+--- org.tatrman:grounding-core:0.12.6
|    +--- org.tatrman:server-proto:0.12.6
|    |    +--- org.tatrman:ttr-plan-proto:0.14.1
|    +--- org.tatrman:text:0.12.6
\--- org.tatrman:grounding-lexicon:0.12.6
     +--- org.tatrman:ttr-lexicon:0.22.3
     +--- org.tatrman:text:0.12.6 (*)
```

(`org.tatrman` lines only.) `clean build` on the same lane compiled both consumer classes.

## OL W1b·S1 internal release — the `ttrnlp.stages` group, 2026-11-17

`nlp/v0.11.6` points to merge `d6e11b8372c7eb4ee1c1751967b85554ede10ce3` of
[#430](https://github.com/Collite/ttr-server/pull/430) (review-335: one repair round, then PASS; PR CI
[44069877169](https://github.com/Collite/ttr-server/actions/runs/44069877169) green on head `97fc2d39`).
The next free version was checked against the remote `nlp/v*` tags and GHCR before the cut: both ended at 0.11.5.

| Tag | Published artifact | Release CI |
|---|---|---|
| `nlp/v0.11.6` | `ghcr.io/collite/nlp:0.11.6` | [44084166582](https://github.com/Collite/ttr-server/actions/runs/44084166582), green on `d6e11b83` |

Manifest-list digest: `nlp:0.11.6` → `sha256:3c4e15f1011f99bf06fd7cd62c8eed148bc06b2f36c3268d2b58efee3a22a0e1`
(`linux/amd64`, plus its attestation manifest).

The rule engine now reaches the loader and the runner through the `ttrnlp.stages` entry-point group (OL-C6…C10);
pipelines take `stages:`, and `rules:` stays as an alias. Pipeline config, traces and `nlp.v1` are unchanged.
No library cut, no pin change and no cluster roll in this stage.

### Released-image check

The image was pulled by digest and run on a developer Mac (`--platform linux/amd64`) with bp-dsk's nlp
values: `NLP_LANE=option`; `NLP_PIPELINES` with the gazetteer-only `hartland-mini-mentions` pipeline;
`/etc/nlp/packs` mounted with only the `nlp-packs` ConfigMap's README; and `/etc/nlp/lists` mounted with the four
`estate/nlp-lists/*.list.yaml`. Boot log: `packs loaded: state_id=03570ff8a2a062d6 packs=0 lists=4 pipelines=1`.
`grpcurl -plaintext -emit-defaults … org.tatrman.nlp.v1.NlpService/GetStatus` answered `ready: true`, plus:

```json
{"stateId": "03570ff8a2a062d6", "packsLoaded": 0, "listsLoaded": 4, "diagnostics": []}
```

`installed_stages()` in the container returned `['rules']`. The container was removed afterwards.
