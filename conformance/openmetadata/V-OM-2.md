<!-- SPDX-License-Identifier: Apache-2.0 -->
# V-OM-2 — OpenSearch minimum for OM 2.0.6

The [OM 2.0 minimum requirements](https://docs.open-metadata.org/v2.0.x/deployment/minimum-requirements)
state **OpenSearch >=3.4.0**, recommending 3.4.0 or later. The older planning input
of 3.0.0 is superseded: 3.0–3.3 have a script_score NullPointerException triggered
by OM 2.0 staged ranking. A healthy server and working metadata CRUD do not prove
plain-text search works. The pinned [2.0.6 upstream compose](https://github.com/open-metadata/OpenMetadata/blob/2.0.6-release/docker/development/docker-compose-postgres.yml)
uses 3.4.0.

The lane pins **3.4.0**, the exact version documented and shipped by upstream.
Latest OpenSearch at implementation is 3.9.0; this lane does not claim to have
validated every newer release. C-18 inherits the tested 3.4.0 pin.

| Version | Migration / scan / projection | Plain text table search | Deployment decision |
|---|---|---|---|
| 3.4.0 | PASS: clean migration, real Postgres scan, 4 live tests | PASS: HTTP 200, scanned sales found | supported pin |
| 3.0.0 | PASS: clean migration, scan and projection | FAIL as expected: HTTP 500 / null_pointer_exception | below documented minimum |

The live suite queries `GET /api/v1/search/query?q=sales&index=table_search_index`.
The CI matrix tests both versions. The 3.0.0 leg is an explicit **negative
compatibility probe** and must reproduce HTTP 500; green CI for that leg does not
approve 3.0.0 for deployment. Both legs retain `search.jsonl`, summary lines, RDF
results, JUnit and compose logs. Run each against a fresh search volume (never
open a 3.4 data volume using 3.0):

```sh
just om-up && just om-test
just om-down
OM_OPENSEARCH_VERSION=3.0.0 just om-up
OM_OPENSEARCH_VERSION=3.0.0 just om-test
OM_OPENSEARCH_VERSION=3.0.0 just om-down
```

Local execution 2026-10-24: all four live cases pass on each fresh lane (3.4.0:
32.17s, 3.0.0: 30.82s). The 3.0.0 search case passes by detecting the unsupported
version's HTTP 500 and null_pointer_exception. Both final release-image syncs are
no-ops on each catalog. First host sync creates 26 model entities after check
registers 14 property/tag/classification items, links 10 bindings and records 39
metadata updates; second sync has zero counters. Removing one isolated CN quality
produces one stable deprecation; reappearance restores an active status. This
proves the documented search minimum matters even when the metadata API works.
