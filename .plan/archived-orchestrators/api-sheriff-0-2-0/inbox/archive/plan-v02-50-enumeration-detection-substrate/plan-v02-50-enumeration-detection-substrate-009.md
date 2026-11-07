envelope_version=1
sender_type=plan
sender_id=plan-v02-50-enumeration-detection-substrate
epic=api-sheriff-0-2-0
kind=candidate-lesson
created=2026-10-29T08:01:51Z

title=Owed architecture hint: intermittent build/test failures under machine load are accepted (preference-emitter, plan-v02-50-enumeration-detection-substrate)
category=improvement
component=plan-marshall:phase-6-finalize

## Owed `architecture enrich` call

- target module: `maven` (as attributed on the findings; the attribution is the build tool, not a project module, so the orchestrator should decide whether it belongs on `api-sheriff` or `integration-tests`)
- enrich verb: `insight`
- generalized hint text: "The project tolerates intermittent test failures and build errors that only appear while the machine carries foreign load and that pass when run alone; they are accepted as load artefacts, not treated as regressions, after a solo re-run confirms them."

## Evidence shape

Two within-plan recurrences cleared `preference_min_recurrence` (2): accepted `test-failure` findings (5) and accepted `build-error` findings (2), all attributed to the maven build tool.

The step could not call `architecture enrich` itself because it runs after the merge gate (discover-after-merge route), so the owed call is recorded here instead.
