envelope_version=1
sender_type=plan
sender_id=jwks-exact-publish-rule
epic=kidicap-gateway-requirements
kind=landing
created=2026-11-15T12:16:41Z

## What landed

jwks-exact-publish-rule shipped as #605 (merged, through the merge queue as 5aa17338).

```landing-facts
schema=landing-facts/1
plan_id=jwks-exact-publish-rule
epic=kidicap-gateway-requirements
pr=#605
merge_state=merged
cleanup_owed=false
deliverables_total=3
deliverables_done=3
total_tokens=46652073
total_wall_seconds=385296.0
harness=claude
model=claude-sonnet-5-5
effort=medium
plugin_version=0.1.2106
executor_version=0.1.2106
steps=finalize-step-sync-baseline:done,finalize-step-simplify:done,functional-review:done,code-review:done,architecture-refresh:done,pre-push-quality-gate:done,push:done,create-pr:done,ci-verify:done,automatic-review:done,sonar-roundtrip:done,branch-cleanup:done,finalize-step-preference-emitter:done,record-metrics:done,finalize-step-print-phase-breakdown:done
unresolved_review_threads=0
review_body_findings_total=5
review_body_findings_handled=5
sonar_open_new_issues=0
step.functional-review.deliverables_met=1
step.functional-review.deliverables_met_with_residuals=2
step.functional-review.deliverables_not_met=0
step.functional-review.criteria_unevidenced=0
step.code-review.blocking_count=0
step.code-review.advisory_count=2
step.code-review.rules_cited=2
```

## Residue

- **Overlap with PR #607.** While this plan was in finalize, main gained #607 ("tier-2 quick wins"), which changed the same JWKS publish decision with a different rule. The owner decided to keep this plan's claim-time comparison basis on top of #607 and to carry over #607's one extra (key ids of a retired delegate still inside its grace period count as already held). The reconciliation is merge commit 7c0ee841 on the branch; the ADR-0063 paragraph "Why a replaced loader's key ids count as held" states the cost (a new key first seen by the replaced loader's own background refresh, or a dropped key the identity provider re-adds, can be rejected after the grace period until a later claimed window publishes it). The decision log entry "Operator-approved reconciliation with PR 607" records it.
- **Frozen criterion D1.C1 is narrower than the code.** The code discards a refresh whose basis moved during the fetch (operator decision on review finding 2dc000) and counts retired key ids as held (the decision above); no `criteria-authorize` record covers either. The functional review carried this as the non-blocking residual D1.R1.
- **Redundancy kept from the merge.** The merge left two near-duplicate unit tests (`inventedKeyIdPublishesARotatedKeySet` and main's `inventedKeyIdRefreshPublishesANewlyRotatedKey`) and an integration leg (leg 5, from main) that repeats this plan's leg 3 inside one test method. Both reviews recorded them as advisories; deleting tests would have been a weakened-test finding. A follow-up can fold them.
- **Main is not stable under its own quality gate.** After the merge of #618 (import-order rewrite), `verify -Ppre-commit` removes one blank line from 457 existing files on every run, and earlier one blank line from `MainListenerConnectionCapTest.java` (from #607). The diff was cleared after each run and is saved in the session scratchpad (`gate-vs-618-churn.patch`); a follow-up formatter PR with the `skip-bot-review` label, like #608, would fix it.
- **Post-merge CI was still running.** At merge time the Maven Build and Integration Tests runs on merge commit 5aa17338 were in progress; the post-merge wait ran out of budget without a verdict. Verifying them is the orchestrator's job.
- **Commit messages on the branch.** The messages of the two earliest plan commits still describe the comparison as made at settle time; the squash merge does not carry them to main and the PR description disclosed it.
- **Process notes.** The plan went through three merges/rebases of main and several re-fires of both finalize reviews; the pre-push gate was recorded failed once earlier (an unrelated flaky `SniFrontListenerTest`, fixed on main by #609).
