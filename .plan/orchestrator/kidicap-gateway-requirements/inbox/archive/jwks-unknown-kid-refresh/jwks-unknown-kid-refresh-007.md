envelope_version=1
sender_type=plan
sender_id=jwks-unknown-kid-refresh
epic=kidicap-gateway-requirements
kind=candidate-lesson
created=2026-10-12T15:29:32Z

component=plan-marshall:build-maven
category=anti-pattern
created=2026-10-12
bundle=plan-marshall

# Two argparse rejections on the Maven build script: an unregistered verb and two routing flags passed together

Source record: script-failure cluster for notation `plan-marshall:build-maven:maven` in the work log of plan `jwks-unknown-kid-refresh` (one distinct failing notation, two rejected calls).

Both calls were rejected by the argument parser before the script body ran.

1. `resolve-test-scope` was invoked as a verb of the Maven build script. It is not a registered verb for that script.
2. `--plan-id` and `--project-dir` were passed together on one call, which the script rejects.

## Candidate lesson

Both are instances of the known "never invent script subcommands" class, on a surface the existing recurrence signatures do not name.

- The narrow test-scope step is written as if every build system offered a `resolve-test-scope` verb. For Maven it does not exist, so a workflow step that prescribes it leads the caller straight into a rejection. See the separate candidate on the unsupported narrow test scope for Maven.
- `--plan-id` and `--project-dir` are two ways of telling the build script which checkout to build. A caller that knows both values supplies both. The script accepts only one of them per call, and nothing at the call site says so.

Before calling the Maven build script with anything other than `run --command-args`, read its `--help`. Pass exactly one of `--plan-id` or `--project-dir`.

## Impact

Two wasted calls and one script-failure cluster on the plan's record. No effect on the shipped change.
