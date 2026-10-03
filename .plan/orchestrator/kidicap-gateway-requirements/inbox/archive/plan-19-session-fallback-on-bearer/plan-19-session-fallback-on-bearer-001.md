envelope_version=1
sender_type=plan
sender_id=plan-19-session-fallback-on-bearer
epic=kidicap-gateway-requirements
kind=finding
created=2026-09-24T09:49:11Z

# Finding: no per-request access log is enabled

Raised while refining PLAN-19 (`auth.session_fallback`). Deliverable 5 asked for the chosen auth
branch "in the access log", but the gateway has no access log switched on.

## Observed

- The gateway ships events and metrics, not its own access-log implementation. It relies on the
  Quarkus HTTP access log (`quarkus.http.access-log.*`), as `doc/plan/09-release-readiness.adoc`
  states.
- No `quarkus.http.access-log.*` setting exists anywhere in the repository (application
  properties, integration-test config, compose sample). Per-request access logging is therefore
  off by default and is not routed to the console.

## Scope decision in PLAN-19

The operator decided that PLAN-19 delivers only a new branch counter
(`sheriff_auth_branch_total{route,branch}`, recorded on `session_fallback` routes). The
access-log part of deliverable 5 is handed back here rather than implemented in PLAN-19.

## Asked of the orchestrator

Decide whether to stage a plan that enables and documents a console access log: default on/off,
format, redaction of sensitive headers and query strings. The auth branch would then be carried
as a field once that log exists.
