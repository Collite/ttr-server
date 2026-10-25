# SPDX-License-Identifier: Apache-2.0
import json
from pathlib import Path

import pytest
from pydantic import JsonValue

from ttr_connect_openmetadata.export_reader import DirSource
from ttr_connect_openmetadata.mapping import MappingError, plan_from_export
from ttr_connect_openmetadata.model import (
    Connection,
    Edge,
    Export,
    LinkMetricToAsset,
    LinkTermToAsset,
    Node,
    SetColumnTags,
    SetTableProps,
    UpsertMetric,
    UpsertTerm,
)

FIXTURES = Path(__file__).parent / "fixtures"


def test_handwritten_seed() -> None:
    export = DirSource(FIXTURES / "export").read()
    plan = plan_from_export(export)
    assert [op.model_dump(mode="json") for op in plan.operations[:5]] == json.loads(
        (FIXTURES / "plan/seed.plan.json").read_text()
    )


def test_missing_locator_warns_and_does_not_link() -> None:
    export = DirSource(FIXTURES / "export").read()
    export.defs.connections[0].locators = {}
    plan = plan_from_export(export)
    assert not any(op.op == "LinkTermToAsset" for op in plan.operations)
    assert len(plan.warnings) == 1


def test_full_plan_golden(export: Export, request: pytest.FixtureRequest) -> None:
    plan = plan_from_export(export)
    actual = [op.model_dump(mode="json") for op in plan.operations]
    path = FIXTURES / "plan/world.plan.json"
    if request.config.getoption("--update-goldens"):
        path.write_text(json.dumps(actual, ensure_ascii=False, indent=2) + "\n")
    assert actual == json.loads(path.read_text())
    export.graph.nodes.reverse()
    export.graph.edges.reverse()
    export.defs.definitions = dict(reversed(list(export.defs.definitions.items())))
    assert plan_from_export(export) == plan
    assert plan.warnings == []


def test_cn_identity_languages_parents_and_relations(export: Export) -> None:
    terms = {
        op.qname: op for op in plan_from_export(export).operations if isinstance(op, UpsertTerm)
    }
    party = terms["cn.kind.Party"]
    assert party.iri == "https://example.org/northline/Party"
    assert party.display == "Party"
    assert party.description == "A trading party"
    assert party.synonyms == ["osoba", "partner", "party"]
    prospect = terms["cn.kind.Prospect"]
    assert prospect.parent == "northline Concepts.Party"
    assert prospect.fqn == "northline Concepts.Party.Prospect"
    purchase = terms["cn.event.Purchase"]
    assert {r.term for r in purchase.related} == {
        "northline Concepts.Product",
        "northline Concepts.Customer",
    }
    assert terms["er.entity.sale"].related[0].term == "northline Entities.buyer"
    assert terms["er.entity.buyer.id"].parent == "northline Entities.buyer"
    assert terms["md.dimension.Calendar"].props["ttrKind"] == "dimension"
    assert terms["md.hierarchy.CalendarRollup"].props["ttrKind"] == "hierarchy"
    node = next(n for n in export.graph.nodes if n.id == "cn.kind.Party")
    node.displayLabel.pop("en")
    terms2 = [op for op in plan_from_export(export).operations if isinstance(op, UpsertTerm)]
    assert next(t for t in terms2 if t.qname == node.id).display == "Party (Osoba)"


@pytest.mark.parametrize(
    "aggregation,expected",
    [
        ("sum", "SUM"),
        ("COUNT", "COUNT"),
        ("avg", "AVERAGE"),
        ("min", "OTHER"),
        ("max", "OTHER"),
        ("median", "OTHER"),
    ],
)
def test_metrics_use_definitions(export: Export, aggregation: str, expected: str) -> None:
    definition = export.defs.definitions["md.measure.revenue"]
    definition.properties["aggregation"] = {"default": aggregation}
    definition.properties["definition"] = {"block": "amount * 2", "tag": "pl"}
    metrics = [op for op in plan_from_export(export).operations if isinstance(op, UpsertMetric)]
    assert len(metrics) == 3
    revenue = next(m for m in metrics if m.name == "revenue")
    assert revenue.metricType == expected
    assert revenue.metricExpression.code == aggregation + " amount * 2"
    assert revenue.metricExpression.language == "SQL"
    assert next(m for m in metrics if m.name == "balance").props["ttrAdditivity"] == "semiAdditive"


def test_asset_links_and_column_maps(export: Export) -> None:
    plan = plan_from_export(export, studio_url="https://studio.example/")
    tables = [op for op in plan.operations if isinstance(op, SetTableProps)]
    assert len(tables) == 4
    sales = next(t for t in tables if t.fqn.endswith(".sales"))
    assert sales.fqn == "northline-pg.northline.retail.sales"
    assert sales.props == {
        "ttrQname": "db.retail.sales",
        "ttrUrl": "https://studio.example/viewer?qname=db.retail.sales",
    }
    assert sales.tags == ["Tatrman.Grounded"]
    assert next(t for t in tables if t.fqn.endswith(".products")).tags == []
    columns = [op for op in plan.operations if isinstance(op, SetColumnTags)]
    assert {c.column for c in columns if c.fqn.endswith(".sales") and c.tags} == {
        "amount",
        "buyer_id",
        "id",
        "sold_at",
    }
    assert next(c for c in columns if c.column == "name" and c.fqn.endswith(".buyers")).tags == []
    links = [op for op in plan.operations if isinstance(op, LinkTermToAsset)]
    assert any(
        link.term_fqn == "northline Concepts.Party" and link.asset_fqn.endswith(".buyers")
        for link in links
    )
    assert any(
        link.term_fqn == 'northline Entities.buyer."er.entity.buyer.id"'
        and link.asset_fqn.endswith(".buyers.id")
        and link.asset_type == "column"
        for link in links
    )
    assert any(
        link.term_fqn == 'northline Entities."md.dimension.Buyer"'
        and link.asset_fqn.endswith(".sales.buyer_id")
        for link in links
    )
    metric_links = [op for op in plan.operations if isinstance(op, LinkMetricToAsset)]
    assert {(link.metric, link.asset_fqn.rsplit(".", 1)[-1]) for link in metric_links} == {
        ("revenue", "amount"),
        ("balance", "amount"),
        ("units", "id"),
    }


def test_multiple_connections_and_schema_filter(export: Export) -> None:
    connection = export.defs.connections[0].model_copy(deep=True)
    connection.id = "secondary"
    connection.database = "northline_cz"
    export.defs.connections.append(connection)
    plan = plan_from_export(export)
    assert len([op for op in plan.operations if isinstance(op, SetTableProps)]) == 8
    assert any(
        isinstance(op, LinkTermToAsset) and ".northline_cz." in op.asset_fqn
        for op in plan.operations
    )
    assert not any(
        isinstance(op, SetTableProps)
        for op in plan_from_export(
            export,
            connections=[
                Connection(
                    id="wrong-schema",
                    database="n",
                    schema="other",
                    locators={"openmetadata": {"service": "n"}},
                )
            ],
        ).operations
    )
    assert plan_from_export(export, connections=[]).warnings


def test_physical_schema_locator_keeps_model_handle_and_bindings(export: Export) -> None:
    connection = export.defs.connections[0]
    original = connection.schema_name
    connection.locators = {"openmetadata": {"service": "northline-pg", "schema": "public"}}
    plan = plan_from_export(export)
    tables = [op for op in plan.operations if isinstance(op, SetTableProps)]
    links = [op for op in plan.operations if isinstance(op, LinkTermToAsset)]
    assert tables and links
    assert all(".public." in op.fqn for op in tables)
    assert all(".public." in op.asset_fqn for op in links)
    assert connection.schema_name == original == "retail"
    # Physical names never broaden which model schemas this connection owns.
    connection.schema_name = "other"
    assert not any(isinstance(op, SetTableProps) for op in plan_from_export(export).operations)


@pytest.mark.parametrize("schema", ["", 7, {}, []])
def test_invalid_physical_schema_locator_fails(export: Export, schema: JsonValue) -> None:
    export.defs.connections[0].locators = {
        "openmetadata": {"service": "northline-pg", "schema": schema}
    }
    with pytest.raises(MappingError, match="OM schema locator"):
        plan_from_export(export)


def test_qname_cn_names_allow_case_insensitive_cross_kind_names(export: Export) -> None:
    export.graph.nodes.append(
        Node(id="cn.quality.party", kind="quality", model="cn", label="party")
    )
    with pytest.raises(MappingError, match="collision"):
        plan_from_export(export)
    plan = plan_from_export(export, cn_name_mode="qname")
    terms = [op for op in plan.operations if isinstance(op, UpsertTerm) and op.cn]
    assert len({op.name.casefold() for op in terms}) == len(terms)
    party = next(op for op in terms if op.qname == "cn.kind.Party")
    assert party.name == "cn.kind.Party" and party.display == "Party"
    child = next(op for op in terms if op.qname == "cn.kind.Prospect")
    assert child.parent == party.fqn
    assert child.fqn == party.fqn + '."cn.kind.Prospect"'
    with pytest.raises(MappingError, match="CN name mode"):
        plan_from_export(export, cn_name_mode="invalid")


def test_cycle_multiple_parents_and_address_collision(export: Export) -> None:
    cycle = Edge.model_validate({"from": "cn.kind.Party", "to": "cn.kind.Prospect", "kind": "is_a"})
    export.graph.edges.append(cycle)
    with pytest.raises(MappingError, match="cyclic"):
        plan_from_export(export)
    export.graph.edges.pop()
    export.graph.edges.append(
        Edge.model_validate(
            {
                "from": "cn.kind.Prospect",
                "to": "cn.kind.Product",
                "kind": "is_a",
            }
        )
    )
    with pytest.raises(MappingError, match="multiple"):
        plan_from_export(export)
    export.graph.edges.pop()
    export.graph.nodes.append(Node(id="cn.role.Party", kind="role", model="cn", label="Party"))
    with pytest.raises(MappingError, match="collision"):
        plan_from_export(export)


def test_parent_order_is_topological(export: Export) -> None:
    export.graph.edges.append(
        Edge.model_validate(
            {
                "from": "cn.kind.Party",
                "to": "cn.role.Customer",
                "kind": "is_a",
            }
        )
    )
    terms = [op for op in plan_from_export(export).operations if isinstance(op, UpsertTerm)]
    positions = {term.qname: i for i, term in enumerate(terms)}
    assert (
        positions["cn.role.Customer"] < positions["cn.kind.Party"] < positions["cn.kind.Prospect"]
    )


def test_missing_metric_definition_and_invalid_options(export: Export) -> None:
    with pytest.raises(MappingError, match="world"):
        plan_from_export(export, world="wrong")
    with pytest.raises(MappingError, match="studio URL"):
        plan_from_export(export, studio_url="file:///tmp/s")
    export.defs.definitions.pop("md.measure.units")
    with pytest.raises(MappingError, match="missing definition"):
        plan_from_export(export)


def test_sdk_accepts_metric_and_term_payloads(export: Export) -> None:
    from metadata.generated.schema.api.data.createGlossaryTerm import CreateGlossaryTermRequest
    from metadata.generated.schema.api.data.createMetric import CreateMetricRequest

    for op in plan_from_export(export).operations:
        if isinstance(op, UpsertMetric):
            request = CreateMetricRequest.model_validate(
                {
                    "name": op.name,
                    "description": op.description,
                    "displayName": op.display,
                    "metricType": op.metricType,
                    "metricExpression": op.metricExpression.model_dump(),
                    "extension": op.props,
                }
            )
            assert request.metricType is not None
        if isinstance(op, UpsertTerm):
            term = CreateGlossaryTermRequest.model_validate(
                {
                    "name": op.name,
                    "glossary": op.glossary,
                    "description": op.description,
                    "parent": op.parent,
                    "synonyms": op.synonyms,
                    "relatedTerms": [r.term for r in op.related],
                    "iri": op.iri,
                    "extension": op.props,
                }
            )
            assert str(term.iri) == op.iri


def test_glossary_names_are_unique_across_all_parents(export: Export) -> None:
    # OM 2.0.6 rejects two leaf names like 'id', even with different parents.
    terms = [op for op in plan_from_export(export).operations if isinstance(op, UpsertTerm)]
    names = [(op.glossary, op.name.casefold()) for op in terms]
    assert len(names) == len(set(names))
    buyer_id = next(op for op in terms if op.qname == "er.entity.buyer.id")
    assert buyer_id.name == "er.entity.buyer.id"
    assert buyer_id.display == "id"
    assert buyer_id.parent == "northline Entities.buyer"


def test_case_insensitive_glossary_name_collision_fails_before_writes(export: Export) -> None:
    customer = next(n for n in export.graph.nodes if n.id == "cn.role.Customer")
    customer.label = "party"
    with pytest.raises(MappingError, match="glossary name collision"):
        plan_from_export(export)
