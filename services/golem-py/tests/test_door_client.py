# SPDX-License-Identifier: Apache-2.0
"""OL W1c·S1·T2 (a)(b)(c) — the SQL door's request and what its stream means (OL-C12, OL-C16).

The stream is `worker.v1.ResultBatch`es. The batches here are built the way the proto
describes the producer: Arrow IPC, the schema with the first batch and data rows after
it; a refusal is an ERROR message carrying the door's code; a capped answer carries
`top_n_applied` on the last batch.

The live client runs against an in-process `QueryService` fake (a real `grpc.aio` server on
the loopback): what it records is what went over the wire.
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
from org.tatrman.plan.v1 import plan_pb2  # noqa: E402
from org.tatrman.query.v1 import query_pb2_grpc  # noqa: E402
from org.tatrman.translate.v1 import translator_pb2  # noqa: E402
from org.tatrman.worker.v1 import worker_pb2  # noqa: E402

from golem_py.door_client import LiveSqlDoor, SqlDoorPort, decode_result_batches  # noqa: E402
from golem_py.errors import DoorRejected, LaneServiceUnavailable  # noqa: E402
from tests.grpc_fakes import serve, unused_target  # noqa: E402

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


# ------------------------------------------------------------------ the live client (T2 a)


class FakeQuery(query_pb2_grpc.QueryServiceServicer):  # type: ignore[misc]
    def __init__(self, batches: list[Any]) -> None:
        self.requests: list[Any] = []
        self.batches = batches

    async def Run(self, request: Any, context: Any) -> Any:  # noqa: N802
        self.requests.append(request)
        for batch in self.batches:
            yield batch


def _register(fake: FakeQuery) -> Any:
    return lambda server: query_pb2_grpc.add_QueryServiceServicer_to_server(fake, server)


SQL = "SELECT s.city, SUM(w.ext_sales_price) FROM er.entity.web_sales w JOIN er.entity.store s"


async def test_the_door_request_carries_sql_er_the_world_the_subject_and_the_allow_list() -> None:
    fake = FakeQuery(_stream(_batch(["Brno"], [1.0])))
    async with serve(_register(fake)) as target:
        result = await LiveSqlDoor(target).run(
            sql=SQL,
            world="hartland_cz",
            caller_subject="user-42",
            allowed_entities=["er.entity.web_sales", "er.entity.store"],
        )

    (request,) = fake.requests
    assert request.source == SQL
    assert request.source_language == translator_pb2.SQL
    assert request.source_schema == plan_pb2.ER
    assert request.world == "hartland_cz"
    assert request.context.user_id == "user-42"  # the door applies THIS subject's row rules
    assert list(request.allowed_er_reads.objects) == ["er.entity.web_sales", "er.entity.store"]
    assert result.rows == [{"city": "Brno", "revenue": 1.0}]


async def test_an_empty_slice_still_sends_the_allow_list() -> None:
    """Present-and-empty means "no model reads"; an absent list would mean "read anything"."""
    fake = FakeQuery(_stream(_batch(["Brno"], [1.0])))
    async with serve(_register(fake)) as target:
        await LiveSqlDoor(target).run(
            sql=SQL, world="hartland_cz", caller_subject="u", allowed_entities=[]
        )

    assert fake.requests[0].HasField("allowed_er_reads")
    assert list(fake.requests[0].allowed_er_reads.objects) == []


async def test_the_doors_refusal_arrives_through_the_live_client_typed() -> None:
    refusal = worker_pb2.ResultBatch(
        is_first=True,
        is_last=True,
        messages=[_error("translator_rejected", "line 1:0 mismatched input 'SELEC'")],
    )
    async with serve(_register(FakeQuery([refusal]))) as target:
        with pytest.raises(DoorRejected) as caught:
            await LiveSqlDoor(target).run(
                sql="SELEC 1", world="hartland_cz", caller_subject="u", allowed_entities=[]
            )

    assert caught.value.code == "translator_rejected"
    assert caught.value.message == "line 1:0 mismatched input 'SELEC'"


async def test_an_unreachable_door_is_lane_unavailable() -> None:
    with pytest.raises(LaneServiceUnavailable) as caught:
        await LiveSqlDoor(unused_target(), timeout_s=1.0).run(
            sql=SQL, world="hartland_cz", caller_subject="u", allowed_entities=[]
        )

    assert caught.value.service == "query.v1"


def test_the_live_door_satisfies_the_port() -> None:
    assert isinstance(LiveSqlDoor("127.0.0.1:1"), SqlDoorPort)
