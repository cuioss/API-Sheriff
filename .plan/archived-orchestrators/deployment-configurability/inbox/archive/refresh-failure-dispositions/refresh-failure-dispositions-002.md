envelope_version=1
sender_type=plan
sender_id=refresh-failure-dispositions
epic=deployment-configurability
kind=candidate-lesson
created=2026-09-17T03:49:07Z

component=integration-tests
category=improvement

# Keycloak strict rotation revokes the successor grant on detected refresh-token reuse, but the user session survives

## What happened

TASK-7 (`BffRefreshReuseIT`) pinned its expected Keycloak reuse verdict from the documented strict-rotation semantics: "only the replayed token is revoked, the successor keeps refreshing". The first live native run contradicted this. On Keycloak 26.5.7 with `revokeRefreshToken=true` and `refreshTokenMaxReuse=0`:

- the replayed refresh token is refused, AND
- the successor grant is revoked too (the successor's next refresh gets 401 and the gateway logs ApiSheriff-111 credential-rejected), BUT
- the Keycloak USER session created by the login survives (`GET /admin/realms/{realm}/users/{id}/sessions` still lists it). Revocation is scoped to the refresh grant / client session, not a user-session logout.

The test was re-pinned to the observed verdict (`replayedCookieEndsItsSessionAndRevokesTheSuccessorGrantButNotTheUserSession`), and the reversion runs proved each assertion can fail.

## Rule

- Do not pin an IdP reuse-detection verdict from documentation alone. Plan for the first live run to confirm or flip it, and word the task so a flipped verdict is handled inside the task rather than as a new fix task.
- Strict rotation detects a LATER reuse of an already-rotated token. It does not detect the first redemption of a stolen token. Claims in operator docs and the threat model must keep that boundary (see TASK-18).
- Integration-realm gotchas this suite hit: `refresh-client` registers no `backchannel.logout.url`, so a server-mode back-channel push is unreachable. Because the realm imports a custom client-scope set without the built-in `basic` scope, the access token carries no `sid`, so the Keycloak session id must be found by diffing the user's session list around each login.

## Coverage note

The verdict is recorded in ADR-0046 and `doc/security-threat-model.adoc`, which this plan authored. The lessons corpus does not carry it. The orchestrator decides whether the ADR is enough.

## Evidence

- Plan refresh-failure-dispositions, TASK-7 / TASK-18; native IT jobs 245291db (first run, successor got 401) and 2ead9378 (green after re-pin), reversion job abe890bd.
