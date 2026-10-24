# TTR → OpenMetadata

Standalone Python connector for XM design §4.B M-1-β. It consumes Tatrman's tier 1
`graph.json`, tier 0 `defs.json`, and CN `cn.ttl` (`owl` profile). The model remains
the source of definitions; the catalog receives projections. Scanner-owned tables
and columns are linked, never created.

S2.1 implements the offline planner and readers. `check` and `sync` explicitly raise
`NotImplementedError` until S2.2. Local OM conformance, RDF acceptance (V-OM-1),
OpenSearch verification (V-OM-2) and the image belong to S2.3.

## Run

Python >=3.12, uv; SDK `openmetadata-ingestion==2.0.6` resolves to the exact
`2.0.6.0` distribution in `uv.lock`. [SDK facts](docs/sdk-facts.md) record the
installed generated fields and pinned server endpoints.

```sh
uv sync --locked
uv run --locked ttr-connect-openmetadata plan --export-dir tests/fixtures/export
uv run --locked ttr-connect-openmetadata plan \
  --export-url https://metadata.example/model/export \
  --world northline --studio-url https://studio.example \
  --state-file /tmp/northline-om.json
make check
uv build
```

`plan` writes one typed operation per JSON line to stdout and warnings to stderr.
The directory must contain `graph.json`, `defs.json`, `cn.ttl`, and `.snapshot`.
The URL is the **full export route**, without query parameters. The reader requests
world graph + world definitions + CN OWL, and rejects mixed ETags, snapshots,
grammars, worlds, scoped graphs and malformed Turtle. Unknown JSON fields are
preserved for forward compatibility. Requests have a 30-second timeout.

`--world` defaults to the export's world and must match it. `--studio-url` adds
`<url>/viewer?qname=<encoded-qname>`. `--no-deprecate-missing` suppresses stale
operations. `plan` never calls OM and never updates the state file. Exit codes:
0 success/304; 2 export/state read failure; 3 mapping failure. Exit 1 for OM errors
is reserved for the S2.2 writer. Argparse usage failures exit 2.

## Mapping (C-15/C-16)

| Input | Operations / catalog representation |
|---|---|
| CN OWL | Ensure `<world> Concepts`, import Turtle; CN term payloads also carry `ttrQname` and fallback fields |
| CN nodes | Preserve IRI, flattened multilingual synonyms, `is_a` parent, symmetric `relatedTo` for involves/open relations |
| ER entity / attribute | `<world> Entities` root / child term; ER relations become related terms |
| MD dimension / hierarchy | Entities terms with `ttrKind`; a mapped dimension attribute links through its dimension term |
| MD measure | Metric, definition's aggregation + expression; SUM/COUNT/AVG → SUM/COUNT/AVERAGE, others → OTHER; `ttrAdditivity` |
| DB table / view | Properties on an existing scanned OM Table, description, `Tatrman.Grounded` when bound |
| DB column | `ttrQname`, `Grounded` when bound; applied through the owning table |
| ER/MD/CN binding | Term-to-table/column link; MD per-field maps determine actual columns; metrics use their own asset relation |
| Registry | String properties `ttrQname`, `ttrUrl`, `ttrKind`, `ttrAdditivity` on applicable types; classification `Tatrman`, tags `Grounded` / `Deprecated` |

Display names prefer English; Czech-only names append `(<cs label>)` to the source
name. Descriptions prefer English, otherwise retain all language-labelled text.
Names are deterministic; OM FQN collisions, ambiguous bindings, multiple CN parents
and cyclic hierarchies fail explicitly. Terms are ordered with parents first.
Related-term references are finalized after all terms exist by the S2.2 writer.
CN operations carry `cn: true` so that writer can apply custom properties after OWL
import or use the fallback payload when `--cn-path glossary` is selected.

Table FQNs come from each `defs.json.connections[]` descriptor:
`<locators.openmetadata.service>.<database>.<schema>.<table>`. Only matching schemas
participate. Dot-containing name segments are quoted. Multiple connections yield
separate asset operations. Missing service/database locators produce one warning
per connection and no links for that connection. The planner does not know which
assets OM has scanned: S2.2 will warn on a missing OM table and continue.

## State and deprecation

State JSON has `snapshot`, `ids` (TTR qnames), and `om_version`. State is written
only after successful sync, using a same-directory temporary file, fsync and atomic
rename. IDs are sorted and deduplicated. The URL reader sends `If-None-Match` from
state; an initial 304 ends the run with no operations. A 304 without state or after
part of a new export is an export error.

Missing ids plus `delta.removed` yield `Deprecate` operations; live rename targets
suppress deprecation of their old names. The writer resolves candidates by
`ttrQname`; `kind` is a target-type hint, never an invented OM FQN. Only materialized
targets are changed. Term deprecation, metric `Tatrman.Deprecated`, and removal of
managed table/column tags belong to S2.2. **Deprecate, never delete**: the operation
schema has no delete variant. This Stage does not claim a successful OM sync.

## Fixture provenance and goldens

All fixture names are invented. `tests/fixtures/export` copies the ttr-core
`packages/kotlin/ttr-metadata/src/test/resources/xm-goldens` from commit
`feea9114991ed9c1952ceb8a8e863bf1ef1fbc92` (`grammar/v0.18.8`). `graph.json/world.json`
and `defs.json/world.json` are copied verbatim; `ttl/world-owl.ttl` becomes `cn.ttl`.
This is the raw-world OWL oracle used for CLI/HTTP parity; `ttl/owl.ttl` additionally
contains an S1.3-only injected binding and does not match the raw fixture.

```sh
make sync-fixtures CORE=../../../tatrman-xm
uv run --locked pytest tests/test_mapping.py --update-goldens
make check
```

The five handwritten seed operations remain an independent oracle. The full plan
golden adds all mappings, while semantic tests independently assert IRI, hierarchy,
synonyms, metric code/type, per-column bindings and connection behavior. Golden
updates are explicit and must be reviewed. Unit tests mock HTTP; no deployed
environment is contacted.
