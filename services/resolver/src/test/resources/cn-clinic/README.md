# cn-clinic — R-cn-6's fixture estate (CN-P4 S4.4 T6)

A tiny invented clinic (package `clinic`): two code entities whose code attributes are member vocabularies, a `model cn`
with a vocabulary-bound kind grounded on one of them, and one cs concept term. Read by `CnPeriodsCodesTest`, compiled at
test time by the real producer (`CnEstate.build`). Every code is invented; `I21` is a member of BOTH code vocabularies
in the test's data layer, so only the concept's governance can say which one a question means.
