# WS-01: Container Health-Check for Docker-Only Deployment

epic: deployment-configurability

## Charter

The published main image — `api-sheriff/src/main/docker/Dockerfile.native`, distroless, the artifact
released to GHCR — ships without a `HEALTHCHECK`, so a docker-only operator gets no in-band health
signal from `docker ps`, `docker compose ps`, or a `depends_on: service_healthy` edge. The JFR image
already has one, and the integration stack works around the gap with a host-side readiness script.
This workstream gives the main image a minimal, shell-less-compatible health check, wires it through
both compose stacks, and proves it. It closes when `docker ps` reports a real health state for the
released image and a test asserts it.

## Scope

- In scope: `api-sheriff/src/main/docker/Dockerfile.native` and `Dockerfile.native.jfr`; the
  `healthcheck:` blocks for the gateway services in `integration-tests/docker-compose.yml` and its
  overrides; `deployment/compose-sample/docker-compose.yml` and `scripts/wait-for-ready.sh`; the
  integration-test assertion that the container reaches a healthy state; and the health documentation.
- Out of scope: the readiness/liveness ENDPOINTS themselves (`GatewayReadinessCheck` and the
  management interface) — they exist and are correct; this workstream consumes them. The upstream
  services' own healthcheck blocks (keycloak, toxiproxy, grpc-echo, passthrough-backend) stay untouched.
  Where the endpoints are MOUNTED is WS-02's concern.

## Plans

| Plan | Status | Notes |
|------|--------|-------|
| PLAN-01-distroless-health-check | staged | Give the distroless main image a working HEALTHCHECK and verify it end-to-end |

## Sequencing and Surface Notes

- Queue head. The only plan introducing a `HEALTHCHECK` line and a gateway `healthcheck:` block.
- Overlaps WS-02's PLAN-02 on both compose files. Sequenced, never parallel.
- Hard constraint: the distroless base ships neither a shell nor curl — stated in the Dockerfile
  header and restated in the compose file. The JFR image's `CMD echo -n '' > /dev/tcp/...` form is
  therefore NOT portable to it, which is the whole substance of the mechanism decision.
- The management interface is HTTPS-by-default on a single port (9000). Any probe that speaks HTTP
  to it must survive the `quarkus.tls.plain-management` opt-out being absent, and must not duplicate
  the scheme knowledge that `start-integration-container.sh` reads from the compose label.
