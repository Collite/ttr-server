# SPDX-License-Identifier: Apache-2.0
"""The model slice — what the LLM is told about the model (OL-C14, W1c·S1·T6).

`meta.v1 GetModel(packages)` returns the package's ER entities, each with its attributes
(type, key, description) and the relations between them. The slice keeps the entities
of the BOUND refs first — the ones the core already tied the question to — then the
rest in the order the service sent them, and stops at the cap
(`GOLEM_LLM_MODEL_SLICE_ENTITIES`, default 40). A relation stays only when both of its
ends did: a join the model cannot name an allowed entity for is a join it must not write.

The slice's entity names ARE the allow-list (OL-C14): the lane checks the SQL against
them and the door re-checks them as `allowed_er_reads` (OL-C12). So the slice is a plain
frozen data class — what the prompt renders is exactly what the checks enforce, with no
second copy to drift.

Which entity a bound ref belongs to is read off the ref itself: an `er.*` ref names its
entity as a prefix (`er.entity.web_sales.ext_sales_price` → `er.entity.web_sales`). An
`md.*` ref carries no ER name on the wire, so it ranks nothing first — the entity still
appears, in the service's order, if the cap leaves room.
"""

from __future__ import annotations

import sys
from collections.abc import Sequence
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Protocol, runtime_checkable

_GENERATED = Path(__file__).resolve().parents[2] / "generated"
if str(_GENERATED) not in sys.path:  # pragma: no cover - import bootstrap
    sys.path.insert(0, str(_GENERATED))

import grpc  # noqa: E402
from org.tatrman.common.v1 import response_message_pb2  # noqa: E402
from org.tatrman.meta.v1 import meta_pb2, meta_pb2_grpc  # noqa: E402
from org.tatrman.plan.v1 import plan_pb2  # noqa: E402

from golem_py.errors import LaneServiceUnavailable  # noqa: E402

SERVICE = "meta.v1"
DEFAULT_SLICE_ENTITIES = 40


@dataclass(frozen=True)
class SliceAttribute:
    name: str
    type: str = ""
    is_key: bool = False
    nullable: bool = False
    description: str = ""


@dataclass(frozen=True)
class SliceEntity:
    qname: str  # canonical `er.<namespace>.<name>` — the form `allowed_er_reads` takes
    description: str = ""
    attributes: tuple[SliceAttribute, ...] = ()
    bound: bool = False  # a bound ref of the lattice names this entity


@dataclass(frozen=True)
class SliceRelation:
    name: str
    from_entity: str
    to_entity: str
    join_pairs: tuple[tuple[str, str], ...] = ()


@dataclass(frozen=True)
class ModelSlice:
    packages: tuple[str, ...]
    entities: tuple[SliceEntity, ...] = ()
    relations: tuple[SliceRelation, ...] = ()
    # Entities the package had that the cap left out — the prompt can say the slice is
    # partial instead of letting the model assume it saw the whole package.
    omitted_entities: int = 0

    @property
    def allowed_entities(self) -> tuple[str, ...]:
        """The ER read allow-list (OL-C14): the slice's entity names, nothing else."""
        return tuple(e.qname for e in self.entities)


@runtime_checkable
class ModelSlicePort(Protocol):
    """Raises `LaneServiceUnavailable("meta.v1", …)` when the model cannot be read."""

    async def slice(self, *, bound_refs: Sequence[str], cap: int) -> ModelSlice: ...


def render_qname(qn: Any) -> str:
    """`QualifiedName` → `er.<namespace>.<name>`; the schema code lower-cased, empty
    parts skipped (an unqualified name stays bare rather than gaining a stray dot)."""
    code = qn.schema_code
    schema = "" if code == plan_pb2.SCHEMA_CODE_UNSPECIFIED else plan_pb2.SchemaCode.Name(code)
    return ".".join(part for part in (schema.lower(), qn.namespace, qn.name) if part)


def _names_entity(ref: str, qname: str) -> bool:
    return ref == qname or ref.startswith(qname + ".")


def _entity(bundle_entity: Any, bound: bool) -> SliceEntity:
    return SliceEntity(
        qname=render_qname(bundle_entity.object_descriptor.qualified_name),
        description=bundle_entity.object_descriptor.description,
        attributes=tuple(
            SliceAttribute(
                name=a.object_descriptor.local_name or a.object_descriptor.qualified_name.name,
                type=a.detail.type,
                is_key=a.detail.is_key,
                nullable=a.detail.nullable,
                description=a.object_descriptor.description,
            )
            for a in bundle_entity.attributes
        ),
        bound=bound,
    )


def slice_from_bundle(
    bundle: Any, *, packages: Sequence[str], bound_refs: Sequence[str], cap: int
) -> ModelSlice:
    """A `ModelBundle` → the capped slice. Pure: the specs drive it without a channel."""
    if cap <= 0:
        raise ValueError(f"the model slice cap must be positive, got {cap}")
    named = [(render_qname(e.object_descriptor.qualified_name), e) for e in bundle.entities]

    # Bound first, in the order the lattice's refs name them; then the rest, as served.
    bound_order: list[str] = []
    for ref in bound_refs:
        for qname, _ in named:
            if _names_entity(ref, qname) and qname not in bound_order:
                bound_order.append(qname)
    by_name = dict(named)
    ordered = [(q, by_name[q], True) for q in bound_order]
    ordered += [(q, e, False) for q, e in named if q not in bound_order]

    kept = [_entity(e, bound) for _, e, bound in ordered[:cap]]
    kept_names = {e.qname for e in kept}
    relations = tuple(
        SliceRelation(
            name=r.name,
            from_entity=render_qname(r.from_entity),
            to_entity=render_qname(r.to_entity),
            join_pairs=tuple(
                (render_qname(p.from_attr), render_qname(p.to_attr)) for p in r.join_pairs
            ),
        )
        for r in bundle.relations
        if render_qname(r.from_entity) in kept_names and render_qname(r.to_entity) in kept_names
    )
    return ModelSlice(
        packages=tuple(packages),
        entities=tuple(kept),
        relations=relations,
        omitted_entities=max(0, len(ordered) - cap),
    )


class LiveModelSlice:
    """The real `meta.v1 GetModel` rpc (ModelSlicePort)."""

    def __init__(self, target: str, packages: Sequence[str], timeout_s: float = 10.0):
        if not packages:
            # GetModel refuses an empty package list by contract; say so at construction
            # rather than as a remote error mid-conversation.
            raise ValueError("the model slice needs at least one package (GOLEM_META_PACKAGES)")
        self.target = target
        self.packages = tuple(packages)
        self.timeout_s = timeout_s

    async def slice(self, *, bound_refs: Sequence[str], cap: int) -> ModelSlice:
        request = meta_pb2.GetModelRequest(packages=list(self.packages))
        try:
            async with grpc.aio.insecure_channel(self.target) as channel:
                stub = meta_pb2_grpc.MetadataServiceStub(channel)
                resp = await stub.GetModel(request, timeout=self.timeout_s)
        except grpc.aio.AioRpcError as exc:
            raise LaneServiceUnavailable(
                SERVICE, f"{exc.code().name}: {exc.details() or ''}".rstrip(": ")
            ) from exc
        errors = [m for m in resp.messages if m.severity == response_message_pb2.ERROR]
        if errors:
            # An application-level refusal arrives as OK + messages (meta.proto's rule);
            # a slice built from a model the service would not serve is no slice.
            raise LaneServiceUnavailable(
                SERVICE, "; ".join(f"{m.code}: {m.human_message}" for m in errors)
            )
        return slice_from_bundle(resp.model, packages=self.packages, bound_refs=bound_refs, cap=cap)
