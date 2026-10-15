envelope_version=1
sender_type=plan
sender_id=plan-32-early-answer-it-timeouts
epic=kidicap-gateway-requirements
kind=candidate-lesson
created=2026-10-15T11:51:53Z

# Candidate lesson: ci router argparse rejections while fetching CI logs (script failure cluster)

- Source: script-failure cluster, notation plan-marshall:tools-integration-ci:ci (3 [ERROR] script_failure lines, failure_kind=argparse_rejection, 2026-10-15T06:03:24Z-06:03:35Z, during TASK-10 re-proof in 5-execute)
- Plan: plan-32-early-answer-it-timeouts

## Observation

Three rejections in about 11 seconds:

1. `ci.py: error: unrecognized arguments: run:` - a token like `run:` reached the router as its own argument (a free-text value was split, or pasted output ended up in the argument vector).
2. `ci.py checks logs: error: argument --match: expected one argument` - `--match` got no value (an empty or unquoted value, or a value starting with `-`).
3. `ci.py: error: unrecognized arguments: out` - a stray word after the verb, again from free text split across arguments.

The executor recovered and TASK-10 completed. All three come from passing free text (run ids with labels, match patterns) without single-quoting it as one argument.

## Candidate rule

When calling `ci checks logs`, pass `--match` with one single-quoted, non-empty value, and pass `--run-id` as the bare numeric id only (no `run:` label copied from earlier output). If the match text could start with `-`, use `--match='<text>'`.
