envelope_version=1
sender_type=plan
sender_id=plan-v02-12-idp-addressing-model
epic=api-sheriff-0-2-0
kind=candidate-lesson
created=2026-10-20T20:30:17Z

# Candidate lesson: a daemon-routed Maven build does not inherit the caller's env; use --execution-mode in_process with env VAR=... for a recorded run that needs an override

Plan plan-v02-12-idp-addressing-model (PR #492, merge 03cb4755), lessons-capture step. Component: build execution (build-maven executor / compose sample).

## Observation

A recorded build needed `API_SHERIFF_IMAGE` overridden (compose sample on arm64, because the published image is amd64-only — see the companion candidate). Setting the variable for the executor call had no effect: the default execution mode routes the build through the build-server daemon, which runs with its own environment, not the caller's.

## Rule

When a recorded build must see an environment override, run it with `--execution-mode in_process` and pass the variable via `env VAR=value python3 .plan/execute-script.py plan-marshall:build-maven:maven run ...` (one command, no `VAR=val cmd` shell prefix). A daemon-routed build silently ignores caller env.

## Destination suggestion

Project lesson (API-Sheriff build practice); candidate for CLAUDE.md "Build Commands" or a memory entry.
