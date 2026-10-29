# SPDX-License-Identifier: Apache-2.0
"""The sole extraction-table writer: parameterized, transactional, ACTIVE-only."""

from __future__ import annotations

import json
import re
from datetime import date, datetime
from decimal import Decimal
from pathlib import Path
from typing import Any

import psycopg
import yaml
from psycopg import sql
from psycopg.rows import dict_row

from docproc.kinds.common import utc_instant, verdict
from docproc.kinds.consolidate import digest, record_decision, subject_scope
from docproc.kinds.rule_dsl import pack_file
from docproc.runtime.predicate import _MISSING, path_value
from docproc.runtime.registry import KindRefused, KindResult, RunContext, TypeRegistryClient
from docproc.sdk.models import ListAnnotationsRequest, RunVisibility
from docproc.sdk.store import Warehouse


def identifier(value: str) -> sql.Identifier:
    parts = value.split(".")
    if not 1 <= len(parts) <= 2 or any(not re.fullmatch(r"[a-z_][a-z0-9_]*", p) for p in parts):
        raise ValueError("record-etl/invalid-identifier")
    return sql.Identifier(*parts)


def scalar(value: Any, schema: dict[str, Any]) -> Any:
    if value is None:
        return None
    if "x-ttr-scale" in schema:
        try:
            number = Decimal(str(value))
        except ArithmeticError:
            raise ValueError("record-etl/invalid-decimal") from None
        scale = int(schema["x-ttr-scale"])
        if not number.is_finite() or number.as_tuple().exponent < -scale:  # type: ignore[operator]
            raise ValueError("record-etl/decimal-scale")
        return number
    if schema.get("format") == "date":
        try:
            return date.fromisoformat(str(value))
        except ValueError:
            raise ValueError("record-etl/invalid-date") from None
    if schema.get("format") == "date-time":
        try:
            return utc_instant(str(value), "record-etl/date-time")
        except ValueError:
            raise ValueError("record-etl/invalid-date-time") from None
    return value


def json_row(row: dict[str, Any]) -> dict[str, Any]:
    return {
        k: str(v) if isinstance(v, Decimal) else v.isoformat() if isinstance(v, date | datetime) else v
        for k, v in row.items()
    }


class RecordEtl:
    def __init__(
        self, warehouse: Warehouse, registry: TypeRegistryClient, pack_path: str = "", conninfo: str = ""
    ) -> None:
        self.warehouse, self.registry = warehouse, registry
        self.pack = Path(pack_path) if pack_path else None
        self._conninfo = conninfo

    def run(self, ctx: RunContext) -> KindResult:
        subject, _ = subject_scope(ctx)
        consumes = ctx.manifest["graph"]["consumes"]
        if not consumes or any(
            not key.startswith("Record.") or not self.registry.entries.get(key, {}).get("feedsRecords", False)
            for key in consumes
        ):
            raise ValueError("record-etl/feeds-records-required")
        mapping = yaml.safe_load(pack_file(self.pack, ctx.manifest["engine"]["mapping"]).read_text())
        table = identifier(mapping["table"])
        entity = mapping["entity"]
        if set(consumes) != {"Record." + entity.rsplit(".", 1)[-1]}:
            raise ValueError("record-etl/mapping-entity-mismatch")
        if not self._conninfo:
            raise ValueError("record-etl/connection-required: TTR_CONN_EXTRACT_RECORDS")
        records = [a for a in ctx.annotations if a.type in consumes]
        if any(a.unit_id not in ctx.units for a in records):
            raise ValueError("record-etl/record-outside-scope")
        result = KindResult()
        rows: list[dict[str, Any]] = []
        refs = []
        failures: list[dict[str, Any]] = []
        # Fetch immutable key decisions before opening a world transaction/subject lock.
        decisions_by_run = {
            run_id: list(
                self.warehouse.iter_annotations(
                    ListAnnotationsRequest(run_id=run_id, types=["Decision"], active_only=False)
                )
            )
            for run_id in {record.run_id for record in records}
        }
        ref_cache: dict[tuple[str, str, str, str], Any] = {}
        try:
            with psycopg.connect(self._conninfo, row_factory=dict_row) as conn:
                # Serialize concurrent ACTIVE generations of one subject. Candidate reads do not take write locks.
                if ctx.item.visibility == RunVisibility.ACTIVE:
                    conn.execute("SELECT pg_advisory_xact_lock(%s)", (int(digest(entity, subject)[:15], 16),))
                current = conn.execute(
                    sql.SQL("SELECT * FROM {} WHERE subject_id = %s ORDER BY row_id").format(table), (subject,)
                ).fetchall()
                seen = set()
                for record in records:
                    try:
                        payload = json.loads(record.payload_json, parse_float=Decimal)
                        validator = self.registry.schema_for(record.type)
                        # Validate original JSON numbers, then use exact Decimal values for column conversion.
                        validator.validate(record.payload())
                        if any(c.get("status") == "open" for c in payload.get("conflicts", [])):
                            raise ValueError("record-etl/open-conflict")
                        key = record_decision(record, decisions_by_run[record.run_id], subject)["chosen"]
                        row_id = digest(entity, subject, key)
                        if row_id in seen:
                            raise ValueError("record-etl/duplicate-record-key")
                        seen.add(row_id)
                        lineage = sorted(
                            {record.id, *record.derived_from, *(e["annotationId"] for e in payload["evidence"])}
                        )
                        row: dict[str, Any] = {
                            "row_id": row_id,
                            "subject_id": subject,
                            "annotation_refs": lineage,
                            "consolidation_run": payload["consolidationRun"],
                        }
                        schema = validator.schema
                        assert isinstance(schema, dict)
                        for column, spec in mapping["columns"].items():
                            identifier(column)
                            if column in ("row_id", "subject_id", "annotation_refs", "consolidation_run"):
                                continue
                            if "from" not in spec:
                                raise ValueError("record-etl/unknown-mapping")
                            value = path_value(payload, spec["from"])
                            if value is _MISSING:
                                value = None
                            layer, field = spec["from"].split(".", 1)
                            field_schema = (
                                schema.get("properties", {}).get(layer, {}).get("properties", {}).get(field, {})
                            )
                            if "ref" in spec and value is not None:
                                if not isinstance(value, dict) or not value.get("qname"):
                                    raise ValueError("record-etl/invalid-ref")
                                ref = spec["ref"]
                                cache_key = (ref["table"], ref["value"], ref["match"], value["qname"])
                                found = ref_cache.get(cache_key)
                                if found is None:
                                    found = conn.execute(
                                        sql.SQL("SELECT {} FROM {} WHERE {} = %s").format(
                                            identifier(ref["value"]), identifier(ref["table"]), identifier(ref["match"])
                                        ),
                                        (value["qname"],),
                                    ).fetchall()
                                    ref_cache[cache_key] = found
                                if len(found) != 1:
                                    raise ValueError("record-etl/unresolved-ref")
                                value = found[0][ref["value"]]
                            row[column] = scalar(value, field_schema)
                        # State mappings may explicitly supply valid-time columns from their schema attributes.
                        rows.append(row)
                        refs.append(
                            {"entity": entity, "rowId": row_id, "consolidationRun": payload["consolidationRun"]}
                        )
                    except (ValueError, KeyError, ArithmeticError) as exc:
                        code = (
                            str(exc)
                            if isinstance(exc, ValueError) and str(exc).startswith(("record-etl/", "record/"))
                            else "record-etl/invalid-record"
                        )
                        failures.append({"annotationId": record.id, "rule": code})
                        result.verdicts.append(verdict(record.unit_id, "record-etl/row", code))
                    except Exception as exc:
                        # jsonschema errors can contain sensitive payload values; keep a stable diagnostic.
                        from jsonschema import ValidationError

                        if not isinstance(exc, ValidationError):
                            raise
                        failures.append({"annotationId": record.id, "rule": "record-etl/schema"})
                        result.verdicts.append(verdict(record.unit_id, "record-etl/schema", "invalid record payload"))
                if ctx.item.visibility == RunVisibility.ACTIVE and failures:
                    # Raising inside the connection rolls back, releasing the advisory lock.
                    raise KindRefused("record-etl/rows-rejected", diagnostics=failures)
                rows.sort(key=lambda row: row["row_id"])
                old = {r["row_id"]: r for r in current}
                new = {r["row_id"]: r for r in rows}
                ignored = {mapping.get("key", ""), "consolidation_run", "annotation_refs"}

                def comparable(row: dict[str, Any]) -> dict[str, Any]:
                    # Numeric equality and aware datetime equality compare values/instants,
                    # independent of decimal scale, session timezone and run provenance.
                    return {k: v for k, v in row.items() if k not in ignored}

                diff = {
                    "added": sorted(new.keys() - old.keys()),
                    "removed": sorted(old.keys() - new.keys()),
                    "changed": sorted(
                        key for key in old.keys() & new.keys() if comparable(old[key]) != comparable(new[key])
                    ),
                }
                if ctx.item.visibility == RunVisibility.ACTIVE:
                    conn.execute(sql.SQL("DELETE FROM {} WHERE subject_id = %s").format(table), (subject,))
                    for row in rows:
                        conn.execute(
                            sql.SQL("INSERT INTO {} ({}) VALUES ({})").format(
                                table,
                                sql.SQL(", ").join(identifier(k) for k in row),
                                sql.SQL(", ").join(sql.Placeholder() for _ in row),
                            ),
                            tuple(row.values()),
                        )
                # Full candidate row data is artefact-only; P2 must apply its storage/redaction floor.
                result.sections = {
                    "outputs": {
                        "recordRows": refs,
                        "recordDiff": diff,
                        **(
                            {"candidateRows": [json_row(r) for r in rows]}
                            if ctx.item.visibility != RunVisibility.ACTIVE
                            else {}
                        ),
                    }
                }
        except (psycopg.Error, OSError):
            # Never expose conninfo, server error details or SQL values through Completion.error/logs.
            raise RuntimeError("record-etl/database-failed") from None
        return result
