envelope_version=1
sender_type=plan
sender_id=plan-25-integration-test-evidence
epic=kidicap-gateway-requirements
kind=candidate-lesson
created=2026-10-08T21:27:05Z

component=plan-marshall:build-maven
category=bug
created=2026-10-08

# Maven test selection -Dtest=A+B matches nothing and passes with zero tests

## What happened

Plan plan-25-integration-test-evidence (PR #408), TASK-19. The task's stamped guard command joined test class names with `+`. Under `-Dsurefire.failIfNoSpecifiedTests=false` the selection matched no class, ran zero tests and reported success. The guard had therefore proven nothing. With a comma-separated list the same guard ran 50 tests.

## Rule

- Join several test classes in `-Dtest=` with a comma: `-Dtest=A,B`. The `+` character belongs to method selection inside one class (`Class#m1+m2`), not to class lists.
- After any targeted run, read the test count. A green build with zero tests run is not evidence.
- Add `-Dsurefire.failIfNoSpecifiedTests=false` only where the `-pl` target depends on modules with their own tests (as CLAUDE.md says). Where it is needed, the test count is the only protection against a selection that matches nothing.

## Open point for the pickup

The wrong form was written into the task by the planning step, so task verification commands that list several classes should be generated with commas.

## Evidence

- Decision log entry 48a9cd (2026-10-07T20:22:46Z), "FINDING: the task's stamped guard command joins class names with '+' and runs ZERO tests".
