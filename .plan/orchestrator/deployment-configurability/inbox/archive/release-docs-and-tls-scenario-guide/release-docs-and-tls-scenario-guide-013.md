envelope_version=1
sender_type=plan
sender_id=release-docs-and-tls-scenario-guide
epic=deployment-configurability
kind=landing
created=2026-09-15T18:26:13Z

## What landed

release-docs-and-tls-scenario-guide (PLAN-24) shipped as #305 (merged via merge queue, squash fb652223191b8f876dee98283ebf1d568d72f391).

```landing-facts
schema=landing-facts/1
plan_id=release-docs-and-tls-scenario-guide
epic=deployment-configurability
pr=#305
merge_state=merged
cleanup_owed=false
deliverables_total=10
deliverables_done=10
total_tokens=5805844
total_wall_seconds=30872.0
steps=finalize-step-sync-baseline:done,pre-push-quality-gate:done,pre-submission-self-review:done,finalize-step-simplify:done,architecture-refresh:done,push:done,create-pr:done,ci-verify:done,automatic-review:done,sonar-roundtrip:done,branch-cleanup:done,lessons-capture:done,finalize-step-preference-emitter:done,record-metrics:done,finalize-step-print-phase-breakdown:done,emit-landing:pending,archive-plan:pending
step.branch-cleanup.merge_mechanism=merge_queue
step.sonar-roundtrip.new_code_issue_count=0
step.record-metrics.any_phase_missing_end_time=false
```

## Residue

- Review round 1 dispositions changed spec wording: CodeRabbit d44822 led the operator to drop `-Dsurefire.failIfNoSpecifiedTests=false` from the api-sheriff targeted-test examples in AGENTS.md/CLAUDE.md (kept `-am`, added a when-needed note) — this deliberately departs from the literal PLAN-24 deliverable-10 / lessons-intake C11 wording; the flag is still documented as needed when the `-pl` target depends on tested modules (e.g. integration-tests).
- Deferred follow-up (operator Hold on CodeRabbit 6da2fb): a contract test tying the AGENTS.md and CLAUDE.md module lists to the root `pom.xml` `<modules>`; recorded as lesson 2026-09-15-17-001.
- Reported, not fixed (other plans' or out-of-scope files, listed in the PR body): `doc/user/bff-cookie.adoc` (PLAN-25) still lists logout as a back-channel trust leg and carries a transitional note; `deployment/compose-sample/docker/sheriff-config/gateway.yaml` trusted-proxy comment is wrong (`ForwardPolicyStage.applyRegeneratedForwarding`); `doc/technical_aspects.adoc` token-sheriff version below 0.9.5; `release.yml` "Both signed digests" comment over-broad; `BuildGateCoverageContractTest` failure message cites CLAUDE.md lines now 3 off; legacy dotted package keys and a stale `ConfigLoader.coerce()` best-practice note in architecture metadata that `architecture enrich` cannot remove; `configuration.adoc`, `LogMessages.adoc` and `security-threat-model.adoc` were surveyed by targeted search only.
- Scope widened in-run per refine decision: six read-declared docs were corrected in place (architecture.adoc, fapi_next_steps.adoc, bff-session.adoc, context-path.adoc, downstream-parent.adoc, endpoint-routes.adoc).
- Post-merge verification not yet observed by the plan: the PR-attached benchmark run (`Run Integration Benchmarks`) was still in progress, and the main-branch Maven Build run for fb65222 was not checked.
- Module-tests arm of the pre-push gate is DEGRADED in this project (no whole-tree module-tests canonical); full verify, coverage, integration-tests and jfr lanes were run green during execute instead.
