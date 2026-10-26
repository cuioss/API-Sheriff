envelope_version=1
sender_type=plan
sender_id=plan-v02-27-test-simplification
epic=api-sheriff-0-2-0
kind=candidate-lesson
created=2026-10-25T22:00:24Z

# Candidate lesson: the pre-push quality gate resolves no bundles and no test canonicals for a Maven project

**Suggested route:** plan-marshall (the ../plan-marshall lessons-routing inbox). The defect is in the pre-push-quality-gate step together with build-maven canonical resolution.

**Source:** plan-v02-27-test-simplification. Decision-log entries 037fba and ab51a4.

## What happened

For API Sheriff, a multi-module Maven reactor, `derive_gate_bundles` found 0 bundles. Both `test-compile` and `module-tests` resolve to `architecture_error`. The step therefore degraded every time to "whole-tree quality-gate (verify -Ppre-commit) satisfied by daemon job ...". The entry at e10673b8 records it as "no bundles resolve and no test-compile/module-tests canonical resolves (honest degradation as before)". The degradation was reported honestly, but the gate's per-bundle and targeted-test layer never ran.

## Corrective direction

- Make `derive_gate_bundles` and the `test-compile` / `module-tests` canonicals resolve for build-maven modules. The architecture inventory already knows the reactor modules, for example `test -pl api-sheriff -am`.
- If the canonicals cannot resolve, the gate should report one explicit non-fit signal, not an `architecture_error` per canonical. The operator can then tell "not applicable here" from "broken".
