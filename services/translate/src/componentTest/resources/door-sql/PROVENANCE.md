<!-- SPDX-License-Identifier: Apache-2.0 -->
# `door-sql/` — copied SQL-only goldens from the Golem's query door

**Byte-for-byte copies**, like `../door-parity/`:

| | |
|---|---|
| source repo | `Collite/kantheon` |
| source path | `agents/golem/src/test/resources/door-sql/` (written and compared by `EntitySqlEmitterSpec`) |
| source commit | `6d30d22` (branch `feat/lr-p1-door-envelope`) |
| copied | 2026-10-07 |

The questions here are ones only the door's SQL form can ask: a ranking (`ORDER BY <measure> DESC`
+ `LIMIT N`) and a trend whose time grain the door implied (`GROUP BY` + `ORDER BY` the calendar's
month, with the year first when the period spans two). The TransDSL wire has no `ORDER BY` and no
`LIMIT`, so the door refuses these in TransDSL by name, and there is no second language to compare
with.

`DoorParityComponentSpec` therefore asserts the half of parity that still applies: each file
**compiles** through the query service's own route (ER → DB → PostgreSQL) against
`HartlandErFixture`, and keeps the clause it was written for (`ORDER BY`; for a ranking, `DESC` and
`FETCH NEXT`).

**When the source changes, refresh the copy in the same change** and update the commit above.
