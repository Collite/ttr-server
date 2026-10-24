<!-- SPDX-License-Identifier: Apache-2.0 -->
# V-OM-1 — OM 2.0.6 RDF import

Local real API probe, 2026-10-24; ingestion-bot JWT, PostgreSQL 16, OpenSearch 3.4.0.
Fixtures `tests/fixtures/ttl/{owl,both}.ttl` are byte copies of ttr-core's published
XM goldens. `owl.ttl` includes the projection test's additional CN→DB binding;
`fixtures/export/cn.ttl` remains the unchanged world OWL oracle.

| Observation | OWL | BOTH (OWL Class + SKOS Concept punning) |
|---|---|---|
| PUT importRdf, format=turtle, dryRun=false | HTTP 200, 8 terms | HTTP 200, 8 terms |
| Party authored IRI | https://example.org/northline/Party | same |
| Other term IRIs | original urn:ttr:northline:… | same |
| Party displayName | Party | Party |
| Party synonyms | [] | [party, osoba, partner, partner] |
| Czech rdfs:label Osoba | not retained as synonym | not retained as synonym |
| Language tags | flattened/lost | flattened/lost |
| Parent relation | isa skipped (invalid relation type) | same |
| Punning | not applicable | accepted, one term per IRI |

On a clean glossary both responses report `termsCreated=8`, `termsUpdated=0`,
`relationsAdded=0`, `customPropertiesCreated=0`, `relationTypesRegistered=0`;
conceptMappingsAdded is 1 for OWL, 2 for BOTH. Reimports report updates instead of
creations. Both responses include a relation-type admin-privilege message and
`Relation isa skipped`. URN-derived catalog names contain spaces replacing dots;
the connector resolves imported terms by IRI and retains that catalog name.

**Decision (A-XM-9 / C-15): default --cn-path glossary.** The importer accepts our
RDF, but loses meaningful hierarchy and localized labels. Direct graph projection
sets the source IRI, stable parent, English display/description and the deduplicated
synonyms from every language. OM's term model still cannot retain language tags.
Explicit `--cn-path owl` remains supported and reconciles the imported entities;
its importer warnings remain visible. The live suite separately verifies RDF
acceptance and the default glossary projection. No importer-created metadata is
claimed to be a lossless TTR round trip.

Reproduce: `just om-up && just om-test`. CI uploads `rdf-import.jsonl`, including
the complete actual response and imported terms, with the summary JSON lines.
