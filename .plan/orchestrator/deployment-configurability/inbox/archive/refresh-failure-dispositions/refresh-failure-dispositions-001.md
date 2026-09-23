envelope_version=1
sender_type=plan
sender_id=refresh-failure-dispositions
epic=deployment-configurability
kind=candidate-lesson
created=2026-09-17T03:48:54Z

component=api-sheriff
category=bug

# Native image misclassifies an IdP 400 invalid_grant as transient when a token-sheriff-client DSL-JSON converter is not registered for reflection

## What happened

After the refresh-failure dispositions landed, `BffTokenRefreshIT` failed only in the native integration-test lane: two tests expected 401 and got 200. The IdP (Keycloak) answered the refresh with HTTP 400 `invalid_grant`, but the gateway logged `ApiSheriff-127` (pre-redemption, session kept) with cause `TransportException: Token endpoint returned unexpected HTTP status 400`. The JVM unit lane was green the whole time.

Root cause was in the gateway, not the engine, the realm or the disposition logic: `TokenClientDslJsonReflection` (api-sheriff/src/main/java/de/cuioss/sheriff/gateway/quarkus/TokenClientDslJsonReflection.java) did not register `_TokenErrorResponse_DslJsonConverter`, a converter token-sheriff-client 0.9.5 ships. In the native image DSL-JSON could not read the error body, `TokenEndpointClient.errorCode` swallowed the `IOException`, and the engine raised `TransportException` instead of `CredentialRejectedException`. The coordinator then classified the failure as PRE_REDEMPTION and kept a revoked or replayed session alive. That is a security-relevant false keep.

## Rule

- Treat a `TransportException` on a 4xx token-endpoint response in the native lane as a reflection-registration gap first. It is not an engine or IdP defect until the converter list has been checked.
- After every token-sheriff-client version bump, confirm that every engine `_*_DslJsonConverter` is registered. `TokenClientDslJsonReflectionTest.shouldRegisterEveryEngineDslJsonConverter` now pins this in the JVM lane (reversion-proven), so a red run of that test is the early signal.
- A green JVM lane says nothing about refresh-failure classification. Only the native `verify -Pintegration-tests` run exercises it.

## Evidence

- Plan refresh-failure-dispositions, finding cb22d2, fix TASK-12 (commit 0ffdbde), PR #314.
- Native IT at HEAD 3ccf00e after the fix: `quarkus-refresh.log` shows ApiSheriff-111 x2 and ApiSheriff-127 x0.
