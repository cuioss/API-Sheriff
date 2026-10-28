# PLAN-V02-50: Enumeration Detection Substrate — the per-source detector, its trip response and the HTTP/2 abuse bounds

epic: api-sheriff-0-2-0
workstream: WS-03

> Staged plan spec — one shippable unit of work, ready for `/plan-marshall` hand-off.
> The orchestrator EMITS the command below; it never launches the plan inline.
> Staged 2026-10-27 on the request of `plan-v02-06-enumeration-hardening` (inbox
> `plan-v02-06-enumeration-hardening-008`), which shipped the existence-oracle half of `PLAN-V02-06` as #528
> (`c957ee51`, ADR-0073) and moved the rest here by the operator's split at outline: one PR for all nine of its
> deliverables would have exceeded the review bots' 100-file cap.

## Objective

`PLAN-V02-06` closed the endpoint-existence oracle: an untrusted caller now gets one uniform `404` for every rejection
raised before the trust boundary, held to a response-timing floor (`edge/end/ResponseDelay`,
`edge/error/UniformNotFound`), and a route that sets `rate_limit` logs a boot WARN. It did not add the half that
**detects** enumeration. Nothing yet counts what a single source does across requests, so a scanner is answered
uniformly but never noticed, and no consumer can act on it.

This plan builds the gateway's one **per-source detection substrate** with a written contract that `PLAN-V02-07`
consumes, the **response on trip**, and the **HTTP/2 and gRPC stream-abuse bounds** of threat-model row `gw-08`. It also
carries the residue and the deferred findings of #528.

## Deliverables

1. **Per-client 404-rate detection substrate** (spec D3 of `PLAN-V02-06`). A NEW shared component: a per-source leaky
   bucket over 4xx recon codes (400/403/404), keyed by source (+ host), with static-resource exclusion, that trips on a
   configurable threshold. In-memory and single-node by decision; it emits its trip event for external correlation.
   Its consumers: `PLAN-V02-07`; in the `api-sheriff-0-3-0` epic, `PLAN-V03-01` (honeypot); in the
   `kidicap-gateway-requirements` epic, `PLAN-21` (an address-based limit on the BFF login-initiation and callback
   paths, if that plan picks it). **This is the gateway's one per-source limiter**: no consumer builds a second one.
   What the substrate counts must be what the caller saw. After #528 an untrusted caller's honest `401`/`400` is
   rendered as the uniform `404`, so key the count on the rejection event, not on the rendered status code.
2. **Response on trip** (spec D4). Soft throttle (tarpit-lite) or a temporary local block of a tripped source,
   config-driven, plus a structured security event that feeds the signal system. **This plan owns the block/ban
   primitive and any delay primitive.** Reuse `edge/end/ResponseDelay` (non-blocking, a Vert.x timer, no held thread),
   which #528 shipped for the timing floor, so `PLAN-V03-01` can apply it to decoy hits instead of writing its own
   tarpit. Read the local security notes of `plan-v02-06-enumeration-hardening` (see D8) before choosing the delay.
3. **The general-purpose substrate contract** (spec D7), stated as its own named line item. D1 ships the mechanism; this
   ships the contract that stops the next consumer having to generalise it. Publish the substrate as a
   general-purpose API with a written contract, not as a recon-code-specific bucket. The contract states, at minimum:
   the per-source (+ host) keying and its cardinality bound; how an arbitrary weighted event is admitted (not only 4xx
   recon codes); the sliding-window and bucket semantics and their configuration; the strike/ban state transitions
   and their query API; and the structured emit shape a consumer formats from, kept raw (ECS/OCSF formatting is
   `PLAN-V02-07` D3's). **`PLAN-V02-07`'s weights and window are the named first consumer; design against that
   consumer explicitly** and record which of its needs the contract deliberately does not serve. A substrate
   `PLAN-V02-07` must generalise on arrival is this deliverable failing. Never split D1 from D3.
4. **HTTP/2 and gRPC stream-abuse bound** (spec D8), threat-model row `gw-08` in `doc/security-threat-model.adoc`,
   currently `GAP`.

   The full requirement: rate-limit stream creation and reset per connection (not only concurrent streams, which bounds
   a different thing); bound total CONTINUATION/header-frame size and count per stream and drop over-limit
   connections; re-derive `Content-Length` on any h2→h1 downgrade; never forward a client's h2c upgrade headers to the
   backend. API Sheriff terminates h2 via ALPN and proxies gRPC over upstream h2, so both sides inherit this.

   **Part of it is already in place.** `edge/admission/EdgeHardeningOptions` bounds header size, initial-line length,
   chunk size and idle timeout on every listener. `Upgrade` and `Connection` are in `ConnectionHeaders.REQUEST_STRIP`
   (#185, pinned in `ConnectionHeadersTest`), so the h2c strip is done except for `HTTP2-Settings`, which nothing
   strips. `maxConcurrentStreams` is capped at 100 per connection (#466). `PLAN-V02-45` (#506, ADR-0060 Amendment A2)
   added a fixed 25 s request-body deadline and a fixed five-minute gRPC stream-idle bound; its gateway-sent
   `NO_ERROR` resets do not feed a Rapid-Reset count. Remaining scope: a stream-reset rate bound, CONTINUATION
   bounds, the `HTTP2-Settings` strip, and whether ADR-0060's declared-length framing (#385) already re-derives
   `Content-Length` on h2→h1 (if it does, pin it with a test; do not re-implement it). `GrpcDispatchStage`'s Javadoc
   claims the `gw-08` bounds hold on the gRPC path while the threat model says `GAP`; reconcile the two.

   **A weak fit, recorded as one, and the first deliverable to peel off.** It shares no code with D1–D3. It sits here
   because D3's contract admits an arbitrary weighted event and a stream-reset-rate trip is a plausible second
   consumer, keyed by connection rather than by client. If outline finds the per-source (+host) keying does not
   generalise to per-connection HTTP/2 state, say so and re-scope rather than force it, or peel D4 into its own plan.
   Verify the exact enforcement call site at outline against the h2/gRPC termination code.

   **Also the `gw-02` residue.** Row `gw-02` (request framing) is `PARTIAL`: pinned for HTTP/1.1 and, on HTTP/2, for the
   stream-scoped gate rejection, a declared body on `HEAD`, the refusal of a non-decimal `Content-Length`, the
   bodyless-method rule and declared-length framing. Unpinned on HTTP/2: the other framing-gate clauses and an inbound
   body failure after dispatch. Close them here and flip `gw-02` with `gw-08`, or report which clause remains open.

   **Legitimate upload traffic and the reset bound.** Clients that reset every stream the gateway answered before the
   request body ended used to trip the transport's Rapid-Reset guard on an ordinary upload burst (`GOAWAY`
   `ENHANCE_YOUR_CALM` in `run-k6-upload-small-benchmark`, Open Defect 16). #431 (ADR-0066) closed that source: the
   gateway ends such a stream itself with `RST_STREAM(NO_ERROR)` (`edge/end/EarlyResponseEnd`,
   `edge/end/InboundStreamWindow`). Any reset-rate bound must keep that path out of its count; the upload benchmark
   and `Http2EarlyResponseResetIT` are its regression tests.

   Test: a Rapid-Reset/CONTINUATION flood does not exhaust CPU or memory; a client `HTTP2-Settings` header is not
   forwarded upstream; an h2→h1 downgrade re-derives framing.
5. **Tests** for D1–D4: the bucket trips on a scan pattern, static resources are excluded, a tripped source gets the
   configured response, an honest caller is untouched, the cardinality bound holds under many sources.
6. **Architecture documentation and ADRs**, each a named line item. (a) Document the new substrate in
   `doc/architecture.adoc` and record an ADR for the per-source mechanism and its contract. ADR-0062 (platform-first)
   requires a recorded reason for a hand-rolled component, so state why the bucket is built here and not taken from a
   platform mechanism. (b) Record the decision adr-propose found in #528 and could not write because #528 was at 98 of
   100 files: **a planned configuration key the gateway does not enforce (`rate_limit`) is kept, parsed and warned at
   boot rather than removed**, in contrast to ADR-0064, which removed the inert `circuit_breaker`. State that a future
   `rate_limit` implementation builds on this plan's mechanism. (c) Three-layer documentation: `doc/configuration.adoc`
   (threshold, predicate, action), `doc/user/`, `doc/development/`. Derive each ADR ordinal from `doc/adr/` on the
   branch at write time and again against `origin/main` before the push: #528 took `0073` mid-finalize and the
   duplicate showed only on the CI merge ref. Write the records to `doc/development/adr-writing.adoc`;
   `AdrRecordLengthContractTest` fails the build on a record body over 500 lines.
7. **Documentation residue of #528** (advisories of its code review; re-read each at outline and drop one that no
   longer holds). `config/model/RouteConfig.java` (near line 58) describes `rateLimit` in a third wording; align it to
   "planned, not implemented". `integration-tests/.../BffSessionFallbackIT.java` (near line 311) Javadoc still says the
   BEARER branch refuses with `401`, while an untrusted caller now sees the uniform `404`. `doc/user/endpoint-routes.adoc`
   (near line 402) and `doc/user/anchors.adoc` (near line 316) scope the pre-trust `404` to an unauthenticated caller,
   although the pre-auth checks answer it to every caller. `doc/development/declared-limit-assertion-coverage.adoc`
   (near line 535) cites `bff-overlap.yaml:37` for a declaration that sits at line 38. The 50 ms floor and the jitter
   remain reasoned, not measured: either measure them (a benchmark of the slowest pre-trust arm against the floored
   `404`) or keep the statement that they are reasoned in ADR-0073.
8. **Deferred findings of #528 and one flaky test.** The operator deferred review points on the uniform-404 change to
   this plan. They are recorded in the git-ignored `.plan/local/security_notes.adoc` under
   `plan-v02-06-enumeration-hardening` (entries dated 2026-10-26 and 2026-10-27). Read them at outline; for each, fix
   it here or re-defer it with the operator and keep its detail in that file. `GatewayEdgeRouteTest$RelayStartFailure
   .relayThatFailsToStartReleasesTheUpstreamResponse` failed once in the CI sonar-build isolated fork (run 40504680597)
   and passed on rerun. Make it deterministic with an explicit synchronisation point or an awaited condition, confirm it
   under `-XX:ActiveProcessorCount=1`, and do not answer a red run with a retry. Read the same local notes before
   touching the release path it exercises.

**Split guard:** 8 deliverables, within the operator-authorised 12; proceed unsplit. #528 reached 98 of 100 files with
three deliverables, so size this PR at outline. If the projection exceeds 90 files, peel D4 (with its tests and docs)
into its own plan first, then D7 and D8.

## Claim Labels

- OBSERVED: #528 shipped spec D1, D2 and D9 of `PLAN-V02-06` and merged as `c957ee51`; D3, D4, D7 and D8 are not on
  `main` — its inbox message `plan-v02-06-enumeration-hardening-008` and `git log` on `origin/main`
  - verdict: corroborated | checked_at: c957ee513126fe929dd5b3266212fdb3159f7a3b | by: api-sheriff-0-2-0/analyze | rescoped: n/a | evidence: PR 528 merged as c957ee51; the landing message names D1, D2, D9 shipped and D3, D4, D7, D8 not shipped
- OBSERVED: no per-source detection component exists on `main`; `config/model/RateLimitConfig` is parsed and enforced by
  nothing, and `edge/end/ResponseDelay` is the only delay primitive — `git grep` for a leaky bucket or per-source
  counter under `api-sheriff/src/main`
  - verdict: corroborated | checked_at: c957ee513126fe929dd5b3266212fdb3159f7a3b | by: api-sheriff-0-2-0/analyze | rescoped: n/a | evidence: git grep on origin/main finds no per-source counter; RateLimitConfig is parsed only; ResponseDelay is the one delay primitive
- OBSERVED: threat-model row `gw-08` is `GAP` and `HTTP2-Settings` is stripped nowhere in `api-sheriff/src/main` —
  `doc/security-threat-model.adoc` (~3936) and `git grep -i http2-settings`
  - verdict: corroborated | checked_at: c957ee513126fe929dd5b3266212fdb3159f7a3b | by: api-sheriff-0-2-0/analyze | rescoped: n/a | evidence: security-threat-model.adoc ~3936 lists gw-08 as GAP; git grep -i http2-settings finds nothing in api-sheriff/src/main
- HYPOTHESIS: `maxConcurrentStreams` (100 per connection, #466) and the h2c `Upgrade`/`Connection` strip are still the
  only HTTP/2 abuse bounds on `main`; read `EdgeHardeningOptions` and `ConnectionHeaders.REQUEST_STRIP` (verify-at-outline)
- HYPOTHESIS: the per-source (+ host) keying generalises to per-connection HTTP/2 state; if it does not, D4 re-scopes or
  peels off (verify-at-outline)
- HYPOTHESIS: the five residue statements of D7 hold on `main` — the plan reported them from its code review and the
  orchestrator read the `RouteConfig` and the `bff-overlap.yaml` citation only (verify-at-outline)

## Expected Surface

- NEW: a per-source detection component under `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/events/` or a new
  package — D1, D3
- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/edge/error/ErrorResponseWriter.java`,
  `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/edge/error/UniformNotFound.java` — D1 reads the rejection event at the render point; D2 the trip response
- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/edge/end/ResponseDelay.java` — D2 reuses it
- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/edge/GatewayEdgeRoute.java` — D2: where the delayed answer is written
- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/events/EventType.java`,
  `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/events/GatewayEventCounter.java`,
  `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/quarkus/SheriffMetrics.java`,
  `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/ApiSheriffLogMessages.java`, `doc/LogMessages.adoc` — the trip event, metric and log record
- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/config/model/**`,
  `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/config/validation/rule/LimitRules.java` — D1, D2: threshold, predicate and action config, and its boot rules
- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/edge/admission/EdgeHardeningOptions.java`,
  `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/edge/end/EarlyResponseEnd.java`,
  `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/edge/end/InboundStreamWindow.java`,
  `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/edge/upstream/GrpcDispatchStage.java`,
  `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/http/ConnectionHeaders.java` — D4
- OBSERVED: `doc/security-threat-model.adoc` — flip `gw-08` (and `gw-02` if closed) once D4 lands
- OBSERVED: `doc/architecture.adoc`, `doc/configuration.adoc`, `doc/user/`, `doc/development/`, `doc/adr/` — D6
- OBSERVED: the five D7 files, `api-sheriff/src/test/java/de/cuioss/sheriff/gateway/edge/GatewayEdgeRouteTest.java` — D7, D8
- `benchmarks/**` — the upload benchmark as D4's regression check; `api-sheriff/src/test/**` and
  `integration-tests/**` — D5

## Dependencies and Sequencing

- Depends on: `PLAN-V02-06` landed (#528). Re-read `edge/error/`, `edge/end/` and `edge/GatewayEdgeRoute` at outline:
  #528 added the uniform-404 branch and the response-delay primitive there.
- Depended on by: `PLAN-V02-07`, which consumes D1 and D3 and must not run before this plan lands (its spec re-points
  here); `PLAN-V03-01` (honeypot) and kidicap `PLAN-21`, if that plan picks the address-based limit.
- Writes ADRs, so it is never concurrent with `PLAN-V02-48`; it also shares `config/` and `edge/` with `PLAN-V02-49`
  and the feature plans, so check them at emit time.
- The cap on new ADR ordinals: the next free one on `origin/main` at `c957ee51` is `0074`.

## Hand-Off Command

```text
/plan-marshall task="implement .plan/orchestrator/api-sheriff-0-2-0/plans/PLAN-V02-50-enumeration-detection-substrate.md" plan_id=plan-v02-50-enumeration-detection-substrate
```

**The explicit `plan_id` is load-bearing — do not drop it.**

## Security Findings

Fix a security finding inside this plan whenever possible; that is the preferred outcome. A finding
that is not fixed right away (out of scope, unconfirmed, deferred) stays out of git: it is never
written into a tracked file, a commit message, the PR title, body or review replies, a code comment,
the docs, or this plan's inbox and landing messages. Record it instead in
`.plan/local/security_notes.adoc` (git-ignored; create the file if it is missing and append, never
rewrite). Each entry carries the date, this plan's id, the location (file and line or component),
what was observed, and why it was not fixed. Tracked text may say only that a security note was
recorded locally, never what it says.

## Write-Boundary

The plan implementing this spec touches only its own repository source and tests. It creates and
edits NO file under `.plan/orchestrator/` other than its own `inbox/{sender}-{seq}` message, and
reports its outcome through its PR and that message.
