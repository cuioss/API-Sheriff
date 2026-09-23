envelope_version=1
sender_type=plan
sender_id=bff-refresh-integration-coverage
epic=deployment-configurability
kind=landing
created=2026-09-09T11:58:17Z

## What landed

bff-refresh-integration-coverage shipped as #282 (merged) — the BFF near-expiry refresh
now fires, and all three `RefreshOutcome.Kind` branches are driven through a live edge.

```landing-facts
schema=landing-facts/1
plan_id=bff-refresh-integration-coverage
epic=deployment-configurability
pr=#282
merge_state=merged
deliverables_total=6
deliverables_done=6
total_tokens=2954996
total_wall_seconds=165300
steps=finalize-step-sync-baseline:done,pre-push-quality-gate:done,pre-submission-self-review:done,finalize-step-simplify:done,architecture-refresh:done,push:done,create-pr:done,ci-verify:done,automatic-review:done,sonar-roundtrip:done,branch-cleanup:done,lessons-capture:done,finalize-step-preference-emitter:done,record-metrics:done,finalize-step-print-phase-breakdown:done,emit-landing:done,archive-plan:pending
step.create-pr.pr_number=282
step.branch-cleanup.merge_state=merged
step.branch-cleanup.merge_mechanism=merge_queue
step.branch-cleanup.merge_commit=b5369cae3957444d9e5c55fb79789b5e6c55fba4
step.record-metrics.total_tokens=2954996
step.record-metrics.total_wall_seconds=165300
```

## Residue

Four things the epic should carry forward. None blocks this plan; each outlived it.

**The charter changed mid-plan, and the plan is named for the old one.** It was chartered
as characterisation only — reproduce the refresh failure, record the verdict. The operator
overrode that and authorised the fix, which is why TASK-8 exists and why the near-expiry
path now works. The plan id still says "integration-coverage"; what landed is a fix plus
its coverage.

**A green integration suite bought nothing from the Sonar gate.** This plan's whole
deliverable was integration coverage, and the PR still failed at `new_coverage` 43.3%
against an 80% threshold. The ITs run in a separate module against a native binary in
Docker, so JaCoCo never instruments them. Both signals were true about different things.
Neither local gate could have caught it: `verify` and `verify -Ppre-commit` do not run
`-Pcoverage`, so the repository's pre-commit process structurally cannot see the metric
that blocks its merges. That is a gap in the process, not in this plan, and it will bite
every plan whose value is a coverage number.

**Fixing one vacuous-green lane exposed another.** The failsafe include
(`**/integration/**/*IT.java`) matched nothing under failsafe 3.6.0, so the
integration-tests lane reported BUILD SUCCESS while running zero ITs — that is why the
required `integration-tests/conclusion` check was vacuously green on `main`. The same fix
applied to the `jfr` profile made it select `MtlsHandshakeIT`, which that profile has no
`test.mtls.*` properties for, so it would fall back to port 10443 and pass green having
proven nothing about mTLS. Caught in review, fixed by exclusion. The class — a test that
silently falls back to a default when its configuration is absent — is not specific to
either lane.

**The JFR lane depends on an image it never builds.** It builds only `api-sheriff:jfr`,
yet starts the full ten-instance compose stack, so its ability to run at all rests on an
`api-sheriff:distroless` some earlier `-Pintegration-tests` run happened to leave behind.
Untouched here deliberately — recording it rather than codifying it with more properties.

Separately: `fix/failsafe-it-include-pattern` was staged as its own PR, but this branch
carried the same fix and it landed with #282. That branch and its worktree at
`.plan/temp/failsafe-fix` are now redundant.
