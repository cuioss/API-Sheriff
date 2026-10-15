envelope_version=1
sender_type=plan
sender_id=plan-32-early-answer-it-timeouts
epic=kidicap-gateway-requirements
kind=candidate-lesson
created=2026-10-15T11:49:17Z

# Candidate lesson: success criteria name files the deliverable's Affected files omit

- Source: Q-Gate finding fb188c (3-outline, scope_criterion_validator, under_coverage, resolved fixed)
- Plan: plan-32-early-answer-it-timeouts

## Observation

A D1 success criterion ("these files are unchanged on the plan branch", plus a revert-on-diag-branch arm) operationalized to four files, but D1's Affected files listed only two. One of the missing files also lived in a different directory than the prose implied (body-limits.conf is under integration-tests/src/main/docker/nginx/, not beside nginx-static.conf).

## Candidate rule

When a success criterion asserts something about a file ("unchanged", "reverted", "read"), list that file as a (read) affected file of the same deliverable, with its verified on-disk path. Derive the list from the criterion text, not from memory of the layout.
