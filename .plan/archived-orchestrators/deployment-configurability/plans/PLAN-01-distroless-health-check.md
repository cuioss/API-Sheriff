# PLAN-01: Minimal Container Health-Check for the Distroless Main Image

epic: deployment-configurability
workstream: WS-01

> Staged plan spec — the emitted command is a one-line pointer, so this spec is the whole brief.

## Objective

`api-sheriff/src/main/docker/Dockerfile.native` — the distroless native image published to GHCR, the
artifact an operator actually deploys — ships with no `HEALTHCHECK`, so `docker ps`, `docker compose
ps` and `depends_on: service_healthy` all report nothing for it. The JFR image has one; the
integration stack papers over the gap with a host-side readiness script. Give the main image a
minimal health check that works in a shell-less image, wire it through both compose stacks and the
shipped sample, and prove it. Keep it minimal: this is a Docker-level serving signal, not a second
implementation of `GatewayReadinessCheck`.

## Deliverables

1. **Decide the mechanism, then implement it.** The distroless base ships neither a shell nor curl,
   so the JFR image's `CMD echo -n '' > /dev/tcp/127.0.0.1/8443` form is not portable. Settle on a
   mechanism that works shell-less and record why — candidates include a `HEALTHCHECK CMD` invoking
   the native binary itself with a probe flag, a statically-linked probe binary added to the image,
   a base-image change, or accepting a compose-level `healthcheck:` where a Dockerfile-level one is
   impossible. It must not enlarge the attack surface the distroless base exists to shrink.
   ⚠ **A standing counter-claim must be refuted, not ignored.**
   `deployment/compose-sample/docker-compose.yml:209-211` already argues the opposite conclusion in
   the shipped operator-facing sample: *"NO healthcheck: key. The distroless image ships neither a
   shell nor curl, so every in-container probe form is unavailable by construction — a healthcheck
   here could only ever fail."* That is stronger than this plan's premise: it asserts the whole
   mechanism space is empty, not merely that the JFR form is unportable. Deliverable 1 must either
   **refute it with a working built image** or **adopt it** and fall back to the compose-level route —
   and whichever way it lands, that comment is rewritten to say what is actually true.
2. **Add the health check to `Dockerfile.native`** and **align `Dockerfile.native.jfr`** so the two
   images report health the same way rather than diverging.
3. **Wire the gateway services' `healthcheck:` blocks** into `integration-tests/docker-compose.yml`
   and `deployment/compose-sample/docker-compose.yml`, replacing the standing "no in-container
   healthcheck" comment. Match the interval/timeout/retries shape already used by the `grpc-echo` and
   `toxiproxy` services.
   ⚠ **Surface corrected 2026-08-27 (was understated).** The compose file defines **seven** gateway
   services at HEAD, not two, and each carries its own copy of the "No in-container healthcheck"
   comment (seven occurrences): `api-sheriff` (:166), `api-sheriff-mtls` (:357), `api-sheriff-cookie`
   (:453), `api-sheriff-cookie-2` (:557), `api-sheriff-ws-admission` (:642), `api-sheriff-plain-mgmt`
   (:735) and `api-sheriff-passthrough-empty` (:847). A mechanism covering two of seven leaves five
   instances silently un-probed.
   ⚠ **Two management schemes, not one.** `api-sheriff-plain-mgmt` carries
   `de.cuioss.sheriff.management-scheme: "http"` (:750) while the other six carry `"https"`. The probe
   must work in both, and must read the scheme from that label rather than branching on the service
   name — the compose comment at :819-822 states exactly this.
4. **Verify it** — assert in the integration run that the gateway container reaches a healthy state,
   so a broken health check fails the build rather than being cosmetic. Reconcile with
   `start-integration-container.sh`: decide whether the host-side readiness gate stays, is replaced,
   or becomes a fallback, and say which.
5. **Document it** — a docker-only deployment section covering the container-level check alongside
   the management-interface endpoints, in the health/deployment documentation and the compose-sample
   README path.

## Claim Labels

- OBSERVED: `Dockerfile.native` contains no `HEALTHCHECK`; it ends `EXPOSE 8443 9000` / `USER nonroot`
  / `ENTRYPOINT ["/app/application", "-Dquarkus.http.host=0.0.0.0"]` — read at
  `api-sheriff/src/main/docker/Dockerfile.native`.
  - verdict: contradicted | checked_at: cc10ce2a2c7840162bbc087242d42cbbc3cd14c3 | by: deployment-configurability/cleanup | rescoped: no | evidence: Dockerfile.native now HAS HEALTHCHECK CMD ["/app/application","--health-probe"] at :65-66; ending is unchanged (USER nonroot/ENTRYPOINT)
- OBSERVED: its header comment states *"Distroless: no shell, no package manager — minimal attack
  surface"* and *"Health probes via the management interface on port 9000"* — read at the same file,
  lines 1-8. The shell-absence is a STATED FACT here, not an inference.
  - verdict: corroborated | checked_at: cc10ce2a2c7840162bbc087242d42cbbc3cd14c3 | by: deployment-configurability/cleanup | rescoped: n/a | evidence: header lines 1-8 still carry both quoted phrases verbatim
- OBSERVED: `Dockerfile.native.jfr` is built `FROM quay.io/quarkus/ubi9-quarkus-micro-image:2.0` whose
  comment reads *"UBI micro base (has shell, needed for HEALTHCHECK and JFR output)"*, and carries
  `HEALTHCHECK --interval=15s --timeout=5s --start-period=15s --retries=3 CMD echo -n '' >
  /dev/tcp/127.0.0.1/8443 2>/dev/null || exit 1` — read at that file, lines 2-4 and 33-34.
  - verdict: contradicted | checked_at: cc10ce2a2c7840162bbc087242d42cbbc3cd14c3 | by: deployment-configurability/cleanup | rescoped: no | evidence: Dockerfile.native.jfr now ships the same --health-probe mechanism as Dockerfile.native, not the /dev/tcp form; header comment no longer says "needed for HEALTHCHECK and JFR output"
- OBSERVED: `integration-tests/docker-compose.yml` states *"Health check: no in-container healthcheck
  (the distroless image ships neither a shell nor curl). Readiness is gated host-side by
  start-integration-container.sh probing the published management port 19000 over HTTPS with -k."* —
  read at that file § the `api-sheriff` service.
  - verdict: contradicted | checked_at: cc10ce2a2c7840162bbc087242d42cbbc3cd14c3 | by: deployment-configurability/cleanup | rescoped: no | evidence: integration-tests/docker-compose.yml:408-413 now reads "Health check: inherited from the image, and deliberately not restated here" - the quoted "no in-container healthcheck...gated host-side" text is gone
- OBSERVED: sibling services DO define `healthcheck:` blocks — `grpc-echo` uses
  `["CMD-SHELL", "bash -c 'echo -n > /dev/tcp/127.0.0.1/9000'"]`, `toxiproxy` uses
  `["CMD", "/toxiproxy-cli", "list"]` — read at the same file.
  - verdict: corroborated | checked_at: cc10ce2a2c7840162bbc087242d42cbbc3cd14c3 | by: deployment-configurability/cleanup | rescoped: n/a | evidence: toxiproxy healthcheck and grpc-echo checks both still present
- OBSERVED: the gateway publishes `10443:8443` (application) and `19000:9000` (management) — read at
  the same file § `ports`.
  - verdict: corroborated | checked_at: cc10ce2a2c7840162bbc087242d42cbbc3cd14c3 | by: deployment-configurability/cleanup | rescoped: n/a | evidence: integration-tests/docker-compose.yml:285-286 still publishes 10443:8443 and 19000:9000
- OBSERVED: `start-integration-container.sh` derives each instance's probe URL from the
  `de.cuioss.sheriff.management-scheme` compose LABEL plus the host port published against container
  port 9000, and the compose comment states the script *"must never infer it from the service name"* —
  read at the same file § `labels`.
  - verdict: corroborated | checked_at: cc10ce2a2c7840162bbc087242d42cbbc3cd14c3 | by: deployment-configurability/cleanup | rescoped: n/a | evidence: start-integration-container.sh:115,138-139 still derives scheme/root-path from the two labels, never the service name
- OBSERVED: the management interface is HTTPS by default on a SINGLE port — Quarkus' ManagementConfig
  declares no `ssl-port` and no `insecure-requests` — with `quarkus.tls.plain-management` the only
  sanctioned plain-HTTP opt-out — read at `api-sheriff/src/main/resources/application.properties`
  § the management block.
  - verdict: corroborated | checked_at: cc10ce2a2c7840162bbc087242d42cbbc3cd14c3 | by: deployment-configurability/cleanup | rescoped: n/a | evidence: application.properties:144-148 still states ManagementConfig has no ssl-port/insecure-requests, quarkus.tls.plain-management.reload-period at :193
- HYPOTHESIS: the native binary can serve a self-probe mode (a flag that exits 0/1 against its own
  port) without a second process — confirm/refute at `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/ApiSheriffApplication.java`
  § its main/entrypoint handling (verify-at-outline). This is the load-bearing claim for deliverable 1.
  - verdict: corroborated | checked_at: cc10ce2a2c7840162bbc087242d42cbbc3cd14c3 | by: deployment-configurability/cleanup | rescoped: n/a | evidence: HealthProbe.java exists; ApiSheriffApplication.main routes to HealthProbe.isProbe/probe() before Quarkus.run
- HYPOTHESIS: a Dockerfile `HEALTHCHECK` on a distroless image can invoke `/app/application` directly
  in exec form without a shell — confirm/refute against Docker's `HEALTHCHECK CMD` exec-form
  semantics and an actual `docker build` + `docker inspect` of the image (verify-at-outline).
  - verdict: corroborated | checked_at: cc10ce2a2c7840162bbc087242d42cbbc3cd14c3 | by: deployment-configurability/cleanup | rescoped: n/a | evidence: shipped HEALTHCHECK uses exec (JSON) form CMD ["/app/application","--health-probe"], confirmed live in production Dockerfile
- Verify-first clause: settle both hypotheses against the built image, not against documentation. A
  refutation of the self-probe claim forces the added-binary or compose-only route and re-scopes
  deliverables 1-3.

## Expected Surface

- OBSERVED: `api-sheriff/src/main/docker/Dockerfile.native`
- OBSERVED: `api-sheriff/src/main/docker/Dockerfile.native.jfr`
- OBSERVED: `integration-tests/docker-compose.yml`
- OBSERVED: `deployment/compose-sample/docker-compose.yml`
- HYPOTHESIS: `integration-tests/src/main/docker/health-check.sh` — the existing script may be
  generalised or retired (verify-at-outline)
- HYPOTHESIS: `deployment/compose-sample/scripts/wait-for-ready.sh` — may become redundant (verify-at-outline)
- HYPOTHESIS: `integration-tests/src/test/java/de/cuioss/sheriff/gateway/integration/` — the container-health assertion (verify-at-outline: exact class TBD)
- HYPOTHESIS: `doc/configuration.adoc` — the health/deployment section (verify-at-outline)

## Dependencies and Sequencing

- Depends on: none. Queue head.
- Overlaps with: PLAN-02 on both compose files, and on the probe URL if the mechanism is path-based.
- Adjacent to: `GatewayReadinessCheck` and the management endpoints — consumed, never modified.
- Adjacent to: the upstream services' healthcheck blocks — untouched.

## Hand-Off Command

```text
/plan-marshall task="implement .plan/local/orchestrator/deployment-configurability/plans/PLAN-01-distroless-health-check.md"
```

## Write-Boundary

Touches only repository source and tests. Creates and edits NO file under
`.plan/local/orchestrator/` other than its own `inbox/{sender}-{seq}` message; reports outcome
through its PR and that message. See `orchestration-model.md` § Ledger Write-Boundary.
