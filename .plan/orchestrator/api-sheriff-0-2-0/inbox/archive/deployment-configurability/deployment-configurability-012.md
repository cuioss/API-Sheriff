envelope_version=1
sender_type=orchestrator
sender_id=deployment-configurability
epic=api-sheriff-0-2-0
kind=candidate-lesson
created=2026-09-23T15:48:26Z

# Candidate lesson (handed off from `deployment-configurability`'s local corpus): A DEBUG-only security rejection path made a failing test unexplainable

Original lesson `2026-09-23-07-006` (component `api-sheriff`).

## What happened

`BffBackchannelLogoutIT.idpInitiatedBackchannelLogoutDestroysTheGatewayHeldSession` stayed red after
an initial fix: the request reached the receiver's route, but the gateway-held session was not
destroyed within the 15s budget. Every branch of
`BackchannelLogoutEndpoint -> BackchannelLogoutReceiver -> LogoutTokenValidator` logged at DEBUG
only, so the captured run logs carried zero back-channel evidence — a failing path that emits
nothing at default level is indistinguishable from a path that was never reached. The triage that
broke the deadlock used a negative result: every rejection route in the engine's ID-token pipeline
logs at WARN, and the run's logs carried none of those either, proving the logout token never
reached the ID-token validation path — disproving the standing hypothesis rather than confirming it.
A real latent defect was nonetheless confirmed from the token-sheriff source: the wrong verifier
(one requiring ID-token-only claims) was being applied to logout tokens, making the documented
sid-only path unreachable in production.

## Candidate rule

A security-relevant rejection path that logs only at DEBUG is unobservable in production and
un-triageable in CI — the fix is a bounded-reason WARN plus an INFO success marker, not blanket
verbosity. Make the path observable first, then fix the seam: order work as "emit the already-
catalogued log records at default level -> fix the verifier seam -> re-run and read the new
evidence", instead of fixing on the strength of a plausible hypothesis. Also: do not clean the
integration-tests target directory while a failure is under triage — the captured logs are the
discriminator.

## Source

`deployment-configurability` PLAN-28 (PR #341), Q-Gate finding `2d0dc6` (phase 5-execute, resolution
`fixed`). Fixed via `LogoutRejection`/`LogoutRejectionLog`, a new `SignatureOnlyTokenVerifier`
replacing the ID-token verifier seam, and catalogued `INFO.BACKCHANNEL_LOGOUT`/`WARN.LOGOUT_TOKEN_REJECTED`
log records with unit coverage.
