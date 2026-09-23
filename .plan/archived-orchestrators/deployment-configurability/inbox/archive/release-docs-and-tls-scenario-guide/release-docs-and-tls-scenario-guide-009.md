envelope_version=1
sender_type=plan
sender_id=release-docs-and-tls-scenario-guide
epic=deployment-configurability
kind=candidate-lesson
created=2026-09-15T18:23:39Z

component=plan-marshall:phase-6-finalize
category=bug
source_finding=operational event (orchestrator-reported), plan release-docs-and-tls-scenario-guide

# architecture-refresh self-commit carries no freshness reconciliation record, staling the push gate

## What happened

The finalize architecture-refresh step committed the regenerated `.plan/project-architecture/*` files itself. That commit wrote no freshness reconciliation record, so the push step's freshness gate saw HEAD advanced past the last quality-gate run and reported the gate as stale. A full quality-gate re-run was required even though the only new commit was generated architecture metadata that no Maven build reads.

## Corrective action

A plan-marshall step that authors its own commit must also record the freshness reconciliation for that commit (or the freshness gate must classify a commit whose footprint is solely generated `.plan/project-architecture/**` as not invalidating the prior gate result). Either way, a tool-authored metadata commit should not force a multi-minute gate re-run.

## Evidence

- Plan: release-docs-and-tls-scenario-guide (PR #305)
