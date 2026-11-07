envelope_version=1
sender_type=plan
sender_id=plan-v02-10-per-client-tls-trust
epic=api-sheriff-0-2-0
kind=candidate-lesson
created=2026-10-04T08:45:37Z

component=plan-marshall:phase-3-outline
category=improvement
source=Q-Gate finding 486ae2 (3-outline, taken_into_account)

# Deep-lane outline left the assessment store empty, so assessment coverage could not be checked

## What happened

The first outline declared 19 affected files and 19 survey/mutate files but recorded no CERTAIN_INCLUDE assessment. The assessment store was empty (findings_store_state missing). The Q-Gate assessment-coverage check could not pass and had nothing to compare against. The validator checked all 38 paths by hand instead. The revision recorded 40 assessments, one per declared path.

## Rule

Before an outline returns for Q-Gate, it should record one CERTAIN_INCLUDE assessment for every path it declares (affected, survey and mutate). Otherwise the outline should state explicitly that its path produces no assessments. An empty store makes the coverage check a no-op and forces a full outline re-dispatch to fix.
