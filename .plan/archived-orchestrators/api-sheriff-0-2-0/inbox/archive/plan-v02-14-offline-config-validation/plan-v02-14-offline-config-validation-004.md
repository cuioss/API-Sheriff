envelope_version=1
sender_type=plan
sender_id=plan-v02-14-offline-config-validation
epic=api-sheriff-0-2-0
kind=candidate-lesson
created=2026-10-06T07:35:33Z

# Candidate lesson: LoopbackEphemeralBindArchTest rejects source-level Unicode escapes in test sources

**Source signal:** Q-Gate finding 70a46e (5-execute, fixed by TASK-11)
**Component (suggested):** api-sheriff (test authoring)
**Category (suggested):** anti-pattern

## What happened

- ConfigValidationCommandTest.java:443 (branch-authored in TASK-6) built a path containing a NUL character by writing a backslash-u0000 Unicode escape in the Java source.
- `LoopbackEphemeralBindArchTest.noFixturePassesAWildcardHostLiteral` scans test sources and fails on any source-level Unicode escape ("Unicode escape in guarded source"), because such an escape could hide a wildcard host literal from the scan.
- The failure showed up only in the orchestrator-tier module test run, not during the task.

## Rule

In api-sheriff test sources, build control or special characters at runtime (for example `String.valueOf((char) 0)` or a named char constant), never with a source-level Unicode escape. The architecture guard treats any escape as a possible bypass.

## Evidence

- Fixed in TASK-11. The test still checks the same thing: an argument that is not a valid path exits 2.
