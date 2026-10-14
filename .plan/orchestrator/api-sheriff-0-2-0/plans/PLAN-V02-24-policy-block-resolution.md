# PLAN-V02-24: One Resolution Mechanism for Every Policy Block

epic: api-sheriff-0-2-0
workstream: WS-01

> Staged plan spec — one shippable unit of work, ready for `/plan-marshall` hand-off.
> The orchestrator EMITS the command below; it never launches the plan inline.
> Staged 2026-10-14 from inbox `plan-v02-23-cookie-forwarding-policy-001` (operator decision: PLAN-V02-23
> is paused until the edge pipeline and policy-block resolution are reworked; "GatewayEdgeRoute is a
> nightmare. We need a major rework here."). First of three refactor plans: this one, then
> `PLAN-V02-25` (edge decomposition) and `PLAN-V02-26` (validator split), then the re-grounded V02-23.

## Objective

`doc/user/anchors.adoc` states one rule — a policy block resolves gateway → anchor → endpoint → route,
and every step replaces the whole block — but the code re-implements it per block, partially, by hand.
Adding a block at all four scopes (V02-23's `cookies`) costs about 15 files before any feature logic,
and every future block pays the same. Make a new policy block a declaration, not a code path.

Pure refactor: no observable behaviour changes, except where a block's current resolution disagrees
with the documented rule — each such case is a finding, decided by the operator, never fixed silently.

## Deliverables

1. **Inventory first.** For every policy block resolved in `config/RouteTableBuilder.java`, record its
   declared scopes, its actual resolution chain and how it compares with the documented rule. At
   `5490144f` these are: `resolveEffectiveAuth` (route → endpoint → anchor), `resolveSecurityFilter`
   (route → anchor), `resolveSecurityHeaders` (anchor → gateway), `effectiveAllowedMethods`
   (gateway / endpoint / anchor), `effectiveAllowedOrigins` (route only), `resolveDefaults`,
   `resolveRetryEnabled`, `resolveNotModifiedEnabled`, `resolveWebSocketIdleTimeout`; `forward` is
   route-only. Re-derive the list at outline; do not trust it. Put every divergence from the documented
   rule to the operator at outline with a recommendation.
2. **One generic mechanism.** A block declares its scopes once. Resolution (nearest declared scope wins,
   whole-block replacement) and the relaxing-override WARN (today the single hand-written
   `warnOnWeakeningOverride`) are derived from that declaration. A block that is legitimately not
   whole-block (for example a merged default) says so explicitly in its declaration, not in a bespoke
   method. Migrate every block in the inventory onto it; delete the per-block methods.
3. **Config records stop being constructed positionally in tests.** `AuthConfig` alone is built with
   its canonical constructor 104 times in 8 test files at `5490144f`; adding one component breaks all
   of them. Introduce builders or shared test fixtures for the policy-block records and the scope
   records (`GatewayConfig`, `AnchorConfig`, `EndpointConfig`, `RouteConfig`), and migrate the tests,
   so a new field touches only the tests that are about it.
4. **Declare once, register once.** Measure what adding a block costs today (records, both JSON
   schemas, `ConfigModelReflection`, `DocumentedSetsContractTest`, the array-key inventory in
   `doc/configuration.adoc`) and cut every step the mechanism can derive. Whatever cannot be derived
   gets a contract test that names the missing step (a new block not registered in
   `ConfigModelReflection` must fail the JVM build, not only the native image).
5. **Documentation and record.** `doc/user/anchors.adoc` describes the rule once, and the per-block pages
   link to it instead of restating it. An ADR records the mechanism (derive the ordinal from `doc/adr/`
   at write time). Document how to add a new block in `doc/development/`.

Split guard: 5 deliverables. Keep the PR under 100 files and 150,000 diff characters (review-bot limits);
if deliverable 3's test migration alone would exceed that, ship it as its own first PR.

## Claim Labels

- OBSERVED: `RouteTableBuilder.java` is 659 lines and carries the per-block methods listed in deliverable 1 — read at `5490144f`
- OBSERVED: the only relaxing-override WARN is `RouteTableBuilder.warnOnWeakeningOverride` (line ~578) — `git grep` at `5490144f`
- OBSERVED: `new AuthConfig(` appears 104 times in 8 files under `api-sheriff/src/test` — `git grep -c` at `5490144f`
- HYPOTHESIS: at least one block's resolution diverges from the documented gateway → anchor → endpoint → route rule (for example `securityFilter` skips the endpoint scope) — confirm/refute by deliverable 1 (verify-at-outline)

## Expected Surface

- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/config/RouteTableBuilder.java`
- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/config/model/` — the scope and policy-block records, `ResolvedRoute`
- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/quarkus/ConfigModelReflection.java`
- OBSERVED: `api-sheriff/src/main/resources/schema/gateway.schema.json`, `api-sheriff/src/main/resources/schema/endpoint.schema.json`
- OBSERVED: `api-sheriff/src/test/java/de/cuioss/sheriff/gateway/` — every test constructing the records positionally, `DocumentedSetsContractTest`, `ConfigModelContractTest`
- OBSERVED: `doc/user/anchors.adoc`, `doc/configuration.adoc`, `doc/adr/`, `doc/development/`

## Dependencies and Sequencing

- Depends on: `PLAN-V02-12`'s second PR (the `oidc` back-channel-origin key; it adds to `OidcConfig`,
  `gateway.schema.json` and `ConfigValidator`) having landed — do not rebase a refactor of the config
  model across a feature that is adding to it.
- Before: `PLAN-V02-25` and `PLAN-V02-26` (both build on this mechanism), then the re-grounded
  `PLAN-V02-23`.
- Holds every plan that adds or changes a policy block until it lands: `PLAN-V02-05`, `-06`, `-07`,
  `-20`, `-23`; kidicap `PLAN-20`, `-21`, `-26`, `-29`.

## Hand-Off Command

```text
/plan-marshall task="implement .plan/orchestrator/api-sheriff-0-2-0/plans/PLAN-V02-24-policy-block-resolution.md" plan_id=plan-v02-24-policy-block-resolution
```

## Write-Boundary

The plan implementing this spec touches only its own repository source and tests. It creates and
edits NO file under `.plan/orchestrator/` other than its own `inbox/{sender}-{seq}` message, and
reports its outcome through its PR and that message.
