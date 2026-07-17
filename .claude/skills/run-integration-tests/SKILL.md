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

## Port & health map

| Service | Container | Host | Notes |
|---------|-----------|------|-------|
| api-sheriff HTTPS | 8443 | 10443 | app edge |
| api-sheriff mgmt | 9000 | 19000 | health + metrics (plain HTTP) |
| keycloak HTTPS | 8443 | 1443 | realms: `benchmark`, `integration` |
| keycloak mgmt | 9000 | 1090 | `KC_HEALTH_ENABLED` → `/health/ready` |
| go-httpbin | 8080 | 18080 | proxy upstream |

Health (management port `:19000`, plain HTTP):
- `curl -sf http://localhost:19000/q/health/live` — liveness (the startup wait uses this)
- `curl -s  http://localhost:19000/q/health/ready` — readiness; **this is the diagnostic goldmine** — the token-sheriff JWKS check attaches a per-issuer `withData` block naming the exact failure
- `curl -s  http://localhost:19000/q/health` — aggregate (503 if any check is DOWN)
- Keycloak: `curl -k https://localhost:1090/health/ready`

## Reading logs

- **File logging in the container is broken** (`/quarkus.log` permission-denied on the read-only FS). Do **not** rely on `integration-tests/target/quarkus.log`.
- Use `docker compose -f integration-tests/docker-compose.yml logs api-sheriff` for the app's real stdout (stack traces, config resolution).
- On a **CI** startup failure, `start-integration-container.sh` now dumps `docker compose logs api-sheriff` + `/q/health` into `integration-tests/target/failsafe-reports/` (`api-sheriff-app.log`, `api-sheriff-health.json`), which the workflow uploads as an artifact. Download with `gh run download <run-id> --repo cuioss/API-Sheriff` — the GitHub job log itself does NOT contain the app container stdout.

## Cleanup-before-native checklist

Native builds fail with GraalVM **exit 30** and Keycloak augmentation balloons (2.5 min+) when stale containers compete for CPU/memory:

```
docker compose -f integration-tests/docker-compose.yml down --remove-orphans
docker ps            # kill leftover native-build / keycloak containers from prior runs
```

Don't run parallel heavy builds. If the machine stays contended, prefer letting CI validate — its native build is reliable.

## token-sheriff extension gotchas (why the app may not go healthy)

- `token-sheriff-validation-quarkus` **requires** ≥1 enabled issuer or `TokenValidatorProducer` fails startup; its `JwksEndpointHealthCheck` (@Readiness) is DOWN until each issuer's loader reaches `LoaderStatus.OK`.
- An **HTTP** JWKS loader stays `UNDEFINED` until its first fetch completes; a **file** loader reaches `OK` synchronously.
- `jwks.file-path` is a **plain filesystem path** (`Files.readAllBytes(Path.of(path))`) — **no `classpath:` support**. Mount the JWKS file and reference its absolute path (the IT uses `/app/certificates/test-jwks.json`).
- The IT issuer config lives under a Quarkus `%it` profile in `api-sheriff/src/main/resources/application.properties`, activated by `QUARKUS_PROFILE=it` in `integration-tests/docker-compose.yml`.
