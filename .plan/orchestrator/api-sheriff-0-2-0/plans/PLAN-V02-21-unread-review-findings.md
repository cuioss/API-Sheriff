# PLAN-V02-21: Act on Review Findings That Were Never Read Before Merge

epic: api-sheriff-0-2-0
workstream: WS-05

> Staged plan spec — one shippable unit of work, ready for `/plan-marshall` hand-off.
> The orchestrator EMITS the command below; it never launches the plan inline.
> Source: review findings on PRs #100, #118, #147 and #199 that no one read before those PRs
> merged, surfaced by a telemetry comparison on 2026-10-06 and handed to the orchestrator by the
> operator. Each was checked against `origin/main` at `7f6375a5`; the result is below.

## Objective

Fix the review findings that still hold on `main`, and record an explicit decline with its reason
for the parts that do not. The fixes are small and local: one log level in the cookie-mode session
binding, the integration start script's polling loops and literals, three properties of the
integration compose stack, the image name in one integration test, and two claims in ADR-0031.

## Findings and their dispositions

| # | Finding (source PR) | Checked on `main` | Disposition |
|---|---|---|---|
| 1 | The start script prints the Keycloak URL with a literal port while the health URL is derived from the compose model (#147, CodeRabbit "Derive the Keycloak public target too") | **Holds.** `start-integration-container.sh` prints `https://localhost:1443/auth`, while `KEYCLOAK_HEALTH_URL` comes from the compose discovery block | **Fix** — D2 |
| 2 | `COOKIE_SESSION_SEALED` logged at INFO on every refresh, not only at login (#118) | **Holds.** `SealedSessionCookieCodec` logs `BffLogMessages.INFO.COOKIE_SESSION_SEALED` inside `seal`, and `CookieSessionBinding` reaches `seal` from both `bind` (login) and `persist` (refresh, widening) | **Fix** — D1 |
| 3 | Keycloak retry count is a literal `120`; polling loops duplicated, no `wait_for_url` helper (#147, #100) | **Holds.** `start-integration-container.sh` has three near-identical loops: Keycloak (`120` attempts), go-httpbin (`30`), nginx-static (`30`) | **Fix** — D2 |
| 4a | `api-sheriff` `depends_on` lacks `passthrough-backend` and `toxiproxy` (#100) | **Holds.** It depends on `keycloak`, `go-httpbin`, `asset-origin` and `grpc-echo` only, and the start script waits for neither missing service | **Fix** — D3, after confirming which gateway services route to them |
| 4b | `passthrough-backend` runs `apk add` and generates its certificate at container start (#100) | **Holds in part.** Generating the certificate at start is a documented, deliberate choice (the compose comment explains why it differs from `mismatched-tls-backend`). The `apk add` is not: it needs network access at every start | **Fix the package install, keep the generation** — D3 |
| 4c | No shared hardening anchor (#100) | **Holds.** `security_opt: no-new-privileges`, `cap_drop` and `read_only` are repeated on all 12 gateway services | **Fix** — D3 |
| 5a | `"mTLS enabled"` is an ad-hoc `LOGGER.debug` (#100) | **Holds, but is compliant.** The project rule asks for a `LogRecord` at INFO, WARN and ERROR only; a DEBUG line may be ad hoc | **Declined** — no rule is broken. Raising the boot-time mTLS posture to an INFO `LogRecord` is a separate operator choice |
| 5b | Test keystores such as `mtls-client.p12` are committed (#100) | **Holds, and is deliberate.** They are integration-test fixtures under `integration-tests/src/main/docker/`; `mismatched-tls-backend` must chain to anchors the committed `upstream-truststore.p12` holds, which generated material would not. None of them reaches a shipped artifact | **Declined** — test-only fixtures by design |
| 1b | ADR-0031 says the whole probe target is derived and "no host […] is restated in the script", while the scripts build every probe URL on the literal host `localhost` (#147, CodeRabbit "Narrow the probe-URL derivation claim") | **Holds.** `start-integration-container.sh` builds `KEYCLOAK_HEALTH_URL` and `GATEWAY_MGMT_URL` on `localhost`; `demo-client/scripts/start-dev-environment.sh` and `deployment/compose-sample/scripts/start-sample.sh` do the same. The go-httpbin and nginx-static waits also restate their published ports (`18080`, `18081`) | **Fix** — D5: the code is right and the ADR is narrowed. A host-side probe runs on the Docker host, and the management interfaces serve a bundle issued for `localhost`, so the host is a fixed invariant, not a derivation target |
| 1c | ADR-0031's retry-budget wording does not match the script's literal `120` and `30` (#147, CodeRabbit "Align the retry-budget rule with the implementation") | **Holds** as part of finding 3 | **Fix** — D2 names one constant per gate, D5 makes the ADR say so |
| 6 | `ImageMetadataIT` hardcodes the image name `api-sheriff:distroless` (#199) | **Holds.** `IMAGE = "api-sheriff:distroless"`, while the start script selects the image from the declared `SHERIFF_IMAGE_TYPE` (`distroless` or `jfr`) | **Fix** — D4 |

## Deliverables

1. **Log a cookie-mode seal at INFO only at login.** `COOKIE_SESSION_SEALED` stays an INFO
   `LogRecord` for the seal that `bind` performs; the re-seal on `persist` (refresh, widening) logs at
   DEBUG or not at all. Update the entry in `doc/LogMessages.adoc` so it says when the record fires,
   and add a test that a refresh produces no INFO record.
2. **One polling helper in `start-integration-container.sh`.** Replace the three loops with a single
   `wait_for_url` function taking the URL, the curl options, the attempt count, a label and the
   service name for the log hint. Name the attempt counts (Keycloak `120`, the backends `30`) as
   variables at the top of the script. Behaviour, messages and exit codes stay as they are; the
   Keycloak probe keeps its `-f` and certificate options. Derive the printed Keycloak URL from the
   same compose discovery the health URL uses, instead of the literal port `1443`. Derive the
   go-httpbin and nginx-static published ports from the compose model too, instead of the literals
   `18080` and `18081`.
3. **Three compose fixes in `integration-tests/docker-compose.yml`.**
   - Add `passthrough-backend` and `toxiproxy` to the `depends_on` of every gateway service whose
     routes reach them, with `condition: service_healthy` (both have healthchecks). Find the services
     from their `gateway.yaml` overlays and topology, not by assumption.
   - Remove the start-time `apk add` from `passthrough-backend`: use an image that already carries
     `openssl`, or build a tiny fixture image. Keep generating the certificate at start, as the
     compose comment intends.
   - Factor the repeated hardening keys into one YAML extension anchor (for example
     `x-gateway-hardening`) merged into each gateway service. **Check first** that every reader of
     this file still sees the merged keys: `ItProfileConfigBindingWiringTest` and other tests parse
     the compose file, and a YAML parser without merge-key support would read the anchor as missing
     hardening. If a reader does not resolve merge keys, fix the reader or keep the keys repeated and
     say why.
4. **`ImageMetadataIT` inspects the image the lane built.** Take the image name from the same
   declaration the start script uses (`SHERIFF_IMAGE_TYPE`, passed as a system property or
   environment variable from `integration-tests/pom.xml`), so the test cannot inspect a stale image
   of the other type. Keep the failure messages naming the image.
5. **Make ADR-0031 say what the scripts do.** Amend it in place (summary line, title if needed, and
   the "Derive the whole probe target" rule): the scheme, the published port and the management
   context path are derived from the compose model; the host is the fixed host-side invariant
   `localhost`, and say why (the probe runs on the Docker host, and the management interfaces serve a
   bundle issued for `localhost`). Align the retry-budget wording with D2: one named constant per
   gate, not one constant for every site. Check that all three bring-up scripts match the amended
   text.

## Claim Labels

- OBSERVED: `SealedSessionCookieCodec` logs `COOKIE_SESSION_SEALED` at INFO in `seal`, and `CookieSessionBinding` calls `seal` from both `bind` and `persist` — read at `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/bff/cookie/SealedSessionCookieCodec.java` § `seal` and `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/bff/cookie/CookieSessionBinding.java` § `seal`, on `origin/main` at `7f6375a5`
  - verdict: corroborated | checked_at: 386f3f74094516d787f06dca0946825c62421b89 | by: api-sheriff-0-2-0/cleanup | rescoped: n/a | evidence: SealedSessionCookieCodec.seal logs COOKIE_SESSION_SEALED at INFO; CookieSessionBinding.seal is called from bind and persist; no test asserts the record
- OBSERVED: `start-integration-container.sh` repeats one polling loop three times with literal attempt counts — read at `integration-tests/scripts/start-integration-container.sh` § the Keycloak, go-httpbin and nginx-static waits
  - verdict: corroborated | checked_at: 386f3f74094516d787f06dca0946825c62421b89 | by: api-sheriff-0-2-0/cleanup | rescoped: n/a | evidence: start-integration-container.sh has three wait loops (120, 30, 30) with literals repeated in messages and no shared helper
- OBSERVED: the `api-sheriff` service's `depends_on` names `keycloak`, `go-httpbin`, `asset-origin` and `grpc-echo` only, and `passthrough-backend` runs `apk add --no-cache openssl` at start — read at `integration-tests/docker-compose.yml` § `api-sheriff` and § `passthrough-backend`
  - verdict: corroborated | checked_at: 386f3f74094516d787f06dca0946825c62421b89 | by: api-sheriff-0-2-0/cleanup | rescoped: n/a | evidence: api-sheriff depends_on lists keycloak, go-httpbin, asset-origin, grpc-echo only; passthrough-backend runs apk add openssl at start
- HYPOTHESIS: some gateway services route to `passthrough-backend` or `toxiproxy` at test time, so starting them first matters — confirm/refute at `integration-tests/src/main/docker/sheriff-config*/gateway.yaml` § passthrough and upstream targets (verify-at-outline)
  - verdict: corroborated | checked_at: 386f3f74094516d787f06dca0946825c62421b89 | by: api-sheriff-0-2-0/cleanup | rescoped: n/a | evidence: base topology.properties points at passthrough-backend:8443 and toxiproxy; egress-verify-on/off already depend on passthrough-backend service_healthy
- HYPOTHESIS: every parser of the compose file in the test code resolves YAML merge keys — confirm/refute at `integration-tests/src/test/java/de/cuioss/sheriff/gateway/integration/ItProfileConfigBindingWiringTest.java` § its compose reader (verify-at-outline)
  - verdict: corroborated | checked_at: 386f3f74094516d787f06dca0946825c62421b89 | by: api-sheriff-0-2-0/cleanup | rescoped: n/a | evidence: compose readers in integration-tests use SnakeYAML; the script uses docker compose config; docker-compose.yml has no merge key today
- OBSERVED: ADR-0031 states that "no host and no *published* host port is restated in the script", while the three bring-up scripts build every probe URL on `localhost` and the backend waits restate `18080` and `18081` — read at `doc/adr/0031-Host-side_readiness_gates_derive_the_probe_URL_from_the_resolved_Compose_model_and_assert_readiness.adoc` § the derivation rule, `integration-tests/scripts/start-integration-container.sh` § `KEYCLOAK_HEALTH_URL` and the backend waits, `demo-client/scripts/start-dev-environment.sh` § `IDP_HEALTH_URL`, `deployment/compose-sample/scripts/start-sample.sh` § `MGMT_URL`
  - verdict: corroborated | checked_at: 386f3f74094516d787f06dca0946825c62421b89 | by: api-sheriff-0-2-0/cleanup | rescoped: n/a | evidence: ADR-0031 says no host or published port is restated in the script; the three start scripts use localhost and literal ports
- OBSERVED: `ImageMetadataIT` declares `IMAGE = "api-sheriff:distroless"` while the start script resolves the image from `SHERIFF_IMAGE_TYPE` — read at `integration-tests/src/test/java/de/cuioss/sheriff/gateway/integration/ImageMetadataIT.java` § `IMAGE` and `integration-tests/scripts/start-integration-container.sh` § `resolve_image_tag`
  - verdict: corroborated | checked_at: 386f3f74094516d787f06dca0946825c62421b89 | by: api-sheriff-0-2-0/cleanup | rescoped: n/a | evidence: ImageMetadataIT IMAGE is api-sheriff:distroless; the start script resolves the image from SHERIFF_IMAGE_TYPE

## Expected Surface

- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/bff/cookie/SealedSessionCookieCodec.java`, `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/bff/cookie/CookieSessionBinding.java` — D1
- OBSERVED: `doc/LogMessages.adoc` — D1
- OBSERVED: `api-sheriff/src/test/java/de/cuioss/sheriff/gateway/bff/cookie/` — D1's test
- OBSERVED: `integration-tests/scripts/start-integration-container.sh` — D2
- OBSERVED: `integration-tests/docker-compose.yml` — D3
- HYPOTHESIS: `integration-tests/src/test/java/de/cuioss/sheriff/gateway/integration/ItProfileConfigBindingWiringTest.java` — D3, only if its compose reader needs merge-key support (verify-at-outline)
- OBSERVED: `integration-tests/src/test/java/de/cuioss/sheriff/gateway/integration/ImageMetadataIT.java`, `integration-tests/pom.xml` — D4
- OBSERVED: `integration-tests/src/test/java/de/cuioss/sheriff/gateway/integration/ImageMetadataJfrIT.java` — D4, the `-Pjfr` counterpart the pinned image name has to be reconciled with
- OBSERVED: `integration-tests/scripts/lib-docker-compose.sh`, `demo-client/scripts/start-dev-environment.sh`, `deployment/compose-sample/scripts/start-sample.sh` — D5 checks all three start scripts against ADR-0031
- OBSERVED: `doc/adr/0031-Host-side_readiness_gates_derive_the_probe_URL_from_the_resolved_Compose_model_and_assert_readiness.adoc` — D5

## Dependencies and Sequencing

- Depends on: none.
- Overlaps with: `PLAN-V02-01` (its D7 audits `bff/**`, including the cookie binding) and
  `PLAN-V02-20` (the cookie key), and `PLAN-V02-04` (it audits the ADR corpus D5 edits). The
  disjointness gate decides at emit time; `PLAN-V02-01` runs alone
  in any case.
- `docker-compose*.yml` is a gate-requiring path, so D3 runs the full pre-commit process, including
  `-Pintegration-tests`.

## Hand-Off Command

```text
/plan-marshall task="implement .plan/orchestrator/api-sheriff-0-2-0/plans/PLAN-V02-21-unread-review-findings.md" plan_id=plan-v02-21-unread-review-findings
```

## Write-Boundary

The plan implementing this spec touches only its own repository source and tests. It creates and
edits NO file under `.plan/orchestrator/` other than its own `inbox/{sender}-{seq}` message, and
reports its outcome through its PR and that message.
