<!-- SPDX-License-Identifier: Apache-2.0 -->
# `door-parity/` — copied golden pairs from the Golem's query door

These files are **byte-for-byte copies**, not imports:

| | |
|---|---|
| source repo | `Collite/kantheon` |
| source path | `agents/golem/src/test/resources/door-parity/` (written and compared by `EntitySqlEmitterSpec`) |
| source commit | `ae0776c` (branch `feat/dq-door-entity-sql`) |
| copied | 2026-10-01 |

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
