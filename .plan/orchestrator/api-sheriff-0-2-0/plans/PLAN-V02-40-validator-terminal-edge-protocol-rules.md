# PLAN-V02-40: ConfigValidator Split — TerminalActionRules, EdgeRules and ProtocolRules

epic: api-sheriff-0-2-0
workstream: WS-01

> Staged plan spec — one shippable unit of work, ready for `/plan-marshall` hand-off.
> The orchestrator EMITS the command below; it never launches the plan inline.
> Staged 2026-10-15. Continues the split after `PLAN-V02-26` (#463, slice 1) and `PLAN-V02-28` (LimitRules).
> Operator rulings: the whole split finishes **before** `PLAN-V02-23` (2026-10-14). Then, on 2026-10-15,
> the eleven narrow slices (`PLAN-V02-29` … `-39`) were re-packed into four (`PLAN-V02-40` … `-43`), with
> the character cap lifted for move-only work.

## Objective

Move the next concern groups out of `config/validation/ConfigValidator.java` (2,743 lines at `9e05a3d6`)
into rule classes on the `ValidationRule` seam, together with their tests.

## Deliverables

1. **Extract** these groups into `config/validation/rule/`, each in place in `DEFAULT_RULES`:
   - `TerminalActionRules`: `validateTerminalAction`, `validateAssetIndexAndFallback`
   - `EdgeRules`: `validateForwardedTrust`, `validatePassthroughHostCollision`, `validatePassthroughAliasResolvable`
   - `ProtocolRules`: `validateWebSocketConfig`, `validateRewriteLocationProtocol`
2. **Move the tests** to each rule class's test class (`git mv` where a whole class moves):
   - for `TerminalActionRules`: `ConfigValidatorTest`: TerminalActionConsistency, AssetIndexAndFallback; `ConfigValidatorRedirectTest` (`git mv`)
   - for `EdgeRules`: `ConfigValidatorTest`: RuleViolations trusted_proxies and passthrough, ValidConfiguration passthrough
   - for `ProtocolRules`: `ConfigValidatorTest`: WebSocketAllowlist, RewriteLocationProtocol, WebSocketOriginAllowlist
3. **Update references and docs:** the concern map's state column, and every member-level reference
   listed below.

Slice-specific notes:
- `TerminalActionRules` logs WARN `REDIRECT_EXTERNAL_TARGET_ALLOWED`. `ConfigValidatorRedirectTest`'s `assertNoLogMessagePresent(WARN, ConfigValidator.class)` must target the rule class, or it turns vacuous.
- `EdgeRules` logs WARN `BROAD_TRUSTED_PROXY`.
- `effectiveProtocol` (private in `ConfigValidator`, used by Protocol, TerminalAction, Limit and Forwarding rules) moves to `RuleSupport` in this slice.
- Name references to update: `integration-tests/src/main/docker/sheriff-config*/endpoints/assets.yaml` comments (`validateAssetSource`); the `ConfigLogMessages` and `PassthroughHostGuardStage` Javadoc; `doc/development/tls-edge.adoc`, `doc/user/tls-scenarios.adoc`, `TlsEdgeActivationWiringTest` and `integration-tests/src/main/docker/sheriff-config/topology.properties`.

Estimated diff: ~220 KB (move-only; see the size rule).

## Series Rules (identical in `PLAN-V02-40` … `PLAN-V02-43`)

- **Pure refactor.** The same configurations are accepted and refused, with the same messages, in the same
  order. `DEFAULT_RULES` keeps its order: an extracted rule takes the place of the private method it
  replaces. The evidence is the existing tests passing (operator decision 2026-10-14). Tests move with
  their rules and keep their message assertions. Simplifying them is `PLAN-V02-27`'s job.
- **Follow the pattern #463 set.** One concern class per group in `config/validation/rule/`. Shared helpers
  go in `RuleSupport`, moved by the first slice that needs them. Name the test class after the rule class.
  Read `doc/development/boot-validation-rules.adoc` first, and set each group's state there to "extracted".
  Do not restate counts or positions; `DEFAULT_RULES` is the only record of them.
- **State after `PLAN-V02-24` (#465, `9e05a3d6`).** Policy blocks resolve through `config/PolicyBlock`, one
  descriptor per block. `RuleSupport.resolveAnchor` is gone. `allowed_methods` resolution sits in
  `RouteMatchingRules` through `PolicyBlock.ALLOWED_METHODS`. A moved rule that resolves a block calls the
  descriptor and never hand-lists scopes; it walks a scope set with an exhaustive `switch` over the scope
  enum (lesson `2026-10-15-09-001`). Docs link `_policy_block_scopes` in `doc/user/anchors.adoc` and do
  not restate the block inventory.
- A rule that logs owns its `CuiLogger`. `LogRecord` ids and text stay the same; only the logger category
  changes. Any test asserting on the `ConfigValidator` logger is pointed at the rule class.
- Update member-level references to moved methods in Javadoc, docs, schemas and IT config comments. Leave
  the dated reports under `doc/quality-report/` untouched.
- **Size (operator decision 2026-10-15).** For these move-only slices the 150,000-character cap is lifted.
  It is Sourcery's limit, and Sourcery is optional in `.plan/marshal.json`. The **100-file** cap
  (CodeRabbit, required) still holds. Estimates are V02-26's outline figures × 1.39. Measure
  `git diff --find-renames --name-only origin/main...HEAD | wc -l` before the PR. If a slice would pass
  ~350 KB, or CodeRabbit declines to review it, split at a group boundary and say so in the inbox message.
  The relaxation covers moves only: any behaviour change keeps to the normal limits.
- **Concurrency.** Every slice edits `ConfigValidator.java`, `ConfigValidatorTest.java` and the concern map,
  so they run one at a time, or two at once under the rebase rule (whichever merges second rebases). A slice
  runs alongside `PLAN-V02-44` and `PLAN-V02-25`, whose surfaces are disjoint. Re-fetch `origin/main` before
  outline and before push.
- **Before finalize, re-check the outline's success criteria against every sibling plan that landed during
  the run** (`git log` on the shared files). A criterion part that a sibling made moot is graded met-with-residual,
  naming the PR that removed its subject. Never grade it plainly met (lessons from #467).
- No new ADR, except in the last slice (`PLAN-V02-43`). `PLAN-V02-41` amends ADR-0067.

## Claim Labels

- OBSERVED: the groups and their rule names, from `doc/development/boot-validation-rules.adoc` and inbox `plan-v02-26-config-validator-split-001` — `ConfigValidator.java` at `7e5eb579`
- HYPOTHESIS: the test-case locations in deliverable 2 (verify at outline; other plans may have added cases)

## Expected Surface

- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/config/validation/ConfigValidator.java`, `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/config/validation/rule/`
- OBSERVED: `api-sheriff/src/test/java/de/cuioss/sheriff/gateway/config/validation/`
- OBSERVED: `doc/development/boot-validation-rules.adoc`
- HYPOTHESIS: the reference sites named in the slice-specific notes (verify at outline)

## Dependencies and Sequencing

- After `PLAN-V02-28` (LimitRules, #467) has merged. It can run alongside V02-28 under the rebase rule.
- Before `PLAN-V02-23` (re-grounded) and `PLAN-V02-27`, which both wait for the whole series.

## Hand-Off Command

```text
/plan-marshall task="implement .plan/orchestrator/api-sheriff-0-2-0/plans/PLAN-V02-40-validator-terminal-edge-protocol-rules.md" plan_id=plan-v02-40-validator-terminal-edge-protocol-rules
```

## Write-Boundary

The plan implementing this spec touches only its own repository source and tests. It creates and
edits NO file under `.plan/orchestrator/` other than its own `inbox/{sender}-{seq}` message, and
reports its outcome through its PR and that message.
