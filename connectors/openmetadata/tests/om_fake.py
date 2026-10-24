# SPDX-License-Identifier: Apache-2.0
"""Stateful HTTP oracle. SDK requests cross the real requests/JSON-patch boundary."""

import json
import re
from copy import deepcopy
from typing import Any
from urllib.parse import unquote, urlsplit
from uuid import NAMESPACE_URL, uuid5

import jsonpatch  # type: ignore[import-untyped]
import requests
import responses

from ttr_connect_openmetadata.mapping import plan_from_export
from ttr_connect_openmetadata.model import Export, SetColumnTags, SetTableProps

URL = "http://om.example/api"


def uid(name: str) -> str:
    return str(uuid5(NAMESPACE_URL, name))


class FakeOm:
    def __init__(self, export: Export) -> None:
        self.stores: dict[str, dict[str, dict[str, Any]]] = {
            key: {}
            for key in (
                "glossaries",
                "glossaryTerms",
                "metrics",
                "tables",
                "classifications",
                "tags",
                "metadata/types",
            )
        }
        self.calls: list[tuple[str, str, Any]] = []
        self.import_status = 400
        self.import_terms: list[dict[str, Any]] = []
        self.import_result: dict[str, Any] = {
            "dryRun": False,
            "termsCreated": 0,
            "termsUpdated": 0,
            "messages": [],
        }
        self.drop_metric_assets = False
        self.link_failed = False
        self.failures: dict[tuple[str, str], list[int]] = {}
        self.page_size = 100
        for name in ("string", "table", "column", "glossaryTerm", "metric"):
            self.stores["metadata/types"][name] = {
                "id": uid(name),
                "name": name,
                "description": name,
                "customProperties": [],
            }
        for op in plan_from_export(export).operations:
            if isinstance(op, SetTableProps):
                self.stores["tables"][op.fqn] = {
                    "id": uid(op.fqn),
                    "name": op.fqn.rsplit(".", 1)[-1],
                    "fullyQualifiedName": op.fqn,
                    "columns": [],
                    "tags": [],
                    "extension": {"catalogOwner": "retained"},
                }
            elif isinstance(op, SetColumnTags):
                self.stores["tables"][op.fqn]["columns"].append(
                    {
                        "name": op.column,
                        "dataType": "INT",
                        "fullyQualifiedName": f"{op.fqn}.{op.column}",
                        "tags": [],
                        "extension": {"catalogColumn": "retained"},
                    }
                )

    @property
    def writes(self) -> list[tuple[str, str, Any]]:
        return [call for call in self.calls if call[0] != "GET"]

    def install(self, mock: responses.RequestsMock) -> None:
        for method in ("GET", "PUT", "PATCH", "DELETE"):
            mock.add_callback(method, re.compile(re.escape(URL) + r"/v1/.*"), callback=self.respond)

    def respond(self, request: requests.PreparedRequest) -> tuple[int, dict[str, str], str]:
        assert request.headers.get("Authorization") == "Bearer fixture-token"
        assert request.url
        path = unquote(urlsplit(request.url).path.removeprefix("/api/v1/"))
        body = request.body
        if isinstance(body, bytes):
            body = body.decode()
        data: Any = None
        if body:
            data = (
                body if request.headers.get("Content-Type") == "text/turtle" else json.loads(body)
            )
        method = str(request.method)
        self.calls.append((method, path, deepcopy(data)))
        failures = self.failures.get((method, path), [])
        if failures:
            return self.answer(failures.pop(0), {"code": 1, "message": "injected failure"})
        if method == "DELETE":
            return self.answer(405, {"message": "never delete"})
        if path == "system/version":
            return self.answer(200, {"version": "2.0.6"})
        if "/importRdf" in path:
            assert "dryRun=false" in request.url
            if self.import_status == 200:
                for term in self.import_terms:
                    self.stores["glossaryTerms"][term["fullyQualifiedName"]] = deepcopy(term)
                return self.answer(200, self.import_result)
            return self.answer(self.import_status, {"message": "RDF result"})
        if path.endswith("/assets/add"):
            assert data["dryRun"] is False
            if self.link_failed:
                return self.answer(200, {"status": "partialSuccess", "numberOfRowsFailed": 1})
            term_id = path.split("/")[1]
            term = next(t for t in self.stores["glossaryTerms"].values() if t["id"] == term_id)
            for asset in data["assets"]:
                if asset["type"] == "tableColumn":
                    table = next(
                        t for t in self.stores["tables"].values() if t["id"] == asset["id"]
                    )
                    target = next(
                        c
                        for c in table["columns"]
                        if c["fullyQualifiedName"] == asset["fullyQualifiedName"]
                    )
                else:
                    target = self.stores["tables"][asset["fullyQualifiedName"]]
                target.setdefault("tags", []).append(
                    {
                        "tagFQN": term["fullyQualifiedName"],
                        "source": "Glossary",
                        "labelType": "Manual",
                        "state": "Confirmed",
                    }
                )
            return self.answer(
                200,
                {
                    "status": "success",
                    "dryRun": False,
                    "numberOfRowsProcessed": len(data["assets"]),
                    "numberOfRowsPassed": len(data["assets"]),
                    "numberOfRowsFailed": 0,
                },
            )
        collection = next((k for k in self.stores if path == k or path.startswith(k + "/")), None)
        if collection is None:
            return self.answer(404, {"message": path})
        store = self.stores[collection]
        suffix = path.removeprefix(collection).strip("/")
        if method == "GET":
            if suffix.startswith("name/"):
                entity = store.get(suffix[5:])
                return (
                    self.answer(200, entity) if entity else self.answer(404, {"message": "absent"})
                )
            if suffix:
                entity = next((v for v in store.values() if v["id"] == suffix), None)
                return (
                    self.answer(200, entity) if entity else self.answer(404, {"message": "absent"})
                )
            from urllib.parse import parse_qs

            query = parse_qs(urlsplit(request.url).query)
            offset = int(query.get("after", ["0"])[0])
            values = list(store.values())
            end = offset + self.page_size
            return self.answer(
                200,
                {
                    "data": values[offset:end],
                    "paging": {
                        "total": len(values),
                        "after": str(end) if end < len(values) else None,
                    },
                },
            )
        if method == "PATCH":
            key = next(k for k, v in store.items() if v["id"] == suffix)
            store[key] = jsonpatch.apply_patch(store[key], data)
            if collection == "metrics" and self.drop_metric_assets:
                store[key]["assets"] = []
            return self.answer(200, store[key])
        if suffix and collection == "metadata/types":
            entity = next(v for v in store.values() if v["id"] == suffix)
            from metadata.generated.schema.type.customProperty import CustomProperty

            CustomProperty.model_validate(data)
            assert data["propertyType"]["id"] == uid("string")
            assert not any(p["name"] == data["name"] for p in entity["customProperties"])
            entity["customProperties"].append(data)
            return self.answer(200, entity)
        assert not suffix
        name = data["name"]
        if collection == "glossaryTerms":
            parent = data.get("parent") or data["glossary"]
            assert parent in store or parent in self.stores["glossaries"]
            fqn = f"{parent}.{name}"
            glossary = self.stores["glossaries"][data["glossary"]]
            data["glossary"] = {
                "id": glossary["id"],
                "type": "glossary",
                "fullyQualifiedName": data["glossary"],
            }
            if data.get("parent"):
                data["parent"] = {
                    "id": store[parent]["id"],
                    "type": "glossaryTerm",
                    "fullyQualifiedName": parent,
                }
            data["relatedTerms"] = []
        elif collection == "tags":
            fqn = f"{data['classification']}.{name}"
            data["classification"] = {"id": uid(data["classification"]), "type": "classification"}
        else:
            fqn = name
        entity = {**data, "id": uid(fqn), "fullyQualifiedName": fqn}
        store[fqn] = entity
        return self.answer(200, entity)

    @staticmethod
    def answer(status: int, data: Any) -> tuple[int, dict[str, str], str]:
        return status, {"Content-Type": "application/json"}, json.dumps(data)
