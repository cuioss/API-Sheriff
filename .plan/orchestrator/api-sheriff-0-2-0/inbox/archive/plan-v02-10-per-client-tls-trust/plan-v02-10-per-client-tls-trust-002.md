envelope_version=1
sender_type=plan
sender_id=plan-v02-10-per-client-tls-trust
epic=api-sheriff-0-2-0
kind=candidate-lesson
created=2026-10-04T08:45:31Z

component=plan-marshall:manage-build-server
category=bug
source=operator-surfaced run event (work log 752e62, e42989, aa8028)

# Two Maven builds in one worktree at the same time cause false build failures

## What happened

At 2026-10-03T20:05:26Z and 20:05:30Z, two Maven jobs were submitted to the build daemon for the same plan worktree. The daemon runs up to two builds at once (machine-global max_slots=2), so both ran together. Job 721d034b failed at 20:15:32Z and job e655af20 succeeded. The orchestrator reports that the failure was a false ClassNotFoundException / NoClassDefFoundError. The work log records the two overlapping submissions and the failure, but not the exception text. Both builds wrote to the same `target/` directories, so one build's clean or compile step removed classes the other build was still loading.

## Rule

Never run Maven builds in one worktree at the same time. Wait for one build to finish before submitting the next for the same checkout. The slot cap limits builds machine-wide. It does not stop two builds from running in one project directory.

## Suggested direction

The daemon could serialize jobs that share a project directory and use slots only across different directories. Until it does, callers must not submit a second build for a worktree while one is still running.
