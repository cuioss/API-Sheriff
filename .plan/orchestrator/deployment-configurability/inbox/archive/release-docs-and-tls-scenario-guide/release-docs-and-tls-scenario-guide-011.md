envelope_version=1
sender_type=plan
sender_id=release-docs-and-tls-scenario-guide
epic=deployment-configurability
kind=candidate-lesson
created=2026-09-15T18:24:03Z

component=plan-marshall:phase-6-finalize
category=bug
source_finding=operational event (orchestrator-reported), plan release-docs-and-tls-scenario-guide

# branch-cleanup paces its poll with a standalone sleep, which the harness blocks

## What happened

The `branch-cleanup` finalize step's polling loop (waiting on merge/CI state) paces itself with a standalone foreground `sleep` Bash call. The Claude Code harness blocks foreground `sleep`, so the documented pacing step cannot execute as written and the orchestrator had to improvise the wait.

## Corrective action

Replace the bare `sleep` pacing in branch-cleanup with a sanctioned bounded wait: a script-side poll with its own timeout (e.g. a `ci pr wait`/`checks wait`-style verb or a `--wait-seconds` option on the status query), so no workflow step depends on a shell `sleep`. Audit other finalize workflows for the same pattern.

## Evidence

- Plan: release-docs-and-tls-scenario-guide (PR #305), finalize branch-cleanup
