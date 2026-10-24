<!-- SPDX-License-Identifier: Apache-2.0 -->
# OpenMetadata conformance (XM-P2 / PL OM-5)

Run from the repository root with Docker Compose v2, uv and just; allow ~6 GiB
free Docker memory and several GiB of image storage. This is a disposable **local**
catalog, listening only on 127.0.0.1:18585. Its basic-auth admin/admin and fixture
passwords are public test data. It must never be exposed as a deployed service.

```sh
just om-up       # health → migrate → server → basic-auth bot token → real PG scan
just om-test     # CLI check, sync, read-back, no-op, deprecation, RDF + search probes
just om-down     # remove ONLY this compose project's containers and data volumes
```

`just om-token` prints the local ingestion-bot JWT (handle it as a credential).
The helper logs in as the local admin, gets ingestion-bot, then the bot user's
auth mechanism. Recipes pass its token through the environment without printing
it. `om-scan` can rerun ingestion. The scan service is opt-in (`profiles: [scan]`)
because its JWT exists only after server boot. `om-up` waits for health, then runs
that one-shot service and propagates a nonzero scan exit. OM uses
`DB_USER_PASSWORD`; pipeline service client and self-signup are disabled.

PostgreSQL 16 supplies separate catalog and fixture databases. The **real OM
Postgres connector** scans `northline` / schema `retail`, service `northline-pg`:
three tables and a view matching the published export fixture. Tests never seed
OM table entities directly. The connector adds properties, grounded tags and
term links to these scanner-owned assets. Missing assets remain warnings.

The live tests are marked `om_live` and skip unless **both OM_URL and OM_TOKEN**
are set. `just om-test` mints a token if needed. Use OM_PORT/OM_URL for an alternate
local port and OM_RESULTS_DIR for evidence output; do not point this mutable suite
at a shared catalog. By default results go to ignored `results/` (uploaded by CI).
It runs the actual CLI in a subprocess. A second sync has zero summary changes,
unchanged state bytes and unchanged OM entity versions. An isolated removed CN
quality is deprecated, retained and then restored for reruns.

**OM-5 parity:** server **2.0.6**, SDK/ingestion-base **2.0.6.0/2.0.6**, PostgreSQL,
OpenSearch **3.4.0**, basic auth, explicit one-shot migration, no Airflow client and
no all-in-one/MySQL/Elasticsearch image. Kubernetes/PVC/ESO/ingress behavior belongs
to P3; this lane does not claim deployed-environment acceptance.

- [V-OM-1](V-OM-1.md): OWL and punned RDF acceptance; language/hierarchy loss →
  default glossary path.
- [V-OM-2](V-OM-2.md): supported search pin and the 3.0.0 negative probe.
- [SDK/live facts](../../connectors/openmetadata/docs/sdk-facts.md): tableColumn
  registry, glossary name uniqueness and metric-to-column representation.

Metric.assets supports the parent **table**; exact linked column FQNs are retained
as a sorted JSON string array in registered metric property `ttrColumnAssets`.
The live test reads both back from OM; this is not a native column relationship.
No synthetic measure glossary term or scanner-owned table is created.

Build the same image CI and release use:

```sh
docker build -f connectors/openmetadata/Dockerfile -t connect-openmetadata:dev .
docker run --rm connect-openmetadata:dev --help
```

The pinned ingestion-base user is `openmetadata`, not `airflow`. The Dockerfile
copies only project metadata and src, installs rdflib and the package without
reinstalling the base SDK. The release map publishes ghcr.io/collite/connect-openmetadata
from tag `connect-openmetadata/v0.1.0`; PR CI builds this actual Dockerfile and tests
its entrypoint. No credentials, host virtualenv or test data are in /app.

The server's term-create mapper ignores iri, and an IRI-only patch can return it
without persisting it. New terms receive IRI together with registered source
properties, including ttrIri, in a tracked patch followed by independent GET.
Deprecation is also GET-verified. The suite observes completed approval instances and a quiet event interval
before measuring repeat-sync entity versions.

Status PATCHes send `If-Match: W/"<version>"`. OM otherwise consolidates the
same user's PATCHes for ten minutes, replaying previous metadata changes in a
status event and retriggering approval after deprecation. Conditional writes
skip that consolidation, preserve pure status events and reject concurrent edits
with 412; no 4xx retry or state advance occurs. Deprecation is checked immediately
and again after a delay in the live suite. Reappearance can be Draft or Approved
under the catalog's approval policy. Initial approval-instance completion is
observed before the version/no-op assertions; the suite never disables workflows.

`just om-image-test` runs the built connect-openmetadata:ci image on the isolated
compose network against the same scanned fixture and checks two real syncs. Run
it **after** om-test (both mutate the catalog); CI preserves this order and uploads
image-summaries.jsonl. Override its image parameter for a local tag.

## Registry availability on shared CI runners

Vendor pulls can return HTTP429, and Google cache availability varies by region.
`pull-images.sh` explicitly pulls from Google cache, Docker Hub, then the vendor,
verifies the OM index digest and exports resolved digest URIs before build/compose.
It never switches OM contents: ingestion index is
b7228ccffde7e5d0eb89e48d2c5247476d7b0e87924e11de0c929150a28668b9,
server index is 87706a33003a65b850e1ec832735bddcf0d4a5ab680a79922b0556d4cfc1d359.
Local defaults remain vendor/digest; CI and release use the same resolver. It
also resolves Postgres16 and the selected OpenSearch version to installed digests.

To reproduce with the resolver (requires jq):

```sh
bash conformance/openmetadata/pull-images.sh lane /tmp/om-images.env
set -a; source /tmp/om-images.env; set +a
just om-up && just om-test
just om-image-test
just om-down
```

Overrides are OM_SERVER_IMAGE, OM_INGESTION_IMAGE, OM_POSTGRES_IMAGE and
OM_OPENSEARCH_IMAGE; the Dockerfile accepts OM_BASE_IMAGE (default pinned vendor).
The resolver's base mode only pulls ingestion-base, for the release build.
