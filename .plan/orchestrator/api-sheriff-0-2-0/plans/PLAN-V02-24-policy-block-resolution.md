# PLAN-V02-24: One Resolution Mechanism for Every Policy Block — and One Rule for All of Them

epic: api-sheriff-0-2-0
workstream: WS-01

> Staged plan spec — one shippable unit of work, ready for `/plan-marshall` hand-off.
> The orchestrator EMITS the command below; it never launches the plan inline.
> Staged 2026-10-14 from inbox `plan-v02-23-cookie-forwarding-policy-001` (operator decision: PLAN-V02-23
> is paused until the edge pipeline and policy-block resolution are reworked; "GatewayEdgeRoute is a
> nightmare. We need a major rework here."). Design settled with the operator the same day (decisions
> recorded in `epic.md` § Decisions, 2026-10-14). First of three refactor plans: this one, then
> `PLAN-V02-25` (edge) in parallel with `PLAN-V02-26` (validator), then the re-grounded V02-23.

## Objective

`doc/user/anchors.adoc` states one rule — a policy block resolves gateway → anchor → endpoint → route,
and every step replaces the whole block — but the code has no such rule. `config/RouteTableBuilder.java`
resolves each block with its own hand-written chain, its own scope set and its own fallback (at
`5490144f`):

| Block | Chain today | Nothing declared |
|---|---|---|
| `auth` | route → endpoint → anchor (no gateway scope) | boot error (`RouteTableException`) |
| `allowed_methods` | endpoint → anchor → gateway (no route scope) | `STANDARD_ALLOWED_METHODS` |
| `security_filter` | route → anchor | `null` |
| `security_headers` | anchor → gateway | `null` |
| `upstream_defaults` | endpoint → gateway | `UpstreamDefaultsConfig.defaults()` |
| `forward`, `websocket` | route only | empty (`forward`: forward-all) |
| `upstream.retry` / `not_modified` toggles | route field → effective `upstream_defaults` | from the defaults |

The override WARN (`warnOnWeakeningOverride`) covers only `security_filter` (anchor and route both
declare) and `allowed_methods` (anchor and endpoint both declare). The non-weakenable `auth` floor lives
elsewhere, in `ConfigValidator.validateAnchorAuthFloor`.

This plan (a) makes a policy block a declaration resolved by one generic mechanism, and (b) **normalises
the blocks onto one documented rule** (operator decision 2026-10-14). Part (b) is a deliberate behaviour
change; part (a) is not.

## Deliverables

1. **Inventory, then the normalisation proposal — put to the operator at outline.** Re-derive the table
   above (do not trust it). For each block propose its normalised scope set under the one rule
   gateway → anchor → endpoint → route (nearest declared scope wins, whole-block replacement), and for
   every block where that widens or changes the effective policy state the consequence — in particular
   the security-relevant ones (`security_filter` gaining an endpoint scope, `security_headers` gaining
   endpoint and route scopes, `allowed_methods` gaining a route scope, `auth` gaining a gateway scope).
   A block may keep a narrower scope set only with a stated reason (for example `websocket` exists only
   on a WebSocket route); that reason goes into its declaration and the docs. **The operator decides each
   block's scope set at outline; implement nothing of part (b) before that.**
2. **One generic mechanism.** A small descriptor per block declares: its scopes, an "is declared" test
   (an empty list counts as absent), its fallback (constant, `null`, or a boot error), and its override
   semantics. One resolver walks the scopes. Migrate every block onto it and delete the per-block
   `resolve*` / `effective*` methods. Land this first with the CURRENT chains, so the existing suite
   proves part (a) is behaviour-neutral before part (b) changes anything.
3. **Apply the normalisation decided in deliverable 1** as explicit behaviour changes: the declarations
   change, the schemas allow the block at its new scopes, tests pin each new scope, and every change is
   called out as breaking in the PR and the user docs (pre-1.0: no deprecation, no compatibility shim).
4. **The override WARN keeps its meaning: "both scopes declare the block"** (operator decision). It is
   derived from the declaration for every block that opts in, not hand-coded per block; the opt-in set
   is part of deliverable 1's proposal. The `auth` floor stays a floor (an anchor's auth cannot be
   replaced by a weaker one). **Leave `ConfigValidator.validateAnchorAuthFloor` where it is**: `PLAN-V02-26`
   runs in parallel and owns where validator rules live. If the floor can be derived from the declaration,
   say so in the ADR and leave the move to whichever of the two plans merges second.
5. **Config records get Lombok `@Builder`; tests stop constructing them positionally** (operator
   decision). 24 of the 41 classes in `config/model/` carry `@Builder` at `5490144f`; `AuthConfig` is
   constructed positionally 104 times in 8 test files. Add `@Builder` to the scope and policy-block
   records that lack it and migrate the positional test constructions, so a new field touches only the
   tests that are about it.
6. **Declare once, register once.** Measure what adding a block costs (records, both JSON schemas,
   `ConfigModelReflection`, `DocumentedSetsContractTest`, the array-key inventory in
   `doc/configuration.adoc`) and cut every step the mechanism can derive. Whatever cannot be derived gets
   a contract test that names the missing step — a new block not registered in `ConfigModelReflection`
   must fail the JVM build, not only the native image.
7. **Documentation and record.** `doc/user/anchors.adoc` states the one rule and lists each block's scope
   set; the per-block pages link to it instead of restating it. An ADR records the mechanism and the
   normalisation (derive the ordinal from `doc/adr/` at write time). `doc/development/` says how to add a
   block.

Proof standard (operator decision): the existing test suite, unit and integration, is the evidence —
no additional golden comparison. A test changed to make part (a) pass needs its reason in the PR; part
(b)'s changes are new or changed tests by design. Test simplification is a separate plan after the
refactor has merged (`PLAN-V02-27`).

Split guard: 7 deliverables, within the operator-authorized 12. Keep each PR under 100 files and 150,000
diff characters. Natural split: PR 1 = deliverables 2, 5, 6 (behaviour-neutral); PR 2 = deliverables 3,
4, 7 (the normalisation).

## Claim Labels

- OBSERVED: the per-block chains and fallbacks in the Objective's table — read at `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/config/RouteTableBuilder.java` § `resolveRoute` and the `resolve*` / `effective*` methods, at `5490144f`
- OBSERVED: `warnOnWeakeningOverride` warns for `security_filter` (anchor + route) and `allowed_methods` (anchor + endpoint) only — same file, line ~578
- OBSERVED: the anchor auth floor is enforced in `ConfigValidator.validateAnchorAuthFloor` — `config/validation/ConfigValidator.java` line ~1603
- OBSERVED: 24 of 41 classes under `config/model/` carry `@Builder`; `new AuthConfig(` appears 104 times in 8 test files — `git grep` at `5490144f`

## Expected Surface

- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/config/RouteTableBuilder.java`
- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/config/model/` — the scope and policy-block records, `ResolvedRoute`
- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/quarkus/ConfigModelReflection.java`
- OBSERVED: `api-sheriff/src/main/resources/schema/gateway.schema.json`, `api-sheriff/src/main/resources/schema/endpoint.schema.json`
- OBSERVED: `api-sheriff/src/test/java/de/cuioss/sheriff/gateway/` — the positional constructions, `config/DocumentedSetsContractTest`, `config/model/ConfigModelContractTest`, `RouteTableBuilder` tests
- OBSERVED: `doc/user/anchors.adoc` and the per-block user pages, `doc/configuration.adoc`, `doc/adr/`, `doc/development/`
- HYPOTHESIS: `integration-tests/src/main/docker/sheriff-config*/` — if a normalised scope is exercised end to end (verify-at-outline)

## Dependencies and Sequencing

- **Emitted 2026-10-14 in parallel with `PLAN-V02-26`** (operator: "emit the refactor plans, what can be run
  in parallel sensibly"). The two are disjoint as long as this plan does not touch `ConfigValidator`
  (see deliverable 4).
- Concurrent with `PLAN-V02-12`'s second PR (the `oidc` back-channel-origin key, not yet started at
  emission): it adds a field to `OidcConfig` and `gateway.schema.json` and checks to `ConfigValidator`.
  Whichever merges second rebases (operator rule, 2026-10-14). Re-fetch `origin/main` before outline and
  before push.
- No dependency on kidicap `PLAN-32` (it works in `edge/`, this plan in `config/`).
- Before: `PLAN-V02-25` (builds the merged per-route type on this mechanism), then the re-grounded
  `PLAN-V02-23`, then `PLAN-V02-27`.
- Holds every staged plan that adds or changes a policy block until it lands: `PLAN-V02-05`, `-06`,
  `-07`, `-20`, `-23`.

## Hand-Off Command

```text
/plan-marshall task="implement .plan/orchestrator/api-sheriff-0-2-0/plans/PLAN-V02-24-policy-block-resolution.md" plan_id=plan-v02-24-policy-block-resolution
```

## Write-Boundary

The plan implementing this spec touches only its own repository source and tests. It creates and
edits NO file under `.plan/orchestrator/` other than its own `inbox/{sender}-{seq}` message, and
reports its outcome through its PR and that message.
