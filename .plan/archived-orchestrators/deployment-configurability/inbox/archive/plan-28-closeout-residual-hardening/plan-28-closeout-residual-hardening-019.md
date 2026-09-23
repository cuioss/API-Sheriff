envelope_version=1
sender_type=plan
sender_id=plan-28-closeout-residual-hardening
epic=deployment-configurability
kind=landing
created=2026-09-23T06:23:47Z

## What landed

plan-28-closeout-residual-hardening shipped as #341 (merged).

```landing-facts
schema=landing-facts/1
plan_id=plan-28-closeout-residual-hardening
epic=deployment-configurability
pr=#341
merge_state=merged
cleanup_owed=false
deliverables_total=7
deliverables_done=7
total_tokens=10287592
total_wall_seconds=112655.0
steps=finalize-step-sync-baseline:done,finalize-step-simplify:done,finalize-step-security-audit:done,pre-submission-self-review:done,architecture-refresh:done,pre-push-quality-gate:done,push:done,create-pr:done,ci-verify:done,automatic-review:done,sonar-roundtrip:done,adr-propose:done,branch-cleanup:done,lessons-capture:done,finalize-step-preference-emitter:done,record-metrics:done,finalize-step-print-phase-breakdown:done
```

## Residue

- `ci-verify`'s and `sonar-roundtrip`'s FIND steps recurred multiple times against advancing HEADs (a full phase-5-execute rollback for wait-region-triage fix tasks, then two re-fires against `finalize-step-simplify`/`adr-propose` commits); each round ended green — the composed `steps` list above reflects the final settled state only, not the intermediate loop-backs.
- `cuioss-review-bot` needed a manual out-of-band re-review trigger three times this run (no push trigger, `re_review_on_loopback: false`); each time it published a clean review against the current HEAD. Worth revisiting whether `re_review_on_loopback` should flip for this repo, per the rationale this same plan recorded in `doc/development/re-review-on-loopback.adoc`.
- Two plan-marshall tooling defects surfaced and were reported via SendFeedback (not blocking): `scope_creep_check` emits a finding type `manage-findings` rejects (never persists), and `ci_verify run`'s internal `mark-step-done` silently failed to overwrite a prior `loop_back` record on two green re-fires (corrected manually before this landing).
- ADR-0024 was found stale (missing `paranoid` in its mode-set enumeration) by this plan's own `adr-propose` pass and was amended in the same commit as the three new ADRs (0050/0051/0052).
- A bounded jti-replay residual on the back-channel logout token (finding `e05266`) was accepted with rationale rather than fixed — CodeRabbit raised the same point independently and it was declined against the standing decision (`taken_into_account`, hash `01be48`).
