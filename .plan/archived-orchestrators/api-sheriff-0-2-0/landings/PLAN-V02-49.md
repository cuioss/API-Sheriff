# Landing Analysis: PLAN-V02-49 — Simplify the Tests, Part 2 (shipped as four serial PRs)

epic: api-sheriff-0-2-0
workstream: WS-01
pr: #550 (https://github.com/cuioss/API-Sheriff/pull/550), the last of four: #547, #548, #549, #550

> Landing record for one shipped plan. Written by the `analyze` verb on 2026-10-31 from the inbox message
> `plan-v02-49-test-simplification-part-2-001` (the landing). Checked against `origin/main`, the four merge
> commits, the merged diff `6f06e7b2..5589ff33`, the four PR bodies, the CI runs per merge commit and the PR review state.
> `landing-check` reported `complete: true`, `effort=low`, `model=claude-opus-5-5`.

## Deliverable Fidelity vs Spec

**The `pr` fact in the landing says `#545`. That is stale.** `create-pr` recorded #545, which was closed unmerged, and so was #546
(the single-PR attempt). Both were refused by the review bots at their full size (about 496,000 diff characters), so the plan
cut the work by area into four serial PRs, as the spec's split guard says. The queue row carries `550`; this record names all four.
I read each PR's state through the CI abstraction: #545 and #546 `closed`, #547 `19ad7a8f`, #548 `2ceb1384`, #549 `9e95440a`,
#550 `5589ff33`, all `merged`. The merged range changes 52 files (+1,910 / −3,325), of which one is production code.

| Deliverable (spec) | Verdict | Evidence |
|--------------------|---------|----------|
| D1 — `config/validation/` duplicates, shared fixtures, `LimitRulesTest` Javadoc | shipped | #547 adds `ValidationAsserts`, `RepositoryRoot` and extends `ConfigFixtures` (all three on `main`); the GET-body opt-in cases moved from `LimitRulesTest` to `ConfigModelContractTest` (the spec's "move or correct, say which": moved). #548 moves the other 17 rule classes and declares four removed/survivor pairs per PR body. |
| D2 — `config/` and `routing/` | shipped | #549 (config loading) and #550 (`RouteTable.lookup` cases move to the new `routing/RouteTableTest`, `RouteTableBuilderTest` duplicates removed against `PolicyBlockTest`). Each removal is declared beside its survivor in the PR body, as the spec's standing constraint requires. |
| D3 — edge residue | shipped | `GrpcStreamIdleBound#timeoutMillis()` is package-private on `main` (the merged production diff, verified), read through the test-only `edge/relay/GrpcStreamIdleBounds`. The HTTP/2 late-body-timeout cases run through `retireAfterLateBodyTimeout` and `failRelay`; `otherFailureRetiresOnlyAMarkedRequest` stays on fakes with the reason stated (callers of `markFramingRejection` rethrow). `WebSocketRelayStageTest` gains the relayed upstream-refusal case; `BffRuntimeProducerTest.theViewIsTheOneTheRuntimeRecordsInto` now fails when the recording link is cut. Test homes stated for `EgressClients`, `ReservedPathHandler` and `EdgeRequests`; `TerminalDispatch` keeps its two existing classes; no class added that restates another. GW-07 request body untouched, as ruled. |
| D4 — prove nothing was lost | shipped with a gap | Totals from the PR body: 7,153 → 7,102 (net drop from this work 57, plus six tests `main` gained from #544). JaCoCo per class, merge base against the complete change: 529 classes, 0 lower, 2 higher. **That comparison was measured before four tests were restored and not repeated** — see the Watch. Run time: no visible change (416 s of test time on both sides). |

**Declared surface vs realised.** `landing-check --declared-paths/--realized-paths` reports `expansion_detected`: two realised
files were never declared, `api-sheriff/src/test/java/.../quarkus/BffRuntimeProducerTest.java` (named by spec D3 but absent from
`## Expected Surface`) and `doc/development/boot-validation-rules.adoc`. Nothing declared was left untouched (`missing_count: 0`). No
other plan collided on either file, so no pairing was affected. The spec is shipped, so its declaration is not corrected.

## Metrics and Anomalies

- Tokens: 72,786,787. Wall time: 129,457 s (about 36 h across a day and a half, including the closed single-PR attempts). Model
  claude-opus-5-5, effort `low`, plugin and executor 0.1.2032.
- Step list parsed with a last-colon split: 15 finalize steps `done`, `cleanup_owed=false`. `emit-landing` and `archive-plan` follow the message
  by construction and are not claimed.
- **Reviews closed by operator override at head `9f1817b3`:** `functional-review`, `code-review` and `finalize-step-simplify`
  (`operator close: 0 finding(s) accepted`). That is the fourth recent plan to close a review by override (V02-20, V02-27, V02-48, now V02-49). An
  independent read of the removed assertions that the plan ran found one real loss and three weakened-by-split cases; all are in the PR #550 body, and the lost
  assertion was restored.
- Anomaly: part C's first unit run failed `GatewayEdgeEarlyResponseResetTest.uploadsStillSendingWhenAnsweredStayOnOneConnectionUnderTheEdgesOptions` under host
  load around 30, passed alone and in the full rerun, file untouched by the PR. The plan states the cause is not established.
- Anomaly: a formatter blank line reached a production file (see Routing).

## Routing and Merge Behavior

- Review: CodeRabbit posted a review body on #547, #548 and #550 and only a summary comment on #549; Sourcery posted on all four. I read the unresolved set on each PR
  through the CI abstraction: every row is a `review_body` or `issue_comment` with an empty `thread_id`, **zero unresolved resolvable threads on all four**.
- CI/merge: each PR merged through the merge queue. Per merge commit, Maven Build and Integration Tests are `success` on both the `merge_group` and
  the `push` runs (`19ad7a8f`, `2ceb1384`, `9e95440a`, `5589ff33`), as are Demo Client E2E, Config Validation and Scorecard on the push runs. The PR-attached
  Performance Benchmark ran `success` for the heads of all four (`0c25239a`, `fc85323e`, `0aca31d0`, `9f1817b3`), and the run for #544's head
  (`41091771555`) that the previous analysis left unobserved concluded `success`.
- Sonar: the landing carries no `sonar-roundtrip` count (`done` only); I did not read the SonarCloud measure for the merge head, so the figure is unmeasured
  here, not `0`.
- **The formatter's blank line is on `main` in a production file.** `ResponseStage.java` gained an extra blank line in its import block (`+` at line 25, verified in the merged
  diff `6f06e7b2..5589ff33`); #549 states it came from the pre-commit formatter. The plan's finding stands: the formatter rewrites import spacing in about 380 untouched
  files on every run and each part reverted it. Four plans now report it. It is behaviour-neutral, but it is the first rewrite that reached a committed file.
- Branch cleanup: `branch-cleanup` found #550 already merged, merged nothing itself and did local cleanup only; `switch-and-pull` first failed on a dirty main checkout.
  The `plan-v02-49-part-c` worktree is still listed (`git worktree list`), so cleanup is not complete on disk: `cleanup_owed=false` is the plan's claim, the worktree is
  the observation (Watch).

## Reconciliation Actions

- [x] row `status` → `shipped` — `orchestrator queue --transition PLAN-V02-49 --status shipped`
- [x] row `pr` stamped `550` — `orchestrator queue --set-row PLAN-V02-49 --field pr --value 550`
- [x] row `landing` stamped — `orchestrator queue --set-row PLAN-V02-49 --field landing --value landings/PLAN-V02-49.md`
- [x] row `plan_marshall_plan_id` stamped — `plan-v02-49-test-simplification-part-2`
- [x] epic.md narrative reconciled (queue annotation, Decisions, Watches)
- [x] `PLAN-V02-52` spec corrected in place (see Follow-Ups)
- [x] resume anchor updated; `queue-view.md` regenerated

## Follow-Ups

1. **`PLAN-V02-52` is now emittable.** Its hold ("until #546 lands") is met in substance: #546 closed unmerged and the work is on `main` as #547 to #550. I corrected its
   dependency text in place, and the citation footprint it must re-measure has moved: #552 (`docs(architecture)`, merged 2026-10-31) rewrote seven `doc/architecture/*.adoc` files and
   touched `EdgeSubPackageArchTest`; five of those eight files cite an amendment label or one of the two records `V02-52` relocates (counted by grep, not by the parser). That is inside its declared surface, so no entry was added.
2. **Watches carried or changed** (all in `epic.md`): the pre-commit formatter churn, now four plans and one leaked blank line; the per-class JaCoCo comparison measured before restoration; the part-C unit test
   failure under load; the unremoved `plan-v02-49-part-c` worktree; the advisories the plan left open (`ConfigFixtures` Javadoc anchor/endpoint wording, the `TopologyResolverTest` name,
   `PortalConfig.DEFAULT_CACHE_SECONDS` without a reader, a possibly unreachable marked outcome in `retireFailedRelayConnection`, `test-quality.adoc` format-validator warnings).
3. **Not staged:** no fix plan for the formatter churn. Whether to stage one is the operator's call; the entry in Watches states what a plan would need.
