# Resolver

The deterministic resolver turns the NLP parse and declared vocabulary into a resolution lattice.
Its optional grounding client connects to chrono when `resolver.grounding.enabled` is true.

## Time spans

With NER available and grounding enabled, the fresh-question path calls chrono `Scan` once before
span proposal or universal extraction. Quoted literals are blanked without changing UTF-16 length.
The request carries the same reference datetime, locale, prior questions and package selection as
the subsequent `Ground` calls. A request package overrides the configured default.

An overlapping NER DATE keeps its original entity. Other scanned spans become DATE entities with
source `chrono.scan`; adjacent spans remain separate. Scan's UTF-16 positions are mapped to the NLP
parse's code-point positions at the client boundary, including after an emoji. The original text
and tokens remain available to interpretation. Each surviving DATE goes through the existing
grounding and clarification path once. A `values` application retains its interval with no column
attribution.

The seam preserves separate phrases for both “Q2 sales last year” and “Revenue this year vs last
year”. Interpretation owns narrowing and comparison. The resolver contains no time-phrase rule
table. If Scan fails or returns an invalid response, the turn keeps NER's entities, logs a warning
and adds `scan_failed` to the rung log without retrying Scan.

`ScanSeamTest` replays Scan and Ground responses from `src/test/resources/time-scan/replay.json`
through the gRPC client and pipeline, including the Czech reserve question at `[19,31)` and its
`date_dim.cal_date` attribution. It needs no live chrono. Run `:services:resolver:build` on the
project's designated test cluster.
