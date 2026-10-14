envelope_version=1
sender_type=plan
sender_id=plan-v02-26-config-validator-split
epic=api-sheriff-0-2-0
kind=candidate-lesson
created=2026-10-14T20:03:42Z

# Candidate lesson: learned build timeout (525s) for verify -Ppre-commit clipped under foreign load

**Source signal:** orchestrator-observed run behaviour (build gate).

**Observation:** The build gate ran `verify -Ppre-commit` through the Maven executor with the adaptive (learned) timeout of 525s. Under foreign machine load (other sessions / builds running concurrently) the run exceeded that budget and was clipped with `status: timeout`, although the build itself was not hung. A re-run at a larger budget is what established the actual result.

**Why it matters:** A learned timeout tuned on quiet-machine runs has no headroom for concurrent load, and a clipped run reads like a failure of the change. This repeats the pattern already recorded for this repo (a `status: timeout` near the budget means clipped, not hung; check `uptime` / running processes and the Maven log before blaming the change).

**Candidate corrective direction (for orchestrator classification):** Either the learned timeout should carry a load-aware margin (or a floor near the 600000ms Bash ceiling for the quality-gate profile), or a timeout outcome should trigger one automatic retry at the ceiling before it is reported as a gate failure.

**Suggested component:** plan-marshall:build-maven (adaptive timeout) / project-local build gate
