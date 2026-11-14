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
from pydantic import ValidationError

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
    SourceTree,
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


class PaginationLoop(StoreError):
    """The store repeated an annotation page token: a loop the client detected itself. It is deterministic, so it is
    never transient (`runtime/failures.py`), although it keeps its `502 pagination/loop` so logs read as before: every
    redelivery would loop again and end as "retries exhausted", the cause lost (review-244 R3)."""


#: How many failing fields a shape error names.
_SHAPE_ERRORS = 5


def _shape(r: httpx.Response, code: str, exc: ValidationError) -> StoreError:
    """A 2xx answer of a drifted shape: the failing fields' locations and error types only, e.g. `chunks.0.pageId:
    missing`. Never pydantic's own text: its `input_value` is a slice of the store's body, i.e. document text, and
    diagnostics never include bodies (`runtime/failures.py`)."""
    errors = exc.errors(include_url=False, include_context=False, include_input=False)
    where = [f"{'.'.join(map(str, e['loc'])) or '<root>'}: {e['type']}" for e in errors[:_SHAPE_ERRORS]]
    if len(errors) > _SHAPE_ERRORS:
        where.append(f"(+{len(errors) - _SHAPE_ERRORS} more)")
    return StoreError(r.status_code, code, "; ".join(where))


def own_units(tree: SourceTree, status: int = 200) -> SourceTree:
    """The tree with each unit's `sourceId` filled in. docwh-store prints a `GET /sources/{id}/units` tree's id once, at
    the top (unitTree.json, its `UnitRoutesSpec`); the units carry none and read as source 0. The runner puts the
    tree's units over the ones `get_unit` read, so without this every unit of a whole-source step lost its source and
    every live consolidate refused `consolidate/source-unanswered` for source 0 (T8f W5-5). A unit that names another
    source is `store/units-mismatch`."""
    for unit in tree.units:
        if unit.source_id not in (0, tree.source_id):
            raise StoreError(
                status,
                "store/units-mismatch",
                f"GET /sources/{tree.source_id}/units answered unit {unit.id} of sourceId {unit.source_id}",
            )
    return tree.model_copy(update={"units": [u.model_copy(update={"source_id": tree.source_id}) for u in tree.units]})


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
                raise PaginationLoop(502, "pagination/loop", "repeated annotation page token")
            seen.add(page.next_page_token)
            req = req.model_copy(update={"page_token": page.next_page_token})

    def source_tree(self, source_id: int, text: bool = True) -> SourceTree:
        """`GET /sources/{id}/units`: the tree and the source itself (docwh-store 0.11.14, T8f W5-4; None from an older
        store). A body of another shape is `StoreError` `store/units-shape`, its field locations only, as R2's reads;
        one whose `sourceId` or `source.id` is another source's is `store/units-mismatch`. Every unit answers with
        this source's id (`own_units`, T8f W5-5)."""
        r = self._http.get(f"/sources/{source_id}/units", params={"text": str(text).lower()})
        try:
            tree = SourceTree.model_validate(self._ok(r))
        except ValidationError as exc:
            raise _shape(r, "store/units-shape", exc) from None
        # the tree's id and its source's (review-257 F12): never another source's subject read as this member's. Each
        # says which it is (R9): a wrong tree and a broken source join read differently
        answers = [("sourceId", tree.source_id)] + ([("source.id", tree.source.id)] if tree.source is not None else [])
        for field_name, answered in answers:
            if answered != source_id:
                raise StoreError(
                    r.status_code,
                    "store/units-mismatch",
                    f"GET /sources/{source_id}/units answered {field_name} {answered}",
                )
        return own_units(tree, r.status_code)

    def load_lemmas(self, run_id: str, lemmas: list[dict[str, Any]]) -> None:
        self._ok(self._http.post("/load/lemmas", json={"runId": run_id, "lemmas": lemmas}))

    def find_similar(self, notebook_id: str, query: str, node_kinds: list[str], k: int) -> ContextResult:
        """`POST /findSimilar` — vector similarity inside ONE mart (docwh-store `FindSimilarRequestDto`: `notebookId`
        and `query` required, no defaults). Answers `ContextResult`; a page hit carries `pageId` and its cosine as
        `score`, the page's concept is on `GET /pages/{id}` ([page]). A blank mart or query is refused here, before
        the wire: the DX dump would drop it and the route answer a bare `400` (review-244 F5). An answer of a drifted
        shape (no `chunks`, a hit without `pageId` or `score`) is `store/find-similar-shape` (review-244 F3, R2)."""
        if not notebook_id.strip() or not query.strip():
            raise ValueError("store/find-similar-blank: notebookId and query are required")
        body = SimilarRequest(notebook_id=notebook_id, query=query, node_kinds=node_kinds, k=k).dump()
        r = self._http.post("/findSimilar", json=body)
        try:
            return ContextResult.model_validate(self._ok(r))
        except ValidationError as exc:
            raise _shape(r, "store/find-similar-shape", exc) from None

    def page(self, page_id: int, notebook_id: str) -> Page:
        """`GET /pages/{id}?notebookId=` — proto `Page`; a page outside the mart is a 404, as a missing one. A page
        other than the one asked for is refused, as `get_unit` refuses another unit (review-244 F3); one that does
        not decode is `store/page-shape` (R2)."""
        r = self._http.get(f"/pages/{page_id}", params={"notebookId": notebook_id})
        try:
            page = Page.model_validate(self._ok(r))
        except ValidationError as exc:
            raise _shape(r, "store/page-shape", exc) from None
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
    def source_tree(self, source_id: int, text: bool = True) -> SourceTree: ...
    def list_annotations(self, req: ListAnnotationsRequest) -> ListAnnotationsPage: ...
    def iter_annotations(self, req: ListAnnotationsRequest) -> Iterator[Annotation]: ...
    def load_annotations(self, req: LoadAnnotationsRequest) -> LoadAnnotationsResponse: ...
    def load_lemmas(self, run_id: str, lemmas: list[dict[str, Any]]) -> None: ...
    def find_similar(self, notebook_id: str, query: str, node_kinds: list[str], k: int) -> ContextResult: ...
    def page(self, page_id: int, notebook_id: str) -> Page: ...
    def sources_of_document(self, document_id: str) -> list[dict[str, Any]]: ...
