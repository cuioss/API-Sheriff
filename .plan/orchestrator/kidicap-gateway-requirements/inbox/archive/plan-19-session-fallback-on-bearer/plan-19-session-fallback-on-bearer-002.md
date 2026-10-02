envelope_version=1
sender_type=plan
sender_id=plan-19-session-fallback-on-bearer
epic=kidicap-gateway-requirements
kind=finding
created=2026-09-24T15:34:07Z

# Finding: PLAN-23 must also cover the session branch of `session_fallback` routes

Raised by the PLAN-19 finalize security audit, and routed to PLAN-23 by operator decision.

## Observed

PLAN-19 adds `auth.session_fallback` on `require: bearer` routes. On such a route:

- the BEARER branch enforces `neededScopes` (`403 insufficient_scope`);
- the SESSION branch runs `SessionAuthenticationStage` unchanged, so no scope check runs. Any live
  gateway session is admitted, including one from a login elsewhere that never requested the route's
  scopes.

This is the same asymmetry PLAN-23 closes for `require: session` routes. Because the session branch is
exactly `SessionAuthenticationStage`, PLAN-23's fix also covers `session_fallback` routes, with no extra
mechanism needed.

## What PLAN-19 shipped meanwhile

The caveat is stated explicitly, with no code change, in:

- `doc/configuration.adoc`, the `_auth_session_fallback` SESSION branch row;
- `doc/user/bff-session.adoc`, the session-fallback behaviour table ("No `Authorization`, live session");
- `doc/security-threat-model.adoc`, BFF-16 "Nothing new is accepted".

Each tells operators not to enable `session_fallback` on a route whose scopes are its authorization
boundary for browser users.

## Asked of the orchestrator (for PLAN-23's re-grounding)

1. Add a `session_fallback` route's session branch to PLAN-23's acceptance tests. The integration
   route `bff-session-fallback` in `integration-tests/src/main/docker/sheriff-config/endpoints/bff-scoped.yaml`
   already carries `scopes: ["sheriff_it_endpoint"]` and is a ready fixture.
2. When PLAN-23 lands, remove or rewrite the three caveats above, since they will then be false.
3. Note that PLAN-23 was staged to run before PLAN-19, but PLAN-19 landed first. PLAN-23 now overlaps
   PLAN-19's `SessionAuthenticationStage`-adjacent surface (`AuthenticationStage` branch dispatch,
   `AuthBranch`) and docs, so re-ground it against PLAN-19's merge.
