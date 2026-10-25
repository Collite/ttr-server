# SPDX-License-Identifier: Apache-2.0
"""C-15/16/17 against scanned assets; requires a disposable conformance catalog."""

import json
import os
import shutil
import subprocess
import sys
import time
from pathlib import Path
from typing import Any

import pytest

from .conftest import LiveOm

pytestmark = pytest.mark.om_live
FIXTURE = Path(__file__).parents[1] / "fixtures/export"
PREFIX = "northline-pg.northline.retail."


def test_check_sync_idempotency_and_deprecation(om: LiveOm, tmp_path: Path) -> None:
    token = tmp_path / "token"
    token.write_text(om.token)
    token.chmod(0o600)
    state = tmp_path / "state.json"
    credentials = ["--om-url", om.url, "--om-token-file", str(token)]

    def cli(*args: str) -> dict[str, Any]:
        run = subprocess.run(
            [sys.executable, "-m", "ttr_connect_openmetadata.cli", *args],
            text=True,
            capture_output=True,
            check=False,
        )
        assert run.returncode == 0, run.stderr
        summary: dict[str, Any] = json.loads(run.stdout)
        assert set(summary) == {
            "snapshot",
            "created",
            "updated",
            "deprecated",
            "linked",
            "warnings",
        }
        om.record("summaries", summary)
        assert summary["warnings"] == 0, run.stderr
        return summary

    cli("check", *credentials)
    for entity, names in {
        "table": {"ttrQname", "ttrUrl"},
        "tableColumn": {"ttrQname"},
        "glossaryTerm": {"ttrQname", "ttrUrl", "ttrKind", "ttrIri"},
        "metric": {"ttrQname", "ttrUrl", "ttrAdditivity", "ttrColumnAssets"},
    }.items():
        registry = om.call(
            "GET", f"metadata/types/name/{entity}", params={"fields": "customProperties"}
        )
        assert names <= {p["name"] for p in registry["customProperties"]}
    args = [
        "sync",
        "--export-dir",
        str(FIXTURE),
        "--world",
        "northline",
        "--cn-name-mode",
        "qname",
        "--studio-url",
        "https://studio.example",
        "--state-file",
        str(state),
        *credentials,
    ]
    cli(*args)
    # OM's default approval workflow consumes new-term events asynchronously.
    # Let those initial events settle before measuring no-op/deprecation versions.
    om.settle_initial_approvals()
    assert json.loads(state.read_text())["snapshot"] == "fixture-1"
    terms = [
        t
        for t in om.entities("glossaryTerms", "extension,parent,relatedTerms,synonyms,tags")
        if t.get("extension", {}).get("ttrQname")
    ]
    assert len(terms) == 21
    by_qname = {t["extension"]["ttrQname"]: t for t in terms}
    party = by_qname["cn.kind.Party"]
    assert party["iri"] == "https://example.org/northline/Party"
    customer = by_qname["cn.kind.Prospect"]
    assert customer["parent"]["id"] == party["id"]
    assert by_qname["er.entity.buyer.id"]["parent"]["id"] == by_qname["er.entity.buyer"]["id"]
    assert by_qname["md.dimension.Calendar"]["extension"]["ttrKind"] == "dimension"
    assert by_qname["md.hierarchy.CalendarRollup"]["extension"]["ttrKind"] == "hierarchy"
    assert by_qname["cn.event.Purchase"]["relatedTerms"]
    tables = {
        t["name"]: t
        for t in om.entities("tables", "columns,tags,extension")
        if t["fullyQualifiedName"].startswith(PREFIX)
    }
    assert set(tables) == {"buyers", "products", "sales", "recent_sales"}
    for name, table in tables.items():
        assert table["extension"]["ttrQname"] == f"db.retail.{name}"
        assert table["extension"]["ttrUrl"].startswith("https://studio.example/viewer?")
    sales = tables["sales"]
    assert "Tatrman.Grounded" in {t["tagFQN"] for t in sales["tags"]}
    assert "Tatrman.Grounded" in {t["tagFQN"] for t in tables["buyers"]["tags"]}
    columns = {c["name"]: c for c in sales["columns"]}
    for name in ("id", "amount", "buyer_id", "sold_at"):
        assert columns[name]["extension"]["ttrQname"] == f"db.retail.sales.{name}"
        assert "Tatrman.Grounded" in {t["tagFQN"] for t in columns[name]["tags"]}
    assert by_qname["md.dimension.Buyer"]["fullyQualifiedName"] in {
        t["tagFQN"] for t in columns["buyer_id"]["tags"] if t["source"] == "Glossary"
    }
    metrics = [
        om.call("GET", f"metrics/{m['id']}", params={"fields": "extension,assets,tags"})
        for m in om.entities("metrics")
    ]
    metrics = [m for m in metrics if m.get("extension", {}).get("ttrQname")]
    assert {m["name"] for m in metrics} == {"balance", "revenue", "units"}
    for metric in metrics:
        column = "id" if metric["name"] == "units" else "amount"
        assert metric["extension"]["ttrQname"] == f"md.measure.{metric['name']}"
        assert any(
            a["fullyQualifiedName"] == PREFIX + "sales" and a["type"] == "table"
            for a in metric["assets"]
        )
        assert PREFIX + f"sales.{column}" in json.loads(metric["extension"]["ttrColumnAssets"])
    versions = {e["id"]: e["version"] for e in [*terms, *metrics, *tables.values()]}
    before = state.read_bytes()
    second = cli(*args)
    assert all(second[k] == 0 for k in ("created", "updated", "deprecated", "linked", "warnings"))
    assert before == state.read_bytes()
    entities = om.entities("glossaryTerms") + om.entities("metrics") + om.entities("tables")
    assert all(e["version"] == versions[e["id"]] for e in entities if e["id"] in versions)
    # Remove an isolated quality from a coherent export; keep all scanner-owned assets.
    edited = tmp_path / "export"
    shutil.copytree(FIXTURE, edited)
    removed = "cn.quality.sold_at"
    graph = json.loads((edited / "graph.json").read_text())
    graph["nodes"] = [n for n in graph["nodes"] if n["id"] != removed]
    graph["edges"] = [e for e in graph["edges"] if removed not in (e["from"], e["to"])]
    (edited / "graph.json").write_text(json.dumps(graph))
    changed = args.copy()
    changed[changed.index("--export-dir") + 1] = str(edited)
    assert cli(*changed)["deprecated"] == 1
    term = om.call("GET", f"glossaryTerms/{by_qname[removed]['id']}")
    assert term["entityStatus"] == "Deprecated"
    time.sleep(2)
    assert om.call("GET", f"glossaryTerms/{term['id']}")["entityStatus"] == "Deprecated"
    assert (
        len([t for t in om.entities("tables") if t["fullyQualifiedName"].startswith(PREFIX)]) == 4
    )
    # Restore so the lane can be repeated without resetting the catalog.
    cli(*args)
    # OM's default approval workflow can advance a restored Draft to Approved.
    assert om.call("GET", f"glossaryTerms/{term['id']}")["entityStatus"] in {"Draft", "Approved"}


def test_plain_text_search_minimum(om: LiveOm) -> None:
    version = os.environ.get("OM_OPENSEARCH_VERSION", "3.4.0")
    for _attempt in range(30):
        response = om.session.get(
            f"{om.url}/v1/search/query",
            params={"q": "sales", "index": "table_search_index", "size": "10"},
            timeout=30,
        )
        result = response.json()
        if version == "3.0.0" or response.status_code != 200:
            break
        hits = result.get("hits", {}).get("hits", [])
        if any(h.get("_source", {}).get("fullyQualifiedName") == PREFIX + "sales" for h in hits):
            break
        time.sleep(1)
    om.record("search", {"opensearch": version, "status": response.status_code, "result": result})
    if version == "3.0.0":
        # Negative compatibility probe: OM 2.0 staged ranking triggers the
        # documented OpenSearch 3.0-3.3 script_score bug. Never deploy this pin.
        assert response.status_code == 500, result
        assert "null_pointer_exception" in json.dumps(result), result
    else:
        assert response.status_code == 200, result
        assert any(
            h["_source"]["fullyQualifiedName"] == PREFIX + "sales" for h in result["hits"]["hits"]
        )
