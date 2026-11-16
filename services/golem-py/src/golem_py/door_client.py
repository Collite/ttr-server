# SPDX-License-Identifier: Apache-2.0
"""The SQL door — `query.v1 Run` with SQL over `ER` (OL-C12, W1c·S1·T4).

The LLM lane writes one `SELECT` over `er.*` names; the door translates, validates,
applies the CALLER's row rules and runs it. So the answer is governed whoever wrote the
query: the subject that travels here is the user's, never the Golem's.

What a request carries (OL-C12): the SQL as `source`, `source_schema = ER`, the
configured world, the caller's subject, and the ER read allow-list (`allowed_er_reads`)
— the model slice's entity names (OL-C14). The door re-checks that list against the
validated plan on every call, cache hits included, so a query that reads outside the
slice is refused by the door and not merely by our own checks.

What comes back is a stream of `worker.v1.ResultBatch`: Arrow IPC bytes, the schema
with the first batch and data after it (the proto's own description of the stream). A
refusal travels as an ERROR batch — a `ResponseMessage` with the door's code — and a
result the validator's row cap bounded says so with a `top_n_applied` notice on the last
batch. `decode_result_batches` is the whole of that reading, and it is pure, so the
specs drive it without a channel.

⚑ **The live gRPC client is not built in this stage.** `query.proto` imports
`org/tatrman/translate/v1/translator.proto` (the `Language` enum `RunRequest` needs for
`source_language = SQL`), and no published `ttr-plan-proto` wheel carries that file —
not 0.8.4 (master's Python pin) and not the newest (0.12.11); master's JVM pin (0.14.1)
has no wheel at all. OL-C12 allows `plan.v1` stubs from the wheel only, so generating
`query.v1` is a stop condition of W1c·S1, recorded under the stage list's "Found while
doing". The port, the errors and the result decoding are here; `LiveSqlDoor` follows
once the planner rules on where `translator.proto` comes from.
"""

from __future__ import annotations

import sys
from collections.abc import Iterable, Sequence
from pathlib import Path
from typing import Any, Protocol, runtime_checkable

_GENERATED = Path(__file__).resolve().parents[2] / "generated"
if str(_GENERATED) not in sys.path:  # pragma: no cover - import bootstrap
    sys.path.insert(0, str(_GENERATED))

import pyarrow as pa  # noqa: E402
from org.tatrman.common.v1 import response_message_pb2  # noqa: E402

from golem_py.errors import DoorRejected  # noqa: E402
from golem_py.query_client import QueryResult  # noqa: E402

# The validator's row cap bounded the answer (BI C-10): a full page is "the estate's
# ceiling", not "the end of the data".
TOP_N_APPLIED = "top_n_applied"


@runtime_checkable
class SqlDoorPort(Protocol):
    """`query.v1 Run`, SQL over ER (OL-C12). Raises `DoorRejected` for a translator or
    validation refusal and `LaneServiceUnavailable("query.v1", …)` when the door cannot be
    reached — two different refusals of the lane (OL-C16), so they stay two types."""

    async def run(
        self,
        *,
        sql: str,
        world: str,
        caller_subject: str,
        allowed_entities: Sequence[str],
    ) -> QueryResult: ...


def _first_error(batches: Sequence[Any]) -> Any | None:
    for batch in batches:
        for message in batch.messages:
            if message.severity == response_message_pb2.ERROR:
                return message
    return None


def _truncated(batches: Sequence[Any]) -> bool:
    """`top_n_applied` arrives twice by contract — a typed `Notice` and a WARNING message
    for readers that know only messages. Either one is enough."""
    for batch in batches:
        if any(n.code == TOP_N_APPLIED for n in batch.notices):
            return True
        if any(m.code == TOP_N_APPLIED for m in batch.messages):
            return True
    return False


def decode_result_batches(batches: Iterable[Any]) -> QueryResult:
    """A `Run` stream → `QueryResult`: column names, rows as dicts, `truncated`.

    An ERROR message anywhere in the stream is the door's refusal and raises
    `DoorRejected` with its code and text; rows from a refused query are never returned
    (OL-C16), even if a batch carried some before the error arrived.
    """
    received = list(batches)
    error = _first_error(received)
    if error is not None:
        raise DoorRejected(error.code, error.human_message)

    payload = b"".join(batch.arrow_ipc for batch in received)
    provenance: dict[str, str] = {}
    fingerprint = next((b.schema_fingerprint for b in received if b.schema_fingerprint), "")
    if fingerprint:
        provenance["schema_fingerprint"] = fingerprint
    if not payload:
        return QueryResult(provenance=provenance, truncated=_truncated(received))

    table = pa.ipc.open_stream(pa.py_buffer(payload)).read_all()
    return QueryResult(
        rows=table.to_pylist(),
        columns=list(table.column_names),
        provenance=provenance,
        truncated=_truncated(received),
    )
