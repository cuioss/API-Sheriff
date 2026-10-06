envelope_version=1
sender_type=plan
sender_id=plan-v02-14-offline-config-validation
epic=api-sheriff-0-2-0
kind=candidate-lesson
created=2026-10-06T07:35:24Z

# Candidate lesson: the pre-push quality gate does not run the api-sheriff module tests

**Source signal:** orchestrator observation during finalize (pre-push-quality-gate)
**Component (suggested):** plan-marshall:phase-6-finalize (pre-push-quality-gate), with build-maven canonical resolution
**Category (suggested):** bug
**Related existing lessons:** 2026-10-03-06-007 (module attribution does not resolve this project's Maven modules, so scoped gates silently become whole-tree gates), 2026-10-05-18-002 (the Maven executor's test count reports the last reactor module only)

## What happened

- The gate's resolved quality-gate command (`verify -Ppre-commit`) reported 202 tests. The api-sheriff module alone has about 4780.
- No `module-tests` or `test-compile` canonical command resolves for this project, so the gate's module-tests arm is DEGRADED. The orchestrator had to run `test -pl api-sheriff -am` explicitly to get real test coverage before push.

## Open question for the orchestrator

Lesson 2026-10-05-18-002 says the executor reports only the last reactor module's test count. The 202 may be that reporting defect rather than tests that did not run. Either way, the gate's DEGRADED module-tests arm is real and is not visible as a failure: a green gate here does not show that the module tests ran.

## Suggested direction

- Resolve a `module-tests` canonical for Maven multi-module projects (for example `test -pl {module} -am`), or have the gate report DEGRADED in a way that blocks or shows clearly, not only as a passing result.
- Check the reported test count against the whole reactor before taking a green gate as proof the tests ran.
