envelope_version=1
sender_type=plan
sender_id=plan-v02-14-offline-config-validation
epic=api-sheriff-0-2-0
kind=landing
created=2026-10-06T07:38:00Z

## What landed

plan-v02-14-offline-config-validation shipped as #387 (merged via the merge queue, merge commit 1a20edade64aee1cb92fbddec7352a920fb5b46d); issue #175 closed with a shipping comment.

```landing-facts
schema=landing-facts/1
plan_id=plan-v02-14-offline-config-validation
epic=api-sheriff-0-2-0
pr=#387
merge_state=merged
cleanup_owed=false
deliverables_total=7
deliverables_done=7
total_tokens=5913514
total_wall_seconds=59615
steps=finalize-step-sync-baseline:done,finalize-step-simplify:done,finalize-step-security-audit:done,pre-submission-self-review:done,architecture-refresh:done,pre-push-quality-gate:done,push:done,create-pr:done,ci-verify:done,automatic-review:done,sonar-roundtrip:done,adr-propose:done,branch-cleanup:done,lessons-capture:done,finalize-step-preference-emitter:done,record-metrics:done,finalize-step-print-phase-breakdown:done,emit-landing:done,archive-plan:pending
step.branch-cleanup.merge_mechanism=merge_queue
step.sonar-roundtrip.new_code_issue_count=0
step.create-pr.pr_number=387
```

## Residue

- Packaging verdict: the flag on the gateway binary shipped (ADR-0061). PLAN-V02-01 (ADR-0005 reversal) had not landed, so its Quarkus command-mode verdict could not be read; ADR-0061 records that. If V02-01 later adopts command mode, revisit whether `--validate-config` should move onto it.
- The pre-existing public overloads `ConfigLoader.load()`, the 3-argument `TopologyResolver.resolve(...)` and `EnvSecretResolver.resolve(String)` now have no production caller (tests only). Kept in #387; a pre-1.0 cleanup candidate.
- CodeRabbit comment declined on #387: deriving the NOT CHECKED catalogue from shared refusal descriptors instead of a hand-kept list (ADR-0061 Risks). Would touch TLS/JWKS/BFF/portal refusal paths; belongs in its own plan if wanted.
- Operator decisions folded in during finalize: report records are one line each (line feed rendered as `\n`); Unicode format / line-separator characters are replaced in report output.
- Finalize ran 2 loop-back rounds (self-review ordinal fix; Sonar + review-comment fix round of 5 tasks). Sourcery cannot review PRs of this size (size refusal, optional bot).
