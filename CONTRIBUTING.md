# Contributing to Tatrman Server

Thank you for your interest in Tatrman Server — the open runtime of the Tatrman
ecosystem. This document covers **how contributions land**: the legal sign-off,
where different kinds of change go, and how to build the server. By participating
you agree to abide by our [Code of Conduct](./CODE_OF_CONDUCT.md).

## Developer Certificate of Origin (DCO)

Tatrman uses the **[Developer Certificate of Origin 1.1](https://developercertificate.org/)**,
not a CLA. You assert it by signing off every commit:

```bash
git commit -s -m "your message"
```

This appends a `Signed-off-by: Your Name <you@example.com>` trailer. Commits
without a sign-off are flagged by CI and cannot be merged.

**Agent-authored patches are welcome.** Submit them as your own: your
`Signed-off-by` carries the DCO and you take responsibility for the change.

## Where changes go

- **Edges** — SPI-shaped additions with a small blast radius: **workers**
  (engine adapters), **connectors**, emit plugins, deployment glue, docs. These
  land through the normal **PR → review → conformance** path. A first
  contribution is best shaped as *your* worker or connector.
- **Core** — the service contracts (the `*.v1` protos and the MCP surface), the
  plan wire format, and the governed query path. Changes here go through the
  **public RFC process** — see
  [GOVERNANCE.md](./GOVERNANCE.md) (which points at the standard's governance in
  the [`tatrman`](https://github.com/Collite/ttr-core) repo). Open a design
  discussion before a large PR.

The MCP surface is the ecosystem's most public wire; treat changes to it as core.

## Development quickstart

Tatrman Server is a Gradle (Kotlin) build with some Python workers. Convenience
recipes are in the [`justfile`](./justfile).

```bash
./gradlew build                 # build + test all modules
just build      / just test     # same, via the justfile
just build-py   / just test-py  # the Python workers (uv)
bash scripts/check-spdx.sh      # SPDX header gate (add-spdx.sh to add headers)
bash scripts/check-dependency-rules.sh   # module dependency rules (contracts §7)
```

The Kotlin spine consumes the published `org.tatrman:*` toolchain artifacts from
Maven Central; you do not need a local checkout of `tatrman` to build.

## Commit and PR hygiene

- Sign off every commit (`git commit -s`).
- Keep unrelated changes in separate commits/PRs; reference the issue or RFC.
- CI must be green: build, tests, the SPDX header gate, the dependency-rules
  check, and the no-retired-persona gate.

## Communication

- **Bugs** → GitHub Issues (use the templates).
- **Questions / ideas** → GitHub Discussions.
- **Security** → do **not** open a public issue; see [SECURITY.md](./SECURITY.md).
- **Chat** → <!-- TODO(G2): one community chat, linked from the tatrman repo. --> announced with the 1.0 launch.
