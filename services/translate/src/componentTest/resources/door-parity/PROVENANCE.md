<!-- SPDX-License-Identifier: Apache-2.0 -->
# `door-parity/` — copied golden pairs from the Golem's query door

These files are **byte-for-byte copies**, not imports:

| | |
|---|---|
| source repo | `Collite/kantheon` |
| source path | `agents/golem/src/test/resources/door-parity/` (written and compared by `EntitySqlEmitterSpec`) |
| source commit | `6d30d22` (branch `feat/lr-p1-door-envelope`; previously `ae0776c`, `feat/dq-door-entity-sql`) |
| copied | 2026-10-07 (refresh; first copy 2026-10-01) |

Each pair is one question as the Golem's query door asks it, in its two languages:
`<name>.transdsl.json` (a `transdsl.v1.Query`), `<name>.sql` (SQL over the model's entities, with
bare `JOIN`s and `{pN}` placeholders), and — when the question binds a quoted literal —
`<name>.params.json`, the typed-text `parameters` the door sends with either form.

`DoorParityComponentSpec` parses both against `HartlandErFixture` (the refs are the public hartland
demo's) and asserts they unparse to the **same PostgreSQL** with the same positional bindings — the
claim being that switching the door's language changes the protocol's text and nothing the
database sees.

**When the source changes, refresh the copy in the same change** and update the commit above;
a stale copy checks a door that no longer exists.

**2026-10-07 refresh (LR C-2).** A time-grain grouping's SQL now ends `ORDER BY` the grain; the
TransDSL wire has no `ORDER BY`, so those pairs differ by that line alone — named in
`KNOWN_DIFFERENCES` (`ORDERED_BY_GRAIN`), with the rest asserted equal. The pairs TransDSL refuses
outright (`TRANSDSL_REFUSES_UNFILTERED_JOIN`) assert the SQL side's grain `ORDER BY` on its own,
coarse to fine (`unfilteredJoinOrderedBy`) — the year+month pair is the only multi-grain order.
Shapes only the SQL form can write (a ranking, an implied trend grain) have no TransDSL twin; they
are under `../door-sql/` and asserted to compile. (`door-parity/` itself is unchanged by kantheon
`ef934f4`: no pair carries a ranking.)
