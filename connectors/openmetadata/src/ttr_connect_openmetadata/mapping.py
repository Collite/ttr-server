# SPDX-License-Identifier: Apache-2.0
"""Pure tier 1 + tier 0 projection into ordered OpenMetadata operations."""

import json
from collections import defaultdict
from urllib.parse import quote

from pydantic import JsonValue

from .model import (
    Connection,
    Delta,
    Deprecate,
    EnsureClassification,
    EnsureCustomProperty,
    EnsureGlossary,
    EnsureTag,
    Export,
    ImportRdf,
    LinkMetricToAsset,
    LinkTermToAsset,
    MetricExpression,
    Node,
    Plan,
    PlanOperation,
    RelatedTerm,
    SetColumnTags,
    SetTableProps,
    UpsertMetric,
    UpsertTerm,
)


class MappingError(Exception):
    """The export cannot be projected safely (C-14 exit 3)."""


def _segment(name: str) -> str:
    if '"' in name:
        raise MappingError(f"unsupported quote in OM name: {name}")
    return f'"{name}"' if "." in name else name


def _fqn(*segments: str) -> str:
    return ".".join(_segment(s) for s in segments)


def _display(node: Node) -> str:
    if node.displayLabel.get("en"):
        return node.displayLabel["en"]
    name = node.label or node.id.rsplit(".", 1)[-1]
    if node.displayLabel.get("cs"):
        return f"{name} ({node.displayLabel['cs']})"
    return next(iter(sorted(node.displayLabel.items())), ("", name))[1]


def _description(node: Node) -> str:
    return node.description.get("en") or "\n".join(
        f"{lang}: {text}" for lang, text in sorted(node.description.items())
    )


def _props(node: Node, studio_url: str | None) -> dict[str, str]:
    result = {"ttrQname": node.id}
    if studio_url:
        result["ttrUrl"] = f"{studio_url.rstrip('/')}/viewer?qname={quote(node.id, safe='')}"
    return result


def _term_nodes(nodes: dict[str, Node]) -> dict[str, Node]:
    return {
        key: n
        for key, n in nodes.items()
        if n.model == "cn"
        or (n.model == "er" and n.kind in {"entity", "attribute"})
        or (n.model == "md" and n.kind in {"dimension", "hierarchy"})
    }


def _terms(
    export: Export,
    nodes: dict[str, Node],
    world: str,
    studio_url: str | None,
) -> tuple[list[UpsertTerm], dict[str, str]]:
    terms = _term_nodes(nodes)
    parents: dict[str, str] = {}
    related: dict[str, set[str]] = defaultdict(set)
    for edge in export.graph.edges:
        if edge.from_id not in terms or edge.to not in terms:
            continue
        source, target = terms[edge.from_id], terms[edge.to]
        parent: tuple[str, str] | None = None
        if edge.kind == "is_a" and source.model == target.model == "cn":
            parent = (edge.from_id, edge.to)
        elif (
            edge.kind == "contains"
            and source.kind == "entity"
            and target.model == "er"
            and target.kind == "attribute"
        ):
            parent = (edge.to, edge.from_id)
        if parent:
            child, owner = parent
            if child in parents and parents[child] != owner:
                raise MappingError(f"multiple glossary parents for {child}")
            parents[child] = owner
        if edge.kind in {"involves", "relation"} or edge.kind.startswith("rel_"):
            # OM's relatedTo relationship is symmetric.
            if source.model == target.model:
                related[source.id].add(target.id)
                related[target.id].add(source.id)

    # OM 2.0.6 enforces name uniqueness across the whole glossary, including
    # children. ER attributes commonly share 'id'/'name'; retain the human label
    # in displayName and use their canonical qname as the stable catalog name.
    names = {
        key: key
        if (node.model == "er" and node.kind == "attribute") or node.model == "md"
        else node.label or key.rsplit(".", 1)[-1]
        for key, node in terms.items()
    }
    owners: set[tuple[str, str]] = set()
    for key, node in terms.items():
        identity = ("Concepts" if node.model == "cn" else "Entities", names[key].casefold())
        if identity in owners:
            raise MappingError(f"glossary name collision at {identity}")
        owners.add(identity)

    addresses: dict[str, str] = {}
    order: list[str] = []
    visiting: set[str] = set()

    def address(key: str) -> str:
        if key in addresses:
            return addresses[key]
        if key in visiting:
            raise MappingError(f"cyclic glossary parent at {key}")
        visiting.add(key)
        node = terms[key]
        glossary = f"{world} {'Concepts' if node.model == 'cn' else 'Entities'}"
        base = address(parents[key]) if key in parents else _segment(glossary)
        result = base + "." + _segment(names[key])
        if result in addresses.values():
            raise MappingError(f"glossary FQN collision at {result}")
        addresses[key] = result
        visiting.remove(key)
        order.append(key)
        return result

    for key in sorted(terms):
        address(key)
    result = []
    for key in order:
        node = terms[key]
        props = _props(node, studio_url)
        if node.iri:
            props["ttrIri"] = node.iri
        if node.model == "md":
            props["ttrKind"] = node.kind
        result.append(
            UpsertTerm(
                qname=key,
                fqn=addresses[key],
                glossary=f"{world} {'Concepts' if node.model == 'cn' else 'Entities'}",
                name=names[key],
                display=_display(node),
                description=_description(node),
                synonyms=sorted({text for texts in node.synonyms.values() for text in texts}),
                parent=addresses.get(parents.get(key, "")),
                related=[RelatedTerm(term=addresses[t]) for t in sorted(related[key])],
                iri=node.iri,
                props=props,
                cn=node.model == "cn",
            )
        )
    return result, addresses


def _metrics(export: Export, nodes: dict[str, Node], studio_url: str | None) -> list[UpsertMetric]:
    result: list[UpsertMetric] = []
    names: set[str] = set()
    for key, node in sorted(nodes.items()):
        if node.model != "md" or node.kind != "measure":
            continue
        name = node.label or key.rsplit(".", 1)[-1]
        if name in names:
            raise MappingError(f"metric name collision at {name}")
        names.add(name)
        definition = export.defs.definitions.get(key)
        if definition is None:
            raise MappingError(f"missing definition for metric {key}")
        props = definition.properties
        aggregation = props.get("aggregation", "")
        if isinstance(aggregation, dict):
            aggregation = aggregation.get("default", "")
        aggregation = str(aggregation or "")
        expression: JsonValue = props.get("definition") or props.get("expression") or ""
        if isinstance(expression, dict):
            expression = expression.get("block", "")
        code = " ".join(str(value) for value in (aggregation, expression) if value)
        extensions = _props(node, studio_url)
        additivity = props.get("class") or node.props.get("additivity")
        if additivity:
            extensions["ttrAdditivity"] = str(additivity)
        metric_type: str = {"SUM": "SUM", "COUNT": "COUNT", "AVG": "AVERAGE"}.get(
            aggregation.upper(), "OTHER"
        )
        result.append(
            UpsertMetric.model_validate(
                {
                    "qname": key,
                    "name": name,
                    "display": _display(node),
                    "description": _description(node),
                    "metricExpression": MetricExpression(code=code),
                    "metricType": metric_type,
                    "props": extensions,
                }
            )
        )
    return result


def _binding_targets(export: Export, nodes: dict[str, Node]) -> dict[str, set[str]]:
    """Resolve groundings and bindings, including per-field MD cubelet maps."""
    targets: dict[str, set[str]] = defaultdict(set)
    for edge in export.graph.edges:
        if edge.kind in {"er2db", "md2db", "md2er", "grounds"}:
            if edge.from_id in nodes and edge.to in nodes:
                targets[edge.from_id].add(edge.to)

    def resolve(ref: str, model: str, kind: str) -> str | None:
        matches = [
            key
            for key, n in nodes.items()
            if n.model == model and n.kind == kind and (key == ref or key.endswith("." + ref))
        ]
        if len(matches) > 1:
            raise MappingError(f"ambiguous binding reference: {ref}")
        return matches[0] if matches else None

    for definition in export.defs.definitions.values():
        if definition.kind != "md2db_cubelet":
            continue
        table = definition.properties.get("target")
        if not isinstance(table, str) or table not in nodes:
            continue
        for field, kind in (("attributesMap", "attribute"), ("measuresMap", "measure")):
            mappings = definition.properties.get(field)
            if not isinstance(mappings, dict):
                continue
            for ref, mapping in sorted(mappings.items()):
                column = mapping.get("column") if isinstance(mapping, dict) else mapping
                source = resolve(ref, "md", kind)
                destination = table + "." + str(column)
                if source and destination in nodes:
                    targets[source].add(destination)

    def leaves(key: str, seen: frozenset[str]) -> set[str]:
        if key in seen:
            return set()
        if key in nodes and nodes[key].model == "db":
            return {key}
        return set().union(*(leaves(t, seen | {key}) for t in targets.get(key, set())))

    return {key: leaves(key, frozenset()) for key in sorted(targets)}


def _assets(
    export: Export,
    nodes: dict[str, Node],
    terms: dict[str, str],
    metrics: list[UpsertMetric],
    studio_url: str | None,
    connections: list[Connection],
) -> tuple[list[PlanOperation], list[str]]:
    warnings: set[str] = set()
    targets = _binding_targets(export, nodes)
    bound = set().union(*targets.values()) if targets else set()
    contains = {
        e.to: e.from_id
        for e in export.graph.edges
        if e.kind == "contains"
        and e.from_id in nodes
        and e.to in nodes
        and nodes[e.from_id].model == "db"
        and nodes[e.from_id].kind in {"table", "view"}
    }
    bound_tables = {contains.get(key, key) for key in bound}
    table_addresses: dict[str, list[str]] = defaultdict(list)
    addresses: dict[str, list[tuple[str, str, str | None]]] = defaultdict(list)
    operations: list[PlanOperation] = []
    usable: list[tuple[Connection, str]] = []
    if not connections and any(n.model == "db" for n in nodes.values()):
        warnings.add("db assets have no connection locator; asset links omitted")
    for connection in sorted(connections, key=lambda c: c.id):
        locator = connection.locators.get("openmetadata")
        service = locator.get("service") if isinstance(locator, dict) else None
        if not isinstance(service, str) or not service or not connection.database:
            warnings.add(
                f"connection {connection.id} has no OM service/database locator; links omitted"
            )
            continue
        usable.append((connection, service))
    owned_fqns: dict[str, str] = {}
    for key, node in sorted(nodes.items()):
        if node.model != "db" or node.kind not in {"table", "view"}:
            continue
        schema = node.schema_name
        for connection, service in usable:
            if connection.schema_name and schema and connection.schema_name != schema:
                continue
            schema_name = schema or connection.schema_name
            if not schema_name:
                warnings.add(f"db asset {key} has no schema; links omitted")
                continue
            fqn = _fqn(
                service, connection.database or "", schema_name, node.label or key.split(".")[-1]
            )
            if fqn in owned_fqns:
                if owned_fqns[fqn] != key:
                    raise MappingError(f"db FQN collision at {fqn}")
                continue
            owned_fqns[fqn] = key
            table_addresses[key].append(fqn)
            addresses[key].append((fqn, "table", None))
            operations.append(
                SetTableProps(
                    fqn=fqn,
                    props=_props(node, studio_url),
                    description=_description(node),
                    tags=["Tatrman.Grounded"] if key in bound_tables else [],
                )
            )
    for column, table in sorted(contains.items()):
        node = nodes[column]
        for fqn in table_addresses.get(table, []):
            name = node.label or column.rsplit(".", 1)[-1]
            addresses[column].append((fqn + "." + _segment(name), "column", name))
            operations.append(
                SetColumnTags(
                    fqn=fqn,
                    column=name,
                    props={"ttrQname": column},
                    tags=["Tatrman.Grounded"] if column in bound else [],
                )
            )
    links: dict[str, PlanOperation] = {}
    for source, destinations in sorted(targets.items()):
        term = terms.get(source)
        # MD dimension attributes are represented by their dimension term (C-15).
        if not term and source in nodes and nodes[source].model == "md":
            term = terms.get(source.rsplit(".", 1)[0])
        metric = next((m for m in metrics if m.qname == source), None)
        for target in sorted(destinations):
            for fqn, asset_type, _ in addresses.get(target, []):
                operation: PlanOperation
                if term:
                    operation = LinkTermToAsset.model_validate(
                        {
                            "term_fqn": term,
                            "asset_fqn": fqn,
                            "asset_type": asset_type,
                        }
                    )
                elif metric:
                    operation = LinkMetricToAsset.model_validate(
                        {
                            "metric": metric.name,
                            "asset_fqn": fqn,
                            "asset_type": asset_type,
                        }
                    )
                else:
                    continue
                links[operation.model_dump_json()] = operation
    operations.extend(links[key] for key in sorted(links))
    return operations, sorted(warnings)


def plan_from_export(
    export: Export,
    world: str | None = None,
    studio_url: str | None = None,
    connections: list[Connection] | None = None,
) -> Plan:
    world = world or export.graph.world
    if world != export.graph.world:
        raise MappingError(f"world {world} differs from export world {export.graph.world}")
    if studio_url and not studio_url.startswith(("http://", "https://")):
        raise MappingError("studio URL must use HTTP(S)")
    nodes = {n.id: n for n in export.graph.nodes if not n.external}
    operations: list[PlanOperation] = [
        EnsureGlossary(name=f"{world} Concepts"),
        EnsureGlossary(name=f"{world} Entities"),
        EnsureClassification(name="Tatrman"),
        EnsureTag(classification="Tatrman", name="Grounded"),
        EnsureTag(classification="Tatrman", name="Deprecated"),
    ]
    registry: dict[str, tuple[str, ...]] = {
        "table": ("ttrQname", "ttrUrl"),
        "tableColumn": ("ttrQname",),
        "glossaryTerm": ("ttrQname", "ttrUrl", "ttrKind", "ttrIri"),
        "metric": ("ttrQname", "ttrUrl", "ttrAdditivity", "ttrColumnAssets"),
    }
    for entity_type, properties in registry.items():
        for name in properties:
            operations.append(
                EnsureCustomProperty.model_validate(
                    {
                        "entity_type": entity_type,
                        "name": name,
                    }
                )
            )
    operations.append(ImportRdf(glossary=f"{world} Concepts", turtle=export.ttl))
    terms, addresses = _terms(export, nodes, world, studio_url)
    operations.extend(terms)
    metrics = _metrics(export, nodes, studio_url)
    operations.extend(metrics)
    assets, warnings = _assets(
        export,
        nodes,
        addresses,
        metrics,
        studio_url,
        export.defs.connections if connections is None else connections,
    )
    operations.extend(assets)
    return Plan(snapshot=export.snapshot, operations=operations, warnings=warnings)


def deprecations(
    previous_ids: set[str],
    current_ids: set[str],
    delta: Delta | None = None,
) -> list[Deprecate]:
    missing = previous_ids - current_ids
    if delta:
        missing |= set(delta.removed)
        missing -= {r.from_id for r in delta.renamed if r.to in current_ids}
    missing -= current_ids
    result: list[Deprecate] = []
    for key in sorted(missing):
        parts = key.split(".")
        kind: str | None = None
        if key.startswith("md.measure."):
            kind = "metric"
        elif (
            parts[0] == "cn"
            or key.startswith("er.entity.")
            or (key.startswith(("md.dimension.", "md.hierarchy.")) and len(parts) == 3)
        ):
            kind = "glossaryTerm"
        elif parts[0] == "db" and len(parts) in {3, 4}:
            kind = "table" if len(parts) == 3 else "column"
        if kind:
            result.append(Deprecate.model_validate({"kind": kind, "qname": key}))
    return result


def json_lines(plan: Plan) -> str:
    return "".join(
        json.dumps(op.model_dump(mode="json"), ensure_ascii=False, separators=(",", ":")) + "\n"
        for op in plan.operations
    )
