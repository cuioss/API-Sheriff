envelope_version=1
sender_type=plan
sender_id=integration-test-evidence-follow-ups
epic=kidicap-gateway-requirements
kind=candidate-lesson
created=2026-10-12T21:09:30Z

# Candidate lesson: finalize re-fires every head-dependent step on each HEAD advance

Source: orchestrator-observed run behaviour during 6-finalize of this plan (PR #433, merged as 128fd07e).

## What happened

Each review-triage fix commit (here doc-only: the d4e3af fix in 75f19e91 and the CodeRabbit fixes in fceb918e) advanced HEAD, and finalize re-ran every head-dependent step on the new HEAD: simplify, security-audit, pre-submission self-review, pre-push quality gate, CI wait and automated review. For a commit whose whole footprint is `*.adoc`, the build gate and code-oriented steps cannot observe anything new, so the re-fire costs minutes and review-bot rounds while proving nothing.

## Candidate rule / improvement

plan-marshall finalize should scope the re-fire to the delta since the last fully-gated HEAD: when the delta is documentation-only (the same classes `build-decision` / CLAUDE.md treat as documentation-only), skip re-running the build gate and code-only steps (simplify, security-audit) and keep only the doc-relevant checks (self-review over touched claims, CI, review re-request). Until then, batch all doc-only review-triage fixes into one commit per round to pay the re-fire once.

Component candidate: plan-marshall:phase-6-finalize. Classification deferred to the orchestrator.
