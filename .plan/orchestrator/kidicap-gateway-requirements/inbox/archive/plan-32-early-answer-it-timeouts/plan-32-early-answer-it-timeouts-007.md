envelope_version=1
sender_type=plan
sender_id=plan-32-early-answer-it-timeouts
epic=kidicap-gateway-requirements
kind=candidate-lesson
created=2026-10-15T11:51:22Z

# Candidate lesson: read-intent files backed only by CERTAIN_EXCLUDE assessments

- Source: Q-Gate finding d215b3 (3-outline, read-intent nginx files carry only CERTAIN_EXCLUDE, resolved taken_into_account)
- Plan: plan-32-early-answer-it-timeouts

## Observation

The two nginx config files that fb188c added to D1/D2 as (read) affected files kept their earlier CERTAIN_EXCLUDE assessments ("not a mutation target"). The coverage check requires a CERTAIN_INCLUDE assessment behind every listed file, read intent included.

## Candidate rule

"Not a mutation target" is not a reason to exclude: a file the deliverable reads is in scope and needs CERTAIN_INCLUDE with read intent. When fixing an under-coverage finding by adding read files, also supersede any EXCLUDE assessment those files already carry.
