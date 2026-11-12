# SPDX-License-Identifier: Apache-2.0
"""Shared infrastructure failure classification; diagnostics never include bodies."""

import httpx
import psycopg

from docproc.sdk.store import PaginationLoop, StoreError


def transient(exc: Exception) -> bool:
    """A failure a redelivery may not meet again: transport, the database, a store 401/429/5xx. Not the client's own
    `PaginationLoop`: its 502 is a label, and the loop is deterministic (review-244 R3)."""
    if isinstance(exc, PaginationLoop):
        return False
    return (
        isinstance(exc, httpx.TransportError | psycopg.OperationalError)
        or isinstance(exc, StoreError)
        and (exc.status >= 500 or exc.status in (401, 429))
    )


def diagnostic(exc: Exception) -> str:
    if isinstance(exc, StoreError):
        return f"{type(exc).__name__} status={exc.status} code={exc.code}"
    return type(exc).__name__
