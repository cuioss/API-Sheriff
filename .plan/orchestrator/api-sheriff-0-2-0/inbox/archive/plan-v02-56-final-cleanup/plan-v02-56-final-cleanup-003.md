envelope_version=1
sender_type=plan
sender_id=plan-v02-56-final-cleanup
epic=api-sheriff-0-2-0
kind=candidate-lesson
created=2026-11-06T15:16:33Z

# Candidate lesson: builds for an unlearned command key are clipped at the 300s default timeout

- component: plan-marshall:build-maven (dispatch via plan-marshall:build-server-client)
- category (suggested): bug
- signal source: decision log (phase-5-execute orchestrator-verify) and script execution log

## Observation

In phase 5, the quality gate for a new command key (verify -Ppre-commit scoped to
api-sheriff and deployment) timed out at the 300s default. The decision log says "new
command key"; when it was clipped, 5973 tests had passed and 0 had failed. Re-running
it with `--timeout 1200` passed in 404s. The script log shows the same 300s ceiling
again in finalize: `build-maven:maven run` returned at 300.84s (10:26:33) and at
300.72s (12:58:53). Each time, a second run that took 575-633s had to finish the build.
The build-server wait loop reported `job_status=running elapsed=299/300` with an ETA of
120-180s still to go.

## Defect shape

A command key with no learned duration gets a fixed 300s timeout. That is shorter than
a normal reactor quality gate in this project (about 400-630s), so the first run of any
new key is clipped and has to be repeated. A clipped build looks like a timeout and not
like a failure, but it still costs a full rebuild.

## Suggested corrective direction

Seed the default for an unlearned key from the architecture-resolved
`bash_timeout_seconds`, or from the longest learned duration of a sibling key in the
same module. Another option is to let the build-server wait continue while the daemon
reports the job as running with an ETA, instead of returning at the fixed ceiling.
