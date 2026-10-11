# SPDX-License-Identifier: Apache-2.0
"""`NlpClient` — the async gRPC client for the `nlp` front (NL-16).

The wheel ships the client so that every consumer speaks the same one. There were
going to be at least three otherwise: `nlp-mcp`, the legacy predecessor front,
and whatever wanted `RunPipeline` next — each with its own idea of a deadline and
its own proto-to-Python translation.

**Its value-add is `run_pipeline` returning a `Document`.** The rpc answers with an
`AnnotatedDocument`, which is a wire shape: nested `FeatureValue` oneofs, features
as dotted keys. A caller wants annotation sets it can iterate and features it can
compare, so the client runs `doc_from_proto` and hands back the same gatenlp
`Document` the service was holding. That is the whole reason not to let consumers
use the generated stub directly.

**Deadlines on every call, retries on none.** A deadline is this client's
business, because a caller that forgets one gets a hang rather than an error.
Retries are not: the policy depends on what the caller is doing — `nlp-mcp` has a
circuit breaker (EXAMPLES.md §5d), a batch job wants to retry for a long time, an
interactive request wants to fail now. A retry loop in here would be one more
thing to fight.
"""

from __future__ import annotations

from collections.abc import Sequence
from dataclasses import dataclass, field
from typing import Any

from ttrnlp import proto
from ttrnlp.doc.model import Document
from ttrnlp.doc.serialize import doc_from_proto

DEFAULT_TIMEOUT_S = 30.0


def _stubs():
    """`(nlp_pb2, nlp_pb2_grpc)` — the extra checked before either is touched.

    `nlp_pb2_grpc` imports `grpc` itself, so a core-only install would otherwise
    fail with `No module named 'grpc'` rather than the message naming the extra.
    The check leads because it is also the honest diagnosis: on a core-only
    install `protobuf` is missing too, so resolving the messages first would
    blame the stubs — which the wheel does ship — for a missing dependency.
    """
    proto.require_grpc()
    return proto.nlp_pb2(), proto.nlp_pb2_grpc()


@dataclass
class Diagnostic:
    """A `ResponseMessage` from the Rule-6 slot, flattened."""

    severity: str
    code: str
    message: str


@dataclass
class PhaseTrace:
    phase: str
    kind: str
    annotations_added: int
    elapsed_ms: int


@dataclass
class EngineVersion:
    op: str
    engine: str
    model: str
    model_version: str


@dataclass
class PipelineResult:
    """What `run_pipeline` returns — a Document, not a wire message."""

    document: Document
    language: str = ""
    language_confidence: float = 0.0
    used: list[EngineVersion] = field(default_factory=list)
    phases: list[PhaseTrace] = field(default_factory=list)
    messages: list[Diagnostic] = field(default_factory=list)
    trace_id: str = ""
    elapsed_ms: int = 0

    def diagnostic(self, code: str) -> Diagnostic | None:
        """The first message with `code`, if any.

        Present because the code every caller actually looks for is
        `NLS-NLP-011` — "an op was not routed in this lane" — and a caller that
        has to write the list comprehension itself tends not to look at all.
        """
        return next((m for m in self.messages if m.code == code), None)


@dataclass
class ReloadResult:
    applied: bool
    state_id: str
    diagnostics: list[Diagnostic] = field(default_factory=list)


class NlpClient:
    """Async client for `org.tatrman.nlp.v1.NlpService`.

    Args:
        target: `host:port` of the front's gRPC port (7271 by default).
        timeout_s: The deadline applied to every call unless overridden per call.

    Use as an async context manager, or call `close()`. One client holds one
    channel and is safe to share across concurrent calls — that is what a channel
    is for, and making one per request costs a TCP and HTTP/2 handshake each time.
    """

    def __init__(self, target: str, *, timeout_s: float = DEFAULT_TIMEOUT_S):
        # `_stubs()` first: it is what turns a missing `grpcio` into the message
        # naming the extra. A bare `import grpc` above it would get there first
        # and raise the ModuleNotFoundError this client exists to avoid.
        self._pb2, pb2_grpc = _stubs()
        grpc = proto.require_grpc()
        self._target = target
        self._timeout_s = timeout_s
        self._channel = grpc.aio.insecure_channel(target)
        self._stub = pb2_grpc.NlpServiceStub(self._channel)

    async def __aenter__(self) -> NlpClient:
        return self

    async def __aexit__(self, *exc_info) -> None:
        await self.close()

    async def close(self) -> None:
        await self._channel.close()

    @property
    def target(self) -> str:
        return self._target

    def _deadline(self, timeout_s: float | None) -> float:
        return self._timeout_s if timeout_s is None else timeout_s

    # ---- the pipeline surface (the reason this exists) --------------------

    async def run_pipeline(
        self,
        text: str,
        pipeline: str,
        *,
        language: str = "",
        include_sets: Sequence[str] = (),
        include_types: Sequence[str] = (),
        timeout_s: float | None = None,
    ) -> PipelineResult:
        """Run a named pipeline and get a `Document` back.

        Args:
            text: The text to analyse.
            pipeline: A pipeline name the front has configured. Unknown names
                come back as `INVALID_ARGUMENT` with the configured names in the
                message — worth showing the user verbatim.
            language: Empty asks the front to detect it.
            include_sets / include_types: Return only these annotation sets or
                types. Empty means everything, per contracts §2.2 — asking for
                nothing would be indistinguishable from a pipeline that matched
                nothing.
        """
        response = await self._stub.RunPipeline(
            self._pb2.RunPipelineRequest(
                text=text,
                language=language,
                pipeline=pipeline,
                include_sets=list(include_sets),
                include_types=list(include_types),
            ),
            timeout=self._deadline(timeout_s),
        )
        return PipelineResult(
            document=doc_from_proto(response.document),
            language=response.language,
            language_confidence=response.language_confidence,
            used=[_engine_version(ev) for ev in response.used],
            phases=[
                PhaseTrace(
                    phase=p.phase,
                    kind=p.kind,
                    annotations_added=p.annotations_added,
                    elapsed_ms=p.elapsed_ms,
                )
                for p in response.phases
            ],
            messages=[_diagnostic(m) for m in response.messages],
            trace_id=response.trace_id,
            elapsed_ms=response.elapsed_ms,
        )

    async def reload_packs(self, *, timeout_s: float | None = None) -> ReloadResult:
        """Ask the front to re-read its pack sources (NL-15).

        `applied=False` means nothing changed and the previous snapshot is still
        serving — not an error, and `state_id` is the one still in force.
        """
        response = await self._stub.ReloadPacks(
            self._pb2.ReloadPacksRequest(), timeout=self._deadline(timeout_s)
        )
        return ReloadResult(
            applied=response.applied,
            state_id=response.state_id,
            diagnostics=[
                Diagnostic(severity=d.severity, code=d.code, message=d.message)
                for d in response.diagnostics
            ],
        )

    # ---- the pre-existing rpcs -------------------------------------------

    async def analyze(
        self,
        text: str,
        *,
        language: str = "",
        ops: Sequence[str] = (),
        engine_hints: dict[str, str] | None = None,
        timeout_s: float | None = None,
    ):
        """The raw `AnalyzeResponse`, deliberately not wrapped.

        Analyze answers with token and entity lists, which are already the shape a
        caller wants; the deployed consumers (Themis, Echo, kantheon) read the
        proto directly, and wrapping it here would give them a second vocabulary
        for the same fields.
        """
        return await self._stub.Analyze(
            self._pb2.AnalyzeRequest(
                text=text,
                language=language,
                ops=[self._pb2.NlpOp.Value(op) for op in ops],
                engine_hints=engine_hints or {},
            ),
            timeout=self._deadline(timeout_s),
        )

    async def batch_lemmatize(
        self,
        texts: Sequence[str],
        *,
        language: str = "",
        timeout_s: float | None = None,
    ) -> list[list[str]]:
        """Lemmas per input text, positional to `texts` (RS-6)."""
        response = await self._stub.BatchLemmatize(
            self._pb2.BatchLemmatizeRequest(texts=list(texts), language=language),
            timeout=self._deadline(timeout_s),
        )
        return [list(result.lemmas) for result in response.results]

    async def get_status(self, *, timeout_s: float | None = None):
        """The raw `StatusResponse`: readiness, lane, capabilities, pack state."""
        return await self._stub.GetStatus(
            self._pb2.StatusRequest(), timeout=self._deadline(timeout_s)
        )


def _diagnostic(message: Any) -> Diagnostic:
    common_pb2 = proto.common_pb2()

    return Diagnostic(
        severity=common_pb2.Severity.Name(message.severity),
        code=message.code,
        message=message.human_message,
    )


def _engine_version(ev: Any) -> EngineVersion:
    return EngineVersion(
        op=ev.op, engine=ev.engine, model=ev.model, model_version=ev.model_version
    )


__all__ = [
    "DEFAULT_TIMEOUT_S",
    "Diagnostic",
    "EngineVersion",
    "NlpClient",
    "PhaseTrace",
    "PipelineResult",
    "ReloadResult",
]
