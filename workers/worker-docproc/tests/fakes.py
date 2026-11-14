# SPDX-License-Identifier: Apache-2.0
"""Contract fakes; fixture manifests/schemas are the only production-shaped data."""

from __future__ import annotations

import hashlib
import json
from collections.abc import Iterator
from pathlib import Path
from typing import Any

import yaml

from docproc.kinds.common import meta
from docproc.runtime.validators import span_within_unit
from docproc.sdk.models import (
    Annotation,
    ContextResult,
    ListAnnotationsPage,
    ListAnnotationsRequest,
    LoadAnnotationsRequest,
    LoadAnnotationsResponse,
    MetadataValue,
    Page,
    Processor,
    Source,
    SourceTree,
    Unit,
)
from docproc.sdk.store import StoreError
from tests.conftest import CONTRACTS, WORKER

PACK = WORKER / "tests/fixtures/packs/hartland-mini"


def _response(name: str) -> dict[str, Any]:
    value: dict[str, Any] = json.loads((CONTRACTS / name).read_text(encoding="utf-8"))["response"]
    return value


def page_hit(page_id: int, score: float | None = None, mart: str = "hartland-public") -> dict[str, Any]:
    """A `/findSimilar` page hit as docwh-store prints it: findSimilar.json's (the store's RetrievalRoutesSpec holds
    the route to that file), with this page id, mart and score — the page's cosine to the query (the fixture's when
    None)."""
    hit: dict[str, Any] = json.loads(json.dumps(_response("findSimilar.json")["chunks"][0]))
    hit["pageId"] = hit["citation"]["pageId"] = page_id
    if score is not None:
        hit["score"] = score
    hit["citation"]["sourceRef"] = f"docwh://{mart}/page/{page_id}"
    return hit


def concept_page(page_id: int, qname: str, title: str = "Item") -> dict[str, Any]:
    """`GET /pages/{id}` of a concept page compile wrote: page.json's (the store's spec holds the route to it)."""
    page: dict[str, Any] = json.loads(json.dumps(_response("page.json")))
    page["id"], page["title"] = page_id, title
    page["conceptRef"].update(entityId=qname, metadataQname=qname, displayLabel=title)
    return page


def split_source_metadata(doc: str) -> dict[str, MetadataValue]:
    """`Source.metadata` as split writes it for the document's registration (docwh-pipeline `SplitKind`): documentId,
    version, recordedAt, feed, subjectId, lang. These live on the Source, never on the DOCUMENT unit, which carries
    only title and lang (T8f W5-4, review-257 F15).

    A hand copy of the producer's key list: its pin is kantheon docwh-pipeline's `SplitKindSpec` (the loaded
    Source.metadata equals the registration's six keys, blanks omitted; review-257 R4)."""
    document = json.loads((PACK / "registrations" / (doc.rsplit(".", 1)[0] + ".json")).read_text())["document"]
    keys = ("documentId", "version", "recordedAt", "feed", "subjectId", "lang")
    return {k: MetadataValue(single=document[k]) for k in keys if document.get(k)}


class FakeWarehouse:
    """The store as the kinds see it. The retrieval reads answer the routes' own shapes (✅W5-2-C): `/findSimilar`
    refuses a blank mart or query before the wire, as `StoreClient.find_similar` does (`ValueError
    store/find-similar-blank`, review-244 F5/N2), and answers `ContextResult` built from [similar] (page hits, see
    `page_hit`); `GET /pages/{id}` answers [pages] (see `concept_page`), 404 outside them.

    One source truth (review-257 F15): `GET /sources` and `GET /sources/{id}/units` both answer each source id of
    [units] as `Source(id, metadata=source_metadata[id], notebookId=mart)`. [register] fills a source's metadata as
    split writes it for a pack document; a test that needs another listing sets [sources], and one modelling a store
    before 0.11.14 puts the source id in [unanswered].

    The listing is lenient where the store is not (review-257 R8): `GET /sources?documentId=` lists a source no test
    registered (no `documentId`) under every document, so a test that does not care about the listing finds its own
    source there (diff's `this_source`, link's mart). The store would not list it. A test of the unlisted path sets
    [sources] itself."""

    def __init__(self, units: list[Unit], mart: str = "hartland-public") -> None:
        self.units = {u.id: u for u in units}
        self.mart = mart
        self.similar: list[dict[str, Any]] = []
        self.similar_calls: list[dict[str, Any]] = []
        self.pages: dict[int, dict[str, Any]] = {}
        self.page_reads: list[tuple[int, str]] = []
        self.source_reads = 0
        self.annotations: list[Annotation] = []
        self.lemmas: dict[int, dict[str, Any]] = {}
        self.sources: list[dict[str, Any]] | None = None  # None = one source per source id of the units
        # `GET /sources/{id}/units`'s `source` (docwh-store 0.11.14): its metadata as split writes it, by source id
        self.source_metadata: dict[int, dict[str, MetadataValue]] = {}
        # source ids whose units answer carries no `source`: a docwh-store before 0.11.14 (review-257 F11)
        self.unanswered: set[int] = set()
        self.loads = 0
        self.next_id = 1000
        self.visibility: dict[str, Any] = {}
        self.generations: dict[str, str] = {}

    def get_unit(self, unit_id: int, notebook_id: str = "") -> Unit:
        return self.units[unit_id].model_copy(deep=True)

    def register(self, doc: str, source_id: int = 100) -> FakeWarehouse:
        """Source [source_id] holds pack document [doc] as split loaded it: its Source's metadata, split's keys."""
        self.source_metadata[source_id] = split_source_metadata(doc)
        return self

    def _source(self, source_id: int) -> Source:
        return Source(id=source_id, metadata=self.source_metadata.get(source_id, {}), notebook_id=self.mart)

    def source_tree(self, source_id: int, text: bool = True) -> SourceTree:
        """The tree with its source, as unitTree.json's response has it (no source for an [unanswered] id)."""
        units = sorted(
            (u for u in self.units.values() if u.source_id == source_id), key=lambda u: (u.parent_id is not None, u.idx)
        )
        source = None if source_id in self.unanswered else self._source(source_id)
        return SourceTree(source_id=source_id, units=units, source=source)

    def iter_annotations(self, req: ListAnnotationsRequest) -> Iterator[Annotation]:
        for a in self.annotations:
            if req.unit_id is not None and a.unit_id != req.unit_id:
                continue
            if req.run_id is not None and a.run_id != req.run_id:
                continue
            if req.types and a.type not in req.types:
                continue
            live = self.visibility.get(a.run_id, "ACTIVE") == "ACTIVE"
            if req.active_only is not False and not live:
                if not req.generation or self.generations.get(a.run_id) != req.generation:
                    continue
            # The store's generationProducers (P2 S2.3): a re-run family's live output gives way to the generation's.
            if live and req.generation and a.producer in req.generation_producers:
                continue
            yield a.model_copy(deep=True)

    def list_annotations(self, req: ListAnnotationsRequest) -> ListAnnotationsPage:
        return ListAnnotationsPage(annotations=list(self.iter_annotations(req)))

    def load_annotations(self, req: LoadAnnotationsRequest) -> LoadAnnotationsResponse:
        if any(a.run_id != req.run_id for a in req.annotations):
            raise ValueError("one run per batch")
        for annotation in req.annotations:
            span_within_unit(annotation, self.units)
        if any(a.run_id == req.run_id for a in self.annotations):
            raise ValueError("run already loaded")
        self.loads += 1
        self.visibility[req.run_id] = req.visibility
        if req.generation:
            self.generations[req.run_id] = req.generation
        ids = []
        for a in req.annotations:
            self.next_id += 1
            ids.append(self.next_id)
            self.annotations.append(a.model_copy(update={"id": self.next_id}, deep=True))
        return LoadAnnotationsResponse(ids=ids)

    def load_lemmas(self, run_id: str, lemmas: list[dict[str, Any]]) -> None:
        self.lemmas.update({entry["unitId"]: entry for entry in lemmas})

    def find_similar(self, notebook_id: str, query: str, node_kinds: list[str], k: int) -> ContextResult:
        if not notebook_id.strip() or not query.strip():  # as StoreClient.find_similar refuses it, before the wire
            raise ValueError("store/find-similar-blank: notebookId and query are required")
        self.similar_calls.append({"notebookId": notebook_id, "query": query, "nodeKinds": node_kinds, "k": k})
        return ContextResult.model_validate({"chunks": self.similar[:k], "grounded": bool(self.similar)})

    def page(self, page_id: int, notebook_id: str) -> Page:
        self.page_reads.append((page_id, notebook_id))
        if page_id not in self.pages or notebook_id != self.mart:
            raise StoreError(404, "page/not-found", str(page_id))
        return Page.model_validate(self.pages[page_id])

    def sources_of_document(self, document_id: str) -> list[dict[str, Any]]:
        self.source_reads += 1
        if self.sources is not None:
            return list(self.sources)
        sources = [self._source(i) for i in sorted({u.source_id for u in self.units.values()})]
        # as the store's documentId filter; a source no test registered names no document and is listed
        return [s.dump() for s in sources if meta(s, "documentId") in ("", document_id)]


class PackManager:
    def __init__(self, pack: Path = PACK) -> None:
        self.pack = pack
        self.processors: dict[tuple[str, str], Processor] = {}
        for path in sorted((pack / "processors").glob("*.yaml")):
            text = path.read_text()
            manifest = yaml.safe_load(text)
            family, version = manifest["family"], str(manifest["version"])
            digest = "sha256:" + hashlib.sha256(f"{family}\0{version}".encode()).hexdigest()
            p = Processor(
                family=family,
                version=version,
                version_hash=digest,
                kind=manifest["kind"],
                host=manifest["host"],
                role="ROLE_" + manifest["role"].upper(),
                manifest_yaml=text,
            )
            self.processors[(family, digest)] = p
        self.extra_types: list[dict[str, Any]] = []
        self.bundles: dict[str, list[Annotation]] = {}

    def bundle(self, bundle_ref: str) -> list[Annotation]:
        return [a.model_copy(deep=True) for a in self.bundles[bundle_ref]]

    def processor(self, family: str, version_hash: str) -> Processor:
        return self.processors[(family, version_hash)]

    def hosted_processors(self) -> list[Processor]:
        return [p for p in self.processors.values() if p.host == "docproc"]

    def graph(self, plan_id: str) -> dict[str, Any]:
        return {"registryVersion": "1"}

    def registry(self, version: str) -> dict[str, Any]:
        entries = []
        model_entries = {e["key"]: e for e in yaml.safe_load((self.pack / "types/registry.yaml").read_text())["types"]}
        for path in sorted((self.pack / "types/generated").glob("*.schema.json")):
            schema = json.loads(path.read_text())
            stem = path.name.removesuffix(".schema.json")
            key = "Statement[" + stem.split(".", 1)[1] + "]" if stem.startswith("Statement.") else stem
            entries.append(
                {
                    **model_entries[key],
                    "key": key,
                    "schemaJson": json.dumps(schema),
                    "feedsRecords": model_entries[key]["feeds_records"],
                }
            )
        engine_root = WORKER / "tests/fixtures/types"
        if (engine_root / "engine.yaml").exists():
            engine = yaml.safe_load((engine_root / "engine.yaml").read_text())
            for entry in engine["types"]:
                entries.append({"key": entry["key"], "schemaJson": (engine_root / entry["schema"]).read_text()})
        return {"version": version, "types": entries + self.extra_types}
