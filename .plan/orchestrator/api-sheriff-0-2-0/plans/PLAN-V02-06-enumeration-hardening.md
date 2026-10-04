# PLAN-V02-06: Enumeration Hardening — existence-oracle + 404-scanner detection

epic: api-sheriff-0-2-0
workstream: WS-03

> Staged plan spec — one shippable unit of work, ready for `/plan-marshall` hand-off.
> The orchestrator EMITS the command below; it never launches the plan inline.

## Objective

Close the endpoint-existence oracle and detect enumeration scanning. Today deny-by-default routing means
no path listing exists, but the status codes leak existence: a non-existent path returns 404, an
existing-but-auth-required path 401, an existing-but-off-allowlist path 400 — an anonymous attacker can
map real endpoints. This plan presents a **uniform 404** (body + code + timing) to **untrusted /
unauthenticated** sources while keeping honest 401/403 for authenticated callers past the trust
boundary, and introduces a **per-client 404-rate detection substrate** (a CrowdSec-`http-probing`-style
leaky bucket) with a written contract that `PLAN-V02-07` consumes.

## The rejection contract this plan builds on

`PLAN-V02-13` has landed (#383, ADR-0059). The concerns at the rejection dispatch in
`GatewayEdgeRoute` are split as follows:

| Concern | Owner |
|---|---|
| WHICH `EventCategory` a rejection carries | on `main`: routing rejections (`NO_ROUTE_MATCHED`, `PASSTHROUGH_HOST_SMUGGLED`, `METHOD_NOT_ALLOWED`) report under `ROUTING` (`urn:api-sheriff:problem:routing`) |
| HOW a rejection is RENDERED to a browser vs. a JSON client (`Accept` negotiation) | on `main`: `portal/ErrorPageClassifier` |
| WHETHER an untrusted caller sees the honest code or a uniform 404 (the trust-boundary branch) | this plan, D1 |
| Response-TIMING uniformity on the reject path | this plan, D2 |

This plan changes the code a rejection resolves TO, not what it is CALLED. It does not touch the category assignment and does not alter the content negotiation — its uniform-404
must work through that negotiation, not around it.

## Deliverables

1. **Uniform-404 for untrusted sources.** When the caller is unauthenticated / untrusted, a would-be
   401 (`TOKEN_MISSING`) / 400 (`PATH_NOT_ALLOWED`) / 404 (`NO_ROUTE_MATCHED`) is rendered as an
   **identical 404** (same body, code, and — deliverable 2 — timing). Authenticated callers past the
   trust boundary keep honest `401/403`. **Stated as its own line item** (behaviour change; the
   trust-boundary condition is the crux — an outline must not collapse it to "return 404").

   - `renderProblem` is already content-negotiating: `portal/ErrorPageClassifier` marks
     `NO_ROUTE_MATCHED`, `PASSTHROUGH_HOST_SMUGGLED`, `PATH_NOT_ALLOWED`, `METHOD_NOT_ALLOWED` and
     `TOKEN_MISSING` as HTML-eligible. Design the uniform-404 against that negotiating renderer, and do not reintroduce
     a status or shape oracle through the HTML branch.
   - `renderProblem` has several call sites in `GatewayEdgeRoute.java` (five when last counted). The
     trust-boundary branch must account for every one; re-count at outline.
   - Verify at outline whether `PASSTHROUGH_HOST_SMUGGLED` is a further pre-auth 404 event that
     belongs in the uniform-404 scope beside `NO_ROUTE_MATCHED`.
2. **Response-timing uniformity** for the reject path, so 404-vs-would-be-401 are not timing-
   distinguishable (route rejects through one code path / add jitter; watch early-exit shortcuts).
3. **Per-client 404-rate detection substrate** — a NEW shared component: a per-source leaky bucket over
   4xx recon codes (400/403/404), keyed by source (+ host), with static-resource exclusion, that trips
   on a configurable threshold. In-memory / single-node by decision; emits its trip event for external
   correlation. `PLAN-V02-07` and, in the `api-sheriff-0-3-0` epic, `PLAN-V03-01` (honeypot) consume it.
4. **Response on trip**: soft throttle / tarpit-lite or temporary local block of a tripped source
   (config-driven), plus a structured security event (feeds the signal system).
5. **Tests** (oracle closed for unauth, honest codes for auth, bucket trips on a scan pattern, static
   resources excluded).
6. **Architecture documentation + ADR** — **stated as its own named line item**. Document the NEW
   per-client detection substrate (a new component in the edge/pipeline architecture) in
   `doc/architecture.adoc`, and record an **ADR** for the uniform-404-for-untrusted trust-boundary
   policy (the existence-oracle decision, its rationale, and the deliberate scoping to untrusted
   callers). Plus **three-layer documentation** (`configuration.adoc` threshold/predicate/action config,
   `doc/user/`, `doc/development/`), including the usability note (uniform-404 is scoped to untrusted
   callers precisely to preserve authenticated-client error handling). Derive the ADR ordinal from
   `doc/adr/` on the branch at write time; a duplicate ordinal fails the build.
7. **The general-purpose substrate contract** — **stated as its own named line item.** D3 ships the
   mechanism; D7 ships the *contract* that stops the next consumer having to generalise it. Publish
   the substrate as a general-purpose API with a written contract, not as a recon-code-specific
   bucket. The contract states, at minimum: the per-source (+ host) keying and its cardinality bound;
   how an arbitrary weighted event is admitted (not only 4xx recon codes); the sliding-window/bucket
   semantics and their configuration; the strike/ban state transitions and their query API; and the
   structured emit shape a consumer formats from. **`PLAN-V02-07`'s weights and window are the named
   first consumer — design against that consumer explicitly**, and record which of its needs the
   contract deliberately does not serve, so that plan can work around them rather than discover them.
   A substrate `PLAN-V02-07` must generalise on arrival is this deliverable failing.
8. **HTTP/2 & gRPC stream-abuse bound** — threat-model row `gw-08` in
   `doc/security-threat-model.adoc`, currently `GAP`.

   The full requirement: rate-limit stream creation and reset per connection (not only concurrent
   streams, which bounds a different thing); bound total CONTINUATION/header-frame size and count
   per stream and drop over-limit connections; re-derive `Content-Length` on any h2→h1 downgrade;
   never forward a client's `Upgrade`/`Connection` (h2c) headers to the backend. API Sheriff
   terminates h2 via ALPN and proxies gRPC over upstream h2, so both sides inherit this requirement.

   **Part of it is already in place.** `edge/EdgeHardeningOptions` bounds header size, initial-line
   length, chunk size and idle timeout on every listener. It does not set `maxConcurrentStreams`,
   does not rate-bound stream resets, and does not strip h2c `Upgrade` — that is this deliverable's
   remaining scope. `GrpcDispatchStage`'s Javadoc claims the `gw-08` bounds hold on the gRPC path
   while the threat model says `GAP`; reconcile the two.

   **This deliverable is a weak fit for the plan and is recorded as one.** It sits here because D7's
   contract admits an arbitrary weighted event, and a stream-reset-rate trip is a plausible second
   consumer of that substrate, keyed by connection rather than by client. If outline finds the
   per-source (+host) keying does not generalise cleanly to per-connection HTTP/2 state, say so and
   re-scope rather than force it. Verify the exact enforcement call site at outline against the
   h2/gRPC termination code.

   **Also the `gw-02` residue.** `PLAN-V02-13` left threat-model row `gw-02` (request framing) at
   `PARTIAL`: the HTTP/2 clauses beyond the stream-scoped gate rejection are not pinned. They are
   HTTP/2 framing bounds of the same kind as this deliverable's, so close them here and flip `gw-02`
   with `gw-08`, or report which clause remains open.

   Test: a Rapid-Reset/CONTINUATION-flood load does not exhaust CPU/memory; client
   `Upgrade: h2c`/`Connection` headers are not forwarded upstream; an h2→h1 downgrade path re-derives
   framing.

**Split-guard.** Eight deliverables — past the presumptive split threshold, proceeding unsplit. D8 is
the weakest fit and shares no code with D1/D2; it is the first candidate to peel off. **Split line if
outline finds the scope too heavy: D1+D2 (the oracle and its timing) | D3+D4+D7 (the substrate and its
contract) | D8 alone.** D5 and D6 follow whichever half they test and document. Never split between D3
and D7: shipping the substrate without its contract is the failure the contract exists to prevent. If
the trust-boundary threading in D1 is itself large, split D1 off rather than bloat.

## Claim Labels

- OBSERVED (the oracle): the differentiated codes are real — `events/EventType.java`: `NO_ROUTE_MATCHED`
  (404), `TOKEN_MISSING` (401), `PATH_NOT_ALLOWED` (400); rendered by `edge/GatewayEdgeRoute.java`
  `renderProblem` per the event's HTTP mapping.
  - verdict: corroborated | checked_at: 05f6ee3ebb5ae32fb75082b660e6abdb7617edb6 | by: api-sheriff-0-2-0/cleanup | rescoped: n/a | evidence: EventType PATH_NOT_ALLOWED:58(400) NO_ROUTE_MATCHED:62(404) TOKEN_MISSING:101(401) unchanged
- OBSERVED: deny-by-default routing (no listing) — `pipeline/RouteSelectionStage.java`
  (`NO_ROUTE_MATCHED`, "the gateway never forwards an unmatched request").
  - verdict: corroborated | checked_at: 05f6ee3ebb5ae32fb75082b660e6abdb7617edb6 | by: api-sheriff-0-2-0/cleanup | rescoped: n/a | evidence: RouteSelectionStage:38 comment intact; process():74 still throws NO_ROUTE_MATCHED
- OBSERVED: no per-client recon detection exists — `events/GatewayEventCounter.java` counts events
  globally (Micrometer), not per-source; a grep for a per-client/leaky-bucket construct returns nothing.
  Confirm/refute at `events/` § its counter set (verify-at-outline).
  - verdict: corroborated | checked_at: 05f6ee3ebb5ae32fb75082b660e6abdb7617edb6 | by: api-sheriff-0-2-0/cleanup | rescoped: n/a | evidence: GatewayEventCounter still flat ConcurrentHashMap<EventType,AtomicLong>; no sliding-window construct in main (control: 14 EventCounter refs)
- HYPOTHESIS: the "trusted / authenticated caller" signal needed for the trust-boundary branch is
  available at the render point (the request carries its auth outcome). Confirm/refute at
  `edge/GatewayEdgeRoute.java` § where `renderProblem` is called and what auth state is in scope
  (verify-at-outline) — if the reject path cannot see auth state, the branch needs the pipeline to
  thread it, which widens the deliverable.
  - verdict: corroborated | checked_at: 05f6ee3ebb5ae32fb75082b660e6abdb7617edb6 | by: api-sheriff-0-2-0/cleanup | rescoped: n/a | evidence: renderProblem(ctx, request, eventType) signature unchanged at GatewayEdgeRoute:1500
- Verify-first clause: confirm that collapsing to 404 does not break the shipped BFF/XHR contracts
  (the user-info endpoint deliberately returns 401-not-redirect for XHR) — the uniform-404 must NOT
  apply to those authenticated-session flows; scope the "untrusted" predicate against the landed auth
  model, not this spec's prose.
  - verdict: unverifiable | checked_at: 05f6ee3ebb5ae32fb75082b660e6abdb7617edb6 | by: api-sheriff-0-2-0/cleanup | rescoped: n/a | evidence: UserInfoEndpoint javadoc :64-66 still 401 problem+json never redirect; now references content negotiation (ErrorPageClassifier, #343)

## Expected Surface

- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/edge/GatewayEdgeRoute.java` `renderProblem` — the trust-boundary-aware uniform-404 branch
- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/pipeline/RouteSelectionStage.java` — the route-miss origin of `NO_ROUTE_MATCHED`
- OBSERVED absence → NEW: a per-client recon-detection component under `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/events/` or a new package
- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/events/EventType.java` / `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/events/GatewayEventCounter.java` — a new trip event + counter
- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/config/model/**` — config for the threshold / trusted-source predicate / response action
- OBSERVED: `doc/configuration.adoc`, `doc/user/`, `doc/development/`; `api-sheriff/src/test/**`
- `doc/security-threat-model.adoc` — flip `gw-08` from `GAP` to `COVERED` once D8 lands — D8
- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/edge/EdgeHardeningOptions.java` — D8: the per-listener transport bounds (header size, initial-line and chunk size, idle timeout). This is where `maxConcurrentStreams`, the stream-reset rate bound and the h2c `Upgrade` strip would land.
- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/edge/GrpcDispatchStage.java` — D8: its Javadoc claims the gw-08 HTTP/2 abuse bounds hold on the gRPC path, while `doc/security-threat-model.adoc` still marks gw-08 `GAP`. Reconcile one or the other.

## Dependencies and Sequencing

- Depends on: none. The taxonomy this plan's uniform-404 branch is built against has landed with
  `PLAN-V02-13` (#383). Re-read `GatewayEdgeRoute` and `DispatchStage` at outline: both were changed
  by `PLAN-V02-13` and `PLAN-V02-10` after this spec was last grounded.
- Depended on by: `PLAN-V02-07`, which consumes D3 and D7 and must not run before this plan lands.
- Overlaps with: `PLAN-V02-05` on `ResponseStage` and the edge. The two are close enough that the
  disjointness check should read `PLAN-V02-05`'s current outline if it is in flight, not only its
  staged spec.

## Standing Conventions

Three-layer docs, Sonar zero-findings, named line items, integration tests in the same plan.

## Hand-Off Command

```text
/plan-marshall task="implement .plan/orchestrator/api-sheriff-0-2-0/plans/PLAN-V02-06-enumeration-hardening.md" plan_id=plan-v02-06-enumeration-hardening
```

## Write-Boundary

The plan implementing this spec touches only its own repository source and tests. It creates and
edits NO file under `.plan/orchestrator/` other than its own `inbox/{sender}-{seq}` message, and
reports its outcome through its PR and that message.
