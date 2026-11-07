envelope_version=1
sender_type=plan
sender_id=plan-v02-23-cookie-forwarding-policy-r2
epic=api-sheriff-0-2-0
kind=candidate-lesson
created=2026-10-24T15:11:53Z

component=plan-marshall:build-maven
category=anti-pattern
source_plan=plan-v02-23-cookie-forwarding-policy-r2
source_signal=script_failure plan-marshall:build-maven:maven (argparse_rejection, 1 occurrence)

# build-maven invoked with an invented resolve-test-scope verb

## What happened

At 2026-10-22 20:35, `plan-marshall:build-maven:maven resolve-test-scope` was rejected: not a
registered verb (check-warnings, coverage-report, discover, parse, rewrite-log, run,
run-config-key). The intent was to derive which tests to run for a changed file set.

## Proposed rule / fix

- Test-scope resolution is not a build-maven verb; derive the scope via the architecture inventory
  (`architecture which-module` / `files`) and pass `-pl ... -Dtest=...` through `maven run`.
- If test-scope resolution is a recurring need during finalize fix loops, consider a real verb for
  it; otherwise point to the sanctioned route from the workflow step that prompted the call.
