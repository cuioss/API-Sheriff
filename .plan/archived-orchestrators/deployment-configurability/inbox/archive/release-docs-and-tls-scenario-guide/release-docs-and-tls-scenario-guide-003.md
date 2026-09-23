envelope_version=1
sender_type=plan
sender_id=release-docs-and-tls-scenario-guide
epic=deployment-configurability
kind=candidate-lesson
created=2026-09-15T18:22:24Z

component=plan-marshall:phase-3-outline
category=improvement
source_finding=qgate 3-outline 444e7e (taken_into_account)

# Search-based exclusion criterion was written before running the search it describes

## What happened

Deliverable 6's criterion said a content search for `0\.1\.[01]` over operator-facing docs "returns only historical records (ADRs, quality-report, context-path-verification)". Running that exact search (`architecture search --content --pattern '0\.1\.[01]' --category doc`, 14 hits over 99 files) showed two further legitimate historical hits not on the list (`doc/user/container-image.adoc` certificate evidence, `doc/development/release-process.adoc` positive-control run) and one listed file (context-path-verification) that produced no hit. A verifier applying the criterion literally would have failed the deliverable. The survey also surfaced a genuinely stale passage (container-image.adoc lines 296-299), which moved into the mutate set.

## Corrective action

When a criterion is phrased as "search X returns only Y", run the search at outline time and write the allow-list from its actual result: every hit either listed with the specific passage that justifies it, or scheduled for mutation. Drop allow-list entries that produce no hit.

## Evidence

- Plan: release-docs-and-tls-scenario-guide (PR #305)
- Finding hash: 444e7e, phase 3-outline, severity info
