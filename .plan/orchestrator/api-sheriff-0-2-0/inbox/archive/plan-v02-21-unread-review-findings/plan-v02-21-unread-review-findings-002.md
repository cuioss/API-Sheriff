envelope_version=1
sender_type=plan
sender_id=plan-v02-21-unread-review-findings
epic=api-sheriff-0-2-0
kind=candidate-lesson
created=2026-10-13T14:14:39Z

# Candidate lesson: new discovery rows leak into a negatively-filtered readiness set

**Source signal:** Q-Gate finding `5a1f11` (phase 3-outline, severity warning, resolution fixed in-run). File: `integration-tests/scripts/start-integration-container.sh`

## What happened

Deliverable 2 added role-tagged port rows (Keycloak 8443, go-httpbin 8080, nginx-static 8080) to the same discovery output the script already parses, and claimed the gateway rows stayed byte-compatible. But `start-integration-container.sh:268` built `READINESS_TARGETS` as every row NOT starting with `keycloak ` (`grep -v`). That set feeds `GATEWAY_SERVICES` for `up -d --wait`, the readiness loop and the URL banner. Any new row would have been treated as a gateway service and broken the wait. The outline's "byte-compatible" claim covered the existing rows but not what the existing consumers do with the added ones.

## Fix applied in-run

D2 now emits port rows as `port <service> <container_port> <published>` and builds `READINESS_TARGETS` by positive selection (`grep ^api-sheriff`, the same prefix as the Python `GATEWAY_PREFIX`). No port row reaches `GATEWAY_SERVICES`, `up -d --wait`, the diagnostics/readiness loops or the URL banner. `KEYCLOAK_TARGET` (`grep ^keycloak`) cannot match a port row, and port lookups use awk so they stay `set -e` safe when nginx-static is absent.

## Candidate rule

When adding rows to an output that downstream consumers filter by exclusion (`grep -v`), check every exclusion filter. Prefer positive selection on a stable prefix, or a separate output block. A claim that "existing rows are unchanged" does not show that consumers are unaffected.
