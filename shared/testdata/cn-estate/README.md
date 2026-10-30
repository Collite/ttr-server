# cn-estate — the conceptual tier's shared fixture estate (CN-P4)

A small invented estate (package `shop`) with every layer the conceptual tier reads: an E-R model, an md cubelet with
an explicit `md2er_cubelet`, `model binding` groundings (direct, md-hop, a phase), a `model cn` of twelve concepts, and
cs + en lexicon units. Three consumers read this one copy, so the three read the same estate (cn contracts §5):

| Consumer | Reads | Since |
|---|---|---|
| metadata | `model/`: the registry carries the cn tier, and the wire survives it | CN-P4 S4.2 |
| lex-matcher | `model/`, compiled at test time and held to `expected/lexicon.json`: the v6 concept rows load as declared vocabulary (`CnArchiveLoadTest`, and the gating `CnConformanceTest`) | CN-P4 S4.3 |
| resolver | `model/`, compiled at test time the same way: concepts bind and gate as their landing, lifts, the §1.5 resolution order (`CnResolutionTest`, `CnArchiveRegistryTest`) | CN-P4 S4.4 |

## Source

A byte-for-byte copy of ttr-core's `packages/kotlin/ttr-lexicon-compile/src/test/resources/cn/` at `5e780386` (CN-P8
S8.2, ttr-core#315: `Invoice` is a kind with a lifetime, grounded by `cn2er_kind`; first copied at `ce90a093`, CN-P4
S4.1, ttr-core#268). `expected/lexicon.json` is the v6 archive's lexicon (`ttr-lexicon-compiled/v6`) that ttr-core's
`CnArchiveV6Spec` holds byte-stable. ttr-core's copy is the source: change it there, then copy it here again
(`diff -r` against the source shows any drift). The model files and the conformance cases are hash-pinned in
`conformance/corpus-hashes.sha256`: a re-copy bumps those hashes in the same commit.
