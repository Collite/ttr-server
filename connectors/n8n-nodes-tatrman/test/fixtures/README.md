`descriptor.json` is copied from ttr-server's `services/automation/src/test/resources/descriptor/hartland.json`.
`release_order.json` is the `recipe` section of its `bundles/hartland/automation/release_order/release_order.bundle/manifest.json`.
Source: `tatrman-server-au-p1` at 2026-10-23. These are the Server's checked-in fixtures, not live-run recordings.
Run tests currently use synthetic responses matching AU-server contracts §1; recorded P3 H-1 certification is pending.

The actual recipe-detail response wraps this recipe in `{ bundle, recipe, approvals, verdictSchema }`.
Routes are under `recipe.roles.routes`; actions are the unique `recipe.wiring[].action` values.
