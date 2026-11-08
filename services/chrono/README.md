# chrono

**chrono** — the DATE_TIME grounding kernel. It reads a time phrase of a question („loni“, „minulý měsíc“,
"May 2026", "15. 3. 2026") into a half-open interval `[start, end)` and, when the model offers a date column,
into the recipe that restricts it. The service proto is `org.tatrman.grounding.v1` / `GroundingService`
(RPCs `Ground`, `Scan`, `GetStatus`); `services/grounding-mcp` exposes it to agents as `ground_time` and
`scan_time`.

Two steps, kept apart:

- **Recognition** needs the reference date, the calendar and the conversation — never the model and never a
  clock (`reference_datetime` always comes from the request).
- **Application** — which attribute the interval restricts and in what shape — reads the package's metadata:
  the `event_date` role (or the role the wording names: `due_date`, `posting_date`, `document_date`) and the
  period table (`semantics { kind: period_table }` with `period_start`/`period_end`/`period_code`).

A recognised phrase is always answered with its interval; a model with no date column changes only the
application.

## What it recognises

Czech and English, case-inflected and with or without diacritics. Intervals are `[start, end)`; *ref* is the
reference date of the request.

| Phrase family | Czech | English | Reading |
|---|---|---|---|
| dates | *15. 3. 2026*, *15. května 2026*, *2026-03-15* | *March 15 2026* | that day; a date with no year takes the year of the question or the conversation, else ref's |
| months | *květen 2026*, *v říjnu 2025*, *květnové období* | *May 2026*, *May period* | that month, `PERIOD` `yyyyMM` |
| last month of a year | *poslední měsíc roku*, *v posledním měsíci loňského roku*, *poslední měsíc roku 2025* | *the last month of the year*, *… of last year*, *… of 2025* | December of that year; yearless reads as *December* (and asks the same way) |
| month, no year | *za červenec*, *od června*, *v září* | *in July*, *for September* | that month; the year from the conversation (#214), else ref's — and **asks** this year vs last when the month is later than ref's |
| period code | *období 202605*, *202605* (alone) | *period 202605* | that month; a six-digit run inside other words is no period |
| years | *2025*, *v roce 2025*, *fiskální rok 2026* | *2025*, *fiscal year 2026* | the calendar year (`CALENDAR_YEAR` / `FISCAL_YEAR`); a quarter or half of a fiscal year (*Q4 of fiscal year 2025*) is that quarter or half |
| relative years | *letos*, *loni*, *vloni*, *předloni*, *letošní*, *loňský*, *předloňský*, *tento rok* | *this year*, *last year*, *the year before last* | ref's year, the one before, two before |
| relative months, quarters | *tento / minulý / předminulý měsíc*, *toto / minulé čtvrtletí*, *tento / minulý kvartál* | *this / last month*, *this / last quarter* | ref's period, or the one before; quarters `PERIOD` `yyyyQn` |
| anaphoric | *předchozí / předešlý rok, měsíc, čtvrtletí*; *minulý …* | *the previous year*, *the month before* | the period before the one the conversation is about (#214); *minulý* asks when the two readings differ; nothing to count from → `UNGROUNDABLE no_anchor` |
| elapsed | *uplynulý měsíc / týden / rok / čtvrtletí* | *the past month / year* | the period before ref's |
| weeks | *tento / minulý / předminulý týden* | *this / last week* | Monday to Monday |
| numbered quarter | *2. čtvrtletí 2026*, *druhé čtvrtletí*, *3. kvartál*, *Q2 2026*, *Q2*, *poslední čtvrtletí roku* | *Q2 2026*, *the second quarter of 2025*, *the last quarter of the year*, *… of the previous year* | that quarter, `yyyyQn` (the last quarter of a year is its fourth); yearless reads as a yearless month (and asks the same way); a quarter in a year chrono cannot read — one it reads nowhere (*Q2 next year*, *the last quarter of next year*, *Q2 a year ago*, *každé 2. čtvrtletí*) or digits no pattern places (*Q2 for 2025*) — is not read, nor is a yearless one in a span that says anything else about a year (*Q2 FY25*, *Q2 25*, *Q2 10 stores*, *next Q2*, *Q2 by year*, *2. čtvrtletí o rok dříve*, *ve 2. čtvrtletích*): only a neutral frame (*of the fiscal year*, *kalendářního roku*, *year over year*) lets it take a year; a year only after a bound word (*Q2 since 2020*, *2. čtvrtletí do roku 2025*, *Q2 since last year*) is a range's bound, and the span is not read; so is a quarter after a range or exclusion word (*since Q2*, *in 2025 excluding Q4*, *kromě 4. čtvrtletí*, *Q2 onwards*), but not after *from* (*sales from Q2*) |
| half-year | *1. pololetí 2026*, *druhé pololetí*, *H1 2026*, *poslední pololetí loňského roku* | *H1 2026*, *the first half of 2026*, *the second half of last year*, *the last half of the year* | six months, `yyyyHn` (the last half of a year is its second); yearless reads as a yearless month; in a year chrono cannot read (*H1 next year*, *první pololetí příštího roku*, *H2 fiscal 2025*), not read |
| trailing | *poslední měsíc / týden / rok*, *posledního měsíce*, *v posledním měsíci* | *the last month / week / year* | **asks** (`AWAITING_CLARIFICATION`): `trailing` — the last 30 / 7 / 365 days through ref — or `calendar` — the period before ref's. The answer is not in the conversation, so an anaphoric period of the unit after it („A předchozí měsíc?“) is `no_anchor`, as after an asked *minulý* |
| last N | *posledních 30 dní*, *poslední 3 měsíce* | *last 30 days*, *past 3 months* | exactly N days ending with ref, `[ref − N + 1 day, ref + 1 day)`; N months from the day after the same date N months back; a count of weeks or years (*posledních 6 týdnů*, *past 2 years*) is not read, nor is a count of a larger period (*the last 2 months of the year*, *poslední 2 měsíce roku*, *the last 10 days in March*) |
| year to date | *od začátku roku*, *letos dosud*, *… do dneška*, *… do současnosti*, *… do března* | *year to date*, *YTD*, *… as of today*, *… so far*, *… through September*, *… by end of March* | `[1 January, ref + 1 day)`; ending with a month, to that month's end; in the year the span states, in digits or words (*loni*, *last year*), else ref's; with no ending, a year other than ref's is that whole year; a breakdown (*by region*, *by month*) is no ending, nor is *do* / *to* before a place (*do Německa*, *to Germany*); any other ending that names a time is no one period; with no year stated, a span that says anything else about a year (*YTD FY25*, *YTD in previous years*) is not read |
| days | *dnes*, *včera*, *zítra* | *today*, *yesterday*, *tomorrow* | that day |

Not a period: a bare period noun (*měsíc*, *po měsících*, *roce*) — a breakdown or a trigger word; the grain
adjectives (*měsíční*, *čtvrtletní*, *pololetní*, *kvartální*, *monthly*, *quarterly*); a week, month or day **of** a
stated year ("the last week of 2025"), which no rule reads yet; a quarter or half-year by number in a span that
names any other period — *1. a 2. čtvrtletí*, *first and second quarter of 2025*, *Q1 a Q2 2025*, *last quarter and
Q1*, *Q2 2025 vs 2024*, *Q3 and H1 2025*, *Q1 a březen 2025*, a year joined by a comparison or a conjunction (*Q2
this year vs last year*, *2. čtvrtletí letos a loni*, *Q2 vs 2024*), a comparison with a time on its other side
(*Q2 vs the same quarter last year*, *2. čtvrtletí oproti stejnému období loni*, *Q2 vs a year ago*), a count (*Q1 and the last 3 months*) or year to
date (*Q2 vs YTD*) — which is not one period (a year word with no joiner, *letošní 2. čtvrtletí*, *Q2 last year*, only
says which year); nor is year to date beside any other period (*year to date vs last year*, *YTD vs the same period
last year*), nor a month, date, relative word or year beside another period (*leden až březen 2025*, *March vs last
year*, *minulý a tento měsíc*, *this quarter and last month*, *fiscal year 2025 vs 2024*) or in a year chrono reads
nowhere (*March next year*). A month of a fiscal year is that month: *December of fiscal year 2025* is 202512.
The trailing ask is the singular only: *poslední měsíce* (recent months) does not ask. The last week of any period
and the last month of a quarter — *the last week of the year*, *the last week in October*, *the last month of Q3
2025*, *poslední týden v měsíci*, *poslední měsíc čtvrtletí* — are not read at all, nor is the last quarter, half or
month of a year chrono cannot name (*the last month of next year*, *poslední čtvrtletí každého roku*); an earlier
question with one names no period of its unit. A year is part of no month: *last year in March* is March of last
year. An estate's `ground:chrono` lexicon slice (RV-42)
says which words are chrono's; it never changes what they mean.

A one- or two-digit number after a yearless period is ambiguous: *Q2 25* and *Q2 10 stores* are both left unread;
chrono does not decide whether it is a short year or a count.

## Outcomes of `Ground`

| Status | When | Result |
|---|---|---|
| `OK` · `join` | a period ("May 2026", „období 202605“) and a period table **of its grain** with start, end and code columns: a month meets a monthly table, a quarter a `yyyyQn` one; a `code_format` of another grain (`yyyy`, `yyyyMMdd`) meets none | `JoinRecipe` on the period table, filter `code = {p}` |
| `OK` · `filter` | an anchor date column: a period with no period table, or any other interval | `FilterRecipe` over `period_start({p})`/`period_end({p})`, or datetime bounds `{start}`/`{end}`; a quarter or half-year with no table of its grain takes datetime bounds |
| `OK` · `filter` | no date column, but a period table with a code column and a period phrase of its grain (#140) | `FilterRecipe` `code = {p}` |
| `OK` · `values` | **no date column and no period-code route** (no table, or a period of another grain) | `ValueBinding`: `start`, `end` (datetime, half-open, the request's zone) and `p` (the period code, `yyyyMM` / `yyyyQn`) when the phrase names a period; `sql_preview` empty; `explanation` names the package. The caller places the values. |
| `AWAITING_CLARIFICATION` | the phrase has two readings (a bare future month, „minulý rok“ after a question about a year) | one option per reading, `id` = the period code, `normalized` = its interval; resume with `clarification_answer_id` |
| `UNGROUNDABLE` | nothing read in the span (and no LLM fallback, or the fallback failed); a missing `reference_datetime`; an unknown `clarification_answer_id` | a `ResponseMessage` saying why |
| `UNGROUNDABLE` · `no_anchor` | an anaphoric period with nothing in the conversation to count from (#214) | never read against the reference date |

`UNGROUNDABLE` never means "the model has no date column" — that is `OK` with `values` (TS-1). A recognition
below the confidence threshold goes to the llm-gateway fallback (`source: LLM`) when one is configured; with no date
column it may answer `values` too (`start`/`end`, empty `sql_preview`).

Every `OK` carries `normalized.interval`; `filter` and `join` carry a `sql_preview` rendered from the recipe's own
expression tree, with parameters as `{name}`.

## `Scan` — the time phrases in a question

`Scan(ScanRequest { text, context, package })` returns the time phrases of a question, so no rule about which words
are a time phrase lives outside chrono (TS-P0 S0.3, contracts §3). Each is a `TimeSpan`: `start`, `end` (UTF-16 code
units into `text` as sent, end exclusive), `text`, `kind` (`DATE_TIME`), `confidence`, `trigger` (the slice term that
fired, else the kind of period read: `relative`, `period`, `absolute`, `calendar_year`, `fiscal_year`;
`conversation` for an unanchored one). The context is checked as for `Ground` — no `reference_datetime`, no spans and
an `ERROR` message `reference_required` — and the LLM fallback is never called. The caller blanks quoted literals
first, keeping their length.

How (`scan/SpanScanner.kt`): every window of one to seven tokens is read exactly as `Ground` reads a span, with the
text as the question and `prior_questions` after it.

- **A span reads** as a period at or above the threshold, or as an unanchored one (it *is* a time phrase; `Ground`
  answers it `no_anchor`).
- **Only words of a time and the phrase's articles or prepositions belong in a span.** Measures, attributes, verbs,
  conjunctions and numbers that no rule reads as a time stay outside it: "Q2 sales last year" gives "Q2" and "last
  year". The consumer can still ground Q2 against the question's year. A smaller window reading the same makes
  extra words idle. A period noun before it belongs only when it names the unit read ("year 2025"), or names its
  fiscal calendar ("fiscal year 2025 Q4"); "per quarter last year" leaves "quarter" outside. A phrase keeps its own
  words: „loňského roku“, "Q2 of the year", „od začátku roku 2025 do dneška“.
- **Only linked phrases can suppress one another.** Small words join a phrase ("Q2 of last year"); comparisons,
  bounds, exclusions, relative words and a conjunction directly before a time word link phrases. An unrelated
  clause does not: "Sales last year and costs this month" gives both times. A window the recogniser finds names no one period — "next Q2", "excluding
  Q4", "Q2 since 2020", "Q2 vs the same quarter last year", „1. pololetí letos a loni“ — keeps every span it touches
  out, so the readings of review-215 RR17–RR19 hold through `Scan`. Periods side by side keep their spans only when
  they are all of the phrase and each reads the same without the question: "Q2 2025 vs Q2 2024" and "this quarter and
  last month" give two spans; „leden až březen 2025“ (*leden* borrows the year), „1. a 2. čtvrtletí“ and „minulý a
  tento měsíc“ (a word left out) give none. A phrase a window cuts is read whole.
- **The longest span wins**, then the earliest; spans never overlap and may be adjacent.
- **No numbers.** A six-digit run in a longer text is a document number (*období 202605* is a period, and so is
  *202605* alone); a four-digit run the words around it make an amount ("more than 2000 units") is none. A bare
  four-digit year is a span: the consumer's NER-stands rule decides it.
- **No words that only look like months**, blanked before the windows are read: a month's stem inside another word
  (*zařízení*), or a name ("Jan Novák"). `MonthWords` is shared with the recognizer and conversation reader: *march*
  is a month; *may* is a verb before a pronoun, or lowercase inside a clause without a preposition, number or joined
  month. An abbreviation is a month after a preposition, beside a day/year or joined to another month. Blanking
  checks each part of a token ("Jan-Mar", "March/April", "October's"), preserving UTF-16 offsets.
- **At most 128 tokens.** Longer text returns the first 128 tokens' spans and a WARNING `text_too_long` message,
  also passed through `scan_time`. Candidate checks visit only their own sub-windows; dense text is bounded too.

It is a pure function of `(text, context, package, slice version)`. `src/test/resources/scan/planner-spans.json` holds
review-215's planner spans with the reading each gives (`PlannerSpansSpec`); a scan of the corpus sentences takes
p95 below 20 ms (`SpanScannerBenchmarkSpec` fails above 20 ms).

## Metrics

- `chrono_ground_total{outcome, source, application}` — `application` is `filter`, `join` or `values` for an `OK`,
  `none` otherwise; the `values` share is how often a question reached a model that declares no date column.
- `chrono_ground_latency_ms{outcome, source}`.
- `chrono_scan_total{spans}` — the spans a `Scan` found, `0`, `1`, `2` or `3+` (`error` for invalid context); `chrono_scan_latency_ms`.

## Tests

`just test-kt chrono`. The golden corpus is `src/test/resources/corpus/chrono/cases.json` (`dateRoles: false`
is a package with no date role); `just eval-grounding-build` folds it into `eval/grounding/corpus`.
