# Released writer approval fixtures

All six evidence cases are generated with the published **ttrp 0.22.3** CLI,
matching `gradle/libs.versions.toml`. Run the rebuild on **bp-dsk**:

```sh
bash scripts/rebuild-automation-fixtures.sh /path/to/ttrp-cli-0.22.3/bin/ttrp-cli
```

The script rebuilds the four hero bundles and the base, unqualified, generated-ids,
obligations, obligations/conflict and fulfilment writer bundles. It updates the
fulfilment approval snapshot only after validating both current fingerprints and
retains the superseded text fingerprint for the stale-approval regression.

`ApprovalFixture.copyTo` and the rebuild script replace the hero ER, binding and
DB directories with this fixture's `model.ttrm`, `binding.ttrm` and `db.ttrm`.
The slice maps all five order attributes to `dbo.sales_order` without hero-only
references, and maps the customer and return-request entities used by other native
cases. The generated-ids case also adds its proposal ER, binding and DB files.
The real loader must report zero warnings for every variant.

The base and unqualified cases wire the defaulted writer and hold transition.
Generated IDs add two identical bigint writer proposals; obligations add equal
proposals and a conflicting-date companion. Fulfilment wires qualified stdlib
obligation transitions and keeps evidence for both project and injected stdlib
targets. All native sinks survive the grammar cut: writer, transition, obligation.

The component cases cover governed Open/evaluate, retries, close/restart, exact
keys, defaults, and obligation lookup refusals with the real PostgreSQL ledger.
Query tables are canned except where the fulfilment tests execute SQL in DuckDB.
Physical writes, process-engine delivery and live Hartland certification belong
to their own stages.
