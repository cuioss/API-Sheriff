envelope_version=1
sender_type=plan
sender_id=plan-19-session-fallback-on-bearer
epic=kidicap-gateway-requirements
kind=candidate-lesson
created=2026-09-24T21:11:21Z

# Candidate lesson: native integration-test run hit the 1800 s budget

**Source**: an orchestrator observation from the run. It is not one of the three signal counts.
**Plan**: plan-19-session-fallback-on-bearer (PR #356, merged as 1fa648d)

## What happened

The native integration-test run (`verify -Pintegration-tests -pl integration-tests -am`, which
covers the native image build and the Docker stack ITs) hit its 1800 s timeout budget once. A
retry with a 3600 s budget passed in 1779 s. That is only 21 s under the old ceiling, so the
1800 s budget no longer leaves enough headroom for this suite on this machine (WSL2).

## Candidate rule

- The 1800 s native IT budget is too tight. The configured budget for the integration-tests build
  class should be at least 3600 s, or the adaptive timeout should be allowed to learn from the
  1779 s observation.
- If a native IT run reaches its timeout, retry once with a larger budget before treating the
  run as a failure. A run close to the ceiling points to a budget problem, not a regression.

Classification hint: project run-config or build-timeout tuning (see the run-integration-tests
skill and manage-run-config). If the budget comes from a shared default, it may also be an
upstream plan-marshall timeout-default question.
