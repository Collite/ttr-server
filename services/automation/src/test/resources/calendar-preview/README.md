# Published calendar preview fixture

Produced unchanged by the internally released **ttrp 0.18.14** distribution. The regeneration script uses
an arbitrarily named checkout, declared packages and explicit model roots. Regenerate or verify all bytes
and the exact file set with:

```sh
python3 regenerate.py /path/to/ttrp-cli-0.18.14/bin/ttrp-cli
python3 regenerate.py /path/to/ttrp-cli-0.18.14/bin/ttrp-cli --check
```

The candidates explicitly read the model calendar five days before pinned as-of; this records its read
scope in the producer manifest. The action counts authored working-day flags between that staged date
and as-of. The real-ledger component test uses 2040-10-23 → 2040-10-28 and a calendar with a weekend and
an additional non-working day, yielding two rather than five. As-of is far from both the 2000 Open clock
and the host clock, so either wrong clock yields a different count. Query tables alone are canned. The test
checks pinned world/model/identity, approved Open, evaluate/action value, one journalled Arrow snapshot,
retry/close/restart and pre-claim/atomic read refusals. It does not claim live Query execution.

The date/working-day model files are shared by source-planning and snapshot tests. Other approval
fixtures stay on their independently pinned 0.18.9 producer; no old fixture is relabelled or rebuilt here.
