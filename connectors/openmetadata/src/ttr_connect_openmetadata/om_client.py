# SPDX-License-Identifier: Apache-2.0
"""OM 2.0.6 boundary: typed SDK writes, checked REST, no asset creation/deletion."""

import json
import time
from collections.abc import Callable, Iterator
from typing import Any, Literal, TypeVar
from urllib.parse import quote, urlsplit
from uuid import UUID

import requests
from metadata.generated.schema.api.classification.createClassification import (
    CreateClassificationRequest,
)
from metadata.generated.schema.api.classification.createTag import CreateTagRequest
from metadata.generated.schema.api.data.createGlossary import CreateGlossaryRequest
from metadata.generated.schema.api.data.createGlossaryTerm import CreateGlossaryTermRequest
from metadata.generated.schema.api.data.createMetric import CreateMetricRequest
from metadata.generated.schema.entity.classification.classification import Classification
from metadata.generated.schema.entity.classification.tag import Tag
from metadata.generated.schema.entity.data.glossary import Glossary
from metadata.generated.schema.entity.data.glossaryTerm import GlossaryTerm
from metadata.generated.schema.entity.data.metric import Metric
from metadata.generated.schema.entity.data.table import Table
from metadata.generated.schema.entity.services.connections.metadata.openMetadataConnection import (
    OpenMetadataConnection,
)
from metadata.generated.schema.entity.type import Type
from metadata.generated.schema.type.customProperty import CustomProperty
from metadata.ingestion.ometa.ometa_api import OpenMetadata
from pydantic import BaseModel

from .mapping import om_name, studio_link
from .model import (
    REGISTRY,
    STUDIO_LINK_KEYS,
    Deprecate,
    EnsureCustomProperty,
    EnsureGlossary,
    EnsureTag,
    FieldType,
    ImportRdf,
    LinkMetricToAsset,
    LinkTermToAsset,
    Plan,
    SetColumnTags,
    SetTableProps,
    StudioLink,
    UpsertMetric,
    UpsertTerm,
)
from .urls import http_origin

T = TypeVar("T")
M = TypeVar("M", bound=BaseModel)
Json = dict[str, Any]


class OmError(Exception):
    def __init__(self, status: int | None, body: str) -> None:
        self.status = status
        self.body = body
        super().__init__(f"OM {status if status is not None else 'transport'}: {body}")


class NativeHyperlinkType(BaseModel):
    # The pinned SDK's Type.name rejects hyphens, including this real built-in.
    # Validate its exact name and UUID at the narrow REST lookup boundary.
    id: UUID
    name: Literal["hyperlink-cp"]


class Summary(BaseModel):
    snapshot: str
    created: int = 0
    updated: int = 0
    deprecated: int = 0
    linked: int = 0
    warnings: int = 0
    not_modified: bool = False


class _Paging(BaseModel):
    after: str | None = None


class _Page(BaseModel):
    data: list[Json]
    paging: _Paging


def payload(model: BaseModel) -> Json:
    return model.model_dump(mode="json", by_alias=True, exclude_none=True)


def _glossary_name(term: GlossaryTerm) -> str | None:
    return str(term.glossary.name) if term.glossary and term.glossary.name else None


def tag(fqn: str, source: str = "Classification") -> Json:
    return {"tagFQN": fqn, "source": source, "labelType": "Manual", "state": "Confirmed"}


def merge_tags(existing: list[Json], wanted: list[str], *, remove: str | None = None) -> list[Json]:
    result = [t for t in existing if t["tagFQN"] != remove]
    known = {t["tagFQN"] for t in result}
    return result + [tag(t) for t in wanted if t not in known]


def columns(items: list[Json]) -> Iterator[Json]:
    for column in items:
        yield column
        yield from columns(column.get("children") or [])


class OmClient:
    def __init__(self, url: str, token: str) -> None:
        try:
            origin = http_origin(url, allow_path=True, link_host=False)
        except ValueError as exc:
            raise OmError(None, f"--om-url: {exc}") from exc
        if not token.strip():
            raise OmError(None, "empty OM token")
        self.url = (origin + urlsplit(url).path).rstrip("/")
        self.session = requests.Session()
        self.session.headers["Authorization"] = f"Bearer {token.strip()}"
        config = OpenMetadataConnection.model_validate(
            {
                "hostPort": self.url,
                "authProvider": "openmetadata",
                "securityConfig": {"jwtToken": token.strip()},
                "enableVersionValidation": False,
            }
        )
        # Own retries below: no nested SDK retry, rate-limit swallowing, or hidden 30s sleep.
        self.ometa = OpenMetadata(
            config,
            additional_client_config_arguments={
                "retry": 0,
                "limit_codes": [],
                "timeout": 30,
            },
        )
        self.summary = Summary(snapshot="")
        self.warnings: list[str] = []
        self.skipped_tables: set[str] = set()
        self.skipped_columns: set[str] = set()
        self._tables: dict[str, Table | None] = {}
        self._terms: dict[str, GlossaryTerm] = {}
        self._metrics: dict[str, Metric] = {}
        self._term_aliases: dict[str, GlossaryTerm] = {}
        self._types: dict[str, Json] = {}
        self._glossaries: set[str] = set()
        self._metric_aliases: dict[str, Metric] = {}
        self.version = ""

    def close(self) -> None:
        self.session.close()
        self.ometa.client.close()

    def _retry(self, call: Callable[[], T]) -> T:
        # Three retries after the original attempt; 4xx and transport failures fail immediately.
        for attempt in range(4):
            try:
                return call()
            except Exception as exc:
                current: BaseException | None = exc
                status: int | None = None
                body = str(exc)
                while current is not None:
                    if isinstance(current, OmError):
                        status, body = current.status, current.body
                        break
                    response = getattr(current, "response", None)
                    if response is not None:
                        status, body = response.status_code, response.text
                        break
                    current = current.__cause__
                if status is not None and 500 <= status < 600 and attempt < 3:
                    time.sleep(0.25 * 2**attempt)
                    continue
                raise OmError(status, body) from exc
        raise AssertionError("unreachable")

    def _rest(
        self,
        method: str,
        path: str,
        *,
        data: str | None = None,
        json: Json | None = None,
        params: dict[str, str] | None = None,
        missing: bool = False,
    ) -> Any:
        def request() -> Any:
            response = self.session.request(
                method,
                f"{self.url}/v1/{path}",
                data=data,
                json=json,
                params=params,
                headers={"Content-Type": "text/turtle"} if data is not None else None,
                timeout=30,
                allow_redirects=False,
            )
            if missing and response.status_code == 404:
                return None
            if not 200 <= response.status_code < 300:
                raise OmError(response.status_code, response.text)
            if not response.content:
                return None
            return response.json()

        return self._retry(request)

    def _get(self, cls: type[M], collection: str, name: str, fields: str = "") -> M | None:
        data = self._rest(
            "GET",
            f"{collection}/name/{quote(name, safe='')}",
            params={"fields": fields} if fields else None,
            missing=True,
        )
        return cls.model_validate(data) if data is not None else None

    def _list(self, cls: type[M], collection: str, fields: str) -> list[M]:
        result: list[M] = []
        params = {"limit": "100", "fields": fields}
        cursors: set[str] = set()
        while True:
            page = _Page.model_validate(self._rest("GET", collection, params=params))
            result.extend(cls.model_validate(item) for item in page.data)
            after = page.paging.after
            if not after:
                return result
            if after in cursors:
                raise OmError(None, f"repeated pagination cursor in {collection}")
            cursors.add(after)
            params["after"] = after

    def _create(self, request: BaseModel, cls: type[M]) -> M:
        result = self._retry(lambda: self.ometa.create_or_update(request))
        if result is None:
            raise OmError(None, "SDK returned no entity after create_or_update")
        self.summary.created += 1
        return cls.model_validate(result)

    def _patch(self, source: M, changes: Json, *, count: bool = True) -> M:
        before = payload(source)
        if (
            isinstance(source, GlossaryTerm)
            and before.get("entityStatus") == "Deprecated"
            and changes.keys() - {"entityStatus"}
        ):
            raise OmError(
                None,
                f"refusing a non-status edit to Deprecated term {source.fullyQualifiedName.root}",
            )
        if all(before.get(key) == value for key, value in changes.items()):
            return source
        version = before.get("version")
        if "entityStatus" in changes and version is None:
            raise OmError(None, "cannot conditionally patch term status without its version")
        destination = type(source).model_validate({**before, **changes})
        result = self._retry(
            lambda: self.ometa.patch(
                entity=type(source),
                source=source.model_copy(deep=True),
                destination=destination.model_copy(deep=True),
                allowed_fields={key: True for key in changes},
                override_metadata=True,
                skip_on_failure=False,
                # Status-only changes must not consolidate earlier metadata edits
                # into their event and retrigger approval. Weak ETags are the
                # server's projection-independent conditional-write validator.
                if_match=(f'W/"{version}"' if "entityStatus" in changes else None),
            )
        )
        if result is None:
            raise OmError(None, "SDK produced no patch for changed managed fields")
        if count:
            self.summary.updated += 1
        return type(source).model_validate(result)

    def warn(self, message: str) -> None:
        if message not in self.warnings:
            self.warnings.append(message)
            self.summary.warnings += 1

    def ensure_glossary(self, op: EnsureGlossary) -> Glossary:
        entity = self._get(Glossary, "glossaries", om_name(op.name))
        if entity is None:
            entity = self._create(
                CreateGlossaryRequest.model_validate(
                    {
                        "name": op.name,
                        "description": "Tatrman model projection",
                    }
                ),
                Glossary,
            )
        return entity

    def ensure_classification(self, name: str) -> None:
        if self._get(Classification, "classifications", name) is None:
            self._create(
                CreateClassificationRequest.model_validate(
                    {
                        "name": name,
                        "description": "Tatrman model projection",
                        "mutuallyExclusive": False,
                    }
                ),
                Classification,
            )

    def ensure_tag(self, op: EnsureTag) -> None:
        if self._get(Tag, "tags", f"{op.classification}.{op.name}") is None:
            self._create(
                CreateTagRequest.model_validate(
                    {
                        "name": op.name,
                        "classification": op.classification,
                        "description": f"Tatrman {op.name}",
                    }
                ),
                Tag,
            )

    def _entity_type(self, name: str) -> Json:
        if name not in self._types:
            value = Type.model_validate(
                self._rest(
                    "GET", f"metadata/types/name/{name}", params={"fields": "customProperties"}
                )
            )
            if value.id is None:
                raise OmError(None, f"OM type {name} has no id")
            self._types[name] = payload(value)
        return self._types[name]

    def _field_type(self, name: FieldType) -> Json:
        if name == "string":
            return self._entity_type(name)
        if name not in self._types:
            value = NativeHyperlinkType.model_validate(
                self._rest("GET", f"metadata/types/name/{name}")
            )
            self._types[name] = payload(value)
        return self._types[name]

    def ensure_custom_property(self, op: EnsureCustomProperty) -> None:
        entity = self._entity_type(op.entity_type)
        property_type = self._field_type(op.type)
        properties = entity.get("customProperties") or []
        existing = next((p for p in properties if p["name"] == op.name), None)
        if existing is not None:
            if existing["propertyType"]["id"] != property_type["id"]:
                raise OmError(409, f"{op.entity_type}.{op.name} exists with a non-{op.type} type")
            return
        prop = {
            "name": op.name,
            "description": f"Tatrman {op.name}",
            "propertyType": {"id": property_type["id"], "type": "type"},
        }
        prop = payload(CustomProperty.model_validate(prop))
        self._rest("PUT", f"metadata/types/{entity['id']}", json=prop)
        entity["customProperties"] = [*properties, prop]
        self.summary.created += 1

    def read_version(self) -> str:
        """Read-only version probe, also used before conditional export requests."""
        response = self._rest("GET", "system/version")
        version = response.get("version") if isinstance(response, dict) else response
        if not isinstance(version, str) or not version.strip():
            raise OmError(None, "OM health response contains no version")
        self.version = version
        return version

    def check(self) -> str:
        self.read_version()
        for kind, properties in REGISTRY.items():
            for name, field_type in properties:
                self.ensure_custom_property(
                    EnsureCustomProperty(entity_type=kind, name=name, type=field_type)
                )
        self.ensure_classification("Tatrman")
        for name in ("Grounded", "Deprecated"):
            self.ensure_tag(EnsureTag(classification="Tatrman", name=name))
        return self.version

    def _load_terms(self) -> None:
        self._terms = {
            str(t.id.root): t
            for t in self._list(
                GlossaryTerm,
                "glossaryTerms",
                "extension,relatedTerms,parent,synonyms,tags",
            )
        }

    def _find_term(self, op: UpsertTerm) -> GlossaryTerm | None:
        scoped = [t for t in self._terms.values() if _glossary_name(t) == op.glossary]
        candidates = [
            t for t in scoped if payload(t).get("extension", {}).get("ttrQname") == op.qname
        ]
        if not candidates and op.iri:
            candidates = [t for t in scoped if str(t.iri or "") == op.iri]
        if not candidates:
            candidates = [
                t for t in self._terms.values() if payload(t).get("fullyQualifiedName") == op.fqn
            ]
        if len(candidates) > 1:
            raise OmError(409, f"ambiguous term identity {op.qname}")
        if candidates:
            owner = payload(candidates[0]).get("extension", {}).get("ttrQname")
            if owner and owner != op.qname:
                raise OmError(409, f"term {op.fqn} already owned by {owner}")
            return candidates[0]
        return None

    def import_rdf(self, op: ImportRdf) -> bool:
        try:
            result = self._rest(
                "PUT",
                f"glossaries/name/{quote(om_name(op.glossary), safe='')}/importRdf",
                data=op.turtle,
                params={"format": "turtle", "dryRun": "false"},
            )
            if not isinstance(result, dict) or result.get("dryRun") is not False:
                raise OmError(None, "RDF import did not confirm dryRun=false")
            messages = result.get("messages") or []
            if any(str(message).startswith("Failed ") for message in messages):
                raise OmError(None, f"RDF import reported failures: {messages}")
            for message in messages:
                self.warn(f"RDF import: {message}")
            previous = set(self._terms)
            self._load_terms()
            self.summary.created += len(set(self._terms) - previous)
            self.summary.updated += int(result.get("termsUpdated", 0))
            return True
        except OmError as exc:
            if (
                exc.status is not None
                and 400 <= exc.status < 500
                and exc.status not in (401, 403, 429)
            ):
                self.warn(
                    f"RDF import refused ({exc.status}); using glossary fallback for {op.glossary}"
                )
                return False
            raise

    def _remember_term(self, op: UpsertTerm, term: GlossaryTerm) -> None:
        self._terms[str(term.id.root)] = term
        self._term_aliases[op.fqn] = term

    @staticmethod
    def _extensions(props: dict[str, str], studio_link: StudioLink | None) -> Json:
        result: Json = dict(props)
        if studio_link is not None:
            result["ttrStudioLink"] = payload(studio_link)
        return result

    def upsert_term(self, op: UpsertTerm) -> None:
        term = self._find_term(op)
        needs_iri = bool(op.iri and (term is None or not term.iri))
        if term is None:
            parent = self._term_aliases.get(op.parent or "")
            term = self._create(
                CreateGlossaryTermRequest.model_validate(
                    {
                        "name": op.name,
                        "glossary": om_name(op.glossary),
                        "parent": str(parent.fullyQualifiedName.root) if parent else op.parent,
                        "displayName": op.display,
                        "description": op.description,
                        "synonyms": op.synonyms,
                    }
                ),
                GlossaryTerm,
            )
            # The 2.0.6 request mapper ignores iri. Its updater also does not
            # track iri-only patches: pair it with registered source properties.
            term = self._patch(
                term, {"iri": op.iri, "extension": self._extensions(op.props, op.studio_link)}
            )
        else:
            # Reappearing model terms reactivate through a pure status event first.
            # Never send metadata edits while a term is still Deprecated.
            reactivated = payload(term).get("entityStatus") == "Deprecated"
            if reactivated:
                term = self._patch(term, {"entityStatus": "Draft"})
            before = payload(term)
            extension = {
                **before.get("extension", {}),
                **self._extensions(op.props, op.studio_link),
            }
            for name in op.remove_props:
                extension.pop(name, None)
            changes: Json = {
                "displayName": op.display,
                "description": op.description,
                "synonyms": op.synonyms,
                "extension": extension,
            }
            # An established source identity is immutable. Fill only an absent IRI.
            if op.iri and not term.iri:
                changes["iri"] = op.iri
            desired_parent = self._term_aliases.get(op.parent or "")
            current_parent = before.get("parent")
            if desired_parent is not None:
                if not current_parent or current_parent["id"] != str(desired_parent.id.root):
                    changes["parent"] = self._reference(desired_parent, "glossaryTerm")
            elif current_parent is not None and op.parent is None:
                changes["parent"] = None
            term = self._patch(term, changes, count=not reactivated)
        if needs_iri:
            refreshed = self._get(
                GlossaryTerm,
                "glossaryTerms",
                str(term.fullyQualifiedName.root),
                "extension,parent,relatedTerms,synonyms,tags",
            )
            if refreshed is None or str(refreshed.iri) != op.iri:
                raise OmError(None, f"OM did not retain source IRI for {op.qname}")
            term = refreshed
        self._remember_term(op, term)

    def relate_terms(self, ops: list[UpsertTerm]) -> None:
        for op in ops:
            term = self._term_aliases[op.fqn]
            before = payload(term)
            related = list(before.get("relatedTerms") or [])
            known = {(r["term"]["id"], r.get("relationType", "relatedTo")) for r in related}
            for relation in op.related:
                target = self._term_aliases.get(relation.term)
                if target is None:
                    raise OmError(None, f"missing related term {relation.term}")
                if (str(target.id.root), relation.relationType) not in known:
                    related.append(
                        {
                            "term": self._reference(target, "glossaryTerm"),
                            "relationType": relation.relationType,
                        }
                    )
            if related != (before.get("relatedTerms") or []):
                term = self._patch(term, {"relatedTerms": related})
                self._remember_term(op, term)

    @staticmethod
    def _reference(entity: BaseModel, kind: str) -> Json:
        data = payload(entity)
        return {"id": data["id"], "type": kind, "fullyQualifiedName": data["fullyQualifiedName"]}

    def upsert_metric(self, op: UpsertMetric) -> None:
        candidates = [
            m
            for m in self._metrics.values()
            if payload(m).get("extension", {}).get("ttrQname") == op.qname
        ]
        if not candidates:
            candidates = [m for m in self._metrics.values() if str(m.name.root) == op.name]
        if len(candidates) > 1:
            raise OmError(409, f"ambiguous metric identity {op.qname}")
        fields: Json = {
            "displayName": op.display,
            "description": op.description,
            "metricExpression": op.metricExpression.model_dump(),
            "metricType": op.metricType,
        }
        if candidates:
            metric = candidates[0]
            data = payload(metric)
            owner = data.get("extension", {}).get("ttrQname")
            if owner and owner != op.qname:
                raise OmError(409, f"metric {op.name} already owned by {owner}")
            metric = self._patch(
                metric,
                {
                    **fields,
                    "extension": {
                        **data.get("extension", {}),
                        **self._extensions(op.props, op.studio_link),
                    },
                    "tags": merge_tags(data.get("tags") or [], [], remove="Tatrman.Deprecated"),
                },
            )
        else:
            metric = self._create(
                CreateMetricRequest.model_validate(
                    {
                        "name": op.name,
                        **fields,
                        "extension": self._extensions(op.props, op.studio_link),
                    }
                ),
                Metric,
            )
        self._metrics[str(metric.id.root)] = metric
        self._metric_aliases[op.name] = metric

    def get_table(self, fqn: str) -> Table | None:
        if fqn not in self._tables:
            self._tables[fqn] = self._get(Table, "tables", fqn, "columns,tags,extension")
            if self._tables[fqn] is None:
                self.skipped_tables.add(fqn)
                self.warn(f"OM has not scanned table {fqn}; skipping its properties and links")
        return self._tables[fqn]

    def set_table_props(self, op: SetTableProps) -> None:
        table = self.get_table(op.fqn)
        if table is None:
            return
        data = payload(table)
        changes: Json = {
            "extension": {
                **data.get("extension", {}),
                **self._extensions(op.props, op.studio_link),
            },
            "tags": merge_tags(data.get("tags") or [], op.tags),
        }
        if op.description:
            changes["description"] = op.description
        self._tables[op.fqn] = self._patch(table, changes)

    def set_column_tags(self, op: SetColumnTags) -> None:
        table = self.get_table(op.fqn)
        if table is None:
            return
        data = payload(table)
        column = next((c for c in columns(data["columns"]) if c["name"] == op.column), None)
        if column is None:
            self.skipped_columns.add(f"{op.fqn}.{op.column}")
            self.warn(f"OM has not scanned column {op.fqn}.{op.column}; skipping")
            return
        column["extension"] = {**column.get("extension", {}), **op.props}
        column["tags"] = merge_tags(column.get("tags") or [], op.tags)
        self._tables[op.fqn] = self._patch(table, {"columns": data["columns"]})

    def _asset(self, fqn: str, kind: str) -> tuple[str, Table, Json] | None:
        if kind == "table":
            table = self.get_table(fqn)
            return (fqn, table, payload(table)) if table is not None else None
        # All planner columns belong to a SetTableProps operation, loaded first.
        for name, table in self._tables.items():
            if table is not None:
                column = next(
                    (
                        c
                        for c in columns(payload(table)["columns"])
                        if c.get("fullyQualifiedName") == fqn
                    ),
                    None,
                )
                if column is not None:
                    return name, table, column
        if any(
            table is None and fqn.startswith(name + ".") for name, table in self._tables.items()
        ):
            return None
        self.skipped_columns.add(fqn)
        self.warn(f"OM has not scanned column {fqn}; skipping its links")
        return None

    def link_term_to_asset(self, op: LinkTermToAsset) -> None:
        asset = self._asset(op.asset_fqn, op.asset_type)
        if asset is None:
            return
        table_name, table, target = asset
        term = self._term_aliases[op.term_fqn]
        fqn = payload(term)["fullyQualifiedName"]
        if any(t["tagFQN"] == fqn and t["source"] == "Glossary" for t in target.get("tags") or []):
            return
        ref = {
            "id": str(table.id.root),
            "type": "tableColumn" if op.asset_type == "column" else "table",
            "fullyQualifiedName": op.asset_fqn,
        }
        result = self._rest(
            "PUT",
            f"glossaryTerms/{term.id.root}/assets/add",
            json={"dryRun": False, "assets": [ref]},
        )
        if (
            not isinstance(result, dict)
            or result.get("dryRun") is not False
            or result.get("numberOfRowsFailed", 0)
            or result.get("failedRequest")
            or result.get("numberOfRowsPassed") != 1
            or str(result.get("status", "")).lower() != "success"
        ):
            raise OmError(None, "glossary asset link reported failure")
        # Refetch so subsequent column patches preserve the server's glossary tags.
        self._tables[table_name] = self._get(Table, "tables", table_name, "columns,tags,extension")
        refreshed = self._asset(op.asset_fqn, op.asset_type)
        if refreshed is None or not any(
            t["tagFQN"] == fqn and t["source"] == "Glossary" for t in refreshed[2].get("tags") or []
        ):
            raise OmError(None, f"OM did not retain glossary asset link {op.asset_fqn}")
        self.summary.linked += 1

    def link_metric_to_asset(self, op: LinkMetricToAsset) -> None:
        asset = self._asset(op.asset_fqn, op.asset_type)
        if asset is None:
            return
        table_name, table, _ = asset
        metric = self._metric_aliases[op.metric]
        data = payload(metric)
        assets = data.get("assets") or []
        # OM 2.0.6 silently drops tableColumn from Metric.assets. Use its
        # supported parent-table association and retain exact column FQNs in a
        # registered string property. Never claim a native column relation.
        native_fqn = table_name if op.asset_type == "column" else op.asset_fqn
        ref = {
            "id": str(table.id.root),
            "type": "table",
            "fullyQualifiedName": native_fqn,
        }
        changes: Json = {}
        if not any(a.get("fullyQualifiedName") == native_fqn for a in assets):
            changes["assets"] = [*assets, ref]
        column_fqns: list[str] = []
        if op.asset_type == "column":
            extension = data.get("extension") or {}
            try:
                saved = json.loads(extension.get("ttrColumnAssets", "[]"))
            except (TypeError, ValueError) as exc:
                raise OmError(409, "invalid metric ttrColumnAssets JSON") from exc
            if not isinstance(saved, list) or not all(isinstance(v, str) for v in saved):
                raise OmError(409, "metric ttrColumnAssets must be an array of column FQNs")
            column_fqns = sorted(set([*saved, op.asset_fqn]))
            encoded = json.dumps(column_fqns, ensure_ascii=False, separators=(",", ":"))
            if extension.get("ttrColumnAssets") != encoded:
                changes["extension"] = {**extension, "ttrColumnAssets": encoded}
        if not changes:
            return
        metric = self._patch(metric, changes, count=False)
        # Read independently: a successful PATCH response alone is insufficient.
        metric = self._get(
            Metric, "metrics", str(metric.fullyQualifiedName.root), "extension,tags,assets"
        )
        if metric is None or not any(
            a.get("fullyQualifiedName") == native_fqn for a in payload(metric).get("assets") or []
        ):
            raise OmError(None, f"OM did not retain metric asset {op.asset_fqn}")
        if column_fqns and payload(metric).get("extension", {}).get("ttrColumnAssets") != encoded:
            raise OmError(None, f"OM did not retain metric column {op.asset_fqn}")
        self._metrics[str(metric.id.root)] = metric
        self._metric_aliases[op.metric] = metric
        self.summary.linked += 1

    def normalize_out_of_plan_term_links(self, plan: Plan) -> None:
        current = {op.qname for op in plan.operations if isinstance(op, UpsertTerm)}
        retiring = {
            op.qname
            for op in plan.operations
            if isinstance(op, Deprecate) and op.kind == "glossaryTerm"
        }
        excluded = current | retiring
        for term in list(self._terms.values()):
            if _glossary_name(term) not in self._glossaries:
                continue
            data = payload(term)
            extension = data.get("extension") or {}
            qname = extension.get("ttrQname")
            # Non-status edits re-run approval; only Approved/Draft orphans are eligible.
            # Skip planned retirements too, even if the cache predates that status.
            if (
                data.get("entityStatus") not in {"Approved", "Draft"}
                or not isinstance(qname, str)
                or not qname
                or qname in excluded
            ):
                continue
            desired = {k: v for k, v in extension.items() if k not in STUDIO_LINK_KEYS}
            model = qname.split(".", 1)[0]
            link = studio_link(model, plan.studio_base) if model in {"er", "md"} else None
            if link is not None:
                desired.update(ttrUrl=link.url, ttrStudioLink=payload(link))
            if desired == extension:
                continue
            result = self._patch(term, {"extension": desired})
            persisted = self._get(
                GlossaryTerm, "glossaryTerms", str(result.fullyQualifiedName.root), "extension"
            )
            if persisted is None or payload(persisted).get("extension", {}) != desired:
                raise OmError(None, f"OM did not retain Studio links for {qname}")
            self._terms[str(persisted.id.root)] = persisted

    def deprecate_term(self, term: GlossaryTerm) -> None:
        if payload(term).get("entityStatus") != "Deprecated":
            result = self._patch(term, {"entityStatus": "Deprecated"}, count=False)
            persisted = self._get(
                GlossaryTerm, "glossaryTerms", str(result.fullyQualifiedName.root), "extension"
            )
            if persisted is None or payload(persisted).get("entityStatus") != "Deprecated":
                raise OmError(None, "OM did not retain term deprecation")
            self._terms[str(persisted.id.root)] = persisted
            self.summary.deprecated += 1

    def deprecate_metric(self, metric: Metric) -> None:
        tags = payload(metric).get("tags") or []
        if not any(t["tagFQN"] == "Tatrman.Deprecated" for t in tags):
            result = self._patch(
                metric, {"tags": merge_tags(tags, ["Tatrman.Deprecated"])}, count=False
            )
            self._metrics[str(result.id.root)] = result
            self.summary.deprecated += 1

    def deprecate(self, op: Deprecate) -> None:
        if op.kind == "glossaryTerm":
            for term in list(self._terms.values()):
                if (
                    _glossary_name(term) in self._glossaries
                    and payload(term).get("extension", {}).get("ttrQname") == op.qname
                ):
                    self.deprecate_term(term)
        elif op.kind == "metric":
            for metric in list(self._metrics.values()):
                if payload(metric).get("extension", {}).get("ttrQname") == op.qname:
                    self.deprecate_metric(metric)
        else:
            # Deleted qnames cannot supply an OM address: enumerate all pages, never guess.
            for table in self._list(Table, "tables", "columns,tags,extension"):
                data = payload(table)
                changed = False
                for target in [data] if op.kind == "table" else columns(data["columns"]):
                    if target.get("extension", {}).get("ttrQname") == op.qname:
                        before = target.get("tags") or []
                        after = [t for t in before if t["tagFQN"] != "Tatrman.Grounded"]
                        if before != after:
                            target["tags"] = after
                            changed = True
                if changed:
                    result = self._patch(
                        table,
                        {"tags": data.get("tags") or [], "columns": data["columns"]},
                        count=False,
                    )
                    self._tables[str(result.fullyQualifiedName.root)] = result
                    self.summary.deprecated += 1

    def sync(self, plan: Plan, cn_path: str = "glossary") -> Summary:
        self.summary = Summary(snapshot=plan.snapshot)
        self.warnings.clear()
        self.skipped_tables.clear()
        self.skipped_columns.clear()
        self._tables.clear()
        self._types.clear()
        self._term_aliases.clear()
        self._metric_aliases.clear()
        self._glossaries = {op.name for op in plan.operations if isinstance(op, EnsureGlossary)}
        self.check()
        for warning in plan.warnings:
            self.warn(warning)
        self._load_terms()
        self._metrics = {}
        # OM 2.0.6 list does not populate assets even when requested; get each
        # metric separately so repeat syncs preserve links and remain no-ops.
        for listed in self._list(Metric, "metrics", "extension,tags"):
            metric = self._get(
                Metric, "metrics", str(listed.fullyQualifiedName.root), "extension,tags,assets"
            )
            if metric is None:
                raise OmError(None, "metric disappeared while loading catalog")
            self._metrics[str(metric.id.root)] = metric
        terms = [op for op in plan.operations if isinstance(op, UpsertTerm)]
        for op in plan.operations:
            if isinstance(op, EnsureGlossary):
                self.ensure_glossary(op)
            elif isinstance(op, ImportRdf) and cn_path == "owl":
                # Existing CN entities are reconciled directly. A repeat sync never reimports RDF.
                if any(
                    t.cn and t.glossary == op.glossary and self._find_term(t) is None for t in terms
                ):
                    self.import_rdf(op)
            elif isinstance(op, UpsertTerm):
                self.upsert_term(op)
            elif isinstance(op, UpsertMetric):
                self.upsert_metric(op)
            elif isinstance(op, SetTableProps):
                self.set_table_props(op)
            elif isinstance(op, SetColumnTags):
                self.set_column_tags(op)
            # Registry operations have already run in check; links follow all entity writes.
        self.relate_terms(terms)
        for op in plan.operations:
            if isinstance(op, LinkTermToAsset):
                self.link_term_to_asset(op)
            elif isinstance(op, LinkMetricToAsset):
                self.link_metric_to_asset(op)
            elif isinstance(op, Deprecate):
                self.deprecate(op)
        if plan.studio_links:
            self.normalize_out_of_plan_term_links(plan)
        return self.summary
