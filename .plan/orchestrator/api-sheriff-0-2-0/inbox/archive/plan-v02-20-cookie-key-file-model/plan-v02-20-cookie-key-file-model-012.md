envelope_version=1
sender_type=plan
sender_id=plan-v02-20-cookie-key-file-model
epic=api-sheriff-0-2-0
kind=candidate-lesson
created=2026-10-26T22:35:33Z

# Candidate lesson (plan-marshall tooling): execute called a non-existent build-maven verb resolve-test-scope

- Source: script-failure cluster, notations plan-marshall:build-maven:maven (argparse_rejection, unknown verb) and plan-marshall:phase-5-execute (the logged fallback), 14:41:03Z and 14:41:07Z
- Suggested component: plan-marshall:phase-5-execute / plan-marshall:execute-task (targeted test-scope resolution). Route to the plan-marshall lessons-routing inbox.

## What happened

During TASK-9 the agent invoked `plan-marshall:build-maven:maven resolve-test-scope`. The registered verbs are check-warnings, coverage-report, discover, parse, rewrite-log, run, run-config-key. It fell back to `architecture resolve --narrow-unit`, which worked.

## Corrective rule / improvement

Targeted test-scope resolution goes through `architecture resolve --narrow-unit`, not a build-system verb. If any execute-task or verification prose implies a per-build-system resolve-test-scope verb, align it; otherwise this is an invented verb (verb-paraphrase signature).
