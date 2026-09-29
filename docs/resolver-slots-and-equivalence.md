# Slots and the two mention-homonymy rules (MH)

> `services/resolver` · the Binder. Companion to the frame-role tables in
> `services/resolver/src/main/resources/frame-roles.conf`.

## The problem

One word, two objects. A **channel is named after the thing that composes it** — the Stores
channel is made of stores — so on a modelled estate the bare word `prodejna` / `stores` is
claimed twice:

```
'prodejna' [cs]  ->  er.entity.store        (the DIMENSION: six rows, countable, groupable)
                 ->  er.entity.store_sales  (the FACT the Stores-channel vocabulary is pinned to)
```

Neither owns the other, so the declared-containment collapse does not apply, and both are `V:`
identities inside the Binder's tie band. The verdict is `Ambiguous` → a clarification. The Binder
is **right** on that evidence: these are two unrelated objects. What MH adds is more evidence.

The collision is systematic, not one estate's quirk: wherever a structural discriminator's member
coincides with a dimension — channel, warehouse-as-place vs warehouse-as-fulfilment,
region-as-org vs region-as-geography — the bare word will be claimed twice.

## Rule 1 — the slot decides a CROSS-KIND tie

Since the mention facet shipped, the two candidates differ in `object_kind` (`entity` vs
`entity_with_measures`). And the sentence already says which kind it wants.

`SlotHints.stamp` runs in the pipeline right after span proposal — where the parse is in scope,
because `GateSpans.gate` receives candidates only — and stamps `DomainSpanCandidate.slot`. The
Binder reads `candidate.slot`; a re-gate has no parse, gets `SlotHint.NONE`, and both rules are
no-ops there by construction.

| slot | signal | prefers |
|---|---|---|
| `COUNT_HEAD` | a count quantifier on or beside the head (`kolik`, `how many`, `number of`) | `entity` |
| `GROUP_BY` | a grouping preposition (`podle`, `by`) | `entity`, `attribute` |
| `GOVERNED_VALUE` | the span governs a value (`prodejna Nashville`) | `entity` |
| `FILTER` + measure-capable head | a filter preposition under a measure (`tržby z prodejen`) | `entity_with_measures`, `measure` |
| `FILTER` without | the same shape under a plain entity head (`vratky z prodejen`) | *(nothing — see rule 2)* |
| `COORD_WITH` | a `conj` sibling (`srovnej prodejny a web`) | the sibling's kinds |
| `SUBJECT`, `NONE` | — | *(nothing)* |

It is a **preference**, not an admissibility filter: dis-preferred kinds are dropped only when a
preferred candidate remains. It never empties the band, never promotes anything from below it,
and never touches an `M:` row — a data value is a different species, and "which object does this
word name" is not a question asked of it. A tie between two objects of the **same** kind is
genuine homonymy and still refuses; that is the definition, not a gap.

## Rule 2 — declared reachability decides whether the two readings are the SAME ROWS

The slot says which kind. This asks the harder question, and answers it from declarations rather
than from assumption.

The archive carries, per entity, the facts that relate **to** it and whether the relation is
mandatory (`targets[ref].reachedFrom`, schema `ttr-lexicon-compiled/v3`, derived from
`def relation` + `cardinality.to`). With **D** the dimension, **F** the fact the channel term is
pinned to, and **H** the fact the clause is about (`SlotHint.headRefs`):

| | condition | outcome |
|---|---|---|
| 1 | H is outside D's reach and is not F | neither reading is about this clause — say nothing |
| 2 | H **is** F and the reach is **mandatory** | the readings select the same rows: bind **D** (group-by and member filters stay possible) and record F as an equivalent reading |
| 3 | H is reachable from D but is a **different** fact | the channel term is pinned to the wrong fact — bind **D** |
| 4 | H **is** F but the reach is **nullable** | the join drops rows the restriction keeps: the readings **differ** — admit both and ask |

Rule 4 dominates 2 and 3 for a pair: an estate declaring both a mandatory and a nullable relation
between one pair is contradicting itself, and refuse-over-guess decides. With no `headRefs` —
a bare word, a fragment, a re-gate — nothing fires at all, so a one-word question keeps asking.

Rule 3 is why the two rules ship together: on its own the slot rule prefers the only
measure-capable candidate, and under a returns head that candidate is the wrong fact.

## What comes out

- **`Binding.equivalents`** — `[{ref, rule}]`, `rule = "reach-equal"`. The reading rule 2
  suppressed, so an answer can say *"read as the Store dimension; on this model that is the same
  rows as the Stores channel"*. It is **disclosure**: a consumer may surface it and must not
  re-plan on it. The claim is **equal by DECLARATION** — two readings can be declared-equal and
  still differ on dirty data (an orphan FK no constraint enforced).
- **`Option.object_kind`** on a clarification option — the option's species, so a residual
  question can be worded *"the stores (a dimension), or the Stores channel (sales)?"* instead of
  two labels a user cannot tell apart.

## On a real estate: the rules need a REACHABLE anchor

Measured on hartland, 2026-09-02, through this resolver against that estate's live NLP and its own
compiled archive. Two of the catalogue's questions behave exactly as designed:

| question | outcome |
|---|---|
| *Kolik máme prodejna* ("how many stores") | binds `er.entity.store` — the `COUNT_HEAD` rule, picking the dimension out of a tie the pre-MH resolver raised a clarification for |
| *prodejna* (bare word) | still asks, and the two options now carry `object_kind` `entity` and `entity_with_measures` |

The rest of the catalogue never reaches the tie, and the reason is worth knowing before debugging
a rule that looks inert:

- **The anchor index is keyed on unlemmatised anchors, while the matcher is queried with the
  SURFACE text.** Span proposal finds the anchor through the token's lemma — so the span is
  proposed and correctly scoped — but `prodejen` (gen. pl.) does not `EXACT`-match the stored
  `prodejna`, so no candidate reaches the tie band. The mirror image bites in English: the plural
  anchor `stores` is indexed under `stores`, and the token `stores` lemmatises to `store`, so that
  anchor can never be met at all.
- **A multi-word `TOKENS` alias captures a single token.** `'tržba z prodejen'` is a `TOKENS`
  alias of the revenue measure, so a bare `prodejen` matches it at `1.0` and binds the measure.
- **With no bare anchor for the clause head, `headRefs` is empty**, so the reachability rules have
  no `H` to decide against and the slot rule falls back to asking. On that estate `tržby` and
  `vratky` exist only inside phrases (`'tržby z prodejen'`), so this is the common case.

None of that is a defect of these rules — they decide correctly whenever the tie reaches them —
but it means **"the rule did not fire" usually means "the tie never formed"**. Check, in order:
the archive has a row whose *stored term* equals the surface being asked; the anchor's key is
reachable from the token's lemma; and the clause head bound something.

## Rule 3 — the GOVERNOR decides a member-vs-member tie (tier M)

The two rules above are about `V:` identities: which OBJECT a word names. There is a second
homonymy they cannot see, because it is not about objects at all.

```
'TN'  ->  er.entity.store.state              (a member: the store's state)
      ->  er.entity.customer_address.state   (a member: the customer's state)
      ->  er.entity.warehouse.state          (a member: the warehouse's state)
```

Three `M:` identities — data rows, each its own PK. The slot rule never touches an `M:` row and
the reachability rule is about facts, so on the evidence those two read, this is a same-kind tie
that refuses. Three options labelled `TN` is not a question anyone can answer.

What the sentence adds is the **governor**: the noun the value hangs off. *"stores in TN"* is the
store's state; *"customers in TN"* is the customer's, reached through the address. *"sales in TN"*
is genuinely either, and stays a question.

| the sentence | what happens |
|---|---|
| `stores in TN` | the governor `er.entity.store` OWNS the attribute holding the member ⇒ **bind** |
| `customers in TN` | `customer` holds no `state`, but `customer_address` does and declares `Reach(customer)` ⇒ one declared hop ⇒ **bind** |
| `sales in TN` | a FACT governor never selects among member owners ⇒ **ask**, each option naming its owner |
| `TN` | no governor ⇒ **ask** |

`keep = members.filter { E(m) == G || G ∈ reach(E(m)) || E(m) == owners[G] }`, where `E(m)` is the
entity owning the attribute whose vocabulary produced the member. One survivor binds; several ask;
**none is a no-op** — a governor that reaches no owner has said nothing, and an empty band is not
an answer. `mandatory` is not consulted: a nullable relation still names the owner the user meant.

### How a value reaches the gate at all

A governed value used to be looked up in its anchor's owners **and nowhere else**. Two things
were wrong with that, both measured before anything was written:

- **A multi-owner anchor emitted one candidate per owner on the same span**, and span dedupe kept
  whichever the registry listed first. Now there is ONE candidate gated to the union of the
  value-bearing owners (**A-MH-1a**) — `SpanProposal` proposes spans, it does not decide whose
  member a word is.
- **When the owners hold nothing, the span was swallowed.** `sales in TN` gates `TN` to a fact,
  which has no member vocabulary at all, and the covered-token rule then stopped the value from
  ever being proposed again: a G3 gap for a reason that has nothing to do with the word. So a
  governed value is now ALSO proposed openly (`Origin.OPEN_VALUE`), both questions ride the one
  batch, and `GateSpans.resolveOpenSiblings` collapses the pair to whichever spoke — governed
  first (**A-MH-1b**). A governed lookup that works is byte-identical to what it was.

⚑ On an archive-backed estate the open sibling is not an edge case, it is the load-bearing path.
`lex-matcher` keys member vocabulary on the fuzzy COLUMN's category and matches categories by
exact key, while the resolver's registry gives an entity `categories = [its own ref]` — so a value
gated to an ENTITY never meets that entity's members. The governed lookup succeeds only where the
anchor's own type carries the column categories.

**`Option.member_of`** names the owner on a member option. It is what makes the residual question
answerable: *"the store's state, the customer's, or the warehouse's?"* rather than `TN` three
times. Blank for a vocabulary option, which names its object through `target_ref` already.

## When the value is also a place or a person (the universal/domain seam)

The NER layer types some words **universal** — a place, a person, a date, an amount, a number
(`UniversalClassifier`). A universal span is grounded by the kernel and kept out of every domain
proposal path, so the estate's vocabulary is never asked about it. That is right for `Paris` in
*"sales since we opened in Paris"*, and wrong for `TN` in *"stores in TN"*, where the word is a
member of the store's state column that the NER happens to call a place (ttr-server#118).

**A place or person name that the sentence scopes to a declared entity is looked up as that
entity's member first; the NER reading stands only when no member matches.**

- **The scope is the governor.** The span must be the governed argument of a value-bearing anchor,
  the position that gives any value `GOVERNED_VALUE` above (`stores in TN`, `prodejny v Nashvillu`).
  `SpanProposal` gives it the ordinary governed pair over the entity's extent, marked as a dual
  reading of that entity (`DomainSpanCandidate.dualReadingOf`). Only `LOCATION` and `PERSON` get
  one: dates and amounts belong to grounding, and numbers to the literal path.
- **A member decides.** Right after the broad-pass gate, `UniversalSeam.supersede` reads what each
  dual reading found. A member row — bound, or a tie among members, which tier M then asks by
  owner — removes the universal it was proposed over: never grounded, never assembled, never
  reported, and the span carries one `LITERAL` finding. A declared row alone does not count: a
  model object's alias or an operator word spelled like the place leaves the place standing.
- **No member, no change.** A dual reading that found no member is withdrawn, and the place stands
  exactly as before, grounded and with its G3. The candidate list still shows the pair: proposal
  is unconditional, supersession is not.
- **Paired by the entity, not by position.** A dual reading removes only the universal whose
  offsets are the entity it read. The NER engine's extent and the parse tokens' need not agree to
  the character (a hyphenated name, an anchor word inside the name), and a candidate that merely
  overlaps a universal never removes it.

**Not scoped, not changed.** A bare place (`TN`), an unanchored one, a pre-modifier of the anchor
(`Nashville stores`), the argument of an anchor that holds no values (an operator, a measure word)
and anything under a word that did not bind are proposed by no path, as before. Without a
governor there is no entity whose member the word could be, and reading every place as a
possible value would be a guess.

**A coarse `MISC` is a name.** Engines disagree on what `MISC` means. NameTag's own code rides in
`normalized_value` as `cnec:<code>` and decides alone (`n*` a number, `o*` an object). Without a
code — Stanza, spaCy, the LLM-emulated NER, or a NameTag entity whose tag was lost — `MISC` means
"named, but not a person, place, date or organisation", so it is domain-eligible and goes to the
fuzzy gate like an `ORG`. Unless its text is a number (a digit and no letter: `501001`): a coarse
vocabulary with no number label hands numbers over as `MISC`, and those stay universal `MISC`.

Each supersession is logged once, at INFO, with ids and counts only —
`seam: LOCATION [10,12) superseded by GOVERNED_VALUE (1 contenders) conversation_id=…` — and the
span's text at DEBUG. No proto or lattice field records it. A turn that bound a place-looking value
with **no** `seam:` line took the plain governed path: the NER never typed the word universal (a
NameTag code outside `p`/`g`/`t`/`n`, say), so there was nothing to supersede.

## Configuration

`frame-roles.conf` gains `count-heads` beside `grouping-preps` / `filter-preps` — per-language
data, because the next language is a data change. A config file written before MH has no such
key, loads unchanged, and simply never produces a `COUNT_HEAD` slot.

## Compatibility

Every input the two rules read is defaulted and absent-tolerant, so an estate that declared
nothing behaves exactly as it did before:

| input absent | effect |
|---|---|
| pre-v3 archive (no `reachedFrom`) | reachability rule inert; the slot rule still works |
| no `object_kind` (no mention facet declared) | both rules inert — nothing to prefer |
| no `count-heads` in an override config | `COUNT_HEAD` never fires |
| a re-gate (no parse) | `SlotHint.NONE` — both rules inert |
| `Registry` override without `reached_from` | reachability rule inert |
| no `owner_ref` on the member's attribute | tier M inert — `E(m)` cannot be resolved, so the tie asks |
| a value with no governor, or a fact governor | tier M inert — it asks, by owner |
| no NER entity on a governed argument (NER off, or nothing tagged) | seam inert — no dual reading, nothing superseded |

No reader gate is needed for the v2 → v3 archive crossing: `reachedFrom` is a defaulted field
inside `targets`, so a v2 archive decodes in a v3 reader and a v3 archive is read by an older
reader as a v2 one.
