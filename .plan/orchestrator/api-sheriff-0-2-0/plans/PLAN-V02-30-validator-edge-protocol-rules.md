# PLAN-V02-30: ConfigValidator Split — EdgeRules and ProtocolRules

epic: api-sheriff-0-2-0
workstream: WS-01

> **SUPERSEDED — retired 2026-10-15, not to be run.** The eleven validator slices V02-29..39 were re-packed
> into PLAN-V02-40..43 (#469); all four shipped (#485, #489, #502, #505) and the `ConfigValidator` split is
> complete. Kept as the audit record of the retired slice; the body below is the spec as it stood then.

> Staged plan spec — one shippable unit of work, ready for `/plan-marshall` hand-off.
> The orchestrator EMITS the command below; it never launches the plan inline.
> Staged 2026-10-14 from `PLAN-V02-26`'s landing (#463, slice 1 of the split; follow-up grouping from inbox
> `plan-v02-26-config-validator-split-001`). Operator ruling 2026-10-14: the whole split is finished
> **before** `PLAN-V02-23`. One of the 12 slices `PLAN-V02-28` … `PLAN-V02-39`.

## Objective

Move one more concern group out of `config/validation/ConfigValidator.java` (2,750 lines at `7e5eb579`)
into its rule class on the `ValidationRule` seam, with its tests.

## Deliverables

1. **Extract** `EdgeRules`: `validateForwardedTrust`, `validatePassthroughHostCollision`, `validatePassthroughAliasResolvable`; `ProtocolRules`: `validateWebSocketConfig`, `validateRewriteLocationProtocol` into `config/validation/rule/`, in place in `DEFAULT_RULES`.
2. **Move the tests** — `ConfigValidatorTest`: RuleViolations trusted_proxies and passthrough, ValidConfiguration passthrough, WebSocketAllowlist, RewriteLocationProtocol, WebSocketOriginAllowlist — to the rule class's test class (`git mv` where a whole class moves).
3. **References and docs**: the concern map's state column, and every member-level reference listed below.

Slice-specific notes:
- `EdgeRules` logs WARN `BROAD_TRUSTED_PROXY`: the class owns its `CuiLogger`.
- Name references to update: `ConfigLogMessages` and `PassthroughHostGuardStage` Javadoc, `doc/development/tls-edge.adoc`, `doc/user/tls-scenarios.adoc`, `TlsEdgeActivationWiringTest`, `integration-tests/src/main/docker/sheriff-config/topology.properties`.

Estimated diff: ~128 KB (over the 120 KB target; measure first, ship `ProtocolRules` as its own PR if over ~140 KB).

## Series Rules (identical in every slice `PLAN-V02-28` … `PLAN-V02-39`)

- **Pure refactor.** The same configurations are accepted and refused with the same messages in the same
  order. `DEFAULT_RULES` keeps its order: an extracted rule takes the place of the private method at the
  same position. The existing tests passing is the evidence (operator decision 2026-10-14); tests move
  with their rules and keep their message assertions. Simplifying them is `PLAN-V02-27`'s job.
- **Follow the pattern #463 set**: one concern class per group in `config/validation/rule/`, shared helpers
  in `RuleSupport` (moved by the first slice that needs them), the test class named after the rule class.
  Read `doc/development/boot-validation-rules.adoc` first. In it, set this group's state to "extracted".
  Do not restate counts or positions; `DEFAULT_RULES` is the only record of those.
- A rule that logs owns its `CuiLogger`. `LogRecord` ids and text stay; only the logger category changes.
  Any test asserting on the `ConfigValidator` logger is pointed at the rule class, so it does not turn vacuous.
- Update member-level references to moved methods in Javadoc, docs, schemas and IT config comments. Leave
  the dated reports under `doc/quality-report/` untouched.
- **Size.** Estimates are the V02-26 outline's, times 1.39 (#463 measured 139,406 bytes against ~100 KB).
  Measure `git diff --find-renames origin/main...HEAD | wc -c` before the PR: under 150,000 characters and
  100 files, or split.
- **Concurrency.** Every slice edits `ConfigValidator.java`, `ConfigValidatorTest.java` and the concern
  map, so slices run one at a time by default. Two may run at once under the rebase rule (whichever merges
  second rebases). A slice runs alongside `PLAN-V02-24` and `PLAN-V02-25`, whose surfaces are disjoint.
  Re-fetch `origin/main` before outline and before push.
- **State after `PLAN-V02-24` (#465, `9e05a3d6`).** Policy blocks resolve through `config/PolicyBlock`
  (one descriptor per block). `RuleSupport.resolveAnchor` is gone. `allowed_methods` resolution sits in
  `RouteMatchingRules` through `PolicyBlock.ALLOWED_METHODS`. A moved rule that resolves a block must call
  the descriptor and must not hand-list scopes: walk a scope set with an exhaustive `switch` over the scope
  enum. The docs must not restate the block inventory; they link `_policy_block_scopes` in
  `doc/user/anchors.adoc`.
- No ADR, except in the last slice (`PLAN-V02-39`). Small: no deep-lane ceremony beyond what the lane
  router decides.

## Claim Labels

- OBSERVED: the group and its rule names, from `doc/development/boot-validation-rules.adoc` and inbox `plan-v02-26-config-validator-split-001` — `ConfigValidator.java` at `7e5eb579`
- HYPOTHESIS: the test-case locations listed in deliverable 2 (verify-at-outline; other plans may have added cases)

## Expected Surface

- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/config/validation/ConfigValidator.java`, `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/config/validation/rule/`
- OBSERVED: `api-sheriff/src/test/java/de/cuioss/sheriff/gateway/config/validation/`
- OBSERVED: `doc/development/boot-validation-rules.adoc`
- HYPOTHESIS: the reference sites named in the slice-specific notes (verify-at-outline)

## Dependencies and Sequencing

- After the previous slice in the series order (serial by default; see the series rules).
- Before `PLAN-V02-23` (re-grounded) and `PLAN-V02-27`, which wait for the whole series.

## Hand-Off Command

```text
/plan-marshall task="implement .plan/orchestrator/api-sheriff-0-2-0/plans/PLAN-V02-30-validator-edge-protocol-rules.md" plan_id=plan-v02-30-validator-edge-protocol-rules
```

## Write-Boundary

The plan implementing this spec touches only its own repository source and tests. It creates and
edits NO file under `.plan/orchestrator/` other than its own `inbox/{sender}-{seq}` message, and
reports its outcome through its PR and that message.
