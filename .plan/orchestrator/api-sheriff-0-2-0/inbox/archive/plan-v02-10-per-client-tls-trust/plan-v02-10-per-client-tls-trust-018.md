envelope_version=1
sender_type=plan
sender_id=plan-v02-10-per-client-tls-trust
epic=api-sheriff-0-2-0
kind=landing
created=2026-10-04T08:47:26Z

## What landed

plan-v02-10-per-client-tls-trust shipped as #382 (merged by the merge queue as 35f2bb37).

```landing-facts
schema=landing-facts/1
plan_id=plan-v02-10-per-client-tls-trust
epic=api-sheriff-0-2-0
pr=#382
merge_state=merged
cleanup_owed=false
deliverables_total=4
deliverables_done=4
total_tokens=8927445
total_wall_seconds=92468.0
steps=finalize-step-sync-baseline:done,finalize-step-simplify:done,finalize-step-security-audit:done,pre-submission-self-review:done,architecture-refresh:done,pre-push-quality-gate:done,push:done,create-pr:done,ci-verify:done,automatic-review:done,sonar-roundtrip:done,adr-propose:done,branch-cleanup:done,lessons-capture:done,finalize-step-preference-emitter:done,record-metrics:done,finalize-step-print-phase-breakdown:done,emit-landing:done,archive-plan:n/a
step.branch-cleanup.merge_mechanism=merge_queue
step.sonar-roundtrip.new_code_issue_count=0
```

## Trust resolution

After this change every gateway the integration stack starts resolves trust the way a deployed gateway does: the JWKS fetch through `jwks.tls_profile`, the BFF OIDC back-channel through `egress_tls.oidc_tls_profile`, and nothing through the JVM default trust store override.

Every integration gateway now logs WARN `ApiSheriff-126` at boot and no longer logs WARN `ApiSheriff-122`.

Source note: the staged statement file `.plan/temp/pr-trust-statement.md` lived in the plan worktree and was removed with it at cleanup. The two paragraphs above are copied verbatim from the "Trust resolution" section of the merged PR #382 body, which the create-pr obligation had placed there from part 1 of that file.

## Residue

- Scope beyond the spec: the merge queue's re-test exposed a pre-existing race in the gateway response relay (`DispatchStage` attached the upstream pause too late; a relay-start failure left the client unanswered). It was fixed in this PR with deterministic regression tests; the new race test reproduces the failure on unmodified `main` at 5ddf8081. This is the only change under `api-sheriff/src/main/`.
- Benchmark comparison (base 3a1182e5 vs branch a87ca49d, same machine) is in the PR body. It is marked noisy: the two runs ran under very different machine load and the base run failed its own noise-band check, so no difference is attributed to the change.
- Post-merge verification is left to the orchestrator: the PR-attached `Run Integration Benchmarks` run (benchmark.yml, merged PR) was still pending at cleanup, and the main-branch Maven Build for 35f2bb37 was not checked by this plan.
- Review residue: CodeRabbit's two late findings on the JSSE-argument guard (compose `entrypoint`, `OneOffGatewayContainers.IMAGE` references) were fixed in e5a4748f/74c2b275; Sourcery was quota-refused throughout (optional bot).
