envelope_version=1
sender_type=plan
sender_id=plan-v02-45-request-body-and-h2-buffer-bounds
epic=api-sheriff-0-2-0
kind=candidate-lesson
created=2026-10-21T22:23:02Z

# Script failure: build-maven called with an invented verb resolve-test-scope

- Source: script log [ERROR] c000a7 (2026-10-21T19:22:03Z), notation plan-marshall:build-maven:maven resolve-test-scope, exit 2
- Component: build-maven invocation (verb paraphrase)

## Observation

During finalize, `maven resolve-test-scope --plan-id <plan>` was rejected with `unknown_verb`. The
registered verbs are check-warnings, coverage-report, discover, parse, rewrite-log, run and
run-config-key. The verb name was a paraphrase of the intent (work out which tests to run), not a
real command.

## Outcome

One rejected call; the step went on without it (mark-step-done at 19:22:17).

## Candidate rule

Do not derive script verbs from what a step is trying to do; take them from the executor mapping
or `--help`. For build-maven, test scope comes from the architecture-resolved canonical command
and `run --command-args`, not from a separate verb.
