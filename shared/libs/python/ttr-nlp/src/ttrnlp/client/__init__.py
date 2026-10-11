# SPDX-License-Identifier: Apache-2.0
"""Clients (NL-16, ⚑NLS-D7).

`grpc.py` is the `org.tatrman.nlp.v1` client consumers talk to the `nlp` front
with (nlp-mcp, the legacy predecessor front); `backends.py` holds the HTTP
engine-adapter clients, moved here out of `services/nlp/src/nlp_service/engines/`
because they describe how to talk to a backend image rather than which one to talk
to — routing, the registry and the orchestrator stayed service-side.

Both need optional extras (`[grpc]` and `[http]` respectively), so the names below
resolve on first use. `import ttrnlp.client` on a core-only install must not fail:
most consumers want the rule engine and never open a socket.
"""

from typing import TYPE_CHECKING, Any

if TYPE_CHECKING:  # pragma: no cover
    from ttrnlp.client.backends import BackendClient as BackendClient
    from ttrnlp.client.backends import BackendError as BackendError
    from ttrnlp.client.backends import BackendSpec as BackendSpec
    from ttrnlp.client.backends import EngineResult as EngineResult
    from ttrnlp.client.backends import NerEntity as NerEntity
    from ttrnlp.client.backends import NlpOp as NlpOp
    from ttrnlp.client.backends import Token as Token
    from ttrnlp.client.grpc import NlpClient as NlpClient
    from ttrnlp.client.grpc import PipelineResult as PipelineResult

_EXPORTS = {
    "BackendClient": "ttrnlp.client.backends",
    "BackendError": "ttrnlp.client.backends",
    "BackendSpec": "ttrnlp.client.backends",
    "EngineResult": "ttrnlp.client.backends",
    "NerEntity": "ttrnlp.client.backends",
    "NlpOp": "ttrnlp.client.backends",
    "Token": "ttrnlp.client.backends",
    "NlpClient": "ttrnlp.client.grpc",
    "PipelineResult": "ttrnlp.client.grpc",
}

__all__ = sorted(_EXPORTS)


def __getattr__(name: str) -> Any:
    module_name = _EXPORTS.get(name)
    if module_name is None:
        raise AttributeError(f"module {__name__!r} has no attribute {name!r}")
    from importlib import import_module

    return getattr(import_module(module_name), name)


def __dir__() -> list[str]:
    return __all__
