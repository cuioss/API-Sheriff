envelope_version=1
sender_type=plan
sender_id=plan-sec-03-pending-authorization-store-fairness
epic=security-hardening-tier-1
kind=candidate-lesson
created=2026-11-09T11:08:24Z

component=plan-marshall:phase-6-finalize
category=bug
title=Finalize lane and signal-gate docs drift from the scripts: signal-scan is unregistered and an explicit off lane is ignored under the full posture

# Finalize lane and signal-gate docs drift from the scripts: signal-scan is unregistered and an explicit off lane is ignored under the full posture

## Observation

Two doc-vs-script mismatches were hit while running the finalize tail of plan-sec-03-pending-authorization-store-fairness:

1. `workflow/lessons-capture.md` states the script-failure-cluster count "is computed by `manage-logging signal-scan --plan-id {plan_id}`". The verb does not exist: the executor rejects it with `unknown_verb` (accepted: `decision, read, script, separator, work`). The only remaining route is `manage-logging read --type work`, which for this plan returned 7106 entries (1.2 MB) with no filter flag that narrows by marker or phase (`--phase` is accepted but did not filter the work log).
2. The plan's manifest carried `lane_requested: off` for `adr-propose` and `lessons-capture`. `lanes preview --plan-id` reports both as `declared=off effective=off binds=true` ("explicit 'off' override opts this prunable element out"), yet both steps stayed in the composed `phase_6.steps` because the plan's `execution_profile: full` short-circuits lane resolution. The report says the override binds while the manifest still schedules the step, so the dispatcher has no documented rule for which signal wins.

## Why it matters

- A signal gate whose documented computation cannot run forces each finalize run to improvise the count or skip it.
- An override reported as binding but not applied is a declared-but-not-in-force control: the operator cannot tell from either surface whether the step will run.

## Suggested correction

- Register `manage-logging signal-scan` (or correct the doc to the actual computation), and give `read` a working marker/phase filter.
- Either apply an explicit `off` on a prunable element under the `full` posture too, or have `lanes preview` report `binds=false` with the reason "full posture keeps every candidate", and state in `phase-6-finalize/SKILL.md` how the dispatcher treats a scheduled step whose effective lane is `off`.
