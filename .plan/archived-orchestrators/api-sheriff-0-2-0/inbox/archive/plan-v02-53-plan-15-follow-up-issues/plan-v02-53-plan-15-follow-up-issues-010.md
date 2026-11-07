envelope_version=1
sender_type=plan
sender_id=plan-v02-53-plan-15-follow-up-issues
epic=api-sheriff-0-2-0
kind=landing
created=2026-11-05T19:11:59Z

## What landed

plan-v02-53-plan-15-follow-up-issues shipped as #565 (merged, squash 7a5d52a via the merge queue).

```landing-facts
schema=landing-facts/1
plan_id=plan-v02-53-plan-15-follow-up-issues
epic=api-sheriff-0-2-0
pr=#565
merge_state=merged
cleanup_owed=false
deliverables_total=8
deliverables_done=6
total_tokens=23926749
total_wall_seconds=35264.0
harness=claude
model=claude-opus-5-5
effort=low
plugin_version=0.1.2036
executor_version=0.1.2036
steps=finalize-step-sync-baseline:done,finalize-step-simplify:done,finalize-step-security-audit:done,functional-review:done,code-review:done,architecture-refresh:done,pre-push-quality-gate:done,push:done,create-pr:done,ci-verify:done,automatic-review:done,sonar-roundtrip:done,adr-propose:skipped,branch-cleanup:done,lessons-capture:done,finalize-step-preference-emitter:done,record-metrics:done,finalize-step-print-phase-breakdown:done,emit-landing:done,archive-plan:pending
step.functional-review.deliverables_met=5
step.functional-review.deliverables_met_with_residuals=1
step.functional-review.deliverables_not_met=0
step.functional-review.criteria_unevidenced=0
step.code-review.blocking_count=0
step.code-review.advisory_count=4
step.code-review.rules_cited=3
```

## Residue

- Issues: #323, #325, #326, #327, #328 commented and closed after merge and green merge-commit CI on main. #324 commented and left OPEN — D2 (key-level declared-limit inventory) was cut under the spec's split guard (~1000+ (file,key) rows over 22 limit-bearing descriptors). #329 was already closed via #561; commented with the docs, the zero-hit YAML sweep and the red re-proof of the merged rule.
- Operator follow-up owed: #325's title still reads "restated in four places"; the operator edits it by hand (title edit is outside the CI abstraction).
- D2 and D8 graded superseded by the functional review (D2 cut; D8 is the post-merge manual closure, done as above).
- Four advisory code-review findings left as follow-ups (no blocking): PathSlashes class Javadoc and http/package-info overstate PathSlashes' reach (UpstreamAssetSource.upstreamUri still trims one slash itself); the LocationPathReview Javadoc says every reader uses pathPortion (LocationRewriter, the absolute branch of sameOrigin and PortalRules do not); ReturnTargetScopes keeps an unreachable second copy of the encoded-separator / dot-segment checks behind sameOrigin.
- One finalize loop-back (5-execute): Sonar java:S1192 in the new PathSlashes, fixed by TASK-15 (dbc9e02f). The operator waived re-running the reviews for that commit, but the merge gate (merge_candidate_not_gated) refused the waiver, so security audit and both reviews were re-run at dbc9e02f.
- adr-propose skipped: the epic reserves ADR authoring for its ADR-corpus plans; the settled decisions are in the decision log and the #565 description.
- The landed squash message is the repo default (includes the CodeRabbit summary) because the merge queue takes no per-entry message; it carries no closing keyword.
