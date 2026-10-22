envelope_version=1
sender_type=plan
sender_id=plan-v02-43-validator-reserved-header-topology-rules
epic=api-sheriff-0-2-0
kind=landing
created=2026-10-22T08:56:30Z

## What landed

plan-v02-43-validator-reserved-header-topology-rules shipped as #505 (merged, queue-merged as 1e53ab4f).

```landing-facts
schema=landing-facts/1
plan_id=plan-v02-43-validator-reserved-header-topology-rules
epic=api-sheriff-0-2-0
pr=#505
merge_state=merged
cleanup_owed=true
deliverables_total=7
deliverables_done=7
total_tokens=8126746
total_wall_seconds=69791.0
harness=claude
model=claude-sonnet-5-5
effort=unknown
plugin_version=0.1.1977
executor_version=0.1.1977
steps=finalize-step-sync-baseline:done,finalize-step-simplify:done,finalize-step-security-audit:done,functional-review:done,code-review:done,architecture-refresh:done,pre-push-quality-gate:done,push:done,create-pr:done,ci-verify:done,automatic-review:done,sonar-roundtrip:done,adr-propose:done,branch-cleanup:done,lessons-capture:done,finalize-step-preference-emitter:done,record-metrics:done,finalize-step-print-phase-breakdown:done,emit-landing:done
```

## Residue

- `cleanup_owed=true`: the local `switch-and-pull` onto `main` was not performed. The main checkout was on another branch (`fix/release-promote-tags-after-verify`) with uncommitted edits to `.github/workflows/release.yml` and `doc/development/release-process.adoc`; those were left untouched. The worktree and the local feature branch are removed and the PR is merged, so only the local `main` pull is owed to whoever owns that checkout.
- ADR ordinal: the series-closing ADR was first numbered 0070, collided with the gateway-image ADR that landed on `main` as #504 mid-run, and was renamed ADR-0071 (the orchestrator's finding `orchestrator-api-sheriff-0-2-0-001` asked for exactly this).
- Review closes: `functional-review` and `code-review` were closed by the operator at three heads (841609fc, ee067536, 1094af28). Each close rests on the same tooling false positive: the `weakened_test` check counts test cases per file, so cases moved from `ConfigValidatorTest` into the three per-class tests registered as losses although each moved case runs in its new class with its original message assertion.
- Post-merge: the PR-attached Performance Benchmark run was still in progress when branch-cleanup stopped waiting; the orchestrator verifies the post-merge runs.
- Review bots: Sourcery (optional) refused the PR as over its size ceiling; CodeRabbit (required) participated. All four CodeRabbit findings were fixed or answered and their threads resolved.
- Three lessons about plan-marshall tool behaviour were recorded in the local lesson store and still await routing to the plan-marshall lessons-routing inbox.
