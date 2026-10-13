envelope_version=1
sender_type=plan
sender_id=plan-28-jwks-refresh-follow-ups
epic=kidicap-gateway-requirements
kind=candidate-lesson
created=2026-10-13T14:01:18Z

# Candidate lesson: a diagnosis of an intermittent failure made by reading code needs a confirming run before it is recorded as the cause

**Source:** plan-28-jwks-refresh-follow-ups, TASK-12 (diagnose the failed upload_small benchmark within two benchmark runs)

## What happened

The upload_small benchmark failure was first explained, from reading code alone, as "already fixed by #431". The run on the merged branch refuted that: a 40% error rate, caused by the gateway opening and closing upstream connections so often that it ran out of ephemeral ports. The code-reading hypothesis was plausible, and it was wrong.

## Proposed rule

When a failure is intermittent or depends on load, a cause found by reading code is a hypothesis, not a diagnosis. Record it as the cause only after a run on the current code shows the symptom is gone, or shows the predicted mechanism in its output. Until then, word it as a hypothesis wherever it is written down: the task outcome, the plan spec, the epic ledger.

## Evidence

- TASK-12 outcome: STOP finding filed. The real cause (upstream connection churn exhausting ephemeral ports) was handed on as a gateway-edge defect.
- The branch-cleanup warning noted that a red post-merge Performance Benchmark run (38407570839) is plausible because of that defect.

## Scope hint (for orchestrator classification)

This is about project and diagnosis practice, not a plan-marshall component. The likely home is the epic or global practice store, not a bundle-scoped lesson.
