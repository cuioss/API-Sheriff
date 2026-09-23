# WS-02: Configurable Context Path

epic: deployment-configurability

## Charter

The gateway serves from the root context on the application port, and its management interface
serves health and metrics from the root of port 9000. Neither is configurable — no context-path
property appears anywhere in the repository. That is unusable behind an ingress routing by path
prefix. This workstream establishes HOW a context path is configured, researching the stock Quarkus
mechanism BEFORE writing anything bespoke, covers the management interface alongside the application
port as the operator required, and proves the result. Best case it is configuration, example and
documentation only; the stretch goal is that it is drivable from a documented environment variable.

## Scope

- In scope: the context-path configuration surface (`quarkus.http.root-path`,
  `quarkus.http.non-application-root-path`, and the management interface's own root path); the
  probe URLs in `start-integration-container.sh` and `deployment/compose-sample/scripts/wait-for-ready.sh`;
  any hard-coded management or application path in the integration tests and the benchmark stack;
  the compose environment wiring; and the deployment documentation.
- Out of scope: introducing a bespoke context-path property when a stock Quarkus one exists — the
  research deliverable settles that first. The management PORT, which ADR-0025 fixes as
  deployment-bound (`quarkus.management.port`) and explicitly refuses to accept from gateway.yaml.
  TLS concerns (WS-03). The health-check MECHANISM (WS-01) — this workstream only re-verifies it.

## Plans

| Plan | Status | Notes |
|------|--------|-------|
| PLAN-02-configurable-context-path | staged | Research, configure, de-hardcode, document and test a non-root context path incl. the management interface |

## Sequencing and Surface Notes

- Depends on PLAN-01: a context path can move the probe URL, so this workstream owes the
  re-verification of PLAN-01's probe under a non-root path.
- Overlaps PLAN-01 on both compose files, and WS-03's PLAN-03 on `application.properties`.
- Verification bar is graduated per the operator: stock Quarkus configuration ⇒ a documented manual
  test procedure suffices; anything needing more than configuration ⇒ an integration test is owed.
- ⚠ `start-integration-container.sh` builds its probe URL from a compose LABEL plus the published
  port, deliberately restating no service name or port. A context path must flow through that
  indirection rather than being hard-coded beside it.
