<!-- SPDX-License-Identifier: Apache-2.0 -->
# `ghcr.io/collite/superset`

The official [Apache Superset](https://superset.apache.org/) image plus what an operated deployment needs and the
image does not ship: `psycopg2-binary` (the metadata database), `authlib` (Keycloak sign-in and per-database
OAuth2) and [`ttr-sqlalchemy`](../../shared/libs/python/ttr-sqlalchemy/README.md) (the `tatrman://` dialect and
its Superset engine spec). No configuration is baked in: the deployment mounts its own `superset_config.py`. For a
self-contained local pairing, use [`compose/superset/`](../../compose/superset/README.md) instead.

What is pinned, in the [Dockerfile](Dockerfile):
- the base, by digest (`apache/superset:6.1.0@sha256:…`), since a Docker Hub tag can move;
- the three packages added, by version;
- everything those packages pull in, held to Superset's own pins (the base's `/app/requirements/base.txt`, used as a
  constraint). A rebuild resolves the same set: the build prints `cryptography`, `pyarrow`, `sqlalchemy` and
  `flask-appbuilder` as it ends.

The build fails if the engine spec does not load inside that Superset. CI builds the image (without pushing) on any
change under `images/superset/`.

## Tags

`<Superset version>-ttr<N>` — `6.1.0-ttr1` is Superset 6.1.0 with the first set of pins. A new pin (a newer
`ttr-sqlalchemy`, a driver fix) is the next `N`; a new Superset restarts at `-ttr1`. A pushed tag is never rebuilt.

## Release

The image is tag-only (not in `just publish`, which mints the repo's own version numbers). From an up-to-date
`master`:

```bash
git tag superset/v6.1.0-ttr1 && git push origin superset/v6.1.0-ttr1
```

`release-image.yml` builds `images/superset` and pushes `ghcr.io/collite/superset:6.1.0-ttr1`. It refuses a version
that is not `<x.y.z>-ttr<N>`, on a tag or a dispatch.

**The first push of a new package** creates it unlinked and private, and a cluster's pull credential (an org member,
not the package's owner) gets `403` on it. In the package's settings on GitHub:
1. connect it to `Collite/ttr-server`;
2. set its visibility to public, as for the other images.

Hartland met this on 2026-10-22 with `6.1.0-ttr1`. Later tags of the same package keep these settings.

Local build: `docker build -t superset-ttr:local images/superset`.

## Limits

- **amd64 only.** The release runner is amd64 and the dockerfile lane runs a plain `docker build`. The base is
  multi-arch, so if an arm64 machine (an Apple-silicon Mac) needs the image, use the Kotlin lanes' multi-arch
  `buildx` step for this lane too.
- **Docker Hub, pulled directly.** `apache/superset` is not a Docker *official* image, so `mirror-base-images.yml`
  does not copy it. One pull per release tag (and per CI build of a change here); on `TOOMANYREQUESTS`, rerun the
  job. If the rate limit starts to bite, add `apache/superset` to that workflow's list and point `FROM` at the
  mirror, keeping the digest.
