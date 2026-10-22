# cn-estate — the conceptual tier's shared fixture estate (CN-P4)

A small invented estate (package `shop`) with every layer the conceptual tier reads: an E-R model, an md cubelet with
an explicit `md2er_cubelet`, `model binding` groundings (direct, md-hop, a phase), a `model cn` of twelve concepts, and
cs + en lexicon units. Three consumers read this one copy, so the three read the same estate (cn contracts §5):

| Consumer | Reads | Since |
|---|---|---|
| veles | `model/`: the registry carries the cn tier, and the wire survives it | CN-P4 S4.2 |
| lex-matcher | `expected/lexicon.json`: the v6 concept rows load as declared vocabulary | CN-P4 S4.3 |
| resolver | `expected/lexicon.json`: concept entries, `liftsTo`, the default sense | CN-P4 S4.4 |

## Source

A byte-for-byte copy of ttr-core's `packages/kotlin/ttr-lexicon-compile/src/test/resources/cn/` at `a8d4cf8e` (CN-P4
S4.1). `expected/lexicon.json` is the v6 archive's lexicon (`ttr-lexicon-compiled/v6`) that ttr-core's
`CnArchiveV6Spec` holds byte-stable. ttr-core's copy is the source: change it there, then copy it here again
(`diff -r` against the source shows any drift).
