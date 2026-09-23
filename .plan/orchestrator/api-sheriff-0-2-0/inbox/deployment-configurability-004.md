envelope_version=1
sender_type=orchestrator
sender_id=deployment-configurability
epic=api-sheriff-0-2-0
kind=candidate-lesson
created=2026-09-23T15:48:23Z

# Candidate lesson (handed off from `deployment-configurability`'s local corpus): native image misclassifies an IdP 400 invalid_grant as transient when a token-sheriff-client DSL-JSON converter is unregistered

Original lesson `2026-09-17-06-001` (component `api-sheriff`, category `bug`, created 2026-09-17).

## What happened

`BffTokenRefreshIT` failed only in the native integration-test lane: the IdP answered a refresh with
HTTP 400 `invalid_grant`, but the gateway logged pre-redemption/session-kept with cause
`TransportException`. Root cause: `TokenClientDslJsonReflection` did not register
`_TokenErrorResponse_DslJsonConverter` (a converter `token-sheriff-client` 0.9.5 ships). In the native
image, DSL-JSON could not read the error body, the engine raised `TransportException` instead of
`CredentialRejectedException`, and the coordinator classified the failure as PRE_REDEMPTION —
a security-relevant false keep of a revoked/replayed session.

## Candidate rule

Treat a `TransportException` on a 4xx token-endpoint response in the native lane as a
reflection-registration gap first, not an engine or IdP defect, until the converter list is checked.
After every `token-sheriff-client` version bump, confirm every engine `_*_DslJsonConverter` is
registered. A green JVM lane says nothing about refresh-failure classification — only the native
`verify -Pintegration-tests` run exercises it.

## Status: FIXED (PLAN-26, PR #314)

`TokenClientDslJsonReflectionTest.shouldRegisterEveryEngineDslJsonConverter` now pins this in the JVM
lane (reversion-proven), so a red run of that test is the early signal for any future converter gap.
