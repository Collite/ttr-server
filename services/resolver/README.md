# Resolver

The deterministic resolver turns the NLP parse and declared vocabulary into a resolution lattice.
Its optional grounding client connects to chrono when `resolver.grounding.enabled` is true.

## Time spans

With NER available and grounding enabled, the fresh-question path calls chrono `Scan` once before
span proposal or universal extraction. Quoted literals are blanked without changing UTF-16 length.
The request carries the same reference datetime, locale, prior questions and package selection as
the subsequent `Ground` calls. A request package overrides the configured default.

Original NER DATE, numeric and MONEY entities keep ownership: any positive overlap drops the whole
Scan candidate, including a longer phrase or year range. The existing universal classifier determines
numeric types; domain MISC names do not gain this protection. Adjacent spans remain separate. Other
scanned spans become DATE entities with source `chrono.scan`. Scan's UTF-16 positions are mapped to the NLP
parse's code-point positions at the client boundary, including after an emoji. The original text
and tokens remain available to interpretation. Each surviving DATE goes through the existing
grounding and clarification path once. A `values` application retains its interval with no column
attribution. A number that resembles a year still follows #206's existing contextual promotion;
it becomes a DATE only when the kernel supplies an anchor, otherwise it returns to its numeric reading.
Suppressing true time phrases that overlap numeric NER is an accepted conservative tradeoff.

The seam preserves separate phrases for both “Q2 sales last year” and “Revenue this year vs last
year”. Interpretation owns narrowing and comparison. The resolver contains no time-phrase rule
table. If Scan fails or returns an invalid response, the turn keeps NER's entities, logs a warning
and adds `scan_failed` to the rung log without retrying Scan.

`ScanSeamTest` replays Scan and Ground responses from `src/test/resources/time-scan/replay.json`
through the gRPC client and pipeline, including the Czech reserve question at `[19,31)` and its
`date_dim.cal_date` attribution. It needs no live chrono. Run `:services:resolver:build` on the
project's designated test cluster.

`MistypedYearAttributionTest` replays actual Scan responses for its existing #206 questions from
`numeric-ner-replay.json`: unchanged chrono at server base `448b5e61` (the `chrono/v0.11.32` code),
fixed reference `2026-10-13T00:00:00Z`, empty prior questions and trigger slice. Order IDs retain their
numeric reading; measure years ground once with an anchor and revert without one. Nonempty amount/count
guards also shift the captured bare-year candidates into those questions' numeric spans, including where
the producer already excludes an amount or count. The same fixture supplies the Unicode year-range
overlap guard in `ScanSeamTest`. All replay tests run without a live chrono.
