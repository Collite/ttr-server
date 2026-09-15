# Tatrman Server

Tatrman Server — the open runtime of the Tatrman ecosystem: the deployable
product an organization installs to make its modeled data available for AI
consumption (metadata serving, a deterministic governed query path, and the MCP
surface that fronts it). Apache-2.0.

## The thesis

**Use the LLM for what it is good at — understanding intent — and make everything
between intent and data deterministic.** An agent binds a user's words to modeled
entities and structures a question over those entities (never over physical
tables); the server then does the rest deterministically — it translates the
entity-level query to a physical plan, injects row-level security *into the plan*,
routes it to an engine, and streams typed results back with full provenance.
Governance is a property of the architecture, not a logging feature: there is no
flag that bypasses the validator.

This is what "prepare your data for AI consumption" means here — not embeddings,
but a machine-consumable semantic model with a governed execution path underneath,
so *any* agent, from any vendor, can be given safe access through a contract
rather than through trust.

## Who it's for

Teams that hold valuable data in a warehouse and want to let AI agents ask
questions of it **without** handing the model a database connection and hoping —
data platform engineers, analytics engineers, and the security owners who have to
sign off on "an LLM can now query production."

## What's here

- **Services** — metadata serving (Veles), the governed query path (translate →
  validate → dispatch), fuzzy match, NLP, and the LLM gateway.
- **The MCP surface** — the tool contract that fronts the server for any MCP
  agent (per-user identity pass-through, provenance on every answer).
- **Published artifacts** — `org.tatrman:*` libraries on Maven Central and
  `ghcr.io/collite/*` images.

Status labels used across the docs: **live** (running at a production pilot) ·
**extracted** (implemented in the open lineage) · **planned** (designed, not
built) · **parked** (deliberately deferred).

## Operating & consuming

- **[docs/operations.md](docs/operations.md)** — operating the open Server: the read-only
  health & status page (version, model fingerprint, per-service health), the config
  reference, and the "no admin app" stance (FO-28).
- **[docs/superset.md](docs/superset.md)** — BI via the semantic layer: self-serve Superset
  pairing/embedding on the open tier, and the operated Superset add-on (operate tier).

## Quickstart

A one-command bring-up (single umbrella chart → sample estate → a governed answer
through the MCP surface, with visible provenance) and the `tatrman.org` docs site
land with **1.0**. Until then, build from source:

```bash
./gradlew build      # build + test all modules (or: just build / just test)
```

For architecture and contracts docs, see the companion
[`tatrman`](https://github.com/Collite/ttr-core) repo's `docs/features/` tree
(e.g. `docs/features/resolution/`, `docs/features/ttr-translator/`).

## License, governance & contributing

Tatrman Server is open source under the **[Apache License 2.0](LICENSE)** (see also
[NOTICE](NOTICE)). "Tatrman" and "Tatrman Server" are trademarks of Collite — see
the **[Trademark Policy](https://github.com/Collite/ttr-core/blob/master/TRADEMARKS.md)**.

- **[CONTRIBUTING.md](CONTRIBUTING.md)** — how to contribute (DCO sign-off, edges vs. core).
- **[GOVERNANCE.md](GOVERNANCE.md)** — governance (defined in the `tatrman` repo).
- **[SECURITY.md](SECURITY.md)** — report a vulnerability privately.
- **[CODE_OF_CONDUCT.md](CODE_OF_CONDUCT.md)** — Contributor Covenant 2.1.
