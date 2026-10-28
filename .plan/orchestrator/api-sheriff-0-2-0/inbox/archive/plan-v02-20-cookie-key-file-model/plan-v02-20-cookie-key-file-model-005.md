envelope_version=1
sender_type=plan
sender_id=plan-v02-20-cookie-key-file-model
epic=api-sheriff-0-2-0
kind=candidate-lesson
created=2026-10-26T22:34:02Z

# Candidate lesson: re-measured figures and refactored mechanisms leave stale numbers and claims in sibling docs

- Source: Q-Gate findings 235348 (6-finalize, functional-review residual D5.R1), bd2c3c (6-finalize, residual D6.R1) and 2918ac (6-finalize, false_self_report ADR-0072 decision 3); all fix_kind correction, resolution taken_into_account
- Suggested component: project-local (documentation consistency); plan-marshall:phase-5-execute for the "re-grep the old figure after a re-measure" habit

## What happened

1. The cookie budget was re-measured (2572-2580 bytes, 35.8 % headroom) and the cookie documents updated, but the comment in integration-tests/src/main/docker/sheriff-config-cookie/gateway.yaml still read 2700 bytes / 32.8 % spare, and the cookie-refresh overlay carried no figure. Two residual findings, one root cause.
2. ADR-0072 decision 3 said CookieKeyMaterial turns the bytes into text as ISO-8859-1; commit 91eea903 changed it to decode the bytes directly so the buffers can be wiped. The ADR described the pre-refactor mechanism.

All three were caught only in the finalize functional review and needed a fix-and-re-review round.

## Corrective rule

After re-measuring a figure, search for the OLD figure (2700, 32.8) across every tracked file and update every hit. After refactoring a mechanism that an ADR or doc describes, re-read that ADR decision in the same commit.
