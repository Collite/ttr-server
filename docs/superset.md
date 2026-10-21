<!-- SPDX-License-Identifier: Apache-2.0 -->
# Superset & BI

Tatrman's stance on BI is **bring your own** (FO-6). The open Server publishes the model through its governed query
path, and any SQL-speaking BI tool reads through it. Superset is the reference pairing. It reads through the query
service's [Flight SQL edge](jdbc.md) with [`ttr-sqlalchemy`](../shared/libs/python/ttr-sqlalchemy/README.md), which
carries the `tatrman://` SQLAlchemy dialect and Superset's engine spec for it.

| | Open tier (Apache-2.0) | Operate tier (Platform) |
| --- | --- | --- |
| Superset | **self-serve**: you run it, with `pip install ttr-sqlalchemy` | **operated add-on**: run for you on the estate |
| What you get | the driver, the dialect, the engine spec, a compose convenience, this page | the same, deployed and kept up |

---

## Open tier: self-serve

### What reading through Tatrman means

Every chart, dataset and SQL Lab statement goes through translate → validate → dispatch, like any other caller's. The
validator injects the viewer's row rules into the plan, so a chart cannot show rows its viewer is not entitled to,
however it was built. That only holds if the edge knows who the viewer is. So **a Tatrman database always reads as
its viewer**, with the viewer's own Keycloak token, and never with a shared login.

Each statement leaves a [receipt](jdbc.md#receipts) on the Server, recording who ran what and on which connection.

### Install

Into Superset's own Python environment:

```bash
pip install ttr-sqlalchemy
```

This is tested on Superset 6.1.0, which ships SQLAlchemy 1.4.54. The engine spec registers itself through an entry
point, so there is nothing to configure for Superset to find it. The official `apache/superset:6.1.0` image also
needs `psycopg2-binary` (for a Postgres metadata database) and `authlib` (to sign in with Keycloak).
[`compose/superset/Dockerfile`](../compose/superset/Dockerfile) is that image.

### Configure

The engine spec needs three things from `superset_config.py` and the database settings.

**1. A per-database OAuth2 client, `DATABASE_OAUTH2_CLIENTS["tatrman"]`.** This is how each viewer's token reaches
the edge.

```python
DATABASE_OAUTH2_CLIENTS = {
    "tatrman": {
        "id": "superset-db",                                    # a confidential client, standard flow on
        "secret": "…",
        "issuer": "https://keycloak.example.com/realms/hartland",  # the realm the edge trusts
        # "scope": "openid",                                    # the default
        # or, for a provider that is not Keycloak: "authorization_request_uri" and "token_request_uri"
    }
}
```

- The client's redirect URI is `https://<superset host>/api/v1/database/oauth2/`.
- Tokens must come from the realm the edge trusts (its `QUERY_FLIGHT_AUTH_ISSUER`), asked for at that same URL.
  Keycloak stamps a token with the URL it was asked at.
- If the edge sets `QUERY_FLIGHT_AUTH_AUDIENCE`, the client's tokens must carry that audience (an audience mapper).

**2. "Impersonate logged in user" on, for every Tatrman database** (Advanced → Security). A database without it
refuses to connect, and so does one with no OAuth2 client to get a token from. Both refusals say why.
`BI_REQUIRE_IMPERSONATION = False` lifts them, for a local trial with a fixed `token` or a user in the URL; it is not
for a shared Superset.

**3. One database per world:**

```
tatrman://flight.<host>/hartland_us            behind the gateway (TLS on 443)
tatrman://localhost:7308/hartland_us?tls=false through a port-forward
```

Leave DML and file uploads off; the edge is read-only. Viewers should sign in to Superset with the same realm
(`AUTH_OAUTH`, FAB's `keycloak` provider). Then the consent below is one click, and the Superset user is the person
reading.

### What viewers see

- **The first read of a database asks for consent.** A Keycloak tab asks to let Superset read on the viewer's
  behalf. From then on Superset keeps the token and refreshes it. When the Keycloak session behind it ends, or the
  edge stops accepting the token, the viewer is asked to sign in again.
- **Schemas:** `er.entity` holds the model's entities, `obj.query` its saved logical queries (read like views), and
  `db.dbo` the bound physical tables. Columns are typed from the model, and dates and timestamps are temporal.
- **Time grains:** day, month, quarter and year. There is no week grain yet: the translator cannot yet truncate a
  date to its week.
- **Answers the row cap cut are refused, not shown.** The door caps every answer at its viewer's ceiling: the
  deployment's `VALIDATE_MAX_TOP_N`, which an operator can raise per role (`VALIDATE_MAX_TOP_N_BY_ROLE`, e.g.
  `kantheon-bi-reader:10000`). A chart's own row limit lowers it. A chart whose answer reaches the ceiling gets a
  warning that names the ceiling, because a chart over part of the rows would mislead. SQL Lab asks for one row
  more than its limit, to tell whether there are more, so keep SQL Lab's limit below the ceiling. Superset holds a
  whole answer in memory, so the ceiling also bounds that.
- **Refusals** read as the edge's code and sentence: `translator_rejected: Object 'warehouse' not found within 'er'
  …`.
- **Embedded dashboards** (Superset's guest tokens) cannot read a Tatrman database: a guest has no token of their
  own, and the engine spec does not read without one.

### The compose convenience

[`compose/superset/`](../compose/superset/README.md) runs Superset 6.1.0 locally against a Server's Flight edge, with
two example databases (`hartland_us`, `hartland_cz`) and Keycloak sign-in. It is for trying the pairing out. It is
not a production topology: one container, no workers, no cache, HTTP on localhost.

---

## Operate tier: the operated Superset add-on

> **This is an operate-tier managed add-on, not an open-tier promise.** On an open, self-hosted Server you run
> Superset yourself, as above.

On a Platform estate, Superset runs as a managed add-on. It uses the same image recipe and engine spec, the estate's
Keycloak for sign-in and per-database OAuth2, and the estate's Flight route. The estate's GitOps configuration
deploys it, and its runbook (provisioning, upgrades, backups) lives with that configuration, not in this repo.

---

*See also:* **[jdbc.md](jdbc.md)** (the Flight SQL edge and its other clients) · **[operations.md](operations.md)**
(operating the open Server).
