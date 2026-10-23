<!-- SPDX-License-Identifier: Apache-2.0 -->
# Conformance — the three-tier instrument (RG-P6.S2)

The Resolution & Grounding parity instrument (RS-30). Its **gating** tier is the SV-P3 "parity
demonstrated" gate; the other two tiers seed the fuller E2E coverage that SV-P4 authors.

| Tier | Runnable | Gating? | What it asserts |
|---|---|---|---|
| **Service-level** | `just conformance-service-level` | **YES** (CI) | The service-level corpora + the RV-P4 conversation tier pass, hermetic + self-contained + **no pilot-estate dependency**. |
| **E2E core** (`calls:`) | `services/resolver/.../conformance/calls/` seeds (`CallsSeedConformanceTest`, `RefusalOverGuessConformanceTest`, gated) | drivable seeds run vs the REAL pipeline; full at SV-P4 | Multi-turn door conversations. The refusal-over-guess + clarification-round-trip seeds drive the actual `ResolverPipeline` (SpanProposal → GateSpans → HMAC codec) via hermetic nlp/fuzzy fakes; `seed_only` fixtures (hero, geo-dark) are shape-validated pending the live SV-P4 stack. |
| **Extended** (pilot) | `just eval-grounding` (live) | **NO** (scored-not-gating) | Live grounding eval over the pilot corpus (arrives via RO-19 ask ③). Reports pass-rate; never fails CI. |

## The gating service-level tier — `just conformance-service-level`

Runs seven **hermetic** corpora (zero live services, zero pilot estate) — six service-level, plus
the RV-P4 conversation tier that sits above them:

1. **ENTITIES_ONLY** (resolver) — `Q20ParityTest` over
   `services/resolver/src/test/resources/q20/entities_only.jsonl` (12 cases).
   Replays the recorded Q-20 span-gating behavior through the real `GateSpans`. Acceptance baseline
   from the vendored spike (config C: **P=1.0, ENTITIES_ONLY R≥0.8, 0 spurious, awaiting 1/5**).
   Provenance: `q20-spike-results.json` + `PROVENANCE.txt` (numbers cited, never recomputed).
2. **Q-17 match-quality** (fuzzy) — `MatchQualityCorpusTest` over
   `services/lex-matcher/src/test/resources/match-quality-corpus.jsonl` (40 cases: diacritics /
   inflection / multi-word-order / typos), lemma axis ON, asserting the expected top id per case.
3. **hartland_cz declared layer** (fuzzy — RV-P1.4 T7) — `LexiconConformanceTest` over
   `services/lex-matcher/src/test/resources/conformance/hartland-cz/` (23 cases + 6 per-run
   assertions). The fixture is the **authored source**, not an artifact: `aliases.lex.yaml` +
   `skills/trend.md` are compiled and packed by the real RV-P1.2 toolchain
   (`LexiconValidator` → `LexiconCompiler` → `LexiconPacker`) and read back through
   `LexiconArchiveSource`, so a red case means the *chain* broke rather than that a fixture
   drifted. Classes: `declared` · `operator` (an `op:` trigger from skill **frontmatter**; the body
   must never become a candidate, RV-35) · `method` (EXACT/TYPOS(n) admission, RV-32) ·
   `tokens-margin` (an ambiguous term is returned but never auto-bindable) · `member` ·
   **`profile` / `profile-guard` / `profile-sugar`** (RV-44, added at RV-P3.0 T7: a declared
   matching profile scoring on the canonical, folded and typos strata; the ⚑M-4 short-term guard
   refusing to fuzz a two-letter code while the code still matches itself; and a `method: TYPOS(1)`
   row scoring identically to the profile that sugar expands to).
   Also asserts the **RV-39 layer tuple** (artifact hash, per-category member versions, overlay
   absent) and that **with no archive present the member path is byte-identical** — the
   no-behaviour-change-without-the-artifact promise.
   Provenance: hand-authored 2026-08-03 against the hartland BM-arc Czech world and hartland's real
   `md`/`er` refs; extended 2026-08-06 (RV-P3.0 T7) with the RV-44 profile cases, authored beside
   the `method:` rows rather than replacing them — both spellings have to keep working in one
   corpus, and the `zakaznik`/`prijemka` pair is exactly that contrast.
4. **RV-P2 core lattice** (resolver — RV-P2.5 T6, the **P2 phase gate**) — three specs, all
   hermetic (faked nlp/fuzzy, in-process gRPC; no live service, no LLM):
   `LatticeGoldenTest` over `services/resolver/src/test/resources/lattice/` — four hero cases
   (`h1-cs` the 0-LLM proof · `h1prime-cs` the G4 · `h2-cs` the two honest gaps · `h5-cs` the
   three-operator slice), each asserting the **whole** emitted `ResolutionState`;
   `GateConformanceTest` over `conformance/calls/gate-h1prime-correction.json` — the H1′
   re-gate pair (`resolve.bind:v1` → G4, then `resolve.gate:v1` with the correction → EXACT
   binding, gap closed) driven over a real gRPC channel; and `IssuesRegressionTest` — the two
   2026-07-28 `issues.md` failures as the named cases `issues-260728-1` / `-2`, each asserting
   **both** directions (the right binding present AND the wrong one absent).
   Provenance: the parses are the RV-P0.2 spike's real cached Stanza output; only the NER layer
   is authored, and `lattice/PROVENANCE.md` names every entity it adds and why.
5. **Conformance CONVERSATIONS** (golem-py — RV-P4.4 T2/T3, the **P4 phase gate**) —
   the five hero conversations under [`conformance/conversations/`](conversations/)
   (`h1-answer` · `h1prime-regate` · `h2-ask-pin-resume` · `h4-refusal` ·
   `h5-answer-with-gap`), driven through the Python OS Golem by
   `services/golem-py/tests/test_conformance_conversations.py`. Multi-turn, hermetic:
   the core is RECORDED from the resolver's own lattice goldens, so a core change fails
   this tier the same day it fails `LatticeGoldenTest`. Asserted numerically where the
   claim is a number — H1 is `llm_invocations == 0 ∧ asks == 0`.
   **These fixtures are SHARED**: RV-P5's Kotlin Golem must pass the same files
   unchanged (RV-28 — one corpus, one core, N shells), which is why they live here
   rather than inside one service's test resources. Schema + the invariant vocabulary:
   [`conversations/SCHEMA.md`](conversations/SCHEMA.md). Hash-pinned like every other
   corpus here.
6. **cn-estate declared layer** (fuzzy — CN-P4 S4.3) — `CnConformanceTest` over
   `services/lex-matcher/src/test/resources/conformance/cn-estate/cases.jsonl` (3 cases). The estate is
   `shared/testdata/cn-estate` (an invented `shop` package with a `model cn` of twelve concepts, the copy
   veles and the resolver read too), compiled at test time by the real producer at the pinned grammar
   (`LexiconBuild.run` → lexicon archive **v6**) and read back through `LexiconArchiveSource`. Each case
   runs through Lookup and through Match, which must give the same exact-form targets, and Match
   narrowed to one reading's category returns that reading alone. Classes: `concept` (a concept's
   term reaches its `cn.*` ref, served as an er object's term is) · `beside` (a form a concept and an
   element both carry returns both readings at one score; the resolver orders them) · `member` (a
   member term stays the member: the matcher never lifts to a concept, the resolver does).
   Provenance: authored 2026-10-23 against the cn-estate fixture, itself a byte-for-byte copy of
   ttr-core's `ttr-lexicon-compile` cn fixture (see its README).
7. **Grounding hermetic** — `eval/grounding/tests/` (`test_corpus_valid.py` + `test_report.py`,
   21 checks): corpus-validity of the 123-case bulk + 21-case e2e corpora, and the pure `report.py`
   scoring logic. The **live** run (`run_eval.py` → grounding-mcp + Golem) is the extended tier, NOT here.

### Corpus provenance (hashes — ENFORCED; bump on any deliberate change)
These are verified on every gate run by `just conformance-verify-hashes` (reading
`conformance/corpus-hashes.sha256`); a silent edit — even whitespace/reordering a
semantic test would miss — fails the gate (RG-P6 review I). Keep the two lists in sync.
```
entities_only.jsonl             ccda5f2a891a16789b3b6cb752b3baab9b9a8b8fe96e2f01fd41a8d7444352a7
match-quality-corpus.jsonl      f994789391af7773bb1061f1d7953a2aa870fd5233877574547cca284a5c2a3d
grounding-cases.json            249ce56ce74ec681bec956c47345d9d275ab20dc19d333a0c3da14fbbb3368ff
e2e-cases.json                  0034ed387f31c1dca8ba1a56b22b72f968193ee5f1aacb0f20aa7336facf77f7
hartland-cz/aliases.lex.yaml    78577f1f142fdb6b9bca261e559b69335885358bcd4ba9ed6fb2bd75d45b2208
hartland-cz/skills/trend.md     3db553d63ab7dcbecd021326dbb13f9cab72b71ee9d7d571c663f65349208bc2
hartland-cz/cases.jsonl         1a0754ddac81c219a11c074d427c52c36ded5eab4304feeaa28c0c30c2f54de9
cn-estate/cases.jsonl           075777f50cf9eb888ce567a470c84ded9c43225570e9ec488e43a39b87c1cd31
cn-estate/model/binding/shop.ttrm       071157c002a742675beca8b7a8d0a1d47b81331d75b40ff443c60767801b77bc
cn-estate/model/cn/shop.ttrm            2646bc33720d21b7fca47de6444400f052153f29880e1a2322594acd1eb0e409
cn-estate/model/er/shop.ttrm            4ebedd04daa85e05a0821b873e22e9ecd711fe2d0f01f25347aa87a4a727e3a7
cn-estate/model/lexicon/cs/terms.ttrm   2627ef02f2f429295277a554fbc4d0365b4a9ad54e9de879e3785fee2363f905
cn-estate/model/lexicon/en/terms.ttrm   186a07d7663af482c8d23877cfa6641ac30cce52d26e8643da0555da6802b09e
cn-estate/model/md/shop.ttrm            8a21c45249736ab68acfe9e70b9b424cddb278c0b41fc1641c39bbd8a55f0672
```

The hartland_cz leg pins **three** files, not one: the corpus is authored source compiled at test
time, so the lexicon and the skill are as much fixture as the cases are. A silent edit to either
would change what is served without changing a single expectation. The cn-estate leg pins its cases
and the six model files it compiles, for the same reason: re-copying the estate from ttr-core is a
deliberate change, and bumps these hashes in the same commit.

### Why hermetic (no pilot estate)
The gate must be green in CI without a deployed stack or any pilot estate — that is the SV-P3 promise.
(The grounding leg's one network touch is a one-time install of the **pinned** test deps in
`eval/grounding/requirements-test.txt`, skipped once present — no unpinned/floating package, so the
run stays reproducible; RG-P6 review H.)
The live end-to-end (parse → fuzzy → grounding → door) is intentionally deferred: the E2E core tier is
hand-authored `calls:` fixtures (SV-P4 with the reference Golem), and the extended tier scores the pilot
corpus without gating. What is gated here is **service-level parity against recorded spike/referee
numbers** — the deterministic behavior each service must not regress.

CI: `.github/workflows/ci.yml` job `conformance` runs `just conformance-service-level` on every push/PR.

## S-2 fold audit (RG-P6.S2.T4)

The one normalization spec is `shared/libs/kotlin/text` → `Normalization.fold` (lower → NFD → strip
combining marks); its golden vectors (`NormalizationSpec`) are the fixture. **No understanding-layer site
keeps a private fold.** Call-site status:

| Site | Status |
|---|---|
| lex-matcher (`TextNormalizer.fold`) | ✅ shared (RG-P0.S3) |
| resolver span kernel (`SpanProposal`) | ✅ shared (RG-P5) |
| chrono / money recognizers | ✅ shared (RG-P3) |
| geo span parser (`GeoSpanParser`) | ✅ shared |
| geo `PlaceResolver` / `BoundaryStore.foldPlaceKey` / `GeoCorpusSpec` | ✅ **converged in RG-P6.S2.T4** — private folds removed; `trim()` kept as geo's visible input pre-step; `FoldParitySpec` locks byte-identity to the shared spec. (The old private copies stripped `\p{M}` vs the shared `\p{Mn}` — output-neutral on Czech place names.) |
| **meta.search** `ttr-metadata` `Tokenizer.fold` (tatrman repo → consumed by veles) | ⚠️ **documented characterization bridge — the one remaining copy.** It folds in a **different operation order** (NFD → strip → lowercase, vs the shared lower → NFD → strip), and lives in a *different repo* + a *published* artifact. Converging it requires: (1) a characterization test over veles's keyword corpus proving the op-order swap is output-neutral there; (2) a `ttr-metadata → org.tatrman:text` dependency; (3) a `ttr-metadata` republish. Tracked as the T4 follow-up — not swapped blind (behavior + cross-repo + release risk). |

