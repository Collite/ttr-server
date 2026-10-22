<!-- SPDX-License-Identifier: Apache-2.0 -->
# `ghcr.io/collite/superset`

The official [Apache Superset](https://superset.apache.org/) image plus what an operated deployment needs and the
image does not ship: `psycopg2-binary` (the metadata database), `authlib` (Keycloak sign-in and per-database
OAuth2) and [`ttr-sqlalchemy`](../../shared/libs/python/ttr-sqlalchemy/README.md) (the `tatrman://` dialect and
its Superset engine spec). No configuration is baked in: the deployment mounts its own `superset_config.py`. For a
self-contained local pairing, use [`compose/superset/`](../../compose/superset/README.md) instead.

Every version is pinned in the [Dockerfile](Dockerfile); the build fails if the engine spec does not load inside
that Superset.

## Tags

`<Superset version>-ttr<N>` — `6.1.0-ttr1` is Superset 6.1.0 with the first set of pins. A new pin (a newer
`ttr-sqlalchemy`, a driver fix) is the next `N`; a new Superset restarts at `-ttr1`. A pushed tag is never rebuilt.

## Release

The image is tag-only (not in `just publish`, which mints the repo's own version numbers). From an up-to-date
`master`:

```bash
git tag superset/v6.1.0-ttr1 && git push origin superset/v6.1.0-ttr1
```

`release-image.yml` builds `images/superset` and pushes `ghcr.io/collite/superset:6.1.0-ttr1`. The base comes from
Docker Hub (it is not an official image, so the GHCR base mirror does not carry it); on a `TOOMANYREQUESTS`, rerun
the job.

Local build: `docker build -t superset-ttr:local images/superset`.
