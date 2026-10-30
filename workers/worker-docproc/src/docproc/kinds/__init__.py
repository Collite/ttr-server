# SPDX-License-Identifier: Apache-2.0
"""Production structural kind registration; Noop is deliberately test-only."""

from docproc.kinds.chunk.kind import Chunk
from docproc.kinds.classify import Classify
from docproc.kinds.diff import Diff
from docproc.kinds.import_ import Import
from docproc.kinds.lemmatize import Lemmatize
from docproc.kinds.nlp_pipeline import NlpPipeline
from docproc.runtime.registry import KindRegistry, TypeRegistryClient
from docproc.sdk.lexmatcher import LexMatcher
from docproc.sdk.nlp import Nlp
from docproc.sdk.pipeline import Manager
from docproc.sdk.store import Warehouse


def register_structural(
    kinds: KindRegistry,
    warehouse: Warehouse,
    manager: Manager,
    registry: TypeRegistryClient,
    nlp: Nlp,
    pack_path: str = "",
) -> None:
    kinds.register("import", Import(manager, registry))
    kinds.register("lemmatize", Lemmatize(nlp, warehouse))
    kinds.register("classify", Classify(pack_path))
    kinds.register("nlp-pipeline", NlpPipeline(nlp))
    kinds.register("diff", Diff(warehouse))
    kinds.register("chunk", Chunk(nlp, warehouse, pack_path))


def register_extraction(
    kinds: KindRegistry,
    warehouse: Warehouse,
    registry: TypeRegistryClient,
    lexmatcher: "LexMatcher",
    pack_path: str = "",
    world_conninfo: str = "",
) -> None:
    from docproc.kinds.link import Link
    from docproc.kinds.record_etl import RecordEtl
    from docproc.kinds.rule import Rule

    kinds.register("link", Link(lexmatcher, warehouse))
    kinds.register("rule", Rule(warehouse, registry, pack_path))
    kinds.register("record-etl", RecordEtl(warehouse, registry, pack_path, world_conninfo))
