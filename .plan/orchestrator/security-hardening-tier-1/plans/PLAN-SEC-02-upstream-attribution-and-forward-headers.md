# PLAN-SEC-02: Upstream attribution and forward headers — idle gRPC calls no longer open the breaker; client provenance/override headers withheld

epic: security-hardening-tier-1
workstream: WS-02

> Staged plan spec — one shippable unit of work, ready for `/plan-marshall` hand-off.
> Lives at `plans/PLAN-SEC-02-upstream-attribution-and-forward-headers.md` and is queued as one row
> file, `queue/PLAN-SEC-02.json`, in the epic ledger. The orchestrator EMITS the command below; it
> never launches the plan inline. This spec is SELF-SUFFICIENT: the emitted command is a one-line
> pointer and carries no brief, so every per-plan carry is authored here and nowhere else.

## Objective

Fix two places where client-controlled behaviour reaches what the gateway is supposed to own,
found in the 2026-11-07 ground-truth check of the security notes (code reading at `main`
`7f5c61af`):

1. **M5 (Medium, rank 4, effort M).** Under the default forward mode (forward-all) and under
   negative lists, client-sent provenance and URL-override headers cross to the upstream:
   `X-ProxyScheme`, `X-ProxyHost`, `X-ProxyPort`, `X-ProxyContextPath`, `X-Forwarded-Server`,
   `X-Forwarded-Ssl`, `Front-End-Https`, `X-Url-Scheme`, `X-Forwarded-Scheme`,
   `X-Forwarded-Protocol`, `X-Forwarded-Uri`, `X-Forwarded-Path`, `X-Original-URL`,
   `X-Rewrite-URL`, `X-Original-Host`, `X-Host`, `X-Original-Forwarded-For`,
   `X-Cluster-Client-IP`, `Fastly-Client-IP`. An upstream that trusts the gateway as its proxy can
   then be host/scheme-poisoned (absolute URLs, redirects, reset links, "secure" decisions) or —
   with IIS URL Rewrite or legacy Symfony/Zend honouring `X-Original-URL` / `X-Rewrite-URL` —
   re-targeted to a different path AFTER the gateway authorised the request path. Because the
   gateway never emits `X-Forwarded-Proto`/`-Host` (`trust_scheme_host` is inert), an upstream that
   honours `X-Forwarded-Ssl` / `-Scheme` / `X-Url-Scheme` sees only the client's value.
2. **M6 (Medium on deployments with a reachable `protocol: grpc` route; rank 5, effort M).** The
   gRPC dispatch runs with no request-body idle clock or deadline, and the upstream HEADERS are
   written only with the first body chunk. A client that sends HEADERS without END_STREAM and then
   nothing leaves the upstream with nothing; at 30 s the guard fires a `TimeoutException`, which is
   not a `GatewayException`, so the circuit breaker counts a FAILURE. About 20 such calls timing out
   together open the breaker (`requestVolumeThreshold 20`, `failureRatio 0.5`) and every gRPC caller
   of that upstream gets UNAVAILABLE for 5 s, repeatably. It is the same class as the
   departure-counted-as-failure defect fixed in PR #532.

Each fix ships with a regression test that fails against the unfixed code.

## Deliverables

1. **gRPC: a guard timeout on an attempt whose request head was never written upstream is
   client-attributed (M6).** When the guard times out a gRPC attempt that never published its
   request upstream (no request byte reached the upstream), report it as a client-attributed
   `GatewayException` (breaker-skipped, not retried, mapped like the existing client-abort
   disposition). `UpstreamAttempt` already knows whether `publish` happened. Alternative to decide
   at outline: a time-to-first-message bound (for example 10 s) on the gRPC path before the
   upstream head is written, ending as a breaker-skipped client abort. Reuse the existing
   client-abort event and log record; do not add an `EventType` constant unless outline proves one
   is needed.
   - Tests: a forced-HTTP/2 stub upstream; the client sends HEADERS only (no DATA, no END_STREAM)
     with a shortened guard timeout; assert the breaker records no failure and the client gets the
     client-abort mapping. Matched control: an upstream that never answers AFTER receiving the
     request IS counted as a failure.
2. **Forward policy: the resolver's input names are masked like the regenerated forwarding names
   (M5, X-Proxy* half).** In `ForwardPolicyStage`, introduce a separator-folded resolver-input set
   `FORWARDING_HEADERS ∪ {x-proxyscheme, x-proxyhost, x-proxyport, x-proxycontextpath}` and use it
   in `isSkippedByModeCopy` and in the untrusted-peer mask of `applyRegeneratedForwarding`, so the
   cui-http resolver never sees a client's `X-Proxy*` from an untrusted peer and the mode copy never
   forwards them. (Alternative: cui-http's `resolve(lookup, InetAddress peer)` overload, which
   returns empty for an untrusted peer — choose at outline.)
3. **Withhold the provenance / URL-override header class (M5).** Extend `ConnectionHeaders.REQUEST_STRIP`
   (separator-folded, so `_` spellings are covered) with the scheme/host/URL-override/client-IP names
   listed in the Objective. Decision to settle at outline and record: strip unconditionally (the
   gateway is the provenance authority; pre-1.0 allows the break) versus exempt a trusted peer. The
   recommended default is unconditional, because a trusted front proxy that forwards a client's value
   unchanged would otherwise reopen the hole.
   - Tests (`ForwardPolicyStageTest`): parameterised over every name, canonical and `_` spelling,
     under forward-all and negative-list modes, asserting the name never reaches the upstream, with a
     matched positive control (an unrelated header still crosses); an untrusted peer sending
     `X-ProxyHost` / `X-ProxyContextPath` produces no regenerated value.
4. **Documentation for the changed forwarding contract.** Correct the statements that overclaim
   today: `ForwardPolicyStage` class Javadoc (untrusted-peer forwarding headers "are ignored"; "every
   spelling of every provenance name reaches one of the two sets"); `ConnectionHeaders` Javadoc (the
   mask rationale "names the resolver never queries"); `doc/architecture/routing-and-forwarding.adoc`
   (regenerated headers "replace any client-supplied … `X-Proxy*` chains"); ADR-0003 (sabotaged
   inbound values "never reach the upstream" — amend in place or add an amendment section, per the
   ADR conventions; any new ADR number is derived at write time, never assumed);
   `doc/configuration/routing-and-forwarding.adoc` (list the withheld names); the threat-model rows
   for forwarded-header trust. State that `forwarded.trust_scheme_host` must not be wired without
   the deliverable-2 mask (a naive `.trustAll(flag)` would let an untrusted client's `X-ProxyHost`
   win).

## Standing Constraints

- Pre-1.0 rules: no deprecation, no compatibility switch for the withheld headers; an operator who
  genuinely needs one re-admits it through a positive list only if outline keeps that path open.
- No new dependency. Logging through `CuiLogger` with `%s`; any new INFO/WARN/ERROR record is a
  `LogRecord` documented in `doc/LogMessages.adoc` with the next free identifier, derived at write
  time and never assumed.
- Tests: JUnit 5, AAA, no Mockito/PowerMock/Hamcrest. Every fix starts with a test that FAILS
  against the unfixed code; a fix whose test cannot be made to fail is a refuted premise and loops
  back to re-scope (record it in the plan's inbox message).
- `DispatchStage` is a concurrency hot spot (three race fixes in PR #532). Confine deliverable 1 to
  attempt attribution; do NOT restructure the hand-over / departure / publish ordering. Run the
  existing `InboundBodyDepartureTest`, `UpstreamAttemptReleaseOrderTest` and
  `GatewayEdgeHttp2AbuseTest` with `-DargLine=-XX:ActiveProcessorCount=1` in addition to the normal
  run.
- Do not touch `edge/terminal/TerminalDispatch.java` (PLAN-SEC-01's surface); do not wire
  `forwarded.trust_scheme_host` (tier-3 follow-up).
- Where the project analyses its pull requests with Sonar (`resolve` reports `sonar: configured`): an
  open Sonar issue is fixed in a pushed commit, or dismissed with a stated reason recorded as
  `{issue-key}: {reason}` in the run's progress record and transmitted through the plan-less
  transition verb, `plan-marshall:workflow-integration-sonar:sonar_rest transition --issue-key {key}
  --transition {falsepositive|wontfix}`. A dismissal the run cannot transmit is a blocker, never a
  silent pass. The merge gate reads the open-issue count at the head — `0` confirmed, or every
  remaining issue dismissed — and an `undecidable` read blocks.

## Claim Labels

- OBSERVED: `FORWARDING_HEADERS` holds `x-forwarded-for/-host/-proto/-port/-prefix` and `forwarded` only; `isSkippedByModeCopy` tests that set plus `Cookie`; `copyInboundHeadersExcept` copies every other non-denied name — read at `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/forward/ForwardPolicyStage.java` § `FORWARDING_HEADERS` / `isSkippedByModeCopy` / `copyInboundHeadersExcept`
- OBSERVED: `REQUEST_STRIP` strips hop-by-hop names, `host`, `origin`, `referer`, `date`, the `x_forwarded_*` underscore spellings, `x-real-ip`, `x-client-ip`, `true-client-ip`, `cf-connecting-ip` — and none of the names in the Objective — read at `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/http/ConnectionHeaders.java` § `REQUEST_STRIP`
- OBSERVED: no test under `api-sheriff/src/test` or `integration-tests/src` mentions `x-proxy` — swept 2026-11-07 at `7f5c61af`
- HYPOTHESIS: the default forward mode is forward-all for both dimensions when a route declares no `forward` block — confirm/refute at `api-sheriff/src/main/resources/schema/endpoint.schema.json` § `$defs/forward` and `ForwardPolicyStage` § `process` (verify-at-outline)
- HYPOTHESIS: cui-http 3.2 `ForwardedHeaderResolver` queries the `X-Proxy*` names and honours them only under `trustAll`, which the gateway never sets — confirm/refute at cui-http 3.2 `ForwardedHeaderResolver` § `resolve` and `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/edge/GatewayEdgeRoute.java` § `ForwardedResolverConfig` construction (verify-at-outline)
- OBSERVED: `GrpcDispatchStage` builds its `DispatchStage` with `DispatchStage.NO_BODY_IDLE_TIMEOUT` (no idle clock, no deadline) — read at `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/edge/upstream/GrpcDispatchStage.java` § constructor
- OBSERVED: the breaker is `requestVolumeThreshold(20)`, `failureRatio(0.5)`, `skipOn(GatewayException.class)` — read at `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/edge/upstream/UpstreamGuards.java` § `guardFor`
- HYPOTHESIS: Vert.x `HttpClientRequest.send(ReadStream)` writes the upstream HEADERS only with the first body chunk, so an idle gRPC client leaves the upstream with nothing until the 30 s guard timeout — confirm/refute at vertx-core 4.5.34 `HttpClientRequest` § `send(ReadStream)` and by the deliverable-1 regression test (verify-at-outline)
- Verify-first clause: before scoping deliverable 1, settle whether `UpstreamAttempt` can tell "never published" from "published, no response" at guard-timeout time without new synchronisation; if it cannot, prefer the time-to-first-message alternative and record the choice.

## Expected Surface

- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/forward/ForwardPolicyStage.java` — `FORWARDING_HEADERS`, `isSkippedByModeCopy`, `applyRegeneratedForwarding`, class Javadoc
- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/http/ConnectionHeaders.java` — `REQUEST_STRIP`, Javadoc
- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/edge/upstream/DispatchStage.java` — `UpstreamAttempt` attribution on guard timeout
- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/edge/upstream/GrpcDispatchStage.java`
- HYPOTHESIS: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/edge/upstream/UpstreamFailureMapper.java` — only if the client-attributed timeout needs its own mapping (verify-at-outline)
- OBSERVED: `api-sheriff/src/test/java/de/cuioss/sheriff/gateway/forward/ForwardPolicyStageTest.java`
- OBSERVED: `api-sheriff/src/test/java/de/cuioss/sheriff/gateway/edge/upstream/GrpcDispatchStageTest.java`
- HYPOTHESIS: `api-sheriff/src/test/java/de/cuioss/sheriff/gateway/edge/upstream/InboundBodyDepartureTest.java` — if the forced-h2 stub rig lives there (verify-at-outline)
- OBSERVED: `doc/adr/0003-forwarded-headers.adoc`
- OBSERVED: `doc/architecture/routing-and-forwarding.adoc`
- OBSERVED: `doc/configuration/routing-and-forwarding.adoc`
- OBSERVED: `doc/security-threat-model.adoc` — forwarded-header trust rows
- HYPOTHESIS: `doc/LogMessages.adoc` — only if a new LogRecord is added (verify-at-outline)

## Dependencies and Sequencing

- Depends on: none.
- Overlaps with: PLAN-SEC-01 and PLAN-SEC-03 share only documentation — `doc/security-threat-model.adoc` and `doc/LogMessages.adoc` (two files; `next` asks the operator).
- Adjacent to: `edge/terminal/TerminalDispatch.java` (PLAN-SEC-01) — the gRPC dispatch is entered from there, but the attribution change stays in `edge/upstream/`; `config/model/ForwardedConfig.java` (`trust_scheme_host`, tier 3) — untouched; `edge/upstream/EgressClients.java` (pool wait bound, tier 2) — untouched.

## Hand-Off Command

```text
/plan-marshall task="implement .plan/orchestrator/security-hardening-tier-1/plans/PLAN-SEC-02-upstream-attribution-and-forward-headers.md"
```

## Write-Boundary

The plan implementing this spec touches only its own repository source and tests. It creates
and edits NO file under `.plan/orchestrator/` other than its own
`inbox/{sender}-{seq}` message — the orchestrator owns every other ledger write — and reports
its outcome through its PR and its inbox message. The inbox exception's qualifiers and the
sole sanctioned write mechanism are stated in
`persona-plan-orchestrator/standards/orchestration-model.md` § Ledger Write-Boundary.
