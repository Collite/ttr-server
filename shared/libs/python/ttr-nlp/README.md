<!-- SPDX-License-Identifier: Apache-2.0 -->
# ttr-nlp

The Tatrman NLP suite as a library: an annotation model, a JAPE-class rule
engine, gazetteers, and the clients that talk to the `nlp` service. Apache-2.0,
published to PyPI as **`ttr-nlp`**, imported as **`ttrnlp`**.

Built once in `tatrman-server` and consumed everywhere (NL-9): the `services/nlp`
front hosts it in-process, `nlp-mcp` imports its gRPC client, and the DFP
model-validator wraps its CLI. Rule-pack and list **content** is never part of
the suite — each world maintains its own packs.

## Why it exists

An LLM can be asked what a sentence means. A deterministic pipeline has to be
*told*, and told in something a domain analyst can read, diff and review. GATE's
JAPE is the proven shape for that, but JAPE is a Java DSL inside a Java platform.
This library keeps the semantics and drops the platform: a YAML rule DSL with
JAPE's vocabulary (phases, `input:` visibility, control styles, priorities,
bindings), compiled onto [python-gatenlp]'s PAMPAC matcher.

## Module map

Full detail in the effort's `architecture.md` §2.

| Module | What it holds |
|---|---|
| `ttrnlp.doc` | Annotation model — engine JSON → gatenlp `Document`; `Document ⇄ proto` (P3) |
| `ttrnlp.rules` | The rule engine — YAML DSL → PAMPAC; the JAPE-exact executor |
| `ttrnlp.gazetteer` | List interchange + `Lookup` annotation (lemma / ci / fold-diacritics / exact) |
| `ttrnlp.packs` | Pack + list loading (fail-all) and **the** validation code path |
| `ttrnlp.client` | `NlpClient` (gRPC, `[grpc]`) + the HTTP engine-adapter clients (`[http]`) |
| `ttrnlp.cli` | `ttr-nlp validate` |

## Gazetteer lists

One YAML file is one list, and `matching` is a property of the **list**, not of
an entry — the mode decides what the trie is keyed on, so two modes over one
vocabulary means two files (and a diff that shows which is which).

```yaml
list: dfp-entity-aliases          # id, [a-z0-9-]+
version: 1
matching: lemma                   # exact | ci | lemma | fold-diacritics
annotation: Lookup                # optional; the type emitted
source:                           # provenance, required
  world: dfp
  origin: "glossary@2026-08-01"
entries:
  - term: faktura                 # matched via the token's `lemma` feature
    features: { kind: entity_alias, entity: faktura }
  - term: "obchodní zástupce"     # multi-token: matched as a token sequence
    features: { kind: value_alias, attribute: role, value: obchodni_zastupce }
```

| Mode | Keyed on | Use it for |
|---|---|---|
| `lemma` | the token's `lemma` feature | inflected languages — one entry covers *faktura/faktury/faktuře* |
| `ci` | the token's text, casefolded | names and keywords whose spelling is stable |
| `fold-diacritics` | the text, casefolded and unaccented | text typed without a Czech keyboard (the glossary's `*_ai`) |
| `exact` | the raw character run, no tokens | codes and SKUs (`INV-2026/0042`) |

Every emitted annotation carries the entry's `features` plus `source` (the list
id) and `matching` (the mode that fired). Those two names are **reserved**: an
entry that sets one is rejected at load, because it would erase the provenance of
the annotation it produced.

Matching is deterministic longest-match and nothing else — the longest term wins,
and what it covers is not matched again. **There is no scoring** (NL-17): no
thresholds, no edit distance, no confidence. That line belongs to the world-side
matchers (the glossary service, `lex-matcher-core`, `fuzzy-common`), where a
human can see the thresholds; a test asserts the gazetteer has not grown one.

```python
from ttrnlp.gazetteer import build_gazetteer, load_list

gazetteer = build_gazetteer([load_list("lists/dfp-entity-aliases.list.yaml")])
added = gazetteer.annotate(doc)                      # every list, load order
added = gazetteer.annotate(doc, lists=["dfp-keywords", "dfp-entity-aliases"])
```

## `ttr-nlp validate`

```bash
ttr-nlp validate packs/ lists/                 # a pack file, a pack dir, a list dir
ttr-nlp validate packs/ --model models/dfp     # + the query/parameter cross-check
ttr-nlp validate packs/ --json                 # machine output
```

```text
ERROR NLS-PACK-002 packs/dfp-query-patterns.pack.yaml:dfp-query-patterns — $.phases[query-match].rules[FakturyZakaznika]: `add.features.nazev_zakaznika.from` references `name`, which this rule's LHS never binds (bound here: subjekt) — bindings are rule-scoped

1 error(s) in packs/, lists/ — nothing would load (fail-all).
```

Exit codes: **0** these sources would load · **1** validation errors · **2** the
command could not be run as asked (a path that is not there, a missing
`--model` directory). The 1/2 split says *whose* mistake it is, so a wrapper does
not retry a typo'd path forever.

**Same code path as the service.** This command, the `nlp` service's boot-time
load and its `ReloadPacks` RPC all call `ttrnlp.packs.validate.validate_sources`
— not three readers that agree, one reader. A pack that passes here passes there,
and a test asserts the CLI and the loader emit byte-identical diagnostics on the
same fixtures. It is what lets the DFP model-validator wrap this rather than
reimplement it.

The one exception is `--model` (`NLS-PACK-005`): cross-checking a
`QueryPattern`'s query id and parameter names against a TTR-M model is CLI-only,
because the service never sees model files (contracts §5). `load_sources` has no
`model` parameter at all — and a test asserts that, so the absence reads as the
boundary it is rather than as an omission.

**Fail-all-or-nothing** (NL-15). Three good packs beside one broken one load
*nothing*. The alternative is worse than it sounds: the service comes up looking
healthy, answers most questions, and silently cannot answer the ones the broken
pack was for.

```python
from ttrnlp.packs import load_sources, validate_sources

diagnostics = validate_sources(["packs/", "lists/"], model="models/dfp")
state = load_sources(["packs/", "lists/"], pipelines=config.pipelines)
state.state_id       # same bytes ⇒ same id; what ReloadPacks reports
```

A source is a directory (globbed for `**/*.pack.yaml` and `**/*.list.yaml`), a
single file, or an `http(s)` URL naming one file (needs the `[http]` extra).

## Talking to the `nlp` service

```python
from ttrnlp.client import NlpClient

async with NlpClient("nlp:7271") as client:
    result = await client.run_pipeline(
        "Zobraz všechny faktury od zákazníka Microsoft",
        pipeline="query-patterns",
        language="cs",
    )

    for pattern in result.document.annset("").with_type("QueryPattern"):
        print(pattern.features["query"], dict(pattern.features))

    if degraded := result.diagnostic("NLS-NLP-011"):
        print("degraded:", degraded.message)   # an op the active lane cannot route
```

`run_pipeline` returns a **`Document`**, not a wire message — that is the client's
whole reason to exist. The rpc answers with an `AnnotatedDocument` (nested
`FeatureValue` oneofs, features as dotted keys); the client turns it back into the
same gatenlp `Document` the service was holding, so `annset()`, `with_type()` and
`features` all work as they do in-process.

`.analyze()`, `.batch_lemmatize()`, `.get_status()` and `.reload_packs()` are there
too. Every call carries a deadline (30s by default, per-call overridable) and
**none of them retries** — retry policy belongs to the caller, because a batch job
and an interactive request want opposite answers, and `nlp-mcp` already has a
circuit breaker.

`reload_packs()` returning `applied=False` is an outcome, not an error: the
previous snapshot is still serving and `state_id` names it.

Needs the `[grpc]` extra.

## Install

```bash
pip install ttr-nlp              # core: annotation model, rules, gazetteers, packs
pip install 'ttr-nlp[grpc]'      # + the org.tatrman.nlp.v1 client
pip install 'ttr-nlp[http]'      # + the HTTP engine-adapter clients
```

`[grpc]` is enough on its own: the wheel **carries the generated
`org.tatrman.*.v1` stubs**, built from `shared/proto` at build time and shipped
under `ttrnlp/_proto`. There is nothing to generate after installing, and the
extra's `grpcio`/`protobuf` do not contain them.

A process that already has `org.tatrman.nlp.v1` importable — `services/nlp` and
its own generated tree — keeps using its own: `ttrnlp.proto` appends the bundled
copy to `sys.path` rather than prepending it, so there is never a second
importable copy of one generated module for protobuf's descriptor pool to
collide over. In a source checkout `generated/` is on `pythonpath` and the
bundled copy is not consulted at all.

The mandatory dependency set is deliberately tiny and **model-free** —
`gatenlp`, `pydantic`, `pyyaml`, `jsonschema`. No torch, no Stanza, no spaCy, no
models. That is what lets the rule engine run in-process inside the engine-free
`nlp` front without breaking its engine-free invariant (⚑NLS-D3), and it is
enforced by a test, not just by intent.

### The gatenlp pin

`gatenlp==1.0.8` is an **exact pin** (NL-2), asserted at import time in
`ttrnlp.doc.model`. The rule compiler and the executor are written against
PAMPAC's parser and selection internals, so the pin is a contract rather than a
floor. A vendored-subset exit is pre-approved once local patches exceed two —
record any patch here before reaching for it.

**Local patches to gatenlp: none.**

## Dev

From the repo root:

```bash
just test-py shared/libs/python/ttr-nlp     # pytest
just lint-py shared/libs/python/ttr-nlp     # ruff
just build-py shared/libs/python/ttr-nlp    # uv sync --frozen
```

`just test-py ttr-nlp` (bare module name) resolves too.

## What `0.1.0` is, and is not

**In:** the annotation model, the rule DSL and its JAPE-exact executor,
gazetteers with the four matching modes, the fail-all pack loader,
`ttr-nlp validate`, `Document ⇄ proto`, and the gRPC + HTTP clients.

**Not in:** Czech morphology (`ttrnlp.morph`, arriving at NLS-P7…P9), anything
trained on a corpus (post-v1 by ruling), and rule-pack or list **content** —
that is never part of the suite (NL-17). Each world maintains its own packs.

Full detail in the repo's [`CHANGELOG.md`](https://github.com/Collite/ttr-server/blob/master/CHANGELOG.md).

## Publishing

Tag lane **`python-nlp/v<x.y.z>-RELEASE`** → `.github/workflows/publish-python.yml`
→ PyPI via Trusted Publishing. The repo tree keeps `version = "0.0.0"`; the
workflow injects the version from the tag. Bare tags (no `-RELEASE`) build but do
not publish. See the repo's `PUBLISHING.md`.

[python-gatenlp]: https://github.com/GateNLP/python-gatenlp
