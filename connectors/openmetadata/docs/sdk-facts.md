# SDK facts — XM-P2 S2.1 (2026-10-24)

Installed `openmetadata-ingestion==2.0.6.0`. PyPI normalizes requested `==2.0.6` to `2.0.6.0`; uv.lock pins the exact distribution. No server calls.

## CreateMetricRequest

```text
['assets', 'customUnitOfMeasurement', 'dataProducts', 'description', 'dimensions', 'displayName', 'domains', 'extension', 'filters', 'granularity', 'measures', 'metricExpression', 'metricType', 'name', 'owners', 'provider', 'relatedMetrics', 'reviewers', 'tags', 'unitOfMeasurement']
```

## CreateGlossaryTermRequest

```text
['conceptMappings', 'description', 'displayName', 'domains', 'extension', 'glossary', 'iri', 'mutuallyExclusive', 'name', 'owners', 'parent', 'provider', 'references', 'relatedTerms', 'reviewers', 'style', 'synonyms', 'tags']
```

## CreateGlossaryRequest

```text
['description', 'displayName', 'domains', 'extension', 'mutuallyExclusive', 'name', 'owners', 'provider', 'reviewers', 'tags']
```

## Metric

```text
['assets', 'certification', 'changeDescription', 'customUnitOfMeasurement', 'dataContract', 'dataProducts', 'deleted', 'derivedFrom', 'description', 'dimensions', 'displayName', 'domains', 'entityStatus', 'extension', 'filters', 'followers', 'fullyQualifiedName', 'granularity', 'href', 'id', 'impersonatedBy', 'incrementalChangeDescription', 'measures', 'metricExpression', 'metricType', 'name', 'owners', 'provider', 'relatedMetrics', 'reviewers', 'tags', 'unitOfMeasurement', 'updatedAt', 'updatedBy', 'version', 'votes']
```

## MetricExpression

```text
['code', 'language']
```

## MetricType

COUNT, SUM, AVERAGE, RATIO, PERCENTAGE, MIN, MAX, MEDIAN, MODE, STANDARD_DEVIATION, VARIANCE, SIMPLE, CUMULATIVE, DERIVED, CONVERSION, OTHER

## Language

SQL, Java, JavaScript, Python, External

CreateGlossaryTermRequest.relatedTerms: `annotation=Union[list[FullyQualifiedEntityName], NoneType] required=False default=None description='Other array of glossary term fully qualified names that are related to this glossary term.'`

CreateGlossaryTermRequest.iri: `annotation=Union[AnyUrl, NoneType] required=False default=None description='Canonical IRI of this term in its source ontology. Preserves identity across ontology import/export round-trips.'`

GlossaryTerm.relatedTerms: `annotation=Union[list[TermRelation], NoneType] required=False default=[] description='Other glossary terms that are related to this glossary term with typed semantic relations.' validate_default=True`

GlossaryTerm.iri: `annotation=Union[AnyUrl, NoneType] required=False default=None description='Canonical IRI of this term in its source ontology (e.g., `http://example.com/ontology/hcp#HealthcareProvider`). Preserves identity across ontology import/export round-trips and is used as the RDF subject when present. Empty for terms not sourced from an external ontology.'`

MetricExpression annotations: `{'language': 'metadata.generated.schema.entity.data.metric.Language | None', 'code': 'str | None'}`

## Pinned endpoint and client facts

Verified against the upstream `2.0.6-release` source (read-only, no OM server run):

- [GlossaryResource.java](https://github.com/open-metadata/OpenMetadata/blob/2.0.6-release/openmetadata-service/src/main/java/org/openmetadata/service/resources/glossary/GlossaryResource.java#L655):
  RDF import is PUT `/api/v1/glossaries/name/{name}/importRdf`, accepts `text/turtle`,
  defaults `format=turtle`, and **defaults `dryRun=true`**. S2.2 must explicitly send
  `dryRun=false` for a real sync; leaving it out validates without persisting.
- [GlossaryTermResource.java](https://github.com/open-metadata/OpenMetadata/blob/2.0.6-release/openmetadata-service/src/main/java/org/openmetadata/service/resources/glossary/GlossaryTermResource.java#L873):
  PUT `/api/v1/glossaryTerms/{id}/assets/add` accepts `AddGlossaryToAssetsRequest`.
- [TypeResource.java](https://github.com/open-metadata/OpenMetadata/blob/2.0.6-release/openmetadata-service/src/main/java/org/openmetadata/service/resources/types/TypeResource.java#L503):
  PUT `/api/v1/metadata/types/{id}` accepts one `CustomProperty`, not an entire Type.

Installed client imports verified: `OpenMetadata`, `OpenMetadataConnection`,
`AuthProvider`, `OpenMetadataJWTClientConfig`. `OpenMetadata.patch(entity, source,
 destination, ..., skip_on_failure=True)` defaults to swallowing patch failure;
S2.2 must use `skip_on_failure=False`. `health_check` and `create_or_update` exist.

`CreateGlossaryTermRequest.relatedTerms` contains FQN strings. The returned entity
uses `metadata.generated.schema.type.termRelation.TermRelation`, whose fields are
`term: EntityReference` and `relationType: str` (default `relatedTo`). Create terms
first, then resolve references and patch typed relations. `iri` is directly
accepted by the create request as well as the returned entity.

`Metric.assets` / `CreateMetricRequest.assets` are metric-native asset relations;
metrics are not glossary terms. Mapping emits `LinkMetricToAsset` separately from
`LinkTermToAsset`. The latter alone uses the glossary-term assets endpoint.
MIN/MAX remain OTHER by C-16's intentional mapping, even though the SDK also has
MIN/MAX enums. `MetricExpression` uses `Language.SQL`, code text, and no granularity.

S2.1 tests instantiate all fixture metric and term request payloads with these
actual SDK classes. Live RDF behavior (punning/language tags) and OpenSearch minima
remain pending for S2.3.

## S2.2 writer facts

- `GlossaryTerm.entityStatus` accepts `Deprecated` (and `Draft` on reappearance);
  the SDK has no writable `status` field on the term entity. Metrics use the
  `Tatrman.Deprecated` tag. Other metadata is retained.
- `AddGlossaryToAssetsRequest.dryRun` also defaults **true**. The writer sends
  `false` in the body, checks failed-row counts/status and reads back the tag.
  [GlossaryTermRepository.java](https://github.com/open-metadata/OpenMetadata/blob/2.0.6-release/openmetadata-service/src/main/java/org/openmetadata/service/jdbi3/GlossaryTermRepository.java#L1082)
  special-cases `tableColumn` assets by column FQN; column references carry the
  parent table ID, `type: tableColumn` and the column FQN.
- [OntologyImportResult.java](https://github.com/open-metadata/OpenMetadata/blob/2.0.6-release/openmetadata-service/src/main/java/org/openmetadata/service/rdf/OntologyImportResult.java)
  is the RDF import response (not the CSV bulk result). It has `dryRun`, counters
  and `messages`. [GlossaryRdfImporter.java](https://github.com/open-metadata/OpenMetadata/blob/2.0.6-release/openmetadata-service/src/main/java/org/openmetadata/service/rdf/GlossaryRdfImporter.java#L684)
  catches some per-term exceptions and appends `Failed ...` messages in an HTTP 200
  result. The writer fails on those messages; other messages become WARNs.
- Content/endpoint 4xx RDF failures fall back to term creation; authentication,
  authorization and rate limits (401/403/429) fail without fallback. A repeat sync
  skips import once all CN terms resolve by qname/IRI/FQN in the target glossary.
- [MetricRepository.java](https://github.com/open-metadata/OpenMetadata/blob/2.0.6-release/openmetadata-service/src/main/java/org/openmetadata/service/jdbi3/MetricRepository.java#L127)
  populates asset references before storing them. The writer checks that a metric
  patch retains the requested asset; it fails if the server drops it. Actual
  metric-to-column persistence is a mandatory S2.3 round-trip check, not proved by
  SDK validation or the stateful HTTP fake.
- SDK retries are disabled in this wrapper. One retry boundary covers REST and
  SDK calls: three retries after the first 5xx, exponential 0.25/0.5/1s backoff,
  no 4xx/transport retry. Patch exception causes retain the HTTP response status.
- SDK JSON-patch generation observes Pydantic fields explicitly present in the
  response. Keep returned typed entities and use `model_copy(deep=True)` instead
  of rebuilding responses through default-filled JSON; otherwise absent default
  arrays can generate a patch addressing an array the server never returned.

## S2.3 live findings (A-XM-9)

Verified against real OM 2.0.6 + PostgreSQL 16 + OpenSearch 3.4.0 on 2026-10-24.

- The registry name is **tableColumn**, not column; eleven string properties are
  registered across table/tableColumn/glossaryTerm/metric, including metric
  `ttrColumnAssets`. Read tables with **columns,tags,extension**: without columns,
  the API returns empty column tag arrays even though tags are persisted. This
  matters for preserving metadata and no-op detection.
- Glossary term names are unique **case-insensitively across the whole glossary**,
  even with different parents. ER attributes and MD terms use their canonical
  qname as catalog name (quoted as one FQN segment); displayName stays human and
  parent structure is retained. Other name collisions fail during planning.
- OM silently drops `tableColumn` from native Metric.assets. The connector uses
  a native parent-table asset plus sorted JSON column FQNs in registered string
  property `ttrColumnAssets`; PATCH is followed by independent GET verification.
  This preserves the precise binding without pretending a native column relation
  exists. Other extension data and existing native assets are preserved.
- RDF owl and both profiles are accepted with original IRIs, but hierarchy and
  language tags are lossy. Default CN path is now **glossary**, as the prepared
  fallback requires. Explicit owl remains available. [V-OM-1](../../../conformance/openmetadata/V-OM-1.md)
  records the actual import fields and warnings.
- ingestion-base:2.0.6 runs Python 3.12.15 / SDK 2.0.6.0 as nonroot user
  **openmetadata**; there is no airflow user. The Dockerfile uses the actual user.
- [OM 2.0 minimum requirements](https://docs.open-metadata.org/v2.0.x/deployment/minimum-requirements)
  require OpenSearch **>=3.4.0** due to older script_score failures. See
  [V-OM-2](../../../conformance/openmetadata/V-OM-2.md) for both real lane results.

These live observations supersede S2.1/S2.2's SDK-only assumptions about column
registry naming and native metric-column persistence. Neither fake HTTP success
nor SDK model validation alone is live acceptance.

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

## Native clickable Studio link — 0.1.4

OM2.0.6 supports the native `hyperlink-cp` custom-property type. Its value is
`{"url":"https://studio.example/m/northline/s/er","displayText":"Open E-R model in Studio"}`.
The renderer emits an HTTP(S) `href`, `target="_blank"` and `rel="noopener noreferrer"`.
The connector registers a new `ttrStudioLink` on table/glossaryTerm/metric and
preserves all original string types, including `ttrUrl`. CN terms have neither link
with Studio configured; no-Studio sync leaves existing link properties unchanged.

The pinned Python SDK's `Type.name` uses `EntityName`, which rejects the hyphen in
this genuine built-in name. Only the `hyperlink-cp` REST type lookup uses a narrow
model validating its exact literal name and UUID; existing entity/string type
lookups retain SDK validation. Writes still validate through `CustomProperty` and
the SDK entity payloads. Wrong built-in names/UUIDs and conflicting existing
property types fail; no silent type conversion is performed.

Official source inspected at `2.0.6-release`, commit `a695261b19d48411a39502a5c5fc0b453729eb4e`:
- [Renderer](https://github.com/open-metadata/OpenMetadata/blob/a695261b19d48411a39502a5c5fc0b453729eb4e/openmetadata-ui/src/main/resources/ui/src/components/common/CustomPropertyTable/PropertyValue.tsx)
- [Value schema](https://github.com/open-metadata/OpenMetadata/blob/a695261b19d48411a39502a5c5fc0b453729eb4e/openmetadata-spec/src/main/resources/json/schema/type/customProperties/complexTypes.json)
- [Type-name constant](https://github.com/open-metadata/OpenMetadata/blob/a695261b19d48411a39502a5c5fc0b453729eb4e/openmetadata-ui/src/main/resources/ui/src/constants/CustomProperty.constants.ts)
