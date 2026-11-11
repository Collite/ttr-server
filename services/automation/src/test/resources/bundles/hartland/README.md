# Fixture — the 0.22 hartland automation repo as `/refresh` reads it

Rebuilt for AU Server D3 with the published `org.tatrman:ttrp-cli:0.22.3` distribution.
The four recipe sources are verbatim Core `grammar/v0.22.3`
(`8c20ea5bdb2794b5fe438b2cacd0166405b72908`), under
`packages/kotlin/ttrp-frontend/src/test/resources/recipes/hartland-engine/`.

The model uses that shared hero ER/DB/binding model and automation declarations. Server fixtures retain text
order/request keys, existing deployment names and grants; the native executor is explicitly declared.
Program declarations are split into `automation/programs.ttrm`. There are no process deployments, event ports,
schedules, role delivery addresses or removed native sinks. Engine actions keep their action rows and have no
native sink. The pre-cut world used by the refresh-refusal regression lives in `../../pre-cut/world.ttrm`.

Run from the repository root **on bp-dsk**:

```sh
bash scripts/rebuild-automation-fixtures.sh /path/to/ttrp-cli-0.22.3/bin/ttrp-cli
```

The script replaces all four bundle directories with compiler output: manifest v3, SQL islands, generated
catalog and README, then rebuilds the auxiliary writer evidence cases. No hand-built or pre-cut bundle is
retained here. Typed legacy planner unit tests use separate synthetic metadata; it is never served by `/refresh`.
