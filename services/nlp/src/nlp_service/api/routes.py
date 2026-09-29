# SPDX-License-Identifier: Apache-2.0
from __future__ import annotations

import asyncio
import logging
import os
from typing import Any, Dict, List, Set

from fastapi import FastAPI, HTTPException, Request
from fastapi.responses import JSONResponse
from opentelemetry import metrics, trace
from pydantic import BaseModel, ConfigDict, Field

from nlp_service.config import load_config
from nlp_service.engines.base import NlpOp
from nlp_service.pipeline.orchestrator import Orchestrator
from otel_config import setup_opentelemetry, instrument_fastapi

logger = logging.getLogger("nlp-service")


# ---------------------------------------------------------------------------
# Request/Response models (Pydantic with camelCase for JSON)
# ---------------------------------------------------------------------------

class AnalyzeRequest(BaseModel):
    """Request model for POST /v1/analyze."""

    text: str = Field(..., description="Input text to analyze", min_length=1, max_length=50000)
    language: str = Field("", description="Language code (empty triggers auto-detection)")
    ops: Set[str] = Field(..., description="Set of requested NLP operations")
    mode: str = Field("NORMAL", description="NORMAL or COMPARE mode")
    engineHints: Dict[str, str] = Field(default_factory=dict, description="Engine override per operation")

    model_config = ConfigDict(populate_by_name=True)


class TokenResponse(BaseModel):
    """Token representation in API response."""
    text: str
    charStart: int
    charEnd: int
    lemma: str
    upos: str
    xpos: str
    feats: Dict[str, str]
    depHead: int
    depRelation: str


class NerEntityResponse(BaseModel):
    """NER entity representation in API response."""
    text: str
    label: str
    charStart: int
    charEnd: int
    normalizedValue: str
    sourceEngine: str


class EngineVersionResponse(BaseModel):
    """S-1 model-identity echo (contracts §1 `used[]`)."""
    op: str
    engine: str
    model: str
    modelVersion: str


class AnalyzeResponse(BaseModel):
    """Response model for POST /v1/analyze."""
    language: str
    detectedLanguage: str
    languageConfidence: float
    engineUsed: str
    tokens: List[TokenResponse]
    sentences: List[Dict[str, int]]
    paragraphs: List[Dict[str, int]]
    entities: List[NerEntityResponse]
    byEngine: Dict[str, Any]
    used: List[EngineVersionResponse]
    traceId: str
    elapsedMs: int
    messages: List[Dict[str, Any]]

    model_config = ConfigDict(populate_by_name=True)


# ---------------------------------------------------------------------------
# Application factory
# ---------------------------------------------------------------------------

def create_app() -> FastAPI:
    """Create and configure the FastAPI application."""
    config = load_config()
    service_name = "nlp"

    # OpenTelemetry is OFF unless a collector is named. The chart renders
    # OTEL_EXPORTER_OTLP_HOST only when `telemetry.enabled` and `telemetry.otlpHost`
    # are both set, so its presence is the switch. It used to default to localhost:
    # every pod without a collector built the three exporters anyway, and each
    # retried forever against a port nothing listens on — an ERROR/WARNING pair every
    # few seconds for logs, traces and metrics, in the pod log and in every test run.
    # Off, the tracer and meter are the API's no-ops: the span and the counters below
    # still run, and cost nothing.
    otel_host = os.getenv("OTEL_EXPORTER_OTLP_HOST", "").strip()
    telemetry_on = bool(otel_host)
    if telemetry_on:
        otel_protocol = os.getenv("NLP_SERVICE_OTEL_PROTOCOL", "grpc").lower()
        otel_port = int(os.getenv(
            "OTEL_EXPORTER_OTLP_GRPC_PORT",
            "4317" if otel_protocol == "grpc"
            else os.getenv("OTEL_EXPORTER_OTLP_HTTP_PORT", "4318")
        ))
        otel = setup_opentelemetry(
            service_name=service_name,
            otel_endpoint=f"{otel_host}:{otel_port}",
            protocol=otel_protocol,
            insecure=True,
        )
        tracer = otel["tracer"]
        meter = otel["meter"]
    else:
        logger.info("OpenTelemetry off: OTEL_EXPORTER_OTLP_HOST is not set")
        tracer = trace.get_tracer(service_name)
        meter = metrics.get_meter(service_name)

    # Create metrics
    request_counter = meter.create_counter(
        name="nlp_request_total",
        description="Total Nlp NLP requests",
        unit="1",
    )
    request_duration = meter.create_histogram(
        name="nlp_request_duration_seconds",
        description="Nlp NLP request duration in seconds",
        unit="s",
    )

    app = FastAPI(
        title="Nlp NLP Service",
        description="Multi-engine NLP analysis (Stanza, spaCy, NameTag, langid)",
        version="0.1.0",
    )

    if telemetry_on:
        # Instrument FastAPI via shared library
        instrument_fastapi(app)

        # RG-P1.S2.T6 — trace every front→backend HTTP call. The engine adapters
        # use httpx.Client to reach the MorphoDiTa/NameTag 3 (and Stanza/spaCy)
        # backends; auto-instrumentation emits a client span per call and propagates
        # W3C trace context, so the trace stitches across the front↔backend boundary
        # even though the UFAL servers don't self-instrument.
        try:
            from opentelemetry.instrumentation.httpx import HTTPXClientInstrumentor

            HTTPXClientInstrumentor().instrument()
        except Exception:  # noqa: BLE001 — tracing must never block boot
            logger.warning("httpx OTel instrumentation unavailable; backend calls untraced")

    # Initialize orchestrator
    orchestrator = Orchestrator(config)

    # Health endpoints
    @app.get("/healthz")
    def healthz():
        return {"status": "ok"}

    @app.get("/readyz")
    def readyz():
        # Engines only, and deliberately NOT the pack snapshot — unlike
        # `GetStatus.ready`, which is both. This is the k8s readinessProbe:
        # failing it pulls the pod out of service, taking Analyze and
        # BatchLemmatize down with it, and those need no packs. A YAML typo in a
        # pack tree must cost RunPipeline and nothing else (`packs_state.py`), so
        # the pack half is reported on GetStatus where a consumer can read it
        # without a broken pack tree becoming an outage for Themis and Echo.
        if orchestrator._registry.is_ready():
            return {"status": "ready"}
        return JSONResponse(
            status_code=503,
            content={"status": "not_ready", "reason": "No engines available"}
        )

    @app.get("/version")
    def version():
        matrix = [
            {
                "language": row["language"],
                "op": row["op"].value,
                "engine": row["engine"],
                "modelVersion": row["model_version"],
                "tier": row["tier"],
            }
            for row in orchestrator._registry.capability_matrix()
        ]
        return {
            "service": "nlp-service",
            "version": "0.1.0",
            "capabilities": matrix,
        }

    # Main analysis endpoint
    @app.post("/v1/analyze", response_model=AnalyzeResponse)
    async def analyze(request: AnalyzeRequest, http_request: Request):
        """Run NLP analysis on the input text."""
        with tracer.start_as_current_span("nlp.analyze") as span:
            span.set_attribute("nlp.text_length", len(request.text))
            span.set_attribute("nlp.language", request.language or "auto")
            span.set_attribute("nlp.ops", ",".join(sorted(request.ops)))

            logger.debug(
                "/v1/analyze request | language=%r ops=%s mode=%r text=%r",
                request.language,
                sorted(request.ops),
                request.mode,
                request.text,
            )

            try:
                requested_ops: Set[NlpOp] = set()
                for op_str in request.ops:
                    try:
                        op = NlpOp(op_str)
                        requested_ops.add(op)
                    except ValueError:
                        raise HTTPException(
                            status_code=400,
                            detail=f"Invalid operation: {op_str}. Valid values: {[o.value for o in NlpOp]}"
                        )

                if not requested_ops:
                    raise HTTPException(
                        status_code=400,
                        detail="At least one operation must be specified"
                    )

                # On a worker thread, never on the loop. `analyze` is synchronous and
                # waits on backend HTTP (up to `timeout_seconds` × retries per engine);
                # awaited inline, that wait froze every request on this process, the
                # gRPC contract included, since both servers share the one loop.
                result = await asyncio.to_thread(
                    orchestrator.analyze,
                    text=request.text,
                    language=request.language,
                    ops=requested_ops,
                    mode=request.mode,
                    engine_hints=request.engineHints,
                )

                # Convert to response format
                tokens_response = [
                    TokenResponse(
                        text=t.text,
                        charStart=t.char_start,
                        charEnd=t.char_end,
                        lemma=t.lemma,
                        upos=t.upos,
                        xpos=t.xpos,
                        feats=t.feats,
                        depHead=t.dep_head,
                        depRelation=t.dep_relation,
                    )
                    for t in result.tokens
                ]

                entities_response = [
                    NerEntityResponse(
                        text=e.text,
                        label=e.label,
                        charStart=e.char_start,
                        charEnd=e.char_end,
                        normalizedValue=e.normalized_value,
                        sourceEngine=e.source_engine,
                    )
                    for e in result.entities
                ]

                sentences_response = [
                    {"charStart": s[0], "charEnd": s[1]}
                    for s in result.sentences
                ]

                paragraphs_response = [
                    {"charStart": p[0], "charEnd": p[1]}
                    for p in result.paragraphs
                ]

                # Convert EngineResult to dict for byEngine response
                def engine_result_to_dict(er):
                    return {
                        "tokens": [
                            {
                                "text": t.text,
                                "charStart": t.char_start,
                                "charEnd": t.char_end,
                                "lemma": t.lemma,
                                "upos": t.upos,
                                "xpos": t.xpos,
                                "feats": t.feats,
                                "depHead": t.dep_head,
                                "depRelation": t.dep_relation,
                            }
                            for t in er.tokens
                        ],
                        "entities": [
                            {
                                "text": e.text,
                                "label": e.label,
                                "charStart": e.char_start,
                                "charEnd": e.char_end,
                                "normalizedValue": e.normalized_value,
                                "sourceEngine": e.source_engine,
                            }
                            for e in er.entities
                        ],
                        "sentences": [{"charStart": s[0], "charEnd": s[1]} for s in er.sentences],
                        "paragraphs": [{"charStart": p[0], "charEnd": p[1]} for p in er.paragraphs],
                        "error": er.error,
                    }

                by_engine_response = {
                    name: engine_result_to_dict(er)
                    for name, er in result.by_engine.items()
                }

                # Record metrics
                request_counter.add(1, {"status": "success", "engine": result.engine_used})
                request_duration.record(result.elapsed_ms / 1000.0, {"engine": result.engine_used})

                span.set_attribute("nlp.engine_used", result.engine_used)
                span.set_attribute("nlp.token_count", len(result.tokens))
                span.set_attribute("nlp.entity_count", len(result.entities))
                span.set_attribute("nlp.by_engine_count", len(result.by_engine))

                logger.debug(
                    "/v1/analyze response | engine=%s language=%s tokens=%d entities=%d sentences=%d "
                    "elapsedMs=%d messages=%r firstTokens=%s firstEntities=%s",
                    result.engine_used,
                    result.language,
                    len(result.tokens),
                    len(result.entities),
                    len(result.sentences),
                    result.elapsed_ms,
                    result.messages,
                    [f"{t.text}/{t.upos}" for t in result.tokens[:10]],
                    [f"{e.text}/{e.label}" for e in result.entities[:10]],
                )

                used_response = [
                    EngineVersionResponse(
                        op=ev.op,
                        engine=ev.engine,
                        model=ev.model,
                        modelVersion=ev.model_version,
                    )
                    for ev in result.used
                ]

                return AnalyzeResponse(
                    language=result.language,
                    detectedLanguage=result.language,
                    languageConfidence=result.language_confidence,
                    engineUsed=result.engine_used,
                    tokens=tokens_response,
                    sentences=sentences_response,
                    paragraphs=paragraphs_response,
                    entities=entities_response,
                    byEngine=by_engine_response,
                    used=used_response,
                    traceId=result.trace_id,
                    elapsedMs=result.elapsed_ms,
                    messages=result.messages,
                )

            except HTTPException:
                request_counter.add(1, {"status": "error"})
                raise
            except Exception as e:
                logger.exception(f"Unexpected error in /v1/analyze: {e}")
                request_counter.add(1, {"status": "error"})
                span.record_exception(e)
                raise HTTPException(status_code=500, detail=str(e))

    return app


# ---------------------------------------------------------------------------
# Entry point
# ---------------------------------------------------------------------------

app = create_app()

if __name__ == "__main__":
    import uvicorn

    config = load_config()
    uvicorn.run(
        "nlp_service.api.routes:app",
        host=config.service.host,
        port=config.service.port,
        reload=False,
    )