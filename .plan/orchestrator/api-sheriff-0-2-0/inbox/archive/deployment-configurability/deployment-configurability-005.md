envelope_version=1
sender_type=orchestrator
sender_id=deployment-configurability
epic=api-sheriff-0-2-0
kind=candidate-lesson
created=2026-09-23T15:48:24Z

# Candidate lesson (handed off from `deployment-configurability`'s local corpus): Keycloak strict rotation revokes the successor grant on detected refresh-token reuse, but the user session survives

Original lesson `2026-09-17-06-002` (component `integration-tests`, category `improvement`, created
2026-09-17).

## What happened

`BffRefreshReuseIT` pinned its expected Keycloak reuse verdict from documented strict-rotation
semantics ("only the replayed token is revoked, the successor keeps refreshing"). The first live
native run contradicted this. On Keycloak 26.5.7 with `revokeRefreshToken=true` and
`refreshTokenMaxReuse=0`: the replayed refresh token is refused AND the successor grant is revoked too
(the successor's next refresh gets 401), BUT the Keycloak user session created by login survives
(revocation is scoped to the refresh grant/client session, not a user-session logout).

## Candidate rule

Do not pin an IdP reuse-detection verdict from documentation alone — plan for the first live run to
confirm or flip it, and word the task so a flipped verdict is handled inside the task rather than as
a new fix task. Strict rotation detects a LATER reuse of an already-rotated token; it does not detect
the first redemption of a stolen token — operator docs and the threat model must keep that boundary.

## Integration-realm gotchas noted alongside this finding

`refresh-client` registers no `backchannel.logout.url` (server-mode back-channel push unreachable —
this specific gap was later fixed by PLAN-29/#348); the realm's custom client-scope set omits the
built-in `basic` scope, so access tokens carry no `sid` and the Keycloak session id must be found by
diffing the user's session list around each login.

## Status

The verdict is recorded in ADR-0046 and `doc/security-threat-model.adoc`. Test re-pinned to the
observed verdict in PLAN-26 (PR #314), reversion-proven.
