# SPDX-License-Identifier: Apache-2.0
"""OL W1c·S1·T2 (b)(c) — what the SQL door's stream means (OL-C12, OL-C16).

The stream is `worker.v1.ResultBatch`es. The batches here are built the way the proto
describes the producer: Arrow IPC, the schema with the first batch and data rows after
it; a refusal is an ERROR message carrying the door's code; a capped answer carries
`top_n_applied` on the last batch.

⚑ Spec (a) — the `RunRequest` the door receives — waits for the `query.v1` stubs; see
`door_client.py` and the stage list's "Found while doing".
"""

from __future__ import annotations

import sys
from pathlib import Path
from typing import Any

import pyarrow as pa
import pytest

_GENERATED = Path(__file__).resolve().parents[1] / "generated"
if str(_GENERATED) not in sys.path:  # pragma: no cover
    sys.path.insert(0, str(_GENERATED))

from org.tatrman.common.v1 import response_message_pb2  # noqa: E402
from org.tatrman.worker.v1 import worker_pb2  # noqa: E402

from golem_py.door_client import SqlDoorPort, decode_result_batches  # noqa: E402
from golem_py.errors import DoorRejected  # noqa: E402

SCHEMA = pa.schema([("city", pa.string()), ("revenue", pa.float64())])


def _batch(cities: list[str], revenue: list[float]) -> pa.RecordBatch:
    return pa.record_batch([pa.array(cities), pa.array(revenue)], schema=SCHEMA)


def _stream(*record_batches: pa.RecordBatch) -> list[Any]:
    """Schema + first rows in batch 0, one record batch per later message, an empty
    end-of-stream batch last — the shape `ResultBatch` documents."""
    out = []
    for i, rb in enumerate(record_batches):
        payload = rb.serialize().to_pybytes()
        if i == 0:
            payload = SCHEMA.serialize().to_pybytes() + payload
        out.append(
            worker_pb2.ResultBatch(
                arrow_ipc=payload,
                batch_index=i,
                batch_row_count=rb.num_rows,
                is_first=i == 0,
                schema_fingerprint="abc123" if i == 0 else "",
            )
        )
    out.append(worker_pb2.ResultBatch(batch_index=len(out), is_last=True))
    return out


def _error(code: str, text: str) -> Any:
    return response_message_pb2.ResponseMessage(
        severity=response_message_pb2.ERROR, code=code, human_message=text
    )


def test_rows_and_column_names_come_back_decoded() -> None:
    batches = _stream(_batch(["Brno", "Praha"], [10.0, 20.5]), _batch(["Plzeň"], [3.25]))

    result = decode_result_batches(batches)

    assert result.columns == ["city", "revenue"]
    assert result.rows == [
        {"city": "Brno", "revenue": 10.0},
        {"city": "Praha", "revenue": 20.5},
        {"city": "Plzeň", "revenue": 3.25},
    ]
    assert result.truncated is False
    assert result.provenance["schema_fingerprint"] == "abc123"


def test_a_result_the_row_cap_bounded_says_so() -> None:
    batches = _stream(_batch(["Brno"], [1.0]))
    batches[-1].notices.add(code="top_n_applied", requested=0, applied=1)

    assert decode_result_batches(batches).truncated is True


def test_the_cap_warning_alone_is_enough() -> None:
    """Readers that know only `messages` get the same fact as a WARNING."""
    batches = _stream(_batch(["Brno"], [1.0]))
    batches[-1].messages.add(
        severity=response_message_pb2.WARNING, code="top_n_applied", human_message="capped"
    )

    assert decode_result_batches(batches).truncated is True


@pytest.mark.parametrize(
    ("code", "text"),
    [
        ("translator_rejected", "line 1:8 no viable alternative at input 'SELEC'"),
        ("er_read_not_allowed", "er.entity.payroll is not in the allow-list"),
    ],
)
def test_a_translator_or_validation_rejection_is_a_typed_error(code: str, text: str) -> None:
    batches = [worker_pb2.ResultBatch(is_first=True, is_last=True, messages=[_error(code, text)])]

    with pytest.raises(DoorRejected) as caught:
        decode_result_batches(batches)

    assert caught.value.code == code
    assert caught.value.message == text  # verbatim: the repair call hands it back


def test_rows_before_a_refusal_are_never_returned() -> None:
    """OL-C16: never rows from a query the door refused."""
    batches = _stream(_batch(["Brno"], [1.0]))
    batches[-1].messages.append(_error("validation_failed", "denied"))

    with pytest.raises(DoorRejected):
        decode_result_batches(batches)


def test_an_empty_answer_is_empty_not_an_error() -> None:
    result = decode_result_batches([worker_pb2.ResultBatch(is_first=True, is_last=True)])

    assert result.rows == [] and result.columns == [] and result.truncated is False


def test_the_port_is_structural() -> None:
    class Door:
        async def run(
            self, *, sql: str, world: str, caller_subject: str, allowed_entities: Any
        ) -> Any:
            raise NotImplementedError

    assert isinstance(Door(), SqlDoorPort)
