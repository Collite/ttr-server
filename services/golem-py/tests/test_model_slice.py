# SPDX-License-Identifier: Apache-2.0
"""OL W1c·S1·T2 (e) — the `meta.v1` model slice (OL-C14), over an in-process fake."""

from __future__ import annotations

import sys
from pathlib import Path
from typing import Any

import pytest

_GENERATED = Path(__file__).resolve().parents[1] / "generated"
if str(_GENERATED) not in sys.path:  # pragma: no cover
    sys.path.insert(0, str(_GENERATED))

from org.tatrman.common.v1 import response_message_pb2  # noqa: E402
from org.tatrman.meta.v1 import meta_pb2, meta_pb2_grpc  # noqa: E402
from org.tatrman.plan.v1 import plan_pb2  # noqa: E402

from golem_py.errors import LaneServiceUnavailable  # noqa: E402
from golem_py.model_slice import (  # noqa: E402
    LiveModelSlice,
    ModelSlicePort,
    render_qname,
    slice_from_bundle,
)
from tests.grpc_fakes import serve, unused_target  # noqa: E402


def _qn(name: str, ns: str = "entity") -> Any:
    return plan_pb2.QualifiedName(schema_code=plan_pb2.ER, namespace=ns, name=name)


def _entity(name: str, *attrs: tuple[str, str, bool]) -> Any:
    return meta_pb2.ModelBundleEntity(
        object_descriptor=meta_pb2.ObjectDescriptor(
            qualified_name=_qn(name), local_name=name, description=f"the {name} entity"
        ),
        attributes=[
            meta_pb2.ModelBundleAttribute(
                object_descriptor=meta_pb2.ObjectDescriptor(
                    qualified_name=_qn(attr, f"entity.{name}"), local_name=attr
                ),
                detail=meta_pb2.AttributeDetail(entity=_qn(name), type=typ, is_key=key),
            )
            for attr, typ, key in attrs
        ],
    )


def _relation(name: str, a: str, b: str) -> Any:
    return meta_pb2.RelationDetail(
        name=name,
        from_entity=_qn(a),
        to_entity=_qn(b),
        join_pairs=[
            meta_pb2.AttributeJoinPair(
                from_attr=_qn(f"{b}_id", f"entity.{a}"), to_attr=_qn("id", f"entity.{b}")
            )
        ],
    )


BUNDLE = meta_pb2.ModelBundle(
    entities=[
        _entity("store", ("id", "int", True), ("city", "string", False)),
        _entity("customer", ("id", "int", True)),
        _entity("web_sales", ("ext_sales_price", "decimal", False), ("store_id", "int", False)),
        _entity("date_dim", ("cal_date", "date", True)),
    ],
    relations=[
        _relation("sold_at", "web_sales", "store"),
        _relation("bought_by", "web_sales", "customer"),
    ],
)


class FakeMeta(meta_pb2_grpc.MetadataServiceServicer):  # type: ignore[misc]
    def __init__(self, *, messages: list[Any] | None = None) -> None:
        self.requests: list[Any] = []
        self.messages = messages or []

    async def GetModel(self, request: Any, context: Any) -> Any:  # noqa: N802
        self.requests.append(request)
        return meta_pb2.GetModelResponse(model=BUNDLE, messages=self.messages)


def _register(fake: FakeMeta) -> Any:
    return lambda server: meta_pb2_grpc.add_MetadataServiceServicer_to_server(fake, server)


def test_a_qname_renders_in_the_allow_list_form() -> None:
    assert render_qname(_qn("web_sales")) == "er.entity.web_sales"


def test_bound_refs_entities_come_first_and_the_cap_stops_the_slice() -> None:
    sliced = slice_from_bundle(
        BUNDLE,
        packages=["hartland"],
        bound_refs=[
            "er.entity.date_dim.cal_date",
            "md.measure.revenue",  # names no ER entity: ranks nothing
            "er.entity.web_sales.ext_sales_price",
            "er.entity.date_dim",  # the same entity again: listed once
        ],
        cap=3,
    )

    assert sliced.allowed_entities == (
        "er.entity.date_dim",
        "er.entity.web_sales",
        "er.entity.store",  # then the rest, in the order the service sent them
    )
    assert [e.bound for e in sliced.entities] == [True, True, False]
    assert sliced.omitted_entities == 1
    # A relation survives only with both ends in the slice.
    assert [r.name for r in sliced.relations] == ["sold_at"]
    assert sliced.relations[0].join_pairs == (
        ("er.entity.web_sales.store_id", "er.entity.store.id"),
    )


def test_the_slice_carries_attributes_keys_and_descriptions() -> None:
    sliced = slice_from_bundle(BUNDLE, packages=["hartland"], bound_refs=[], cap=40)

    store = sliced.entities[0]
    assert store.qname == "er.entity.store" and store.description == "the store entity"
    assert [(a.name, a.type, a.is_key) for a in store.attributes] == [
        ("id", "int", True),
        ("city", "string", False),
    ]
    assert sliced.omitted_entities == 0
    assert len(sliced.relations) == 2


def test_a_non_positive_cap_is_refused() -> None:
    with pytest.raises(ValueError):
        slice_from_bundle(BUNDLE, packages=["hartland"], bound_refs=[], cap=0)


async def test_the_live_slice_reads_get_model_for_the_configured_packages() -> None:
    fake = FakeMeta()
    async with serve(_register(fake)) as target:
        sliced = await LiveModelSlice(target, ["hartland"]).slice(
            bound_refs=["er.entity.customer.id"], cap=2
        )

    assert list(fake.requests[0].packages) == ["hartland"]
    assert sliced.allowed_entities == ("er.entity.customer", "er.entity.store")
    assert sliced.packages == ("hartland",)


async def test_a_model_the_service_refuses_is_lane_unavailable() -> None:
    refusal = response_message_pb2.ResponseMessage(
        severity=response_message_pb2.ERROR, code="package_not_found", human_message="no such"
    )
    async with serve(_register(FakeMeta(messages=[refusal]))) as target:
        with pytest.raises(LaneServiceUnavailable, match="package_not_found"):
            await LiveModelSlice(target, ["nope"]).slice(bound_refs=[], cap=5)


async def test_an_unreachable_metadata_service_is_lane_unavailable() -> None:
    with pytest.raises(LaneServiceUnavailable) as caught:
        await LiveModelSlice(unused_target(), ["hartland"], timeout_s=1.0).slice(
            bound_refs=[], cap=5
        )

    assert caught.value.service == "meta.v1"


def test_no_packages_is_refused_at_construction() -> None:
    with pytest.raises(ValueError, match="GOLEM_META_PACKAGES"):
        LiveModelSlice("127.0.0.1:1", [])


def test_the_live_slice_satisfies_the_port() -> None:
    assert isinstance(LiveModelSlice("127.0.0.1:1", ["hartland"]), ModelSlicePort)

