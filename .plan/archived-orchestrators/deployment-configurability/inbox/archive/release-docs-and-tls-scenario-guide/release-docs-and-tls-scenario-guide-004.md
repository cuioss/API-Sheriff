envelope_version=1
sender_type=plan
sender_id=release-docs-and-tls-scenario-guide
epic=deployment-configurability
kind=candidate-lesson
created=2026-09-15T18:22:35Z

component=plan-marshall:manage-tasks
category=bug
source_finding=qgate 4-plan 3100f9 (accepted as false positive)

# files_exist q-gate mechanical check is blind to depends_on ordering

## What happened

The phase-4-plan q-gate mechanical check `files_exist` (component `plan-marshall:manage-tasks:qgate-mechanical-checks`) flagged TASK-006's step target `doc/user/tls-scenarios.adoc` as missing. The file is a write-new target created by TASK-001, and TASK-006 declares `depends_on: TASK-001`. The check reads current disk state only, so every task that consumes a file a predecessor creates produces a false-positive warning that has to be hand-accepted.

## Corrective action

`files_exist` should treat a step target as satisfied when it is declared write-new (or otherwise created) by any task in the target task's transitive `depends_on` closure, and only flag paths that neither exist on disk nor are produced by a predecessor.

## Evidence

- Plan: release-docs-and-tls-scenario-guide (PR #305)
- Finding hash: 3100f9, phase 4-plan, resolution accepted ("False positive by ordering")
