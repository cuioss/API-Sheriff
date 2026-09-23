envelope_version=1
sender_type=plan
sender_id=plan-28-closeout-residual-hardening
epic=deployment-configurability
kind=candidate-lesson
created=2026-09-23T06:15:48Z

# Candidate lesson: a DEBUG-only rejection path made a failing IT unexplainable, and the decisive evidence was a *negative* log result

**Source signal**: Q-Gate finding `2d0dc6` (phase 5-execute, type `test-failure`, severity error, resolution `fixed`)
**Component**: `api-sheriff` — `bff/logout/BackchannelLogoutReceiver`, `bff/logout/LogoutTokenValidator`, `bff/reserved/BackchannelLogoutEndpoint`

## What happened

`BffBackchannelLogoutIT.idpInitiatedBackchannelLogoutDestroysTheGatewayHeldSession` stayed red
after the OIDC-host exemption fix: the request reached the receiver's route, but the gateway-held
session was not destroyed within the 15s budget.

**Every branch** of `BackchannelLogoutEndpoint -> BackchannelLogoutReceiver -> LogoutTokenValidator`
logged at DEBUG only. The captured run logs therefore carried *zero* back-channel evidence —
`keycloak-logs-*.txt` had no logout lines, `quarkus.log` had no receiver lines. A failing path that
emits nothing at default level is indistinguishable from a path that was never reached.

The triage that broke the deadlock used a **negative** result: every rejection route in the engine's
ID-token pipeline logs at WARN (`MandatoryClaimsValidator`, `AuthorizedPartyValidator`,
`ExpirationValidator`, `TokenHeaderValidator`, `IssuerConfigCache.NO_ISSUER_CONFIG`), and
`quarkus.log` carried only two TokenSheriff WARNs for the whole run, both `TokenSheriff-104`
one-part-non-JWT — proving the logout token never reached `idBridge::validateRefreshedIdToken`, and
so disproving the standing hypothesis (audit item BFF-11) rather than confirming it.

BFF-11 was nonetheless confirmed as a **real latent defect** from the token-sheriff source:
`TokenType.ID_TOKEN` mandates `iss`/`exp`/`iat`/`sub`/`aud` and `AuthorizedPartyValidator` demands
`azp`, none of which the Back-Channel Logout spec requires — which makes `LogoutTokenValidator`'s own
documented sid-only path unreachable in production.

## Why this is candidate-lesson shaped

Two rules fell out, and both generalize past this endpoint:

1. **A security-relevant rejection path that logs only at DEBUG is unobservable in production and
   un-triageable in CI.** The flood concern on unauthenticated paths is real, which is why the fix
   was a *bounded-reason* WARN (`WARN.LOGOUT_TOKEN_REJECTED 112`) plus an INFO success marker, not
   blanket verbosity.
2. **Make the path observable first, then fix the seam.** The plan deliberately ordered TASK-14 as
   "emit the already-catalogued log records at default level → fix the verifier seam → re-run and
   read the new evidence", instead of fixing on the strength of a plausible hypothesis. The
   hypothesis turned out to be wrong.

A third, smaller rule: **do not clean the integration-tests target directory while a failure is
under triage** — the captured Keycloak/Quarkus/Failsafe logs are the discriminator.

## Candidate rule

Where a request is rejected on a security boundary, the rejection emits a bounded-cardinality WARN
carrying a reason code at default level. When an IT fails with no evidence, the first task is
observability, not the fix.

## Disposition in this plan

Fixed by TASK-014 (commit `6890527`): `LogoutRejection` / `LogoutRejectionLog`,
`SignatureOnlyTokenVerifier` replacing the ID-token verifier seam, catalogued
`INFO.BACKCHANNEL_LOGOUT 13` / `WARN.LOGOUT_TOKEN_REJECTED 112`, plus unit coverage for each.
