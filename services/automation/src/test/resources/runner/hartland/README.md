# Real AU runner fixture

The six `*.bundle/` directories are copied byte-for-byte from **ttr-demo master c62246e**
(PR #92, 2026-10-22), compiled with **b29d759 / 0.17.3-au.2**. They remain ordinary
external manifest-v3 bundles. `HartlandRunnerSpec` checks every executable file hash.

`erp.sql` and `automation.sql` are the compiler's small planted split-storage fixture,
from **ttr-core b9d1d63**, `packages/kotlin/ttrp-conform/src/test/resources/recipes/hartland/`.
`erp-to-er.sql` projects those physical column names into their ER names using
`ttr-demo c62246e model/binding/er2db.ttrm`. This is the fake Query door's model mapping;
production ER reads always go through Query, never directly to these databases.

The runner tests use two independent in-process DuckDB databases behind the fake door,
then load their Arrow results into a third embedded DuckDB for combines. A fourth,
single-database oracle executes the part expressions as CTEs without copying them.
Every monitor_inbound/settle output is compared, and all their route keys are checked
against the compiler's planted expectations. This is an isolated component-level proof,
not certification of the live Hartland door or model refresh.
