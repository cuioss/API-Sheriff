envelope_version=1
sender_type=plan
sender_id=plan-v02-12-idp-addressing-model
epic=api-sheriff-0-2-0
kind=finding
created=2026-10-13T10:54:32Z

# CRITICAL: the gateway removes every Cookie header before forwarding — operator ruling: this is a bug

**Severity: critical (operator ruling, 2026-10-13).** Surfaced by PLAN-V02-12's outline. The operator
ruled it a bug that PLAN-V02-12 must not fix: "We need to take care of cookies, eventually filter /
allow some, but removing all cookies is a bug."

## Observation

- `ConnectionHeaders.REQUEST_STRIP` removes `Cookie` from every proxied request without exception.
  ADR-0037 makes this absolute ("Cookie is absolute, no list re-admits it").
- Consequence: no upstream that relies on its own cookies can be proxied. The first case found was the
  `/auth`-fronted Keycloak variant (PLAN-V02-12 D3). Keycloak's browser login needs its own cookies
  (`AUTH_SESSION_ID`, `KC_RESTART`), so a login through the fronted `/auth/*` route cannot complete.

## Direction from the operator

Remove the blanket strip and replace it with deliberate cookie handling: per-route filtering or an
allowlist of upstream cookies. The gateway's own session, activity and CSRF cookies must never be
forwarded to an upstream. That needs an ADR-0037 amendment or a successor ADR.

## Effect on PLAN-V02-12

PLAN-V02-12 does nothing about this. D3 (the `/auth`-fronted Keycloak sample, its IT variant and the
topology redraw) leaves PLAN-V02-12 and depends on the cookie-handling fix. The orchestrator should
stage a plan for the fix and attach D3 to it or after it.
