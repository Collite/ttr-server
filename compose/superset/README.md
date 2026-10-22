<!-- SPDX-License-Identifier: Apache-2.0 -->
# Superset over a Tatrman Server: the compose convenience

A local Superset 6.1.0 that reads through a Tatrman Server's Arrow Flight SQL edge, for trying the pairing out. Point
it at a Server's Flight edge; every chart then reads through the governed query path, as the viewer, with their roles
and row rules applied.

**This is not a production topology.** It runs one Superset container with no workers, no cache and no alerts, keeps
its metadata in a local Postgres, and serves plain HTTP on localhost. [docs/superset.md](../../docs/superset.md) covers
the pairing itself, and what a real deployment needs.

## What you need

- **A Server whose Flight edge you can reach.** The example databases expect it on the host at `7308`, through a
  port-forward: `kubectl -n <namespace> port-forward deploy/query 7308:7308`. For an edge behind a gateway, change
  the URIs in [`bundle/databases/`](bundle/databases/) to `tatrman://flight.<host>/<world>` (TLS on 443).
- **The Keycloak realm that edge trusts** (the Server's `QUERY_FLIGHT_AUTH_ISSUER`), at a URL that both your browser
  and the container reach. Keycloak stamps a token with the URL it was asked at, and the edge refuses any other
  issuer.
- **A confidential client** in that realm for Superset, with the standard flow on and two redirect URIs:
  `http://localhost:8088/oauth-authorized/keycloak` for signing in, and
  `http://localhost:8088/api/v1/database/oauth2/` for reading. A second client may do the reading instead
  (`TATRMAN_CLIENT_ID`). Either way, if the edge sets `QUERY_FLIGHT_AUTH_AUDIENCE`, the reading client's tokens must
  carry that audience (an audience mapper).

## Run it

```bash
cp .env.example .env              # fill in the secret key, the issuer and the client
docker compose up -d --build
open http://localhost:8088        # "Sign in with keycloak"
```

The `init` service builds the metadata schema and Superset's roles, then imports the two example databases,
`hartland_us` and `hartland_cz`: one per world, impersonation on, DML off. The first time you open one, in SQL Lab or
a dataset, Superset sends you to Keycloak to let it read on your behalf. From then on it keeps your token and
refreshes it.

Users who sign in become `Gamma`. Members of the group named in `SUPERSET_ADMIN_GROUP` become `Admin`; that needs a
`groups` mapper on the client, so userinfo carries the user's groups.

`docker compose down -v` stops the stack and discards the metadata.

## An unreleased ttr-sqlalchemy

The image builds on the operated one, [`ghcr.io/collite/superset`](../../images/superset/README.md), with its pinned
`ttr-sqlalchemy`. To try a build of your own, drop its wheel into [`wheels/`](wheels/): it replaces the pinned one.

```bash
uv build --wheel ../../shared/libs/python/ttr-sqlalchemy -o wheels
docker compose build
```

Another release of the image: `docker compose build --build-arg SUPERSET_IMAGE=ghcr.io/collite/superset:<tag>`.
