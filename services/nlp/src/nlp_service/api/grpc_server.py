# SPDX-License-Identifier: Apache-2.0
"""gRPC service binding for `org.tatrman.nlp.v1.NlpService` (RG-P1.S1).

gRPC is the service contract; the FastAPI app is a dev/health mirror. This
servicer translates proto ⇄ the orchestrator and stamps the S-1 `used[]` echo
on every response. Async (grpc.aio) so it co-hosts with uvicorn on one loop.

NLS-P3.2 adds `RunPipeline`, `ReloadPacks`, the `GetStatus` additions and the
`ReportToken` stub. The status codes are chosen to say whose problem it is:

    unknown pipeline      INVALID_ARGUMENT     the request named something that
                                               does not exist — and the message
                                               lists what does
    packs failed to load  FAILED_PRECONDITION  the request is fine; the server is
                                               not ready for this rpc. Analyze
                                               keeps working, because it needs no
                                               packs.

An op the active lane cannot route is neither: it is a WARNING on the Rule-6
message slot (`NLS-NLP-011`) and the request completes (NL-14).

NLS-P9.1 (LM) makes the servicer own a second snapshot — the Czech morph
artifact — and wires `ReportToken` to a real sink. The status codes extend the
same rule:

    morph snapshot failed  FAILED_PRECONDITION  and ONLY for the `morph: true`
                           pipelines; every other pipeline, Analyze and
                           BatchLemmatize are untouched
    malformed ReportToken  INVALID_ARGUMENT     no world, no token, or a verdict
                           that is neither of the two the contract defines
    world not served here  accepted=false       plus LM-MORPH-007. A policy
                           answer, not an error — see `ReportToken` below

`ReloadPacks` now re-reads both snapshots through `reload.py`, which validates
both before committing either: a front serving a new pack tree against an old
lexicon is a combination nobody authored and nobody can name from the response.
"""

from __future__ import annotations

import asyncio
import logging
from typing import Set

import grpc

from org.tatrman.common.v1 import response_message_pb2 as common_pb2
from org.tatrman.nlp.v1 import nlp_pb2, nlp_pb2_grpc
from ttrnlp.doc.serialize import doc_to_proto
from ttrnlp.morph import VERDICT_MISS

from nlp_service.config import AppConfig, load_config
from nlp_service.diagnostics import LM_MORPH_007, NLS_NLP_011, message
from nlp_service.engines import EngineRegistry
from nlp_service.engines.base import EngineVersion, NlpOp
from nlp_service.morph_queue import VERDICTS, QueueSink, build_sink, run_retention
from nlp_service.morph_state import MorphSnapshot
from nlp_service.packs_state import PackState
from nlp_service.pipeline.orchestrator import AnalyzeResponse, Orchestrator
from nlp_service.pipeline.runner import PipelineRunner, UnknownPipelineError
from nlp_service.reload import reload_all

logger = logging.getLogger(__name__)

_SEVERITY = {
    "INFO": common_pb2.INFO,
    "WARNING": common_pb2.WARNING,
    "ERROR": common_pb2.ERROR,
}
_MODE = {nlp_pb2.MODE_UNSPECIFIED: "NORMAL", nlp_pb2.NORMAL: "NORMAL", nlp_pb2.COMPARE: "COMPARE"}


def _op_from_proto(op_int: int) -> NlpOp | None:
    name = nlp_pb2.NlpOp.Name(op_int)
    if name == "NLP_OP_UNSPECIFIED":
        return None
    try:
        return NlpOp(name)
    except ValueError:
        return None


def _op_to_proto(name: str) -> int:
    return nlp_pb2.NlpOp.Value(name)


class NlpServicer(nlp_pb2_grpc.NlpServiceServicer):
    def __init__(
        self,
        config: AppConfig | None = None,
        registry: EngineRegistry | None = None,
        packs: PackState | None = None,
        *,
        morph: MorphSnapshot | None = None,
        queue: QueueSink | None = None,
    ):
        self._config = config or load_config()
        self._registry = registry or EngineRegistry(self._config)
        self._orchestrator = Orchestrator(self._config, self._registry)
        # Injectable so tests can hand in a state built over a fixture tree
        # without reaching through the config for a directory.
        self._packs = packs if packs is not None else PackState(self._config)
        self._morph = morph if morph is not None else MorphSnapshot(self._config)
        self._queue = queue if queue is not None else build_sink(self._config.morph)
        # The boot sweep, once and synchronously: a front that was down for
        # longer than a world's retention window must not serve a stale queue
        # for a day before the daily timer's first tick (LM contracts §6).
        self._queue.sweep()
        self._runner = PipelineRunner(
            self._config,
            self._registry,
            self._orchestrator,
            morph=self._morph,
            queue=self._queue,
        )

    @property
    def queue(self) -> QueueSink:
        """The morph queue sink, so a caller that starts the service can also
        start (and stop) its retention timer."""
        return self._queue

    @property
    def morph(self) -> MorphSnapshot:
        return self._morph

    # ---- Analyze ----------------------------------------------------------

    async def Analyze(self, request, context):  # noqa: N802 (gRPC method name)
        ops: Set[NlpOp] = set()
        for op_int in request.ops:
            op = _op_from_proto(op_int)
            if op is not None:
                ops.add(op)
        if not ops:
            await context.abort(grpc.StatusCode.INVALID_ARGUMENT, "at least one op required")

        # On a worker thread: see `analyze` in `routes.py`. This process serves
        # gRPC and REST from one loop, so a backend wait here stalls both.
        result = await asyncio.to_thread(
            self._orchestrator.analyze,
            text=request.text,
            language=request.language,
            ops=ops,
            mode=_MODE.get(request.mode, "NORMAL"),
            engine_hints=dict(request.engine_hints),
        )
        # T7 (the NL-14 tail): the same degrade the pipeline reports, on the rpc
        # that predates it. Shape untouched — one more entry on messages[99],
        # which is what the Rule-6 slot is for.
        language = result.language or self._config.default_language
        for op in self._runner.unrouted_ops(language, sorted(ops, key=lambda o: o.value)):
            result.messages.append(message(NLS_NLP_011, f"{language}/{op.value}"))
        return self._to_analyze_proto(result)

    def _to_analyze_proto(self, r: AnalyzeResponse) -> "nlp_pb2.AnalyzeResponse":
        resp = nlp_pb2.AnalyzeResponse(
            language=r.language,
            detected_language=r.language,
            language_confidence=r.language_confidence,
            engine_used=r.engine_used,
            trace_id=r.trace_id,
            elapsed_ms=r.elapsed_ms,
        )
        for t in r.tokens:
            resp.tokens.append(
                nlp_pb2.Token(
                    text=t.text, char_start=t.char_start, char_end=t.char_end,
                    lemma=t.lemma, upos=t.upos, xpos=t.xpos, feats=t.feats,
                    dep_head=t.dep_head, dep_relation=t.dep_relation,
                )
            )
        for s in r.sentences:
            resp.sentences.append(nlp_pb2.Span(char_start=s[0], char_end=s[1]))
        for p in r.paragraphs:
            resp.paragraphs.append(nlp_pb2.Span(char_start=p[0], char_end=p[1]))
        for e in r.entities:
            resp.entities.append(
                nlp_pb2.NerEntity(
                    text=e.text, label=e.label, char_start=e.char_start, char_end=e.char_end,
                    normalized_value=e.normalized_value, source_engine=e.source_engine,
                )
            )
        for ev in r.used:
            resp.used.append(_engine_version_proto(ev))
        _append_messages(resp, r.messages)
        return resp

    # ---- BatchLemmatize ---------------------------------------------------

    async def BatchLemmatize(self, request, context):  # noqa: N802
        outcome = await asyncio.to_thread(
            self._orchestrator.batch_lemmatize, list(request.texts), request.language
        )
        resp = nlp_pb2.BatchLemmatizeResponse()
        for lemmas in outcome.results:
            resp.results.append(nlp_pb2.LemmaList(lemmas=lemmas))
        for ev in outcome.used:
            resp.used.append(_engine_version_proto(ev))
        return resp

    # ---- RunPipeline (NLS-P3.2) -------------------------------------------

    async def RunPipeline(self, request, context):  # noqa: N802
        if not request.pipeline:
            await context.abort(
                grpc.StatusCode.INVALID_ARGUMENT,
                "pipeline is required — name one of: "
                + (", ".join(self._runner.pipeline_names()) or "none configured"),
            )

        state = self._packs.state
        if state is None:
            # The request is fine; the server is not ready for it. The pack
            # diagnostics are on GetStatus.pack_state rather than crammed into
            # this message — there can be dozens, and one of them is rarely the
            # whole story.
            await context.abort(
                grpc.StatusCode.FAILED_PRECONDITION,
                "rule packs failed to load — see GetStatus.pack_state for the "
                f"diagnostics ({len(self._packs.diagnostics)} of them)",
            )

        spec = self._config.pipelines.get(request.pipeline)
        if spec is not None and not self._runner.morph_ready(spec):
            # Same posture, same code, other snapshot: a `morph: true` pipeline
            # with no lexicon loaded cannot run its own front, and running it on
            # the engine path instead would answer — differently, from a
            # different vocabulary, with nothing on the wire to say so.
            await context.abort(
                grpc.StatusCode.FAILED_PRECONDITION,
                f"pipeline {request.pipeline!r} needs the morph snapshot and it "
                "failed to load — see GetStatus.morph_state for the diagnostics "
                f"({len(self._morph.diagnostics)} of them)",
            )

        # Stays on the loop, unlike Analyze and BatchLemmatize. A `morph: true` run
        # reports its misses into the queue sink, which is mutated from the loop
        # thread only (`SpoolSink.flush`, `ReportToken`); running it on a worker
        # would race them. The rate limit cannot stall it any more — a call over
        # the budget is refused at once (`RG-NLP-004`) — so what remains on the
        # loop is the backends' own response time.
        try:
            result = self._runner.run(
                request.text,
                request.pipeline,
                state,
                language=request.language,
            )
        except UnknownPipelineError as exc:
            await context.abort(grpc.StatusCode.INVALID_ARGUMENT, str(exc))

        # `Runner.run` scheduled the spool write rather than performing it (see
        # `SpoolSink.flush`) — awaited here, so this request still does not
        # answer until its misses are on disk, while the loop stays free to
        # serve every other request for the duration of the write instead of
        # stopping dead in `os.replace`.
        await self._queue.drain()

        resp = nlp_pb2.RunPipelineResponse(
            document=doc_to_proto(
                result.document,
                include_sets=list(request.include_sets),
                include_types=list(request.include_types),
            ),
            language=result.language,
            language_confidence=result.language_confidence,
            trace_id=result.trace_id,
            elapsed_ms=result.elapsed_ms,
        )
        for ev in result.used:
            resp.used.append(_engine_version_proto(ev))
        for trace in result.traces:
            resp.phases.append(
                nlp_pb2.PhaseTrace(
                    phase=trace.phase,
                    kind=trace.kind,
                    annotations_added=trace.annotations_added,
                    elapsed_ms=trace.elapsed_ms,
                )
            )
        _append_messages(resp, result.messages)
        return resp

    # ---- ReloadPacks (NLS-P3.2, NL-15) ------------------------------------

    async def ReloadPacks(self, request, context):  # noqa: N802
        """Both snapshots, or neither (`reload.py`)."""
        applied, state_id, diagnostics = await reload_all(self._packs, self._morph)
        resp = nlp_pb2.ReloadPacksResponse(applied=applied, state_id=state_id)
        for d in diagnostics:
            resp.diagnostics.append(_pack_diagnostic_proto(d))
        return resp

    # ---- ReportToken (LM, NLS-P9.1) ---------------------------------------

    async def ReportToken(self, request, context):  # noqa: N802
        """A token the lexicon missed, or answered wrongly (contracts §6).

        Three outcomes, and the codes distinguish whose problem each one is:

        `INVALID_ARGUMENT`
            The request is malformed — no `world`, no `token` (the proto marks
            both REQUIRED), or a `verdict` that is neither of the two the
            contract defines. A caller that sent nonsense is told so rather than
            given a `false` it would read as "the sink is off".
        `accepted=false` + `LM-MORPH-007`
            The sink is disabled, or this front does not serve that world. Not
            an error: `accepted` is precisely the field that says so, and a
            world this deployment does not serve is a caller pointed at the
            wrong front — spooling it anyway would put one world's tokens in
            another world's file, which is the one thing S-4 forbids outright.
        `accepted=true`
            Spooled, deduped on (world, token), and flushed. One event, one
            write: this is not the hot path.
        """
        if not request.world or not request.token:
            await context.abort(
                grpc.StatusCode.INVALID_ARGUMENT,
                "world and token are both required — a queue entry with neither "
                "a world nor a surface form is not routable to any lexicon",
            )
        verdict = request.verdict or VERDICT_MISS
        if verdict not in VERDICTS:
            await context.abort(
                grpc.StatusCode.INVALID_ARGUMENT,
                f"verdict {request.verdict!r} is not one of: "
                + ", ".join(sorted(VERDICTS)),
            )

        accepted = self._queue.report(
            request.world,
            request.token,
            verdict,
            context_span=request.context_span,
        )
        if not accepted:
            logger.info(
                "%s: ReportToken(world=%s, verdict=%s) refused — %s",
                LM_MORPH_007,
                request.world,
                verdict,
                "the queue sink is not configured"
                if not self._queue.enabled
                else "this front does not serve that world",
            )
            return nlp_pb2.ReportTokenResponse(accepted=False)

        self._queue.flush()
        # `accepted=true` is a promise that the report survives this process.
        # Making it before the write has landed would be exactly the promise the
        # sink's whole design is built to keep.
        await self._queue.drain()
        return nlp_pb2.ReportTokenResponse(accepted=True)

    # ---- GetStatus --------------------------------------------------------

    async def GetStatus(self, request, context):  # noqa: N802
        # `ready` is BOTH halves. The registry answers for the engines; the pack
        # snapshot answers for RunPipeline, and a boot with a broken pack tree
        # leaves it None while every RunPipeline aborts FAILED_PRECONDITION. On
        # the registry alone that front reads healthy — which is what a consumer
        # gating traffic on `ready` acts on, and what the README, `config.yaml`,
        # `k8s/values.yaml` and `packs_state.py` all already say happens.
        #
        # Analyze needs no packs and keeps working either way; `ready` is not a
        # liveness signal, and the diagnostics for the pack half are on
        # `pack_state` below rather than inferred from this bit.
        resp = nlp_pb2.StatusResponse(
            ready=self._registry.is_ready()
            and self._packs.ready
            and self._morph.ready,
            lane=self._config.lane,
        )
        # `served_capabilities`, not the full matrix: an op nothing in this lane
        # can produce has no row (contracts §2.4). Its absence is the NL-14
        # degrade — see the registry's docstring. The morph rows are appended
        # beside them rather than merged into them — see
        # `MorphSnapshot.capabilities` for why both are true at once.
        for row in [
            *self._registry.served_capabilities(),
            *self._morph.capabilities(),
        ]:
            resp.capabilities.append(
                nlp_pb2.Capability(
                    language=row["language"],
                    op=_op_to_proto(row["op"].value),
                    engine=row["engine"],
                    model_version=row["model_version"],
                    tier=_tier_to_proto(row["tier"]),
                )
            )
        for name in self._runner.pipeline_names():
            resp.pipelines.append(
                nlp_pb2.PipelineInfo(
                    name=name, steps=self._config.pipelines[name].steps()
                )
            )
        state = self._packs.state
        resp.pack_state.CopyFrom(
            nlp_pb2.PackState(
                state_id=state.state_id if state else "",
                packs_loaded=state.packs_loaded if state else 0,
                lists_loaded=state.lists_loaded if state else 0,
                diagnostics=[
                    _pack_diagnostic_proto(d) for d in self._packs.diagnostics
                ],
            )
        )
        resp.morph_state.CopyFrom(_morph_state_proto(self._morph))
        return resp


def _engine_version_proto(ev: EngineVersion) -> "nlp_pb2.EngineVersion":
    return nlp_pb2.EngineVersion(
        op=ev.op, engine=ev.engine, model=ev.model, model_version=ev.model_version
    )


def _pack_diagnostic_proto(d) -> "nlp_pb2.PackDiagnostic":
    """The wheel's `Diagnostic` on the wire — five fields, same names, no mapping
    table. That identity is the point (contracts §2.3): a pack author reading a
    `ttr-nlp validate` error and an operator reading a reload response see the
    same text."""
    return nlp_pb2.PackDiagnostic(
        source=d.source, pack=d.pack, severity=d.severity, code=d.code, message=d.message
    )


def _morph_state_proto(morph: MorphSnapshot) -> "nlp_pb2.MorphState":
    """`MorphStats` on the wire (contracts §5), diagnostics included.

    An empty block means no snapshot is configured; a block that is empty
    *except* for diagnostics means one is configured and did not load. The
    difference is the whole reason the diagnostics are here rather than folded
    into `pack_state`: an operator seeing `ready=false` needs to know which of
    the two artifacts is the one that failed.
    """
    diagnostics = [_pack_diagnostic_proto(d) for d in morph.diagnostics]
    state = morph.state
    if state is None:
        return nlp_pb2.MorphState(diagnostics=diagnostics)
    stats = state.stats()
    return nlp_pb2.MorphState(
        version=stats.version,
        language=stats.language,
        rows=stats.rows,
        forms=stats.forms,
        worlds=list(stats.worlds),
        content_hash=stats.content_hash,
        fold_collisions=stats.fold_collisions,
        diagnostics=diagnostics,
    )


def _append_messages(resp, messages: list[dict]) -> None:
    for m in messages:
        resp.messages.append(
            common_pb2.ResponseMessage(
                severity=_SEVERITY.get(m.get("severity", "INFO"), common_pb2.INFO),
                code=m.get("code", ""),
                human_message=m.get("message", ""),
            )
        )


def _tier_to_proto(tier: str) -> int:
    return {
        "SELF_HOSTED_PINNED": nlp_pb2.SELF_HOSTED_PINNED,
        "REMOTE_UNPINNED": nlp_pb2.REMOTE_UNPINNED,
    }.get(tier, nlp_pb2.TIER_UNSPECIFIED)


async def serve_grpc(config: AppConfig, registry: EngineRegistry | None = None) -> grpc.aio.Server:
    """Create + start the async gRPC server. Caller awaits termination.

    Also starts the morph queue's retention sweep (LM contracts §6). It is
    attached to the server object rather than left as a loose task so that
    stopping the server stops it: a timer outliving its service is how a test
    suite ends with a warning about a pending task and a container ends with a
    process that will not exit.
    """
    server = grpc.aio.server()
    servicer = NlpServicer(config, registry)
    nlp_pb2_grpc.add_NlpServiceServicer_to_server(servicer, server)
    listen = f"{config.service.host}:{config.service.grpc_port}"
    server.add_insecure_port(listen)
    await server.start()
    logger.info("nlp gRPC listening on %s", listen)

    if servicer.queue.enabled:
        server.morph_retention_task = asyncio.create_task(  # type: ignore[attr-defined]
            run_retention(
                servicer.queue,
                interval_seconds=config.morph.queue.sweep_interval_seconds,
            )
        )
    return server
