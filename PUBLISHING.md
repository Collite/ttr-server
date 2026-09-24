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
consumer — this repo, `kantheon`, `tatrman-platform`, `ai-platform` — resolves
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
