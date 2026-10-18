# PLAN-V02-43: ConfigValidator Split — ReservedPathRules, HeaderRules, TopologyRules and the end of the split

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
   - `ReservedPathRules`: `validateClientJwksPath`, `validateUserInfo`, `validateLoginPath`, `validateStepUpPath`
   - `HeaderRules`: `validateCors`, `validateContentSecurityPolicy`, `validateHeaderModes`
   - `TopologyRules`: `validateVersion`, `validateEndpointIdUniqueness`, `validateRouteIdUniqueness`, `validateBaseUrlResolvable`
2. **Move the tests** to each rule class's test class (`git mv` where a whole class moves):
   - for `ReservedPathRules`: `ConfigValidatorTest`: BffOidcFoldRules user_info/login part, ClientJwksPathCollision, ClientJwksPathCanonicalForm, StepUpPath, ReservedPathCanonicalForm
   - for `HeaderRules`: `ConfigValidatorTest`: ContentSecurityPolicyInjection, HeaderModesOrphanRefusal, RuleViolations CORS
   - for `TopologyRules`: `ConfigValidatorTest`: ConditionalBaseUrl, RuleViolations version/ids/alias, ValidConfiguration #1
3. **Update references and docs:** the concern map's state column, and every member-level reference
   listed below.

Slice-specific notes:
- **This slice closes the series.** No private rule may remain in `ConfigValidator`: extract any rule a slice missed or another plan added meanwhile. Drop the then-unused `LOGGER`. `ConfigValidatorTest` keeps only the orchestration cases (ValidConfiguration, StructuralVerbRejection, Aggregation).
- **Write the ADR** that `PLAN-V02-26` proposed and did not create: boot validation rules live in concern classes on the `ValidationRule` seam, and `ConfigValidator` runs one ordered list. Derive the ordinal from `doc/adr/` at write time.
- `doc/security-threat-model.adoc`: make every moved test-class name current.
- Add the missing `@param` tags to the seven public rule methods that #485 extracted into `TerminalActionRules`,
  `EdgeRules` and `ProtocolRules` (a non-blocking advisory from #485's code review).

Estimated diff: ~275 KB (move-only; see the size rule).

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
- **The outline's consumer sweep covers prose as well as code** (#485 needed an extra outline round for
  this). For every moved test class, nested class or member, search `doc/**/*.adoc` (above all
  `doc/security-threat-model.adoc`, which cites tests as control evidence), IT Javadoc in
  `integration-tests/`, and `package-info.java`. When the slice extends one list of `RuleSupport` helpers
  or rules, update every other list of the same set: the `rule/package-info.java` list and the
  `ConfigValidator` class Javadoc list.
- A method made public by the move carries complete Javadoc, including `@param` and `@return`.
- **A moved method counts as new code to SonarCloud**, because it sits in a new file (#489 had to loop back for
  S3776/S135 on a verbatim-moved method; lesson `2026-10-18-13-001`). Before the PR, check every moved method against
  the new-code rules (cognitive complexity, loop `continue`/`break` counts) and fix them in the extracting
  deliverable. Batch any late Sonar or review fixes into one commit, so the loop-back re-runs the chain once.
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

- Last. Runs after `PLAN-V02-42`.
- Before `PLAN-V02-23` (re-grounded) and `PLAN-V02-27`, which both wait for the whole series.

## Hand-Off Command

```text
/plan-marshall task="implement .plan/orchestrator/api-sheriff-0-2-0/plans/PLAN-V02-43-validator-reserved-header-topology-rules.md" plan_id=plan-v02-43-validator-reserved-header-topology-rules
```

## Write-Boundary

The plan implementing this spec touches only its own repository source and tests. It creates and
edits NO file under `.plan/orchestrator/` other than its own `inbox/{sender}-{seq}` message, and
reports its outcome through its PR and that message.
