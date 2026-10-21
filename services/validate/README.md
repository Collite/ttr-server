# validate

> **forked-from:** the legacy predecessor platform (`services/validator/`), forked 2026-06-14 (Stage 3.1).
> **also forked-from:** the legacy predecessor platform (`infra/sql-security/`), the policy engine folded in-process (Stage 3.2, 2026-06-14).
> Maintained independently since the fork; do not assume parity with the originals.

**validate** — the validator for kantheon, the single mandatory checkpoint between
"what the user wanted" and "what runs against data". It receives a physical
`PlanNode` (post-translate MAP_TO_PHYSICAL) and:

- **SECURITY** — applies row-level security predicates (wraps matching `TableScan`s
  with `Filter`s) and column rules (DENY → reject, MASK → rewrite).
- **RULES** — enforces TopN (a `LimitOffset` cap) and the DF-V01 column allow/deny set. The cap: an unstated
  request gets `VALIDATE_DEFAULT_TOP_N`, a stated one is granted up to `VALIDATE_MAX_TOP_N`, and
  `VALIDATE_MAX_TOP_N_BY_ROLE` (`role:rows,…`, e.g. `kantheon-bi-reader:10000`) raises that ceiling for a caller
  holding the role (BI C-10). A cut answer carries a `top_n_applied` WARNING.
- **STRICT COERCION** (DF-V06, opt-in) — rejects implicit type-widening the DB would silently coerce.
- **LLM GUARD** (DF-V04, opt-in) — an LLM-as-judge semantic review with configurable fail-open/closed posture.
- **ADMIN BYPASS** (DF-V02) — `apply_security = false` honoured only when the caller's
  `PipelineContext.auth_roles` carries the configured admin role.

Output is the augmented `PlanNode` plus a `SecurityRuleApplied` audit list.

## Identity / roles (the fork's sensitive bit)

validate sources roles from **`PipelineContext.auth_roles`**, populated upstream at the
query-mcp edge from the JWT's `realm_access.roles`. validate **trusts** the context —
it does **not** parse or re-verify the bearer (in-cluster trust model; zero-trust
hop re-validation is deferred, contracts §3 / `kantheon-v1.1.md` §1). Since the Stage 3.2
fold, the in-process policy engine keys row/column policies on these bearer roles
(default `roleSource = bearer`). There is **no whois** anywhere in validate — sql-security's
whois client was dropped in the fold; the optional whois *enrichment* source is a Phase-5
additive.

## Policy engine (in-process since Stage 3.2)

Row-level predicates + column rules are evaluated **in-process** by `policy/PolicyEngine`
(folded from `infra/sql-security`; the gRPC `SecurityService`, the legacy SQL-fragment
endpoint, and OPA were dropped). `SecurityApplier` calls it through the in-process
`LocalPolicyClient` — no network hop.

**Policy store (HOCON, DF-S01).** Policies live in
[`src/main/resources/policies/policies.conf`](src/main/resources/policies/policies.conf)
(`validate.policies = [...]`), included by `application.conf`. **Authoring workflow:** edit
that file, commit, redeploy — policies are version-controlled, not a runtime API. For a
per-environment override without a rebuild, mount a HOCON file into the pod and set
`VALIDATE_POLICIES_FILE` to its path; its `validate.policies` replaces the built-in set.

**Performance.** In-process evaluation is microsecond-scale (no gRPC round-trip). The
`PolicyLatencySpec` guard asserts p50 well under **2 ms** for a single-table plan against
the default policy set — comfortably below the in-cluster network hop the fold removed.

## Proto

The service proto is `org.tatrman.validate.v1` / `ValidateService` (RPC `Validate` kept;
`com.tatrman.validator.v1` is gone). `org.tatrman.security.v1` is now a **data contract**
only (`EvaluatePoliciesRequest`/`Response`, `TablePredicate`, `ColumnRule`) — the in-process
DTOs the `PolicyEngine` consumes/produces. Its gRPC `SecurityService`, legacy SQL-fragment
messages, and `GetStatus` were removed in the 3.2 fold (the surface is not re-exposed).

## Run

```bash
just build-kt validate        # compile
just test-kt validate         # Kotest unit + component suite (98 tests)
just deploy-kt validate       # Jib image + k8s/overlays/local (local K3s)
```

Role source is configurable: `validate.roleSource = bearer` (default; roles from the
forwarded OBO bearer) | `whois` (opt-in ERP role-enrichment, fork Phase 5).

## Status (Stage 3.2 — validate whole)

Validator core + the in-process policy engine in one pod, both forked suites green
(98 tests). Readiness gates on **Veles** only (the policy engine is in-process — no
sql-security hop). Ports: HTTP 7285 (health/ready/status) · gRPC 7286 (`Validate`).
Tag: `validate/v0.1.0`.
