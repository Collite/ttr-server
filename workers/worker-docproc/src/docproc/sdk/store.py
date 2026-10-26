# SPDX-License-Identifier: Apache-2.0
"""The warehouse client (docwh-store REST+JSON; the worker's only door to silver).

The worker reads units and annotations by id and writes annotations through ``LoadApi`` —
pointers travel on the bus, content is fetched here. Errors come back as the
docwh-store routes' envelope: today ``{"error": "<code>", "detail": "<text>"}`` (the
service's ``ErrorDto``); the nested ``{"error": {"code", "message", "detail"}}`` is read
too, so the P1 routes may use either.
"""

from __future__ import annotations

from typing import Any

import httpx

from docproc.sdk.models import (
    ListAnnotationsPage,
    ListAnnotationsRequest,
    LoadAnnotationsRequest,
    LoadAnnotationsResponse,
    Unit,
)


class StoreError(RuntimeError):
    def __init__(self, status: int, code: str, message: str) -> None:
        super().__init__(f"{status} {code}: {message}")
        self.status = status
        self.code = code
        self.message = message


class MartForbidden(StoreError):
    """403 — the unit's source is in no mart the caller may read (RLS at the store)."""


class SchemaRejected(StoreError):
    """422 `annotation/schema` — a payload did not validate against its type's JSON Schema."""


def _error(r: httpx.Response) -> StoreError:
    code, message = "", r.text
    try:
        body: Any = r.json()
        err = body.get("error") if isinstance(body, dict) else None
        if isinstance(err, dict):
            code, message = str(err.get("code", "")), str(err.get("message") or err.get("detail") or "")
        elif isinstance(err, str):
            code, message = err, str(body.get("detail") or "")
    except ValueError:
        pass
    if r.status_code == 403:
        return MartForbidden(r.status_code, code, message)
    if r.status_code == 422 and code == "annotation/schema":
        return SchemaRejected(r.status_code, code, message)
    return StoreError(r.status_code, code, message)


class StoreClient:
    def __init__(self, base_url: str, client: httpx.Client | None = None, timeout_s: float = 30.0) -> None:
        self._http = client or httpx.Client(base_url=base_url, timeout=timeout_s)

    def close(self) -> None:
        self._http.close()

    def _ok(self, r: httpx.Response) -> Any:
        if r.status_code >= 400:
            raise _error(r)
        return r.json()

    def load_annotations(self, req: LoadAnnotationsRequest) -> LoadAnnotationsResponse:
        """`POST /load/annotations` — one run's annotations in one transaction; answers their ids."""
        return LoadAnnotationsResponse.model_validate(self._ok(self._http.post("/load/annotations", json=req.dump())))

    def get_unit(self, unit_id: int, notebook_id: str) -> Unit:
        """`GET /units/{id}` — RLS by the unit's source mart."""
        r = self._http.get(f"/units/{unit_id}", params={"notebookId": notebook_id})
        return Unit.model_validate(self._ok(r))

    def list_annotations(self, req: ListAnnotationsRequest) -> ListAnnotationsPage:
        """`GET /annotations` — one page; follow `next_page_token`."""
        return ListAnnotationsPage.model_validate(
            self._ok(self._http.get("/annotations", params=httpx.QueryParams(req.query())))
        )
