# Fixture — the hartland automation repo, as `/refresh` reads it (AU-server-P0 · S0.2)

- `model/` — the slice of hartland's model the automation package names, and the demo world with its automation
  half (design `17` §2, ttr-core grammar 0.16).
- `automation/automation.ttrm`, `automation/programs.ttrm` — ttr-core's shared fixtures
  (`ttr-parser/src/test/resources/automation/`), verbatim.
- `automation/<recipe>/<recipe>.ttrp` — the four `17` recipes (ttr-core `ttrp-frontend/src/test/resources/recipes/`).
- `automation/<recipe>/<recipe>.bundle/` — **fixture-prebuilt** (`"toolchain": "fixture-prebuilt"`): manifest v3,
  islands and the generated catalog assembled by hand from ttr-core `contracts.md` §5 until ttr-core P2 builds them.
  S1.1 replaces them with `ttrp build` output. Canonical containers and the decision's route islands carry
  placeholder statements.
