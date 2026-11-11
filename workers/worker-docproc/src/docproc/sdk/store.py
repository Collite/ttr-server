# SPDX-License-Identifier: Apache-2.0
"""The warehouse client (docwh-store REST+JSON; the worker's only door to silver).

The worker reads units and annotations by id and writes annotations through ``LoadApi`` —
pointers travel on the bus, content is fetched here. Errors come back as the
docwh-store routes' envelope: today ``{"error": "<code>", "detail": "<text>"}`` (the
service's ``ErrorDto``); the nested ``{"error": {"code", "message", "detail"}}`` is read
too, so the P1 routes may use either.
"""

from __future__ import annotations

import os
from collections.abc import Iterator
from pathlib import Path
from typing import Any, Protocol

import httpx

from docproc.sdk.models import (
    Annotation,
    ContextResult,
    GetUnitResponse,
    ListAnnotationsPage,
    ListAnnotationsRequest,
    LoadAnnotationsRequest,
    LoadAnnotationsResponse,
    Page,
    SimilarRequest,
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


class RunConflict(StoreError):
    """409 — the immutable run has already been committed with different output."""


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
    if r.status_code == 409:
        return RunConflict(r.status_code, code, message)
    if r.status_code == 422 and code == "annotation/schema":
        return SchemaRejected(r.status_code, code, message)
    return StoreError(r.status_code, code, message)


class StoreClient:
    def __init__(
        self, base_url: str, client: httpx.Client | None = None, timeout_s: float = 30.0, token_file: str | None = None
    ) -> None:
        self._http = client or httpx.Client(base_url=base_url, timeout=timeout_s)
        token = Path(token_file).read_text().strip() if token_file else os.environ.get("DOCPROC_SERVICE_TOKEN", "")
        if token:
            self._http.headers["Authorization"] = f"Bearer {token}"

    def close(self) -> None:
        self._http.close()

    def _ok(self, r: httpx.Response) -> Any:
        if r.status_code >= 400:
            raise _error(r)
        return r.json()

    def load_annotations(self, req: LoadAnnotationsRequest) -> LoadAnnotationsResponse:
        """`POST /load/annotations` — one run's annotations in one transaction; answers their ids."""
        return LoadAnnotationsResponse.model_validate(self._ok(self._http.post("/load/annotations", json=req.dump())))

    def get_unit(self, unit_id: int, notebook_id: str = "") -> Unit:
        """`GET /units/{id}` — RLS by the unit's source mart. The route answers `GetUnitResponse` (`{unit, childIds}`);
        a unit other than the one asked for is refused, never processed."""
        r = self._http.get(f"/units/{unit_id}", params={"notebookId": notebook_id} if notebook_id else {})
        unit = GetUnitResponse.model_validate(self._ok(r)).unit
        if unit.id != unit_id:
            raise StoreError(r.status_code, "store/unit-mismatch", f"GET /units/{unit_id} answered unit {unit.id}")
        return unit

    def list_annotations(self, req: ListAnnotationsRequest) -> ListAnnotationsPage:
        """`GET /annotations` — one page; follow `next_page_token`."""
        return ListAnnotationsPage.model_validate(
            self._ok(self._http.get("/annotations", params=httpx.QueryParams(req.query())))
        )

    def iter_annotations(self, req: ListAnnotationsRequest) -> Iterator[Annotation]:
        seen: set[str] = set()
        while True:
            page = self.list_annotations(req)
            yield from page.annotations
            if not page.next_page_token:
                return
            if page.next_page_token in seen:
                raise StoreError(502, "pagination/loop", "repeated annotation page token")
            seen.add(page.next_page_token)
            req = req.model_copy(update={"page_token": page.next_page_token})

    def units_of_source(self, source_id: int, text: bool = True) -> list[Unit]:
        body = self._ok(self._http.get(f"/sources/{source_id}/units", params={"text": str(text).lower()}))
        return [Unit.model_validate(u) for u in body["units"]]

    def load_lemmas(self, run_id: str, lemmas: list[dict[str, Any]]) -> None:
        self._ok(self._http.post("/load/lemmas", json={"runId": run_id, "lemmas": lemmas}))

    def find_similar(self, notebook_id: str, query: str, node_kinds: list[str], k: int) -> ContextResult:
        """`POST /findSimilar` — vector similarity inside ONE mart (docwh-store `FindSimilarRequestDto`: `notebookId`
        and `query` required, no defaults). Answers `ContextResult`; a page hit carries `pageId` and its cosine as
        `score`, the page's concept is on `GET /pages/{id}` ([page]). A blank mart or query is refused here, before
        the wire: the DX dump would drop it and the route answer a bare `400` (review-244 F5)."""
        if not notebook_id.strip() or not query.strip():
            raise ValueError("store/find-similar-blank: notebookId and query are required")
        body = SimilarRequest(notebook_id=notebook_id, query=query, node_kinds=node_kinds, k=k).dump()
        return ContextResult.model_validate(self._ok(self._http.post("/findSimilar", json=body)))

    def page(self, page_id: int, notebook_id: str) -> Page:
        """`GET /pages/{id}?notebookId=` — proto `Page`; a page outside the mart is a 404, as a missing one. A page
        other than the one asked for is refused, as `get_unit` refuses another unit (review-244 F3)."""
        r = self._http.get(f"/pages/{page_id}", params={"notebookId": notebook_id})
        page = Page.model_validate(self._ok(r))
        if page.id != page_id:
            raise StoreError(r.status_code, "store/page-mismatch", f"GET /pages/{page_id} answered page {page.id}")
        return page

    def sources_of_document(self, document_id: str) -> list[dict[str, Any]]:
        """`GET /sources?documentId=` as the service identity: every version, each with its mart (`notebookId`, see
        `models.Source`). No source is `{}` on the wire, so the list is read with a default."""
        body = self._ok(self._http.get("/sources", params={"documentId": document_id}))
        return list(body.get("sources", []))


class Warehouse(Protocol):
    def get_unit(self, unit_id: int, notebook_id: str = "") -> Unit: ...
    def units_of_source(self, source_id: int, text: bool = True) -> list[Unit]: ...
    def list_annotations(self, req: ListAnnotationsRequest) -> ListAnnotationsPage: ...
    def iter_annotations(self, req: ListAnnotationsRequest) -> Iterator[Annotation]: ...
    def load_annotations(self, req: LoadAnnotationsRequest) -> LoadAnnotationsResponse: ...
    def load_lemmas(self, run_id: str, lemmas: list[dict[str, Any]]) -> None: ...
    def find_similar(self, notebook_id: str, query: str, node_kinds: list[str], k: int) -> ContextResult: ...
    def page(self, page_id: int, notebook_id: str) -> Page: ...
    def sources_of_document(self, document_id: str) -> list[dict[str, Any]]: ...
