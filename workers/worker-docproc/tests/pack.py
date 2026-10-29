# SPDX-License-Identifier: Apache-2.0
"""PX section trees (split's future output), test work and reviewed golden comparison."""

from __future__ import annotations

import hashlib
import json
from typing import Any

from docproc.runtime.registry import RunContext
from docproc.sdk.models import Annotation, MetadataValue, Processor, StringList, Unit, UnitKind, WorkItem
from tests.conftest import CONTRACTS, WORKER
from tests.fakes import PACK, PackManager

DOCS = tuple(
    p.name.removesuffix(".expected.json") for p in sorted((PACK / "golden/mini-gold-v1").glob("*.expected.json"))
)


def expected(doc: str) -> dict[str, Any]:
    result: dict[str, Any] = json.loads((PACK / "golden/mini-gold-v1" / f"{doc}.expected.json").read_text())
    return result


def metadata(**values: str | list[str]) -> dict[str, MetadataValue]:
    return {
        k: MetadataValue(single=v) if isinstance(v, str) else MetadataValue(list=StringList(values=v))
        for k, v in values.items()
    }


def units_of(doc: str, source_id: int = 100) -> list[Unit]:
    reg = json.loads((PACK / "registrations" / (doc.rsplit(".", 1)[0] + ".json")).read_text())
    sections = expected(doc)["sections"]
    root = Unit(
        id=source_id * 100,
        source_id=source_id,
        kind=UnitKind.DOCUMENT,
        content_text="\n\n".join(s["text"] for s in sections),
        metadata=metadata(
            title=doc,
            lang=reg["document"]["lang"],
            documentId=reg["document"]["documentId"],
            version=reg["document"]["version"],
            recordedAt=reg["document"]["recordedAt"],
        ),
    )
    out = [root]
    reviews = json.loads((PACK / "documents" / doc).read_text()) if ".json" in doc else []
    for s in sections:
        text = s["text"]
        meta = metadata(
            title=s.get("title", s.get("sectionPath", [""])[-1]),
            lang=reg["document"]["lang"],
            sectionPath=s.get("sectionPath", [s.get("title", "")]),
            headings=[],
            pageBreaks=[],
            tables=[],
        )
        fixture_meta = json.loads((WORKER / "tests/fixtures/unit-metadata.json").read_text())[doc][str(s["idx"])]
        meta.update(metadata(**fixture_meta))
        if reviews:
            meta["field"] = MetadataValue(single="body")
        out.append(
            Unit(
                id=root.id + 1 + s["idx"],
                source_id=source_id,
                idx=s["idx"],
                kind=UnitKind.SECTION,
                parent_id=root.id,
                content_text=text,
                content_hash=s["contentHash"],
                metadata=meta,
            )
        )
    root.content_hash = "sha256:" + hashlib.sha256(root.content_text.encode()).hexdigest()
    return out


def context(
    doc: str, family: str, *, manager: PackManager | None = None, units: list[Unit] | None = None
) -> RunContext:
    manager = manager or PackManager()
    p = next(p for p in manager.processors.values() if p.family == family)
    import yaml

    manifest = yaml.safe_load(p.manifest_yaml)
    units = units or units_of(doc)
    item = WorkItem.strict((CONTRACTS / "workItem.json").read_text()).model_copy(
        update={
            "family": p.family,
            "kind": p.kind,
            "version_hash": p.version_hash,
            "unit_ids": [u.id for u in units[1:]],
            "document_id": units[0].metadata["documentId"].single,
            "version": "1",
            "step_id": f"{family}-{doc}",
            "run_id": f"{family}-{doc}/1",
            "bundle_ref": f"bundle:{doc}",
        }
    )
    return RunContext(item, p, manifest, {u.id: u for u in units[1:]}, [])


def new_context(ctx: RunContext, *, kind: str, engine: dict[str, Any]) -> RunContext:
    """Test-only manifest for kinds/strategies not named by this pack."""
    from dataclasses import replace

    import yaml

    manifest = {**ctx.manifest, "kind": kind, "engine": engine}
    p = Processor.model_validate({**ctx.processor.dump(), "kind": kind, "manifestYaml": yaml.safe_dump(manifest)})
    return replace(ctx, manifest=manifest, processor=p, item=ctx.item.model_copy(update={"kind": kind}))


def golden_rows(doc: str, annotations: list[Annotation], units: dict[int, Unit]) -> list[dict[str, Any]]:
    stem = doc.rsplit(".", 1)[0]
    refs = {u.id: f"g:{stem}:s{u.idx}" if u.kind != UnitKind.DOCUMENT else f"g:{stem}:document" for u in units.values()}
    external: dict[int, str] = {}

    def normalized(value: Any) -> Any:
        if isinstance(value, dict):
            return {
                key: (refs.get(v) or external.setdefault(v, f"g:{stem}:previous:{len(external)}"))
                if key in ("unitId", "parentUnitId", "previousUnitId") and isinstance(v, int)
                else normalized(v)
                for key, v in value.items()
            }
        if isinstance(value, list):
            return [normalized(v) for v in value]
        return value

    return [
        {
            "id": f"g:{stem}:{n}",
            "unit": refs[a.unit_id],
            "type": a.type,
            "spans": [{"unit": refs[s.unit_id], "start": s.start, "end": s.end} for s in a.spans],
            "payload": normalized(a.payload()),
        }
        for n, a in enumerate(annotations)
    ]


def assert_golden(kind: str, doc: str, annotations: list[Annotation], units: dict[int, Unit]) -> None:
    actual = golden_rows(doc, annotations, units)
    path = WORKER / "tests/goldens" / kind / f"{doc}.json"
    want = json.loads(path.read_text())
    for n in range(max(len(want), len(actual))):
        left = want[n] if n < len(want) else None
        right = actual[n] if n < len(actual) else None
        if left != right:
            a = annotations[n] if n < len(annotations) else None
            slices = [units[s.unit_id].slice(s) for s in a.spans] if a else []
            raise AssertionError(f"{path.name} output #{n}\nexpected={left}\nactual={right}\nsliced text={slices!r}")
    from docproc.runtime.registry import TypeRegistryClient

    registry = TypeRegistryClient(PackManager())
    registry.load("1")
    for a in annotations:
        registry.schema_for(a.type).validate(a.payload())


def bundle_of(doc: str, units: list[Unit]) -> list[Annotation]:
    reg = json.loads((PACK / "registrations" / (doc.rsplit(".", 1)[0] + ".json")).read_text())
    facts = [Annotation.model_validate(a) for a in reg["initialBundle"]]
    # JSON connector spans refer to the original rendering. split re-anchors by
    # the stable reviewId; the fixture fake performs that same projection using
    # the recorded body's exact text, without trusting the extraction golden.
    rows = json.loads((PACK / "documents" / doc).read_text())
    by_id = {row["reviewId"]: row for row in rows}
    for fact in facts:
        row = by_id[fact.payload()["reviewId"]]
        body = row["body"]
        unit = next(u for u in units[1:] if body in u.content_text)
        for span in fact.spans:
            assert span.end - span.start == len(body)
            start = unit.content_text.index(body)
            span.unit_id, span.start, span.end = unit.id, start, start + len(body)
        fact.unit_id = unit.id
    return facts
