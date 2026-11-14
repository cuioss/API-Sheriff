# PLAN-SEC-03: Pending-authorization store fairness — no eviction of other users' live login records

epic: security-hardening-tier-1
workstream: WS-03

> Staged plan spec — one shippable unit of work, ready for `/plan-marshall` hand-off.
> Lives at `plans/PLAN-SEC-03-pending-authorization-store-fairness.md` and is queued as one row
> file, `queue/PLAN-SEC-03.json`, in the epic ledger. The orchestrator EMITS the command below; it
> never launches the plan inline. This spec is SELF-SUFFICIENT: the emitted command is a one-line
> pointer and carries no brief, so every per-plan carry is authored here and nowhere else.

## Objective

**M4 (Medium, rank 3, effort M)**, found in the 2026-11-07 ground-truth check of the security
notes (code reading at `main` `7f5c61af`): the BFF's pending-authorization store is bounded
(10,000 records, one per process, shared by login, step-up and widening) and, when full, evicts
its OLDEST records by insertion order with no liveness or expiry check. Login initiation needs no
session and has no rate limiter, and the PAR round trip to the IdP happens before the record is
stored. An anonymous client that initiates more than 10,000 logins within a victim's
redirect-to-callback window (about 330 req/s against a 30 s credential entry, about 5,000 req/s
against a 2 s silent-SSO round trip) evicts every in-flight user's record, so their callbacks fail
with 403: a denial of login for all users mid-flow. This plan makes the store fair: expired records
go first, one source cannot displace another source's live records, and a store full of live
records refuses new initiations instead of breaking someone else's login. A general
login-initiation rate limiter is out of scope (`rate_limit` stays planned).

## Deliverables

1. **Expired records are purged before anything live.** `InMemoryPendingAuthorizationStore.store()`
   first removes expired records (walk from the oldest while `isExpired(now)`), then applies the
   bound. The store's clock source must be the same one `consume` uses (inject or pass `now`;
   decide at outline).
2. **Per-source quota.** Each record carries the requester's source key (the same canonical key the
   edge detection uses — `SourceKeys` / `SourceKeyResolver`: IPv4 address, IPv6 /64 prefix, or
   `unknown`). Each source may hold at most K live records (proposed 32; a constant, documented); a
   source's overflow evicts ITS OWN oldest record, never another source's. The three producers
   (`LoginFlow`, `SessionWidening`, `StepUpCoordinator`) pass the source through; outline settles
   how the BFF reserved-path request obtains it (see the verify-first clause).
3. **Refuse at capacity instead of evicting another user's live record.** When the global bound is
   reached and every record is live, `store` refuses: login initiation (and step-up / widening)
   answers `503` with `Retry-After` through the existing reserved-path rendering. Add an event type
   and a WARN `LogRecord` (next free identifier in the BFF range, derived at write time, documented
   in `doc/LogMessages.adoc`) that carries no request data; log it rate-limited or once per
   transition into the saturated state so a flood cannot amplify log volume.
4. **Tests.** `PendingAuthorizationStoreTest` (or a new store test class): a flood from source A
   cannot evict source B's live record; expired records are purged before any live one; at capacity
   with all records live, `store` refuses; a source over its quota evicts only its own oldest record.
   Endpoint/flow level: `LoginInitiationEndpointTest` answers 503 + `Retry-After` when the store
   refuses. Each store test must fail against the unfixed store.
5. **Documentation.** `doc/security-threat-model.adoc` (around the pending-store residual: the text
   currently says the store "is bounded and whose records expire after five minutes", implying eager
   expiry and omitting eviction) states the new fairness rules; `doc/user/bff-session.adoc` mentions
   the 503 at saturation if it documents login failure modes; `doc/LogMessages.adoc` row for the new
   record.

## Standing Constraints

- Pre-1.0 rules: the `PendingAuthorizationStore` interface may change freely; no deprecation.
- No new dependency. Logging through `CuiLogger` with `%s`, exception first; never log a source
  address, state, nonce, PKCE verifier or any record content.
- Tests: JUnit 5, AAA, no Mockito/PowerMock/Hamcrest. Every store fix starts with a test that FAILS
  against the unfixed store; a fix whose test cannot be made to fail is a refuted premise and loops
  back to re-scope (record it in the plan's inbox message).
- The store stays thread-safe (it is `synchronized` today); document the thread-safety in Javadoc.
- Do not touch `edge/**`, `forward/**` or `tls/**` production code (PLAN-SEC-01 / PLAN-SEC-02);
  reading `edge/admission/SourceKeys` for the key is fine, changing it is not.
- Where the project analyses its pull requests with Sonar (`resolve` reports `sonar: configured`): an
  open Sonar issue is fixed in a pushed commit, or dismissed with a stated reason recorded as
  `{issue-key}: {reason}` in the run's progress record and transmitted through the plan-less
  transition verb, `plan-marshall:workflow-integration-sonar:sonar_rest transition --issue-key {key}
  --transition {falsepositive|wontfix}`. A dismissal the run cannot transmit is a blocker, never a
  silent pass. The merge gate reads the open-issue count at the head — `0` confirmed, or every
  remaining issue dismissed — and an `undecidable` read blocks.

## Claim Labels

- OBSERVED: `store()` puts the record and calls `evictOldestBeyondCapacity()`, which removes the oldest records by insertion order with no liveness or expiry check; expired records are dropped only on `consume` — read at `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/bff/pending/InMemoryPendingAuthorizationStore.java` § `store` / `evictOldestBeyondCapacity` / `consume`
  - verdict: corroborated | checked_at: 3eef748b | by: security-hardening-tier-1/analyze | rescoped: n/a | evidence: base 3eef748b^1 InMemoryPendingAuthorizationStore.store put + evictOldestBeyondCapacity, no expiry test; expiry only in consume
- OBSERVED: the bound is `DEFAULT_MAX_PENDING = 10_000`, one per-process store shared by login, step-up and widening — read at `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/quarkus/BffRuntimeProducer.java` § `DEFAULT_MAX_PENDING` and the store construction
  - verdict: corroborated | checked_at: 3eef748b | by: security-hardening-tier-1/analyze | rescoped: n/a | evidence: base BffRuntimeProducer DEFAULT_MAX_PENDING = 10_000, one store instance shared by LoginFlow, SessionWidening, StepUpCoordinator
- OBSERVED: the three producers call `pendingStore.store(pending)` — read at `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/bff/login/LoginFlow.java`, `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/bff/login/SessionWidening.java`, `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/bff/refresh/StepUpCoordinator.java` § `store` call sites
  - verdict: corroborated | checked_at: 3eef748b | by: security-hardening-tier-1/analyze | rescoped: n/a | evidence: base LoginFlow:140, SessionWidening:173, StepUpCoordinator:178 call pendingStore.store
- OBSERVED: the record TTL is a fixed 5 minutes — read at `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/bff/pending/PendingAuthorizationRecord.java` § `FIXED_TTL`
  - verdict: corroborated | checked_at: 3eef748b | by: security-hardening-tier-1/analyze | rescoped: n/a | evidence: base PendingAuthorizationRecord FIXED_TTL = Duration.ofMinutes(5)
- HYPOTHESIS: `LoginFlow` performs the PAR request (`authorization.authorize(scopes)`) before storing the record, so each flood request also costs one IdP round trip — confirm/refute at `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/bff/login/LoginFlow.java` § `authorize` call vs `store` call (verify-at-outline)
  - verdict: corroborated | checked_at: 3eef748b | by: security-hardening-tier-1/analyze | rescoped: n/a | evidence: LoginFlow.initiate calls authorization.authorize(scopes) (the PAR push) before pendingStore.store, at base and at 3eef748b
- HYPOTHESIS: no limiter of any kind applies to successful login initiations (`rate_limit` is planned, not implemented; `SourceGate` only counts pre-trust rejections) — confirm/refute at `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/config/model/RateLimitConfig.java` and `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/edge/admission/SourceGate.java` § `admit` (verify-at-outline)
  - verdict: corroborated | checked_at: 3eef748b | by: security-hardening-tier-1/analyze | rescoped: n/a | evidence: RateLimitConfig is planned-not-implemented and read by no request-time component; SourceGate.admit only admits reported rejections; only the global, non-per-caller EdgeAdmission cap applies
- HYPOTHESIS (derived count): the flood rates (≈330 req/s for a 30 s window, ≈5,000 req/s for 2 s) are arithmetic `10,000 / window`, not measurements — confirm/refute by the deliverable-4 tests at store level; no load test is required
  - verdict: corroborated | checked_at: 3eef748b | by: security-hardening-tier-1/analyze | rescoped: n/a | evidence: arithmetic on the 10,000 bound (330/s x 30 s, 5,000/s x 2 s), labelled as derived
- Verify-first clause: before scoping deliverable 2, settle how the BFF reserved-path request (`BffRuntime.ReservedHttpRequest` / `ReservedPathHandler`) can carry the canonical source key computed at the edge, without the BFF package depending on edge internals beyond `SourceKeys`; if it cannot be plumbed cleanly, fall back to keying the quota on the peer address and record the decision.

## Expected Surface

- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/bff/pending/InMemoryPendingAuthorizationStore.java`
- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/bff/pending/PendingAuthorizationStore.java`
- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/bff/pending/PendingAuthorizationRecord.java`
- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/bff/login/LoginFlow.java`
- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/bff/login/SessionWidening.java`
- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/bff/refresh/StepUpCoordinator.java`
- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/bff/reserved/LoginInitiationEndpoint.java` — 503 + `Retry-After` on refusal
- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/quarkus/BffRuntimeProducer.java` — store construction
- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/bff/BffLogMessages.java` — new WARN record
- HYPOTHESIS: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/events/EventType.java` — new event constant (verify-at-outline)
- HYPOTHESIS: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/bff/runtime/BffRuntime.java` — source plumbing into the reserved request (verify-at-outline)
- HYPOTHESIS: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/edge/reserved/ReservedPathHandler.java` — source plumbing (verify-at-outline; read-only use of `SourceKeys`)
- OBSERVED: `api-sheriff/src/test/java/de/cuioss/sheriff/gateway/bff/pending/PendingAuthorizationStoreTest.java`
- OBSERVED: `api-sheriff/src/test/java/de/cuioss/sheriff/gateway/bff/reserved/LoginInitiationEndpointTest.java`
- OBSERVED: `api-sheriff/src/test/java/de/cuioss/sheriff/gateway/bff/login/LoginFlowTest.java`
- OBSERVED: `doc/LogMessages.adoc`
- OBSERVED: `doc/security-threat-model.adoc` — pending-store residual
- HYPOTHESIS: `doc/user/bff-session.adoc` — only if it documents login failure modes (verify-at-outline)
- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/tls/PassthroughRelay.java` — added at landing (2026-11-09): operator-authorised relayed-peer hand-over (PR #581); collided with PLAN-SEC-01
- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/tls/RelayedPeerRegistry.java` — added at landing: new class
- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/tls/TlsEdgeProducer.java` — added at landing; collided with PLAN-SEC-01
- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/tls/SniFrontListener.java` — added at landing; collided with PLAN-SEC-01
- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/tls/package-info.java` — added at landing
- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/edge/request/EdgeRequests.java` — added at landing: relayed-peer lookup
- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/pipeline/PipelineRequest.java` — added at landing
- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/edge/GatewayEdgeRoute.java` — added at landing
- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/edge/error/ErrorResponseWriter.java` — added at landing
- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/bff/runtime/ForwardedTrust.java` — added at landing: new class
- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/bff/runtime/PendingQuotaKeys.java` — added at landing: new class
- OBSERVED: `api-sheriff/src/test/java/de/cuioss/sheriff/gateway/tls/SniFrontListenerTest.java` — added at landing; collided with PLAN-SEC-01
- OBSERVED: `api-sheriff/src/test/java/de/cuioss/sheriff/gateway/tls/TlsEdgeProducerTest.java` — added at landing; collided with PLAN-SEC-01
- OBSERVED: `doc/development/tls-edge.adoc` — added at landing; collided with PLAN-SEC-01

## Dependencies and Sequencing

- Depends on: none.
- Overlaps with: PLAN-SEC-01 and PLAN-SEC-02 share only documentation — `doc/security-threat-model.adoc` and `doc/LogMessages.adoc` (two files; `next` asks the operator). If outline adds `edge/reserved/ReservedPathHandler.java` for source plumbing, it stays disjoint from PLAN-SEC-01 (`edge/terminal/`, `edge/relay/`, `tls/`).
- Adjacent to: `bff/reserved/CallbackEndpoint.java` (`consume` is unchanged); `bff/session/**` (session store, untouched); logout CSRF and back-channel logout (other tiers, untouched).

## Hand-Off Command

```text
/plan-marshall task="implement .plan/orchestrator/security-hardening-tier-1/plans/PLAN-SEC-03-pending-authorization-store-fairness.md"
```

## Write-Boundary

The plan implementing this spec touches only its own repository source and tests. It creates
and edits NO file under `.plan/orchestrator/` other than its own
`inbox/{sender}-{seq}` message — the orchestrator owns every other ledger write — and reports
its outcome through its PR and its inbox message. The inbox exception's qualifiers and the
sole sanctioned write mechanism are stated in
`persona-plan-orchestrator/standards/orchestration-model.md` § Ledger Write-Boundary.
