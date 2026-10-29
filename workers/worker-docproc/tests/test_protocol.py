# SPDX-License-Identifier: Apache-2.0
import json

from docproc.runtime.registry import RunContext
from docproc.sdk.models import Completion, CompletionStatus, Unit, WorkItem, canonical
from docproc.sdk.protocol import ProtocolWriter
from tests.conftest import CONTRACTS
from tests.fakes import PackManager


def test_canonical_noop_summary_and_errors() -> None:
    item = WorkItem.strict((CONTRACTS / "workItem.json").read_text())
    processor = next(iter(PackManager().processors.values()))
    context = RunContext(
        item, processor, {}, {101: Unit(id=101, content_text="private source text", content_hash="sha256:u")}, []
    )
    completion = Completion(
        run_id=item.run_id, step_id=item.step_id, status=CompletionStatus.SUCCEEDED, annotation_ids=[5001]
    )
    document = ProtocolWriter().document(context, completion)
    text = canonical(document)
    assert text == (CONTRACTS.parent / "protocol/noop.json").read_text()
    assert "private source text" not in text and "payloadJson" not in text
    assert document["scope"] == {"turnId": item.run_id} and len(document["turns"]) == 1
    completion.status, completion.error = CompletionStatus.FAILED, "timeout"
    document = ProtocolWriter().document(context, completion)
    assert document["turns"][0]["sections"][-1]["errors"]["items"][0]["message"] == "timeout"
    assert json.loads(canonical(document)) == document


def test_generated_clients_import() -> None:
    from docproc._generated.org.tatrman.fuzzy.v1.fuzzy_pb2_grpc import FuzzyServiceStub
    from docproc._generated.org.tatrman.nlp.v1.nlp_pb2_grpc import NlpServiceStub

    assert callable(FuzzyServiceStub) and callable(NlpServiceStub)


def test_error_summary_does_not_copy_content() -> None:
    item = WorkItem.strict((CONTRACTS / "workItem.json").read_text())
    context = RunContext(item, next(iter(PackManager().processors.values())), {}, {}, [])
    completion = Completion(
        run_id=item.run_id,
        step_id=item.step_id,
        status=CompletionStatus.FAILED,
        error="ValueError: private source text",
    )
    assert "private source text" not in canonical(ProtocolWriter().document(context, completion))

    completion.error = "private source text"
    assert "private source text" not in canonical(ProtocolWriter().document(context, completion))
