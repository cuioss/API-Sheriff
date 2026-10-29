envelope_version=1
sender_type=plan
sender_id=plan-v02-50-enumeration-detection-substrate
epic=api-sheriff-0-2-0
kind=candidate-lesson
created=2026-10-29T07:58:46Z

# Candidate lesson: pre-commit gate formatter churn has to be attributed and reverted every round

**Source signal:** build verification across phase 5 and the finalize review-fix rounds.

**Observation:** Each run of the `verify -Ppre-commit` quality gate exited green but left the working tree dirty with formatter/OpenRewrite rewrites of files the branch did not author. The churn reappeared on every gate round (initial execute and every review-fix push), so it had to be attributed and reverted each time rather than once.

**Corrective rule:** After every gate run, check `git status --porcelain` before committing. Keep rewrites only for files the branch authored; revert the rest. Never commit a gate run's tree wholesale. Budget for this as a recurring per-round step, not a one-off cleanup.

**Scope:** project build process (see `doc/development/build-gate-discipline.adoc`).
