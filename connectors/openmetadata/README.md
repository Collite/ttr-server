# TTR → OpenMetadata

Standalone Python connector for XM design §4.B M-1-β. It consumes Tatrman's tier 1
`graph.json`, tier 0 `defs.json`, and CN `cn.ttl` (`owl` profile). The model remains
the source of definitions; the catalog receives projections. Scanner-owned tables
and columns are linked, never created.

The connector includes readers, planner, checked OM writer, a real local
[OM conformance lane](../../conformance/openmetadata/README.md) and a release image.
It targets OM 2.0.6; default CN projection is **glossary** after the RDF acceptance
probe found meaningful hierarchy/language loss. Explicit `--cn-path owl` remains.

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
# Ensure health + custom-property/classification/tag registry (writes when absent).
uv run --locked ttr-connect-openmetadata check \
  --om-url http://localhost:8585/api --om-token-file /secrets/token
uv run --locked ttr-connect-openmetadata sync \
  --export-dir tests/fixtures/export --world northline \
  --om-url http://localhost:8585/api --om-token-file /secrets/token \
  --studio-url https://studio.example --state-file /tmp/northline-om.json
# Same operations as plan: no OM credentials, SDK initialization, writes or state advance.
uv run --locked ttr-connect-openmetadata sync --export-dir tests/fixtures/export --dry-run
make check
uv build
```

`plan` and `sync --dry-run` honor --cn-path (default glossary); select owl
to include the RDF import operation. `plan` writes one typed operation per JSON line to stdout and warnings to stderr.
The directory must contain `graph.json`, `defs.json`, `cn.ttl`, and `.snapshot`.
The URL is the **full export route**, without query parameters. The reader requests
world graph + world definitions + CN OWL, and rejects mixed ETags, snapshots,
grammars, worlds, scoped graphs and malformed Turtle. Unknown JSON fields are
preserved for forward compatibility. Requests have a 30-second timeout.

`--world` defaults to the export's world and must match it. `--studio-url` is the
Studio HTTP(S) origin. Links open the **whole model** at
`<origin>/m/<workspace>/s/db`, `/er` or `/md`; they do not select an individual
object. `--studio-workspace` is the manifest `modeler.toml` `project.name` and
defaults to the export world; set it when those names differ. The workspace is
validated against Studio's manifest/route restrictions. Credentials, query strings
and route paths are not accepted in the origin.

New `cn` concepts remain in the OM glossary: Studio's legacy `cnc` renderer is a
different model. With `--studio-url` configured, sync removes the obsolete
connector-owned `ttrUrl` from CN terms while retaining other custom properties,
IRIs, definitions and asset links. Without that option it leaves existing URLs
untouched. Version0.1.3 changes the successful-state fingerprint, forcing a real
projection after an upgrade even if the source snapshot has not changed.

`--no-deprecate-missing` suppresses stale
operations. `plan` never calls OM and never updates the state file. Exit codes:
0 success/304; 1 OM/credential/success-state-write failure; 2 export/state read
failure; 3 mapping failure. Argparse usage failures exit 2.

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
| ER/MD/CN binding | Term-to-table/column link; MD per-field maps determine actual columns; metrics use native parent-table assets plus exact column FQNs in ttrColumnAssets |
| Registry | String properties `ttrQname`, `ttrUrl`, `ttrKind`, `ttrIri`, `ttrAdditivity`, `ttrColumnAssets` on applicable types; classification `Tatrman`, tags `Grounded` / `Deprecated` |

Display names prefer English; Czech-only names append `(<cs label>)` to the source
name. Descriptions prefer English, otherwise retain all language-labelled text.
OM names are case-insensitive across a whole glossary. For models with distinct
CN kinds/qualities sharing a label (such as `Channel` and `channel`), select
`--cn-name-mode qname` to use canonical CN names while retaining labels in
`displayName`. Default `label` preserves existing catalog naming. Keep the selected
mode stable for that catalog; the writer adopts existing terms by `ttrQname`.
Names are deterministic; OM FQN collisions, ambiguous bindings, multiple CN parents
and cyclic hierarchies fail explicitly. Terms are ordered with parents first.
Related-term references are added after all terms exist. Existing unrelated
properties, tags, related terms and metric asset links are retained.
CN operations carry `cn: true` so that writer can apply custom properties after OWL
import or use the fallback payload when `--cn-path glossary` is selected.

Table FQNs come from each `defs.json.connections[]` descriptor:
`<locators.openmetadata.service>.<database>.<schema>.<table>`. Optional
`locators.openmetadata.schema` supplies the physical catalog schema when the model
uses a handle (for example model `dbo` → PostgreSQL `public`). The descriptor's
`schema` still selects model objects; the locator changes only their OM addresses.
An absent override retains the original behavior; an empty/non-string override fails.
Only matching schemas
participate. Dot-containing name segments are quoted. Multiple connections yield
separate asset operations. Missing service/database locators produce one warning
per connection and no links for that connection. The planner does not know which
assets OM has scanned: sync emits one warning per missing OM table and continues
without creating it. Missing columns are also skipped with a warning.

## State and deprecation

State JSON has `snapshot`, `ids` (TTR qnames), `om_version` and optional `options`.
`options` fingerprints world, Studio URL, CN path/name mode, deprecation mode,
connector version and the current OM version. Sync probes OM's version before
reading the export. Only an exact options match permits `If-None-Match`; a legacy
state without `options` loads successfully but forces a full sync. Offline plan
and dry-run remain independent of OM and credentials. A 304 sync prints zero
counters with `not_modified: true`; a real sync prints `not_modified: false`.
A 304 without compatible state or after part of a new export is an export error.
**Rollback to0.1.1 requires a fresh state path**: that version rejects the new field.
State uses a same-directory temporary file, fsync and atomic rename; ids are sorted
and deduplicated.

Missing ids plus `delta.removed` yield `Deprecate` operations; live rename targets
suppress deprecation of their old names. The writer resolves candidates by
`ttrQname`; `kind` is a target-type hint, never an invented OM FQN. Only materialized
targets are changed: term `entityStatus: Deprecated`, metric tag
`Tatrman.Deprecated`, table/column `Tatrman.Grounded` removed. Other tags, glossary
links and custom properties remain. Reappearing terms return to Draft and metrics
lose the Deprecated tag. **Deprecate, never delete**: neither the operation schema
nor the writer has a delete action. Qnames resolve across all response pages;
term lookup/deprecation is scoped to this world's glossaries.

`sync` calls `check` first, reconciles entities, finalizes term relations, then
links and deprecations. State advances only after every operation succeeds.
Missing scanned tables/columns and planner omissions prevent state advancement,
with a loud `WARN state not advanced: N tables not scanned` diagnostic (plus column
and mapping counts). The next run re-plans from the older ids and rechecks every
deprecation by GET, so an already-deprecated object remains a no-op. A permanently
unscannable object therefore causes a full sync every night until repaired. RDF
import informational messages and a successful glossary fallback do not skip
operations and do not prevent state advancement. Other write failures fail the run.
OM writes are not a transaction: partial changes can remain for an idempotent retry.
A compatible saved snapshot receiving304 skips registry/writes, but still checks
the current OM version. Use two fresh state paths to prove actual writer idempotency.

Successful sync prints exactly one JSON line with `snapshot`, `created`,
`updated`, `deprecated`, `linked`, `warnings`, and `not_modified`. Counts record successful entity
and registry-property creations, successful update patches, deprecation actions,
and asset links. An entity patched more than once can contribute multiple updates;
these are action counts, not unique entity counts. `check` prints the same counters
with an empty snapshot. WARN lines go to stderr. A repeat sync against unchanged
input and catalog makes zero PUT/PATCH calls, even without an HTTP 304.

## OWL import and fallback

Opt-in `--cn-path owl` imports CN Turtle when expected CN terms are missing.
The request explicitly sends `dryRun=false`. Content/endpoint 4xx responses select
the glossary path; 401/403/429 fail immediately. `--cn-path glossary` always skips
RDF import. Both paths create parents first, retain all synonyms, preserve source
IRIs, and attach `ttrQname`. Imported terms are matched by IRI within the target
glossary, so importer-chosen names do not produce duplicates. Existing nonempty
IRIs are never overwritten. Missing IRIs are filled. Existing terms are reconciled
directly instead of reimporting on every sync.

REST calls check HTTP status. All calls have a 30-second timeout; 5xx responses
receive up to three retries (0.25, 0.5, 1 second backoff). 4xx and transport failures
are not retried. SDK patches use deep source/destination copies and
`skip_on_failure=False`. RDF HTTP 200 responses containing failed-term messages,
failed bulk asset rows, and metric links dropped by the server fail the sync;
state does not advance. Metric column FQNs and native parent-table assets are independently read back;
OM 2.0.6 does not retain native column asset references.

Once P3 has installed the CronJob, trigger the projection for the demo:

```sh
kubectl -n openmetadata create job om-project-tatrman-demo \
  --from=cronjob/om-project-tatrman
kubectl -n openmetadata logs -f job/om-project-tatrman-demo
```

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

## Live compatibility and image

Use `just om-up`, `just om-test`, `just om-down` from the repository root. See the
lane README for isolated local credentials, real Postgres scanning and evidence.
The offline suite skips live cases unless OM_URL and OM_TOKEN are set.

OM 2.0.6 requires case-insensitively unique term names across a glossary. ER
attributes and MD terms use canonical qnames as catalog names; human labels and
parents remain intact. Columns use registry type tableColumn and table reads
explicitly request columns,tags,extension. Native Metric.assets cannot retain
columns: the parent table is native, and registered ttrColumnAssets preserves
sorted exact column FQNs as a JSON string array, verified by a fresh GET.

`docker build -f connectors/openmetadata/Dockerfile -t connect-openmetadata:dev .`
builds from repository root using ingestion-base:2.0.6 and its nonroot
openmetadata user. Release image: ghcr.io/collite/connect-openmetadata:0.1.0.
