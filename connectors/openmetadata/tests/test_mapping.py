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
    EnsureCustomProperty,
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
        "ttrUrl": "https://studio.example/m/northline/s/db",
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


def test_studio_links_use_renderable_model_subjects_and_clear_cn_links(export: Export) -> None:
    plan = plan_from_export(export, studio_url="https://studio.example/")
    terms = [op for op in plan.operations if isinstance(op, UpsertTerm)]
    for term in terms:
        if term.cn:
            assert "ttrUrl" not in term.props
            assert "ttrStudioLink" not in term.props
            assert term.remove_props == ["ttrUrl", "ttrStudioLink"]
        else:
            model = term.qname.split(".", 1)[0]
            assert term.props["ttrUrl"] == f"https://studio.example/m/northline/s/{model}"
            assert term.remove_props == []
    for metric in (op for op in plan.operations if isinstance(op, UpsertMetric)):
        assert metric.props["ttrUrl"] == "https://studio.example/m/northline/s/md"
    assert all(
        not t.remove_props for t in plan_from_export(export).operations if isinstance(t, UpsertTerm)
    )


def test_studio_workspace_can_differ_from_export_world(export: Export) -> None:
    plan = plan_from_export(
        export, studio_url="https://studio.example", studio_workspace="catalog-demo"
    )
    sales = next(op for op in plan.operations if isinstance(op, SetTableProps))
    assert sales.props["ttrUrl"] == "https://studio.example/m/catalog-demo/s/db"
    qname = sales.props["ttrQname"]
    assert isinstance(qname, str) and qname.startswith("db.")


@pytest.mark.parametrize("workspace", ["", ".", "..", "c", "_designer", "a/b", "a?x=y"])
def test_studio_workspace_must_be_a_valid_nonreserved_manifest_name(
    export: Export, workspace: str
) -> None:
    with pytest.raises(MappingError, match="Studio workspace"):
        plan_from_export(export, studio_url="https://studio.example", studio_workspace=workspace)


@pytest.mark.parametrize(
    "url",
    [
        "https://",
        "https://studio.example/viewer",
        "https://studio.example?x=y",
        "https://studio.example?",
        "https://studio.example#",
        "https://studio.example\\evil",
        "https://studio.example:abc",
        "https://studio.example:65536",
        "https://studio.example\t",
        "https://studio.example\x00",
        "https://studio.example\x80",
        "https://studio.example\u202e",
        "https://studio.example\u200b",
        "https://studio.example\ufeff",
        "https://studio.example\ud800",
        "https://studio.example\ue000",
        "https://studio.example\u0378",
        "https://studio.example:0",
        "https://studio.example:",
        "https://studio%2Fexample",
        "https://studio%40example",
        "https://stüdio.example",
        "https://studio.example space",
        "https://@studio.example",
        "https://:80",
        "https://studio.example#fragment",
        "https://user:fixture@studio.example",
    ],
)
def test_studio_url_is_an_origin_not_a_route_or_credential_container(
    export: Export, url: str
) -> None:
    with pytest.raises(MappingError, match="studio URL"):
        plan_from_export(export, studio_url=url)


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


def test_native_studio_links_match_urls_and_registry_types(export: Export) -> None:
    plan = plan_from_export(export, studio_url="https://studio.example", studio_workspace="demo")
    properties = [op for op in plan.operations if isinstance(op, EnsureCustomProperty)]
    links = [op for op in properties if op.name == "ttrStudioLink"]
    assert {(op.entity_type, op.type) for op in links} == {
        ("table", "hyperlink-cp"),
        ("glossaryTerm", "hyperlink-cp"),
        ("metric", "hyperlink-cp"),
    }
    assert all(op.type == "string" for op in properties if op.name != "ttrStudioLink")
    for op in plan.operations:
        if isinstance(op, (SetTableProps, UpsertTerm, UpsertMetric)) and "ttrUrl" in op.props:
            link = op.studio_link
            assert link is not None
            assert link.url == op.props["ttrUrl"]
            assert link.url.startswith("https://studio.example/m/demo/s/")
            assert link.displayText.startswith("Open ")
            assert link.displayText.endswith(" model in Studio")
    assert all(
        op.studio_link is None
        for op in plan_from_export(export).operations
        if isinstance(op, (SetTableProps, UpsertTerm, UpsertMetric))
    )


@pytest.mark.parametrize("operation", [SetTableProps, UpsertTerm, UpsertMetric])
def test_string_properties_reject_structured_values(
    export: Export, operation: type[SetTableProps] | type[UpsertTerm] | type[UpsertMetric]
) -> None:
    from pydantic import ValidationError

    op = next(o for o in plan_from_export(export).operations if isinstance(o, operation))
    data = op.model_dump()
    data["props"]["ttrQname"] = {"url": "https://wrong.example"}
    with pytest.raises(ValidationError):
        operation.model_validate(data)


@pytest.mark.parametrize(
    "origin,expected",
    [
        ("HTTPS://Studio.EXAMPLE:443/", "https://studio.example"),
        ("http://Studio.EXAMPLE:80", "http://studio.example"),
        ("https://Studio.EXAMPLE:8443", "https://studio.example:8443"),
        ("http://[2001:DB8::1]:80/", "http://[2001:db8::1]"),
        ("https://xn--stdio-kva.example", "https://xn--stdio-kva.example"),
    ],
)
def test_studio_origins_are_canonical(export: Export, origin: str, expected: str) -> None:
    from ttr_connect_openmetadata.urls import http_origin

    assert http_origin(origin, allow_path=False) == expected
    plan = plan_from_export(export, studio_url=origin)
    assert plan.studio_base == f"{expected}/m/northline/s/"


@pytest.mark.parametrize(
    "url",
    [
        'https://a"b<c>',
        "https://studio.example;x",
        "https://x{y}|z",
        "https://a..b",
        "https://1.2.3.4.5",
        "https://-bad.example",
        "https://bad-.example",
        "https://" + "a" * 64 + ".example",
        "https://" + ".".join(["a" * 63] * 4),
        "https://256.1.2.3",
        "https://[::1]junk",
    ],
)
def test_invalid_origin_host_grammar(export: Export, url: str) -> None:
    with pytest.raises(MappingError, match="invalid host or port"):
        plan_from_export(export, studio_url=url)


@pytest.mark.parametrize(
    "url,cause",
    [
        ("https://studio.example/m/demo", "without a path"),
        ("https://user@studio.example", "credentials"),
        ("ftp://studio.example", "scheme"),
        ("https://studio.example:0", "host or port"),
    ],
)
def test_origin_rejections_explain_the_cause(url: str, cause: str) -> None:
    from ttr_connect_openmetadata.urls import http_origin

    with pytest.raises(ValueError, match=cause):
        http_origin(url, allow_path=False)


def test_dns_root_dot_is_canonical(export: Export) -> None:
    dotted = plan_from_export(export, studio_url="https://Studio.EXAMPLE.")
    plain = plan_from_export(export, studio_url="https://studio.example")
    assert dotted == plain


@pytest.mark.parametrize(
    "base",
    [
        "",
        "garbage",
        "https://studio.example/m//s/",
        "https://studio.example/m/c/s/",
        "https://studio.example/m/demo/s/?query",
    ],
)
def test_invalid_prepared_base_is_rejected(export: Export, base: str) -> None:
    with pytest.raises(MappingError, match="invalid prepared Studio base"):
        plan_from_export(export, studio_base=base)


def test_prepared_base_and_library_cli_input_agree(export: Export) -> None:
    assert plan_from_export(export, studio_base="https://Studio.EXAMPLE.:443/m/demo/s/") == (
        plan_from_export(export, studio_url="https://studio.example", studio_workspace="demo")
    )
    plan = plan_from_export(export)
    assert not plan.studio_links
    plan.studio_base = "https://studio.example/m/demo/s/"
    assert plan.studio_links
