# SPDX-License-Identifier: Apache-2.0
"""Versioned export boundary and typed, write-free connector operations."""

from typing import Annotated, Literal

from pydantic import BaseModel, ConfigDict, Field, JsonValue, model_validator


class ExportModel(BaseModel):
    model_config = ConfigDict(extra="allow", populate_by_name=True)


class Rename(ExportModel):
    from_id: str = Field(alias="from")
    to: str


class Delta(ExportModel):
    since: str
    renamed: list[Rename] = Field(default_factory=list)
    removed: list[str] = Field(default_factory=list)


class Node(ExportModel):
    id: str
    model: str | None = None
    kind: str
    label: str | None = None
    iri: str | None = None
    schema_name: str | None = Field(default=None, alias="schema")
    pkg: str | None = None
    external: bool = False
    displayLabel: dict[str, str] = Field(default_factory=dict)
    description: dict[str, str] = Field(default_factory=dict)
    synonyms: dict[str, list[str]] = Field(default_factory=dict)
    props: dict[str, JsonValue] = Field(default_factory=dict)


class Edge(ExportModel):
    id: str | None = None
    from_id: str = Field(alias="from")
    to: str
    kind: str
    label: str | None = None
    props: dict[str, JsonValue] = Field(default_factory=dict)


class Graph(ExportModel):
    graph: Literal["v1"]
    grammar: str
    snapshot: str
    world: str
    scope: dict[str, JsonValue] | None = None
    nodes: list[Node]
    edges: list[Edge]
    delta: Delta | None = None

    @model_validator(mode="after")
    def unique_nodes(self) -> "Graph":
        if len({n.id for n in self.nodes}) != len(self.nodes):
            raise ValueError("duplicate graph node identity")
        return self


class Connection(ExportModel):
    id: str
    database: str | None = None
    schema_name: str | None = Field(default=None, alias="schema")
    locators: dict[str, JsonValue] = Field(default_factory=dict)


class Definition(ExportModel):
    kind: str
    model: str
    pkg: str
    properties: dict[str, JsonValue]


class Definitions(ExportModel):
    defs: Literal["v1"]
    grammar: str
    snapshot: str
    world: str
    connections: list[Connection] = Field(default_factory=list)
    definitions: dict[str, Definition]


class Export(BaseModel):
    graph: Graph
    defs: Definitions
    ttl: str
    snapshot: str

    @model_validator(mode="after")
    def coherent(self) -> "Export":
        if not self.snapshot or {self.snapshot, self.graph.snapshot, self.defs.snapshot} != {
            self.snapshot
        }:
            raise ValueError("export snapshot mismatch")
        if self.graph.world != self.defs.world or self.graph.grammar != self.defs.grammar:
            raise ValueError("export world/grammar mismatch")
        if self.graph.scope is not None:
            raise ValueError("connector requires an unscoped world export")
        return self


class Operation(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)


class EnsureGlossary(Operation):
    op: Literal["EnsureGlossary"] = "EnsureGlossary"
    name: str


class EnsureClassification(Operation):
    op: Literal["EnsureClassification"] = "EnsureClassification"
    name: str


class EnsureTag(Operation):
    op: Literal["EnsureTag"] = "EnsureTag"
    classification: str
    name: str


class EnsureCustomProperty(Operation):
    op: Literal["EnsureCustomProperty"] = "EnsureCustomProperty"
    entity_type: Literal["table", "tableColumn", "glossaryTerm", "metric"]
    name: str
    type: Literal["string", "hyperlink-cp"] = "string"


class ImportRdf(Operation):
    op: Literal["ImportRdf"] = "ImportRdf"
    glossary: str
    turtle: str


class RelatedTerm(Operation):
    term: str
    relationType: Literal["relatedTo"] = "relatedTo"


class UpsertTerm(Operation):
    op: Literal["UpsertTerm"] = "UpsertTerm"
    qname: str
    fqn: str
    glossary: str
    name: str
    display: str
    description: str
    synonyms: list[str]
    parent: str | None
    related: list[RelatedTerm]
    iri: str | None
    props: dict[str, str | dict[str, str]]
    # Explicitly remove an obsolete connector-owned link; preserve other extensions.
    remove_props: list[Literal["ttrUrl", "ttrStudioLink"]] = Field(default_factory=list)
    # Always present for qname/custom-property updates; S2.2 uses the fallback
    # payload for creation when OWL import refuses the graph.
    cn: bool = False


class MetricExpression(Operation):
    code: str
    language: Literal["SQL"] = "SQL"


class UpsertMetric(Operation):
    op: Literal["UpsertMetric"] = "UpsertMetric"
    qname: str
    name: str
    display: str
    description: str
    metricExpression: MetricExpression
    metricType: Literal["SUM", "COUNT", "AVERAGE", "OTHER"]
    props: dict[str, str | dict[str, str]]


class SetTableProps(Operation):
    op: Literal["SetTableProps"] = "SetTableProps"
    fqn: str
    props: dict[str, str | dict[str, str]]
    tags: list[str]
    description: str = ""


class SetColumnTags(Operation):
    op: Literal["SetColumnTags"] = "SetColumnTags"
    fqn: str
    column: str
    props: dict[str, str]
    tags: list[str]


class LinkTermToAsset(Operation):
    op: Literal["LinkTermToAsset"] = "LinkTermToAsset"
    term_fqn: str
    asset_fqn: str
    asset_type: Literal["table", "column"]


class LinkMetricToAsset(Operation):
    """Metrics are not glossary terms; the writer patches Metric.assets."""

    op: Literal["LinkMetricToAsset"] = "LinkMetricToAsset"
    metric: str
    asset_fqn: str
    asset_type: Literal["table", "column"]


class Deprecate(Operation):
    op: Literal["Deprecate"] = "Deprecate"
    kind: Literal["glossaryTerm", "metric", "table", "column"]
    # State stores TTR ids, not OM addresses. The writer resolves this through
    # ttrQname; no fictitious FQN is inferred from a deleted node's leaf name.
    qname: str


type PlanOperation = Annotated[
    EnsureGlossary
    | EnsureClassification
    | EnsureTag
    | EnsureCustomProperty
    | ImportRdf
    | UpsertTerm
    | UpsertMetric
    | SetTableProps
    | SetColumnTags
    | LinkTermToAsset
    | LinkMetricToAsset
    | Deprecate,
    Field(discriminator="op"),
]


class Plan(BaseModel):
    snapshot: str
    operations: list[PlanOperation]
    warnings: list[str] = Field(default_factory=list)
