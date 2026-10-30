# SPDX-License-Identifier: Apache-2.0
import json
from dataclasses import replace

import pytest

from docproc.kinds.lemmatize import Lemmatize
from docproc.sdk.models import RunVisibility
from tests.conftest import WORKER
from tests.fakes import FakeWarehouse
from tests.fakes_nlp import recorded_nlp
from tests.pack import context, units_of


@pytest.mark.parametrize("doc", ["sheet-002.cs.html", "review-002.cs.json"])
def test_lemma_plane(doc: str) -> None:
    ctx = context(doc, "lemmatize")
    ctx = replace(ctx, item=ctx.item.model_copy(update={"visibility": RunVisibility.ACTIVE}))
    warehouse = FakeWarehouse(units_of(doc))
    with recorded_nlp() as nlp:
        result = Lemmatize(nlp, warehouse).run(ctx)
    assert result.annotations == [] and result.usage.nlp_calls == 1
    assert [v["contentLemmas"] for v in warehouse.lemmas.values()] == json.loads(
        (WORKER / "tests/goldens/lemmatize" / f"{doc}.json").read_text()
    )
    assert result.sections["nlp-calls"]["used"]


def test_batch_cardinality_identity_and_leaf_document() -> None:
    from dataclasses import replace

    from docproc.sdk.models import UnitKind
    from tests.fakes_nlp import RecordedNlp

    doc = "review-002.cs.json"
    ctx = context(doc, "lemmatize")
    ctx = replace(ctx, item=ctx.item.model_copy(update={"visibility": RunVisibility.ACTIVE}))
    ctx.manifest["engine"]["batch"] = 1
    replay = RecordedNlp()
    warehouse = FakeWarehouse(units_of(doc))
    with recorded_nlp(replay) as nlp:
        result = Lemmatize(nlp, warehouse).run(ctx)
    assert result.usage.nlp_calls == 2 and len(replay.calls) == 2
    assert all(len(call.texts) == 1 for _, call in replay.calls)
    assert all("@" in v["lemmatizer"] for v in warehouse.lemmas.values())
    unit = next(iter(ctx.units.values())).model_copy(update={"kind": UnitKind.DOCUMENT})
    leaf = replace(ctx, units={unit.id: unit})
    with recorded_nlp() as nlp:
        Lemmatize(nlp, warehouse).run(leaf)
    assert unit.id in warehouse.lemmas
    for invalid in ("identity", "cardinality"):
        broken = RecordedNlp()
        for (method, _, _), response in broken.responses.items():
            if method == "BatchLemmatize":
                response["used" if invalid == "identity" else "results"] = []
        untouched = FakeWarehouse(units_of(doc))
        with (
            recorded_nlp(broken) as nlp,
            pytest.raises(
                ValueError, match="nlp/no-model-identity" if invalid == "identity" else "nlp/lemma-cardinality"
            ),
        ):
            Lemmatize(nlp, untouched).run(ctx)
        assert not untouched.lemmas


def test_grpc_calls_propagate_current_trace() -> None:
    from opentelemetry import trace

    from tests.fakes_nlp import RecordedNlp

    replay = RecordedNlp()
    ctx = context("review-002.cs.json", "lemmatize")
    span = trace.NonRecordingSpan(
        trace.SpanContext(trace_id=1, span_id=2, is_remote=False, trace_flags=trace.TraceFlags(1))
    )
    with trace.use_span(span), recorded_nlp(replay) as nlp:
        Lemmatize(nlp, FakeWarehouse(units_of("review-002.cs.json"))).run(ctx)
    assert replay.metadata[0]["traceparent"] == "00-00000000000000000000000000000001-0000000000000002-01"


@pytest.mark.parametrize("visibility", [RunVisibility.CANDIDATE, RunVisibility.SHADOW])
def test_nonactive_lemmas_are_hashes_only(visibility: RunVisibility) -> None:
    import hashlib

    ctx = context("review-002.cs.json", "lemmatize")
    ctx = replace(ctx, item=ctx.item.model_copy(update={"visibility": visibility}))
    warehouse = FakeWarehouse(units_of("review-002.cs.json"))
    with recorded_nlp() as nlp:
        result = Lemmatize(nlp, warehouse).run(ctx)
    assert warehouse.lemmas == {}
    rows = result.sections["outputs"]["lemmas"]
    assert len(rows) == 2 and all(set(r) == {"unitId", "lang", "lemmatizer", "lemmasSha256"} for r in rows)
    active = replace(ctx, item=ctx.item.model_copy(update={"visibility": RunVisibility.ACTIVE}))
    with recorded_nlp() as nlp:
        Lemmatize(nlp, warehouse).run(active)
    assert [r["lemmasSha256"] for r in rows] == [
        "sha256:" + hashlib.sha256(r["contentLemmas"].encode()).hexdigest() for r in warehouse.lemmas.values()
    ]
