# SPDX-License-Identifier: Apache-2.0
import pytest

from docproc.kinds.nlp_pipeline import NlpPipeline
from tests.fakes_nlp import recorded_nlp
from tests.pack import assert_golden, context


@pytest.mark.parametrize("doc", ["review-001.en.json", "review-002.cs.json", "claim-001.cs.txt", "claim-002.en.md"])
def test_mapped_outputs(doc: str) -> None:
    ctx = context(doc, "cz-mentions")
    with recorded_nlp() as nlp:
        result = NlpPipeline(nlp).run(ctx)
    assert result.annotations and all(v.payload()["rule"] != "nlp/offset-mismatch" for v in result.verdicts)
    assert_golden("nlp-pipeline", doc, result.annotations + result.verdicts, ctx.units)


def test_real_recorded_emoji_offsets_and_all_mapped_types() -> None:
    from dataclasses import replace

    from docproc.sdk.models import Unit, UnitKind
    from tests.pack import metadata

    text = "🙂 Previously the Aurora Desk Lamp probably failed on 2026-10-03."
    unit = Unit(id=3, kind=UnitKind.SECTION, content_text=text, metadata=metadata(lang="en"))
    ctx = replace(context("review-001.en.json", "cz-mentions"), units={3: unit})
    with recorded_nlp() as nlp:
        result = NlpPipeline(nlp).run(ctx)
    assert not result.verdicts
    mention = next(a for a in result.annotations if a.type == "Mention")
    span = mention.spans[0]
    assert span.start == text.index("Aurora") and unit.slice(span) == mention.payload()["surface"] == "Aurora Desk Lamp"
    assert {a.type for a in result.annotations} == {"Mention", "Date", "Modifier"}
    assert {a.payload()["kind"] for a in result.annotations if a.type == "Modifier"} == {
        "temporal_context",
        "certainty",
    }


def test_declarative_map_filter_unmapped_count_and_bad_surface() -> None:
    from tests.fakes_nlp import RecordedNlp

    replay = RecordedNlp()
    ctx = context("review-002.cs.json", "cz-mentions")
    # Force a wrong surface on the post-emoji item at cp120 (UTF-16 would be 121).
    for (rpc, _, _), response in replay.responses.items():
        if rpc == "RunPipeline":
            for layer in response.get("document", {}).get("annotationSets", []):
                for a in layer.get("annotations", []):
                    if a["type"] == "Lookup" and a.get("charStart") == 120:
                        a.setdefault("features", {})["surface"] = {"stringValue": "wrong surface"}
    ctx.manifest["engine"]["map"] = [
        {"from": "Lookup", "where": {"majorType": "item"}, "to": "Mention", "kind": "product"},
        {"from": "Token", "where": {"impossible": True}, "to": "Mention", "kind": "unused"},
    ]
    with recorded_nlp(replay) as nlp:
        result = NlpPipeline(nlp).run(ctx)
    assert len(result.annotations) == 2 and all(a.payload()["kind"] == "product" for a in result.annotations)
    assert [v.payload()["rule"] for v in result.verdicts] == ["nlp/offset-mismatch"]
    assert result.sections["nlp-calls"]["unmapped"] > 0
    assert all(set(req.include_types) == {"Lookup", "Token"} for method, req in replay.calls if method == "RunPipeline")
