# PLAN-29: Four Upstream Keys That Parse and Act on Nothing

epic: kidicap-gateway-requirements
workstream: WS-02

> Staged plan spec — one shippable unit of work, ready for `/plan-marshall` hand-off.
> Lives at `plans/PLAN-29-upstream-inert-keys.md` and is queued as one row file,
> `queue/PLAN-29.json`, in the epic ledger. The orchestrator EMITS the command below; it never launches
> the plan inline. This spec is SELF-SUFFICIENT: the emitted command is a one-line pointer and carries no
> brief.
> Staged 2026-10-13 from inbox message `integration-test-evidence-follow-ups-001.md` and from PLAN-27's
> landing (`landings/PLAN-27.md`, message `integration-test-evidence-follow-ups-009.md`).

## Objective

A route's `upstream` block accepts four keys the gateway binds, validates and never reads:
`connect_timeout_ms`, `read_timeout_ms`, `retry.max_attempts` and `retry.idempotent_only`. An operator who
sets a 2-second connect timeout gets the gateway's fixed 30-second dispatch timeout; one who sets three
attempts gets two. PLAN-27 (#433) removed the fifth such key, `circuit_breaker`, and recorded in ADR-0064
that these four remain. This plan settles each of them: the key either acts, or it is gone.

Unlike PLAN-27, this plan **may change runtime behaviour**. That is its purpose.

## Source

Inbox `integration-test-evidence-follow-ups-001.md` (finding, 2026-10-12): the remainder of
code-correctness finding DP-2. ADR-0064 § "What this decision does not settle" names the same four keys.
Promoted lesson `2026-10-13-06-003` carries the documentation side.

## Deliverables

1. **Decide and record, per key, whether it is wired or removed.** Four keys, two decisions (the timeout
   pair, the retry pair). Pre-1.0: a removed key is deleted with no deprecation, and a descriptor still
   declaring it is refused at load, as ADR-0064 did for `circuit_breaker`. ⚠ **Put both decisions to the
   operator at outline time, with a recommendation each**; do not settle them silently. Record the
   outcome in a new ADR, or as an amendment to ADR-0064 if both pairs are removed. Derive the ADR ordinal
   by listing `doc/adr/` at write time.
2. **Carry out the timeout decision.** If wired: `connect_timeout_ms` and `read_timeout_ms` reach the
   upstream client and the dispatch guard, and the documented `504` follows from them. State how the
   per-route value relates to the guard's fixed 30 s timeout (replaces it, or is capped by it).
   If removed: delete the keys from `UpstreamConfig`, the schema and the reflection registration, and add
   the load-refusal test.
3. **Carry out the retry decision**, the same way. If wired: `max_attempts` replaces the fixed
   `maxRetries(2)` and `idempotent_only` governs what `DispatchStage.allowsRetryAfter` already vets;
   state the bounds `ConfigValidator` enforces.
4. **Plan DP-6 with the timeout half.** The quality report says the 30 s guard timeout strands a pooled
   upstream connection when it fires. Confirm or refute it against the code first. If it holds and the
   timeout decision is "wire", fix it here; if it holds and the decision is "remove", record it as still
   open with the reason. ⛔ If the fix reaches beyond the dispatch guard and the upstream client, STOP
   and file a finding to the epic inbox.
5. **Every wired key gets a test that goes red when the key is deleted** (CLAUDE.md: a configuration key
   that parses is not a configuration key that acts). A wired timeout is asserted against an upstream that
   stalls; a wired attempt count against an upstream that fails a counted number of times. Add the rows
   to `doc/development/declared-limit-assertion-coverage.adoc`.
6. **Make the documentation say what the gateway does.** `doc/architecture.adoc` (the `504` row and the
   `DEADLINE_EXCEEDED` row name the two timeout keys as the cause), `doc/adr/0008-data-plane-transport.adoc`,
   `doc/configuration.adoc` (the upstream section, its inert-key note, and the validation-rules line on
   the two timeouts), `doc/user/endpoint-routes.adoc` (the example marked "INERT today"),
   `doc/variants/01-base-gateway.adoc` (an example still carrying `read_timeout_ms: 5000` with no note),
   and the DP-2 and DP-6 entries of `doc/quality-report/code-correctness.adoc` and
   `doc/quality-report/README.adoc`. Sweep every example for all four keys, not only the ones listed.

Split guard: 6 deliverables — within the operator-authorized 12.

## Claim Labels

- OBSERVED: `connectTimeoutMs`, `readTimeoutMs`, `maxAttempts` and `idempotentOnly` occur under `api-sheriff/src/main` only in `UpstreamConfig` (record components and Javadoc); nothing reads them — read by searching `api-sheriff/src/main` for the four names at `128fd07e`
  - verdict: corroborated | checked_at: 128fd07e | by: kidicap-gateway-requirements/analyze | rescoped: n/a | evidence: search of api-sheriff/src/main at 128fd07e: the four names occur only in UpstreamConfig (lines 40-70), no reader
- OBSERVED: the dispatch guard applies `withTimeout().duration(30, SECONDS)` and, for a route with retry enabled, `withRetry().maxRetries(2).delay(100, MILLIS).whenException(DispatchStage::allowsRetryAfter)` — read at `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/edge/GatewayEdgeRoute.java` § the guard builder (lines 2568 to 2571), at `128fd07e`
  - verdict: corroborated | checked_at: 128fd07e | by: kidicap-gateway-requirements/analyze | rescoped: n/a | evidence: GatewayEdgeRoute:2568 withTimeout().duration(30, SECONDS); 2570-2571 withRetry().maxRetries(2).delay(100, MILLIS).whenException(DispatchStage::allowsRetryAfter)
- OBSERVED: only `retry.enabled` acts, through `RouteTableBuilder.resolveRetryEnabled` — read at `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/config/RouteTableBuilder.java` § `resolveRetryEnabled`, at `128fd07e`
  - verdict: corroborated | checked_at: 128fd07e | by: kidicap-gateway-requirements/analyze | rescoped: n/a | evidence: RouteTableBuilder:239 calls resolveRetryEnabled, declared at 371
- OBSERVED: the schema declares all four keys — read at `api-sheriff/src/main/resources/schema/endpoint.schema.json` § `upstream` (lines 211 to 219), at `128fd07e`
  - verdict: corroborated | checked_at: 128fd07e | by: kidicap-gateway-requirements/analyze | rescoped: n/a | evidence: endpoint.schema.json:211-219 declares connect_timeout_ms, read_timeout_ms, max_attempts, idempotent_only
- OBSERVED: `doc/architecture.adoc` names `connect_timeout_ms` / `read_timeout_ms` as the cause of `504` in two rows; `doc/user/endpoint-routes.adoc` marks the keys inert in its example; `doc/variants/01-base-gateway.adoc` carries `read_timeout_ms: 5000` unmarked — read at those files at `128fd07e`
  - verdict: corroborated | checked_at: 128fd07e | by: kidicap-gateway-requirements/analyze | rescoped: n/a | evidence: architecture.adoc:1247 and 1349 name the two timeout keys as the 504 cause; endpoint-routes.adoc:136-140 marks the keys inert; variants/01-base-gateway.adoc:115 carries read_timeout_ms: 5000 unmarked
- HYPOTHESIS: the upstream client is built from a bare `HttpClientOptions` with no connect and no idle timeout, only the egress-TLS settings — the sender's statement; the orchestrator saw the bare options object and did not read the whole method. Confirm/refute at `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/edge/GatewayEdgeRoute.java` § `clientFor` (verify-at-outline)
- HYPOTHESIS: the 30 s guard timeout strands a pooled upstream connection when it fires (DP-6) — taken from the quality report, not re-read. Confirm/refute at `doc/quality-report/code-correctness.adoc` § DP-6 and the dispatch path it cites (verify-at-outline)
- HYPOTHESIS: no descriptor in the repository's own stacks relies on any of the four keys, so removal breaks no suite — not checked. Confirm/refute by searching `integration-tests/src/main/docker/`, `benchmarks/` and `deployment/` for the four keys (verify-at-outline)
- Verify-first clause: settle deliverable 1 before writing code. The two decisions determine which of deliverables 2 to 5 exist in which form, and the surface below is declared for both outcomes.

## Expected Surface

- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/config/model/UpstreamConfig.java`
- OBSERVED: `api-sheriff/src/main/resources/schema/endpoint.schema.json`
- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/edge/GatewayEdgeRoute.java` — if a key is wired
- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/config/RouteTableBuilder.java` — if a key is wired
- OBSERVED: `api-sheriff/src/test/java/de/cuioss/sheriff/gateway/config/load/ConfigLoaderTest.java`, `api-sheriff/src/test/java/de/cuioss/sheriff/gateway/config/model/ConfigModelContractTest.java` — PLAN-27 touched both for the one key it removed
- OBSERVED: `doc/architecture.adoc`, `doc/configuration.adoc`, `doc/user/endpoint-routes.adoc`, `doc/variants/01-base-gateway.adoc`
- OBSERVED: `doc/adr/` — one new record or an amendment to ADR-0064, and `doc/adr/0008-data-plane-transport.adoc`
- OBSERVED: `doc/quality-report/code-correctness.adoc`, `doc/quality-report/README.adoc`, `doc/development/declared-limit-assertion-coverage.adoc`
- HYPOTHESIS: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/quarkus/ConfigModelReflection.java` — if a nested record goes away with a removed key (verify-at-outline)
- HYPOTHESIS: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/config/validation/ConfigValidator.java` — bounds for a wired key; the path is the orchestrator's guess (verify-at-outline)
- HYPOTHESIS: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/edge/DispatchStage.java` — if `idempotent_only` is wired (verify-at-outline)
- HYPOTHESIS: `api-sheriff/src/test/java/de/cuioss/sheriff/gateway/edge/` — unit tests for a wired timeout or attempt count (verify-at-outline)
- HYPOTHESIS: `integration-tests/src/main/docker/sheriff-config/endpoints/` and `integration-tests/src/test/java/de/cuioss/sheriff/gateway/integration/` — only if a wired key is also proven at integration level, or a descriptor there declares a removed key (verify-at-outline)

## Dependencies and Sequencing

- Depends on: PLAN-27 — landed (#433). ADR-0064 is the precedent for removal.
- Overlaps with: PLAN-28 on `GatewayEdgeRoute`-adjacent test fixtures only if PLAN-28's deliverables 6
  or 8 find their cause in the gateway's edge; with PLAN-20, PLAN-21, PLAN-26 and PLAN-28 on
  `doc/configuration.adoc`, `doc/architecture.adoc` and `doc/adr/`. `GatewayEdgeRoute` and
  `UpstreamConfig` are touched by no other staged spec.
- ⚠ **Sequence it after PLAN-28.** PLAN-28 diagnoses two intermittent failures on the dispatch path (an
  edge framing test and the `upload_small` benchmark). Changing the dispatch timeout and retry behaviour
  before those are understood would move the ground under the diagnosis.
- **If a test fails against shipped behaviour this plan did not intend to change, STOP and file a
  finding** to the epic inbox.

## Hand-Off Command

```text
/plan-marshall task="implement .plan/orchestrator/kidicap-gateway-requirements/plans/PLAN-29-upstream-inert-keys.md"
```

## Write-Boundary

The plan implementing this spec touches only its own repository source and tests. It creates
and edits NO file under `.plan/orchestrator/` other than its own
`inbox/{sender}-{seq}` message — the orchestrator owns every other ledger write — and reports
its outcome through its PR and its inbox message. The inbox exception's qualifiers and the
sole sanctioned write mechanism are stated in
`persona-plan-orchestrator/standards/orchestration-model.md` § Ledger Write-Boundary.
