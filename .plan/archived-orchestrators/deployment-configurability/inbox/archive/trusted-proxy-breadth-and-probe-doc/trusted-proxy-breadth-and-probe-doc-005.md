envelope_version=1
sender_type=plan
sender_id=trusted-proxy-breadth-and-probe-doc
epic=deployment-configurability
kind=landing
created=2026-09-05T00:14:10Z

## What landed

trusted-proxy-breadth-and-probe-doc shipped as #267 (merged).

```landing-facts
schema=landing-facts/1
plan_id=trusted-proxy-breadth-and-probe-doc
epic=deployment-configurability
pr=#267
merge_state=merged
deliverables_total=3
deliverables_done=3
total_tokens=4157420
total_wall_seconds=27399
steps=finalize-step-sync-baseline:done,pre-push-quality-gate:done,pre-submission-self-review:done,finalize-step-simplify:done,finalize-step-security-audit:done,architecture-refresh:done,push:done,create-pr:done,ci-verify:done,automatic-review:done,sonar-roundtrip:done,adr-propose:done,branch-cleanup:done,lessons-capture:done,finalize-step-preference-emitter:done,record-metrics:done,finalize-step-print-phase-breakdown:done,emit-landing:done,archive-plan:done
step.branch-cleanup.merge_mechanism=merge_queue
step.branch-cleanup.merge_commit_sha=558a38b6034cfc695bb9b88764bebd85693f1ed1
step.finalize-step-sync-baseline.action=rebased
step.finalize-step-sync-baseline.upstream_commit_count=1
step.finalize-step-security-audit.findings_count=0
step.sonar-roundtrip.new_code_issue_count=0
step.sonar-roundtrip.count_status=confirmed
```

## Residue

- **The spec's declared footprint understated the change.** PLAN-15 declared the `HEALTHCHECK`-override
  claim at two sites with a HYPOTHESIS that a third might exist. Outline verification found it at SIX
  (`HealthProbe.java` plus five documentation files) and raised `scope_estimate` from `single_module`
  to `multi_module`. The spec's own instruction — that further sites join deliverable 3 rather than
  become a new plan — was followed, and all six were corrected. WS-01 now has no live ADR/source
  contradiction on this claim.

- **An ADR candidate was identified and deliberately declined for this PR.** `adr-propose` scanned all
  39 corpus ADRs and found no coverage of `trusted_proxies` breadth or the warn-vs-reject threshold.
  Draft title: "The trusted_proxies breadth warning is thresholded at one operator-provisioned network,
  and its un-warned residual is declared". Declined because the plan's declared scope (record the
  reasoning) was already met by the `validateForwardedTrust` Javadoc plus `doc/configuration.adoc`, and
  because adding a file post-review would have re-staled both required review bots and forced another
  trigger/review/triage cycle. The full draft is preserved in this plan's decision log. **The epic should
  decide whether to schedule it as follow-up work.**

- **Two now-redundant pre-existing tests were left in place.** `shouldAcceptTightlyScopedCidrs` and
  `shouldWarnBroadButNotTotalCidr` in `ConfigValidatorTest` are subsumed by the new parameterized
  `broadPrefixThresholdControls`, but removing them would have reached outside this changeset's
  line-level scope. Recorded by the simplify pass as findings.

- **Sourcery never reviewed this PR.** It is in `optional_bots` and refused on a hard quota with a
  ~22-hour ETA, so it did not gate the merge. The coverage gap is real and was not waited out.

- **Neither required review bot re-reviewed on its own after the fix push**, for two different reasons:
  CodeRabbit needed an explicit trigger because `re_review_on_loopback` is `false`, and PR-Agent needed
  a separate `/review` because its workflow does not trigger on push. Both were handled, but the
  finalize run spent two loop-back iterations on it.
