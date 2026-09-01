---
name: run-integration-tests
description: How to build the native image and run/diagnose the API Sheriff Docker integration tests (integration-tests module). Use when running `verify -Pintegration-tests`, building the native executable, bringing up the docker-compose stack (Keycloak/go-httpbin), or debugging a native IT failure (app won't start, health 503, JWKS readiness DOWN). Covers the fast split-step diagnosis loop, the port/health map, reading container logs, and the cleanup-before-native checklist.
mode: knowledge
---

# Running & diagnosing the integration tests

The `integration-tests` module does a **native build → Docker image → `docker compose up` → Failsafe tests** cycle. It is slow (native compile ~5 min, plus Keycloak startup) and its failures are easy to misread. This skill captures the operational knowledge; the canonical build commands themselves live in `CLAUDE.md`.

## Canonical one-shot run

```
python3 .plan/execute-script.py plan-marshall:build-maven:maven run --command-args "verify -Pintegration-tests -pl integration-tests -am"
```

Use a 10-minute Bash timeout. On a loaded machine the whole cycle can exceed the executor's own timeout and tear the stack down before you learn anything — when diagnosing, **split the steps** (below) instead.

## Fast diagnosis loop (seconds per iteration, not minutes)

`Dockerfile.native` copies a **pre-built** `target/*-runner`, so build the native executable once and iterate on the stack directly:

1. Build native once (container build via the Mandrel image — needs Docker + ~12 GB):
   ```
   ./mvnw package -Pnative -pl api-sheriff -am -DskipTests
   ```
   Do **not** pipe a backgrounded build through `| tail` — it truncates the failure you need. The runner lands at `api-sheriff/target/*-runner`.
2. Bring the stack up yourself:
   ```
   docker compose -f integration-tests/docker-compose.yml up -d
   ```
3. Curl the health endpoints (below) and read `docker compose logs api-sheriff`. Re-run only step 3 while iterating.

## Two pointers out of this stack

- **A green run here is not evidence about browser-enforced controls.** These suites drive the gateway with RestAssured. `demo-client/doc/playwright-suite.adoc` states why that leaves a whole class of controls outside their reach, enumerates the class, and carries the Playwright suite that is the only coverage in this repository for it — a suite that gates nothing, since it runs on pushes to `main` and on demand but never on a pull request. Read it before concluding anything about that class from a green run here.
- **Need a subset of this stack rather than all of it?** `demo-client/scripts/start-dev-environment.sh` already carries the working invocation for a trimmed three-container bring-up. Do not reach for a Compose profile instead: the standing prohibition, with its reasoning, is in `demo-client/doc/playwright-suite.adoc`.

## Port & health map

| Service | Container | Host | Notes |
|---------|-----------|------|-------|
| api-sheriff HTTPS | 8443 | 10443 | app edge |
| api-sheriff mgmt | 9000 | 19000 | health + metrics (**HTTPS** — self-signed, always curl with `-k`) |
| api-sheriff-mtls mgmt | 9000 | 19001 | health + metrics for the mTLS instance (**HTTPS**, `-k`) |
| keycloak HTTPS | 8443 | 1443 | realms: `benchmark`, `integration` |
| keycloak mgmt | 9000 | 1090 | `KC_HEALTH_ENABLED` → `/health/ready` |
| go-httpbin | 8080 | 18080 | proxy upstream |

Health (management port `:19000`, **HTTPS**). The management interface has exactly one port, so
activating TLS converted 9000 itself — there is no plain-HTTP fallback. **`-k` is mandatory** (the
stack serves the self-signed localhost bundle); omitting it makes every probe below fail
certificate validation and look like a dead container:
The probe path is **composed**, never restated: the endpoint names below (`/health`, `/health/live`, `/health/ready`) are Quarkus' own fixed suffixes, but the prefix they hang from is the gateway's **management context path**, which is build-time fixed in the image and published host-side by each service's `de.cuioss.sheriff.management-root-path` compose label (see `doc/user/context-path.adoc`). Resolve it once, then compose:

```
MGMT=$(docker compose -f integration-tests/docker-compose.yml config --format json \
  | python3 -c 'import json,sys; print(json.load(sys.stdin)["services"]["api-sheriff"]["labels"]["de.cuioss.sheriff.management-root-path"])')
```

- `curl -skf "https://localhost:19000${MGMT}/health/live"` — liveness (the startup wait uses this)
- `curl -sk  "https://localhost:19000${MGMT}/health/ready"` — readiness; **this is the diagnostic goldmine** — the gateway's own `GatewayReadinessCheck` attaches `config`, one whole-validator `jwks` state, an `issuers` count, and on failure a single `error` string naming the exact cause (there is no per-issuer breakdown — the validator resolves as a unit)
- `curl -sk  "https://localhost:19000${MGMT}/health"` — aggregate (503 if any check is DOWN). This is also the `gatewayHealth` benchmark's target
- Keycloak: `curl -k https://localhost:1090/health/ready` — **no prefix**. Keycloak carries the root-path label for label-set uniformity only; it is inert there, and every consumer splits the IdP row out and keeps this literal

## Reading logs

- **File logging is deployment-supplied, and the shipped default is OFF.** The artifact ships `quarkus.log.file.enabled=false`; each gateway service switches it on with `QUARKUS_LOG_FILE_ENABLED=true` plus `LOG_FILE_PATH=/logs/<name>.log`, and `/logs` is bind-mounted to `${LOG_TARGET_DIR:-integration-tests/target/quarkus-logs}` (a dedicated subdirectory, writable by the container's uid 1001 — the container can NOT write `/quarkus.log` on the read-only root FS). A service missing the enable flag silently produces no file; `ItProfileConfigBindingWiringTest` guards the enable flag, and nothing more — the path and the `/logs` mount come from the Compose file and are not asserted there. `ManagementPlainHttpOptOutIT` reads one of those files.
- Use `docker compose -f integration-tests/docker-compose.yml logs api-sheriff` for the app's real stdout (stack traces, config resolution).
- On a **CI** startup failure, `start-integration-container.sh` now dumps `docker compose logs api-sheriff` + the aggregate health payload into `integration-tests/target/failsafe-reports/` (`api-sheriff-app.log`, `api-sheriff-health.json`), which the workflow uploads as an artifact. Download with `gh run download <run-id> --repo cuioss/API-Sheriff` — the GitHub job log itself does NOT contain the app container stdout.

## Cleanup-before-native checklist

Native builds fail with GraalVM **exit 30** and Keycloak augmentation balloons (2.5 min+) when stale containers compete for CPU/memory:

```
docker compose -f integration-tests/docker-compose.yml down --remove-orphans
docker ps            # kill leftover native-build / keycloak containers from prior runs
```

Don't run parallel heavy builds. If the machine stays contended, prefer letting CI validate — its native build is reliable.

## token-sheriff extension gotchas (why the app may not go healthy)

- **Issuers come from the mounted `gateway.yaml` only.** `application.properties` configures no issuer in any profile — it carries no `%`-profile entry at all — so `QUARKUS_PROFILE=it` switches nothing on in the shipped artifact; it only marks the instance. The request-path validator is built from `gateway.yaml`'s `token_validation` block by `TokenValidatorProducer`.
- **The extension's parallel probes are not in the payload.** `JwksEndpointHealthCheck` and `TokenValidatorHealthCheck` report on the token-sheriff extension's own unqualified validator, fed by its `sheriff.token.issuers.*` namespace, which this gateway never populates. Both are removed from the bean set by `quarkus.arc.exclude-types`, so do not go looking for their `withData` blocks in the readiness payload.
- An **HTTP** JWKS loader stays `UNDEFINED` until its first fetch completes; a **file** loader reaches `OK` synchronously. **Do not wait for that to surface in the readiness payload** — with the extension probes excluded, nothing polls `JwksLoader#getLoaderStatus()` any more. `GatewayReadinessCheck` reports on the validator being *constructible*, which is settled at boot and then cached, so an unreachable IdP leaves readiness **UP** and shows up only as 401s on the request path plus fetch errors in the container stdout. Diagnose it from `docker compose logs`, never from a green readiness probe.
- A JWKS file source is a **plain filesystem path** (`Files.readAllBytes(Path.of(path))`) — **no `classpath:` support**. Mount the JWKS file and reference its absolute path.
- **A named `jwks.tls_profile` must be bound by the deployment.** The compose services load `/app/certificates/benchmark-idp-trust.properties` through `QUARKUS_CONFIG_LOCATIONS`; drop that and boot aborts in `JwksTrustProfileResolver.resolve()` rather than falling back to default trust. A profile bound to a bucket that carries no trust material is refused the same way.
