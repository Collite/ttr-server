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
