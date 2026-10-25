# The Studio Viewer's layout wire fixtures

`layouts.json` (`GET /model/layouts`) and `layout-positions.json` (`GET /model/layout?name=positions`), copied
verbatim from ttr-core `packages/designer/src/data/__tests__/fixtures/metadata/` at commit `a49d3fc` — the bodies the
viewer is tested against. `LayoutRoutesSpec` holds the key set of every body the routes serve to these files
(VL contracts §6, A-VL-2: every field always present). Change them only together with the viewer's copies.
