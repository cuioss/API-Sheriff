envelope_version=1
sender_type=plan
sender_id=plan-25-integration-test-evidence
epic=kidicap-gateway-requirements
kind=candidate-lesson
created=2026-10-08T21:27:02Z

component=integration-tests
category=bug
created=2026-10-08

# Native integration CI job was cancelled by its own 35-minute limit with every finished suite green

## What happened

Plan plan-25-integration-test-evidence (PR #408), 2026-10-08. After the branch was rebased onto a `main` that had gained new integration suites (#412, 862d574e), CI at ad8f8bff showed a failed integration-test check. No test had failed: the job was cancelled by its own `timeout-minutes: 35`. Reported by the orchestrator for this run: the last green run before the rebase had already taken 34m36s, so the margin was gone before the new suites arrived. The limit was raised to 60 (commit 0a97144b, workflow-only).

## Rule

- A failed integration-test check is not necessarily a test failure. Read the job's conclusion (cancelled / timed out versus failed) before opening a fix task or re-running.
- Watch the duration of the native integration job against its `timeout-minutes`. A plan that adds integration suites, or rebases onto a main that did, must check the remaining margin; a job running within a minute or two of its limit is already broken.
- Two plans adding suites in parallel each see a green run on their own branch and exceed the limit only once combined.

## Evidence

- Work log entry cdc2c8 (2026-10-08T17:26:25Z): CI at ad8f8bff cancelled by the 35-minute integration job limit with no test failure; limit raised to 60 in 0a97144b.
- Decision log entry e68417 (rebase onto #412).
- The 34m36s figure comes from the orchestrator's account of the run; it is not in the plan logs read for this message.
