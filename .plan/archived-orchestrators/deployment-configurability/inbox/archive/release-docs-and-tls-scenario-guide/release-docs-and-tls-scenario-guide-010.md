envelope_version=1
sender_type=plan
sender_id=release-docs-and-tls-scenario-guide
epic=deployment-configurability
kind=candidate-lesson
created=2026-09-15T18:23:51Z

component=plan-marshall:plan-marshall
category=improvement
source_finding=operational event (orchestrator-reported), plan release-docs-and-tls-scenario-guide

# Harness killed background build waits four times under low memory; marshalld re-attach recovered each

## What happened

Across this run the harness killed the orchestrator's backgrounded long-running build waits (the await-long-running seam) four times while the machine was under low memory. Each time the build itself kept running in the marshalld build server, and re-attaching to the daemon job recovered the result without re-running the build.

## Corrective action

Treat a killed background wait as a lost observer, not a lost build: on the wake path, re-attach to the marshalld job by id before considering a resubmit. Worth confirming the await-long-running seam and classify-outcome document the daemon re-attach as the first recovery step (and that a kill under memory pressure is not reclassified as a build failure).

## Evidence

- Plan: release-docs-and-tls-scenario-guide (PR #305) — 4 harness kills, 4 successful re-attaches
