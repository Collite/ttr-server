# Executor-type manifests (a copy until the exporters publish theirs)

The registry grid of the descriptor (AU-server S0.2 T3) reads one manifest per executor type. The exporters plan
owns them (exporters `contracts.md` §1: TTR-M documents, one per plugin resource, type names are TTR-M ids); until
the exporters publish them, this service ships the two the hartland world's first deployments use — `native` and
`n8n_l1` — transcribed to JSON. A world executor whose type is not here gets a `refused` cell naming the unknown
type. `family` is the key an action's `sinks:` uses for the type (`n8n: door`); an action without one falls back to
its `native:` sink kind, which the type's `sinks` must list (else `TTRP-TGT-003`).
