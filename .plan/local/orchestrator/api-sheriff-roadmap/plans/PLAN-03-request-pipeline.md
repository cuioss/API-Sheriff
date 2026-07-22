# PLAN-03: Request Pipeline (roadmap 04)

epic: api-sheriff-roadmap
workstream: WS-02

> Staged plan spec. Source of truth: `doc/plan/04-request-pipeline.adoc` — read in full at
> launch; the design docs govern. Re-staged 2026-07-18 with the full work breakdown per the
> operator's standing direction (whole roadmap plan in one plan-marshall plan).

## Objective

Execute `doc/plan/04-request-pipeline.adoc` in full: the gateway's data plane for standard
HTTP verbs — the fixed 8-stage pipeline (security headers/CORS, cui-http basic checks,
route selection on the canonical path, verb gate, thorough checks, offline bearer validation,
zero-trust forward policy, streamed dispatch with retry/circuit breaker, streamed response) —
plus the cross-cutting spine (events, metrics, logging, RFC 9457 error edge), D3b threat-model
hardening (GW-01/02/03/08), edge hardening with graceful drain, and readiness. Replaces the
Plan-01 placeholder resources wholesale.

## Deliverables

The plan doc's 13 work-breakdown items:

1. **Dependencies (PRE-APPROVED by operator 2026-07-18 — do not stop to ask)**:
   `de.cuioss:cui-http` — version MANAGED at 2.1.0 by the parent chain
   (cui-java-bom:1.5.1 `${version.cui.http}`, verified), so declare WITHOUT a version tag;
   its tests-classifier artifact (attack databases) equally approved, also version-managed;
   `io.quarkus:quarkus-smallrye-fault-tolerance` (version from the Quarkus BOM).
   TokenSheriff stack already present (0.9.2 via token-sheriff-bom) — wire, don't add.
2. **Arch-gate (ADR-0005, mandatory from first pipeline PR)**: ArchUnit rule keeping
   framework-agnostic packages free of Quarkus/Vert.x/CDI/MicroProfile/Micrometer imports,
   wired into the quality gate.
3. **Event system + error edge (D4)**: EventCategory/EventType/GatewayEventCounter/
   GatewayException; one handler mapping to RFC 9457 problem+json per the error-contract
   table (a test per row).
4. **RouteRuntime assembly**: boot compilation of RouteTable into immutable runtime objects;
   heavy-object dedup (one SecurityConfiguration per profile shape, one Vert.x client per
   upstream tuple); per-route SmallRye FT Guard/TypedGuard; boot-time rejection of
   grpc/websocket/session; verify whole-second rule is gone (02b did it).
5. **Stages 0–3 + GW-01/GW-02**: security headers, CORS preflight before auth, basic
   pipelines, single-canonical-path invariant (encoded-slash and matrix `;` rejected 400),
   framing/anti-smuggling gate (CL+TE, body-on-bodyless, Connection-strip), route selection,
   verb gate (405+Allow), thorough checks, allowed_paths, body-cap fast-reject. Tests reuse
   cui-http attack databases + GW-01 divergence corpus + GW-02 smuggling corpus.
6. **Stage 4 auth**: shared TokenValidator producer, bearer extraction, scope enforcement
   (401/403); tests with token-sheriff-validation generators artifact + mockwebserver JWKS.
7. **Stage 5 forward policy**: allowlist filtering, TCP-peer gate consuming Plan 03's parsed
   CIDR set (no per-request parsing), ForwardedHeaderResolver regeneration (spoof tests),
   Authorization stripped unless allow-listed, not_modified conditional handling.
8. **Stages 6–7 dispatch/response**: shared Vert.x client through FT guard, hop-by-hop
   stripping, bidirectional streaming with backpressure (never HttpResult<byte[]>, ADR-0006),
   stream-aware retry gate (never after first body byte), mid-stream max_body_bytes abort,
   breaker transitions logged+metered, 502/503+Retry-After/504 mapping, 304 pass-through/strip.
9. **Vert.x edge**: low-priority catch-all route, virtual-thread dispatch, edge hardening
   (header/line/chunk limits, idle timeout, in-flight admission cap, graceful SIGTERM drain —
   defaults recorded doc-first in configuration.adoc), GW-08 h2 abuse bounds verified
   (Rapid Reset, CONTINUATION, no h2c forwarding); delete Plan-01 placeholder
   GatewayResource/ApiSheriff + tests; evaluate dropping quarkus-resteasy (check
   token-sheriff-client-quarkus mapper needs first).
10. **Metrics + readiness (D4/D5)**: five sheriff_* meters (bounded route-label cardinality),
    ApiSheriffLogMessages DSL class documented in doc/LogMessages.adoc, readiness extended
    with config + TokenValidator/JWKS health (reuse shipped checks).
11. **Integration tests**: per-verb happy path with echo-side exact-header assertions; real
    Keycloak bearer accept/tampered-401/missing-scope-403; 400/404/502/504/503+Retry-After
    (stopped upstream, /delay, induced breaker — no Toxiproxy yet); SIGTERM drain IT;
    /q/metrics moving. New ITs use .extract()+JUnit (no Hamcrest).
12. **Benchmarks**: bearer-validated proxied-route WRK benchmark alongside the plain-proxy
    one (validation overhead measured separately). The 02b harness repair (aggregated
    counters, error-rate gate — lesson 2026-07-18-11-001) is the precondition and is landed.
13. **Docs**: LogMessages.adoc; deviations found during implementation go back into
    architecture.adoc doc-first, same PR.

## Expected Surface

- `api-sheriff/` — new events/routing/forward/pipeline packages, Vert.x edge, RouteRuntime,
  metrics/readiness, ArchUnit gate; removes GatewayResource/ApiSheriff placeholders; pom
  (approved deps)
- `integration-tests/` — pipeline IT suites, compose usage (echo + Keycloak, existing)
- `benchmarks/` — bearer-route WRK benchmark (lua/sh + metadata conventions)
- `doc/` — LogMessages.adoc, configuration.adoc (edge defaults, GW-01 decisions),
  architecture.adoc deviations, security-threat-model.adoc GW-01 mirror

## Dependencies and Sequencing

- Depends on: PLAN-01 (shipped #72), PLAN-02 (shipped #74)
- Overlaps with: PLAN-04/05/06 all build on it — run alone
- Scope note: 13 work-breakdown items, far past the ~6 split-guard threshold. Proceeding as
  ONE plan per the operator's standing direction (one roadmap plan = one plan-marshall plan);
  the plan doc itself sanctions a multi-PR split inside the lifecycle (green after each PR),
  which is where the size is managed.

## Hand-Off Command

```text
/plan-marshall Execute doc/plan/04-request-pipeline.adoc IN FULL — all of D1-D5, D3b, and the complete 13-item work breakdown in this one plan; read the plan doc and its Required Reading (architecture.adoc entire, Variant 1, configuration.adoc pipeline sections, ADR-0003/0006/0008, ADR-0005 + Object Reuse, technical_aspects.adoc, cui-http forwarded-header contract) before touching code; the design docs govern. Dependencies are PRE-APPROVED by the operator (2026-07-18) — do not stop to ask: de.cuioss:cui-http (version MANAGED at 2.1.0 by the parent chain via cui-java-bom's ${version.cui.http} — declare WITHOUT a version tag), cui-http's tests-classifier artifact (attack databases, equally approved and version-managed), and io.quarkus:quarkus-smallrye-fault-tolerance (version from the Quarkus BOM). The TokenSheriff 0.9.2 stack is already on the classpath — wire it, add nothing. Use the FULL (deep) planning lane — no light-lane shortcut; this is a codebase-wide data-plane build. Deliver: the ADR-0005 ArchUnit arch-gate in the quality gate; the D4 event system + RFC 9457 error edge (test per error-contract row); boot-time RouteRuntime assembly with heavy-object dedup (one SecurityConfiguration per profile shape, one Vert.x client per upstream tuple), per-route SmallRye FT Guard, boot rejection of grpc/websocket/session; stages 0-3 with the GW-01 single-canonical-path invariant (encoded-slash and matrix-param rejected 400) and the GW-02 framing/anti-smuggling gate, verb gate 405+Allow, tested against the cui-http attack databases plus GW-01 divergence and GW-02 smuggling corpora (upstream count = 0 on any bypass); stage 4 offline bearer validation via the shared TokenValidator (401/403, generators-artifact + mockwebserver tests); stage 5 zero-trust forward policy with the TCP-peer gate consuming Plan 03's parsed CIDR set and drop-and-regenerate forwarding headers (spoof tests); stages 6-7 streamed dispatch/response with backpressure (never HttpResult<byte[]>), stream-aware retry gate, mid-stream body-cap abort, breaker + 502/503+Retry-After/504 mapping, not_modified handling per ADR-0008; the Vert.x catch-all edge on virtual threads with edge hardening (header/line/chunk limits, idle timeout, admission cap, graceful SIGTERM drain — defaults doc-first in configuration.adoc) and GW-08 h2 abuse bounds verified; delete the Plan-01 placeholder GatewayResource/ApiSheriff and evaluate dropping quarkus-resteasy; the five sheriff_* meters, ApiSheriffLogMessages documented in doc/LogMessages.adoc, readiness with reused TokenValidator health checks; the full IT suite (per-verb echo assertions, real-Keycloak bearer, 400/404/502/504/503, SIGTERM drain, /q/metrics — .extract()+JUnit style); and the bearer-validated proxied-route WRK benchmark on the repaired 02b harness. Out of scope: session/OIDC/BFF, websocket/grpc processors (boot-rejected), TLS passthrough/mtls, rate limiting, HTTP/3. Branch feature/plan-04-request-pipeline; a multi-PR split is acceptable with the build green after each PR. Acceptance criteria are the plan doc's section 6 verbatim.
```

## Status Trail

- plan_marshall_plan_id: (set at launch)
- pr: (set when the PR opens)
- landing: (set when recorded at landings/PLAN-03.md)
