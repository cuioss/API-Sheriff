envelope_version=1
sender_type=plan
sender_id=release-docs-and-tls-scenario-guide
epic=deployment-configurability
kind=candidate-lesson
created=2026-09-15T18:21:59Z

component=plan-marshall:phase-3-outline
category=anti-pattern
source_finding=qgate 3-outline eb29c6 (taken_into_account)

# Deep-lane outline reached q-gate with zero CERTAIN_INCLUDE assessments recorded

## What happened

The phase-3-outline deep lane wrote a 10-deliverable solution outline but never wrote the assessment store (`findings_store_state: missing`). The q-gate assessment-coverage check could not pass and the assessed-but-undeclared (missing-coverage) check could not be evaluated at all. The fix was in-run: 26 CERTAIN_INCLUDE assessments were recorded after the q-gate fired.

## Corrective action

Record CERTAIN_INCLUDE assessments for every write-new / write-replace path and files-expected-to-mutate BEFORE the outline is submitted to q-gate; do not rely on q-gate to prompt the assessment pass. Read-only reference and survey-only files are not assessed as includes.

## Evidence

- Plan: release-docs-and-tls-scenario-guide (PR #305, merged fb65222)
- Finding hash: eb29c6, phase 3-outline, severity warning
