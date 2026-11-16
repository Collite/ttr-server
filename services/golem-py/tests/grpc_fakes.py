# SPDX-License-Identifier: Apache-2.0
"""In-process gRPC fakes (OL W1c·S1): a real `grpc.aio` server on the loopback, inside
the test process, so a client is exercised over a real channel — request encoding,
deadlines, status codes — with no service and no network beyond 127.0.0.1."""

from __future__ import annotations

from collections.abc import AsyncIterator, Callable
from contextlib import asynccontextmanager
from typing import Any

import grpc


@asynccontextmanager
async def serve(register: Callable[[Any], None]) -> AsyncIterator[str]:
    """Start a server, let `register` add servicers to it, yield its target."""
    server = grpc.aio.server()
    register(server)
    port = server.add_insecure_port("127.0.0.1:0")
    await server.start()
    try:
        yield f"127.0.0.1:{port}"
    finally:
        await server.stop(None)


def unused_target() -> str:
    """A loopback port nothing listens on — the "service is down" case."""
    import socket

    with socket.socket() as sock:
        sock.bind(("127.0.0.1", 0))
        port = sock.getsockname()[1]
    return f"127.0.0.1:{port}"
