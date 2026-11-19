# SPDX-License-Identifier: Apache-2.0
"""OL W1b·S1 T1 — the service half: `stages:`, the `rules:` alias, a stage
that is not installed (OL-C7, OL-C8), and the runner's fence (OL-C10).

The wheel half (the group, the snapshot, unowned files) is in
`shared/libs/python/ttr-nlp/tests/packs/test_stages.py`.
"""

from __future__ import annotations

import ast
from pathlib import Path

import grpc
import pytest
from pydantic import ValidationError

from org.tatrman.nlp.v1 import nlp_pb2, nlp_pb2_grpc

from nlp_service.config import PipelineConfig, RuleRef, StageRef
from nlp_service.diagnostics import NLS_PACK_010
from nlp_service.packs_state import PackState
from tests.pipeline.test_run_pipeline import (
    PIPELINE,
    TEXT,
    a_config,
    boot,
    query_patterns,
    run_pipeline,
)

SRC = Path(__file__).resolve().parents[2] / "src"

AS_STAGES = PipelineConfig(
    ops=PIPELINE.ops,
    gazetteer=PIPELINE.gazetteer,
    stages=[
        StageRef(stage="rules", pack="hero-patterns", phase="name-candidates"),
        StageRef(stage="rules", pack="hero-patterns", phase="query-match"),
    ],
)


async def a_run(pipeline: PipelineConfig):
    server, port, _ = await boot(a_config(pipelines={"query-patterns": pipeline}))
    try:
        return await run_pipeline(port, text=TEXT, language="cs", pipeline="query-patterns")
    finally:
        await server.stop(None)


# ── (c) `stages:` and the `rules:` alias are one pipeline ────────────────────


def test_the_rules_alias_folds_into_stages():
    assert PIPELINE.model_dump()["stages"] == AS_STAGES.model_dump()["stages"]
    assert PIPELINE.model_dump()["rules"] == []
    assert PIPELINE.steps() == AS_STAGES.steps()


def test_a_pipeline_writing_both_is_refused():
    with pytest.raises(ValidationError, match="not both"):
        PipelineConfig(
            stages=[StageRef(stage="rules", pack="p", phase="q")],
            rules=[RuleRef(pack="p", phase="q")],
        )


@pytest.mark.asyncio
async def test_c_stages_and_the_alias_give_the_same_run_and_traces():
    alias, stages = await a_run(PIPELINE), await a_run(AS_STAGES)

    def shape(resp):
        return [(p.phase, p.kind, p.annotations_added) for p in resp.phases]

    assert shape(stages) == shape(alias)
    assert [p.kind for p in stages.phases] == ["engine", "gazetteer", "rules", "rules"]
    assert [dict(a.features) for a in query_patterns(stages)] == [
        dict(a.features) for a in query_patterns(alias)
    ]


# ── (d) a stage that is not installed ────────────────────────────────────────


def test_d_at_boot_the_pipeline_fails_validation_with_nls_pack_006():
    config = a_config(
        pipelines={"q": PipelineConfig(stages=[StageRef(stage="ner-rules", x=1)])}
    )
    packs = PackState(config)

    assert packs.ready is False
    (diagnostic,) = packs.diagnostics
    assert diagnostic.code == "NLS-PACK-006"
    assert "`ner-rules`, which is not installed in this image" in diagnostic.message


@pytest.mark.asyncio
async def test_d_on_reload_it_is_refused_and_the_previous_snapshot_serves():
    config = a_config()
    server, port, servicer = await boot(config)
    try:
        async with grpc.aio.insecure_channel(f"localhost:{port}") as channel:
            stub = nlp_pb2_grpc.NlpServiceStub(channel)
            before = (await stub.GetStatus(nlp_pb2.StatusRequest())).pack_state

            # The pipeline table the next reload validates against now names a
            # stage this image does not have.
            config.pipelines["later"] = PipelineConfig(stages=[StageRef(stage="nope")])

            refused = await stub.ReloadPacks(nlp_pb2.ReloadPacksRequest())
            assert refused.applied is False
            assert refused.state_id == before.state_id
            assert refused.diagnostics[0].code == NLS_PACK_010
            assert [d.code for d in refused.diagnostics[1:]] == ["NLS-PACK-006"]

            resp = await stub.RunPipeline(
                nlp_pb2.RunPipelineRequest(
                    text=TEXT, language="cs", pipeline="query-patterns"
                )
            )
            (pattern,) = query_patterns(resp)
            assert pattern.features["query"] == "faktury_zakaznika"

            after = (await stub.GetStatus(nlp_pb2.StatusRequest())).pack_state
            assert (after.state_id, after.packs_loaded) == (before.state_id, 1)
    finally:
        await server.stop(None)


# ── OL-C10: the runner reaches the rule engine through the group only ────────


def test_the_service_never_imports_the_rule_engine():
    offenders = []
    for path in sorted(SRC.rglob("*.py")):
        if "_generated" in path.parts:
            continue
        for node in ast.walk(ast.parse(path.read_text(encoding="utf-8"))):
            names = (
                [a.name for a in node.names]
                if isinstance(node, ast.Import)
                else [node.module or ""]
                if isinstance(node, ast.ImportFrom)
                else []
            )
            offenders += [
                f"{path.name}:{node.lineno} {n}"
                for n in names
                if n == "ttrnlp.rules" or n.startswith("ttrnlp.rules.")
            ]
    assert offenders == []
