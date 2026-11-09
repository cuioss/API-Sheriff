# Landing Analysis: PLAN-SEC-03 — Pending-authorization store fairness

epic: security-hardening-tier-1
workstream: WS-03
pr: #581 — https://github.com/cuioss/API-Sheriff/pull/581

> Landing record for one shipped plan. Written by the `analyze` verb (inbox scan, message
> `plan-sec-03-pending-authorization-store-fairness-006.md`) after checking the inbox claims against
> git, the merged code and the PR state through the CI abstraction.

## Deliverable Fidelity vs Spec

The plan's outline grouped the spec's 5 deliverables into 3 (`deliverables_total=3`, `deliverables_done=3`); nothing is left uncovered. Functional review: 1 met, 2 met with residuals, 0 not met. Code review: 0 blocking, 1 advisory.

| Deliverable (spec) | Verdict | Evidence |
|--------------------|---------|----------|
| D1 — expired records purged before anything live | shipped-as-specified | `InMemoryPendingAuthorizationStore.store` calls `purgeExpired(now)` first, over an expiry-ordered index (also covers a later record expiring earlier); clock read after the PAR push. Tests: `FairAdmission.shouldPurgeExpiredRecordsFirst`, `shouldPurgeExpiredRecordBehindYoungerLiveOne` |
| D2 — per-source quota, own-oldest eviction | shipped-modified | Each record carries a quota key; a source over its cap evicts only its own oldest; empty source buckets are removed. Key derived independently in the new `bff/runtime/PendingQuotaKeys` (not the edge-stashed `SourceKey`, which exists only when detection is on). Deviation: per-source cap `MAX_PENDING_PER_SOURCE = 256`, where the spec proposed 32; the diff states no rationale, and the docs and `BffPendingLoginBoundIT` are written around 256. Tests: `shouldKeepOtherSourceRecordThroughFlood`, `shouldEvictOwnOldestOverQuota`, `shouldReplaceOwnOldestAtSaturation`, `shouldReleaseSlotOnConsume` |
| D3 — refuse at capacity instead of evicting a live record | shipped-as-specified | Store throws `GatewayException(PENDING_AUTHORIZATION_SATURATED)` → 503 with `Retry-After: 30` via `ErrorResponseWriter`; WARN `ApiSheriff-145` (no substitution, latched per saturation episode with hysteresis below half capacity), documented in `doc/LogMessages.adoc`. Tests: `shouldRefuseAtSaturation`, `SaturationLogging`, `LoginInitiationEndpointTest.SaturatedStore`, `GatewayEdgeRouteBffWiringTest.SaturatedPendingStoreOverEdge` |
| D4 — tests | shipped-modified | All named behaviours tested. `Retry-After` is asserted at the edge and in `ErrorResponseWriterTest`, not in `LoginInitiationEndpointTest` (a sound split). The edge saturation test drives a stub store; the real store is never saturated through the edge or the IT. "Fails against the unfixed store" is not shown by reading: the `store` signature changed, so the tests cannot compile against the old code |
| D5 — documentation | shipped-as-specified | Threat model: new BFF-20 control, three accepted residuals, updated evidence rows; `doc/user/bff-session.adoc` "When too many logins are unfinished"; `LogMessages.adoc` row 145. Extras: `observability-and-errors.adoc`, `bff-oidc-common.adoc`, `doc/development/README.adoc`, `doc/development/tls-edge.adoc` |
| (added, operator-authorised) relayed client address behind the SNI front | added-unplanned | The quota key has to see the real client behind the SNI front, which the spec's "do not touch `edge/**` / `tls/**`" constraint forbade. The operator authorised the expansion during the run: new `tls/RelayedPeerRegistry`, hand-over in `PassthroughRelay`, wiring in `TlsEdgeProducer`, lookup in `edge/request/EdgeRequests` / `PipelineRequest`, and `bff/runtime/ForwardedTrust` extracted from `GatewayEdgeRoute` (behaviour-preserving). Review fixes on the same branch: fail-closed attribution on a miss, closing the backend leg when the client left during the dial, fail-closed on a null local address, and a registry hit honoured only on the terminated listener's port or an unknown local address. `forward/**` untouched |

**Surface vs declaration** (`inbox landing-check` `surface_delta`, base `origin/main` = c86ee5b9, not stale): 18 declared, 68 realized, 50 added, 0 declared-but-untouched. The expansion is the authorised relayed-peer work plus its tests and docs. The declared surface in `plans/PLAN-SEC-03-…md` was corrected at this landing with the production and colliding entries (see Routing).

## Three-Layer Analysis

The landing was read on three layers by one read-only leaf, and the orchestrator re-read every point recorded as `checked` before writing it here. **By reading, no build, no test run, no write.**

- **Dispatch:** one read-only leaf (`execution-context-level-3`, `orchestrator.analyze` resolved via `plan.effort`) — reason: every full-ship landing gets its own leaf; 68 files changed.
- **Re-read by the orchestrator at 3eef748b:** `InMemoryPendingAuthorizationStore` (`MAX_PENDING_PER_SOURCE`, `purgeExpired(now)` first, empty-bucket removal); `LoginFlow.initiate` (PAR before store, clock after push); `EdgeRequests` attribution call; `GatewayEdgeRoute.dispatchProcessing` (virtual-thread hop); `PassthroughRelay.closeBoth` (single release path); `RelayedPeerRegistry.attribute` (port check); `ReservedPathHandler` quota-key computation.
- **Verdict:** layer 1 clean (all 7 claims corroborated; the verify-first clause settled by an independent key derivation); layer 2 findings — 2 minor (per-source cap 256 vs 32 without rationale; "fails against the unfixed store" not shown); layer 3 findings — minor points held outside tracked text.

### Layer 1 — Specified

Claims 0–6 persisted onto the spec with `corpus set-verdict` (producer `security-hardening-tier-1/analyze`, `checked_at` 3eef748b). The spec was grounded at `7f5c61af`; the base was `fbaab864`. PLAN-SEC-01 moved `tls/**` and `edge/**` in between, but not `bff/pending`, `bff/login` or `bff/refresh`, so the claims were not affected.

| Finding | Claim | Verdict | Polarity | Evidence (file:line) |
|---------|-------|---------|----------|----------------------|
| — | 0: oldest-first eviction with no expiry test | checked | confirms | `InMemoryPendingAuthorizationStore.java` at base, `store` / `evictOldestBeyondCapacity` |
| — | 1: one 10,000-record store shared by three producers | checked | confirms | `BffRuntimeProducer.java` at base, `DEFAULT_MAX_PENDING` |
| — | 2: the three producers call `store` | checked | confirms | `LoginFlow:140`, `SessionWidening:173`, `StepUpCoordinator:178` at base |
| — | 3: fixed 5-minute TTL | checked | confirms | `PendingAuthorizationRecord.FIXED_TTL` |
| — | 4: PAR before store | checked | confirms | `LoginFlow.initiate` |
| — | 5: no per-caller limiter on a successful initiation | checked | confirms | `RateLimitConfig` (planned, unread); `SourceGate.admit`. Only the global `EdgeAdmission` cap applies, so "no limiter of any kind" is slightly overstated |
| — | 6: flood rates are arithmetic | checked | confirms | labelled as derived |
| L1-1 | verify-first clause | checked | confirms | settled by deriving the key in `PendingQuotaKeys`; the "no edge internals beyond `SourceKeys`" wording is not met literally (`bff.runtime` imports `forward.TcpPeerGate`), and `ForwardedTrust` in `bff.runtime` is consumed by the edge, a two-way `bff` ↔ `forward`/`edge` dependency |

**Layer 1 verdict:** clean (one wording note on the verify-first clause).

### Layer 2 — Implemented

| Deliverable | Code trace | Test that can fail | Unexplained file / unborne claim | Verdict |
|-------------|------------|--------------------|---------------------------------|---------|
| D1 | `InMemoryPendingAuthorizationStore.store` / `purgeExpired` | `FairAdmission` cases | none | checked |
| D2 | per-source index, `PendingQuotaKeys` | `FairAdmission` cases | cap 256 vs proposed 32, no rationale in the diff | checked |
| D3 | `PENDING_AUTHORIZATION_SATURATED`, `ErrorResponseWriter.seedRetryAfter`, `BffLogMessages` 145 | `SaturationLogging`, `SaturatedStore`, `SaturatedPendingStoreOverEdge` | none | checked |
| D4 | — | as above | red-against-unfixed not shown (signature change) | not_shown_by_reading |
| D5 | threat model BFF-20, `bff-session.adoc`, `LogMessages.adoc` | n/a | none | checked |
| relayed peer (authorised) | `RelayedPeerRegistry`, `PassthroughRelay`, `TlsEdgeProducer`, `EdgeRequests`, `PipelineRequest` | `RelayedPeerRegistryTest`, `PassthroughRelayTest` (incl. `clientGoneDuringTheDialLeavesNothingBehind`), `PendingQuotaKeysTest` | none | checked |

Files no spec deliverable names, each explained: `UniformNotFound`, `ReconEvents`, `EventCategory` (Javadoc only; the closed sets are unchanged, so the 503 stays outside the uniform-404 and recon sets); `ErrorPageClassifier` (one switch arm forced by the new `EventType`); `GatewayEdgeRoute` (`ForwardedTrust` and registry wiring); `SettableClock`, `EdgeFixtures` and signature-only test edits (support). The narrative's hand-resolved rebase conflict left no conflict markers; the merged `PassthroughRelay`, `SniFrontListener` and `TlsEdgeProducer` read coherently with PLAN-SEC-01's changes. A new in-process peer-attribution mechanism and a per-source quota policy landed with no new ADR (`adr-propose` ran after the merge as a no-op).

**Layer 2 verdict:** findings — 2 minor (D2 cap value; D4 red-against-unfixed not shown).

### Layer 3 — Correct

| Defect | File:line | Triggering input | Effect | Severity |
|--------|-----------|------------------|--------|----------|
| L3-A … L3-F | `edge/request/`, `edge/reserved/`, `bff/runtime/`, `bff/pending/`, `tls/` | — | Possible residual weaknesses beyond the threat model's documented residuals. Not investigated, not fixed; per the project's disclosure rule kept out of tracked text, reported to the operator and recorded in the operator's local notes | minor |

No defect found by reading in: the store's synchronization and index consistency (insert/remove/forget update all three indexes together; purge terminates; per-source eviction is net-zero before the capacity check); the registry's register/release balance (one guarded close path, `remove(key, this)` against port reuse); the registry port check; the producer wiring order; the 503 propagation paths (exception escapes before `Location` / `Set-Cookie`; no caller-driven WARN volume); the `ForwardedTrust` extraction (behaviour-preserving).

Already-public accepted residuals, confirmed by reading: the PAR push happens before the saturation check; about 40 sources at their caps saturate the global bound (no live record is evicted, so the M4 broken-callback failure is closed, and the denial moves to new logins); the shared `unknown` bucket.

**Layer 3 verdict:** findings — minor points held outside tracked text.

### Conformance to the Project's Own Rules

| Rule source | Rule | Verdict | Evidence |
|-------------|------|---------|----------|
| CLAUDE.md § Logging | CuiLogger, `%s`, exception first | conforms | relay debug lines; store WARN has no substitution |
| CLAUDE.md § Logging | INFO/WARN/ERROR as LogRecord in range, documented | conforms | `BffLogMessages` WARN 145; `doc/LogMessages.adoc` row |
| spec / CLAUDE.md § Security | never log source address, state, nonce, PKCE, record content | conforms | redacting `toString`s; `ReservedEndpointLogHygieneTest` extended |
| CLAUDE.md § Testing | JUnit 5 AAA, no Mockito/PowerMock/Hamcrest | conforms | hand-written `java.lang.reflect.Proxy` stand-in; `SettableClock` |
| CLAUDE.md § Javadoc | Javadoc + `@since`, package-info | conforms | new classes documented; `tls/package-info` explains the producer-only bean |
| CLAUDE.md § Pre-1.0 | no `@Deprecated`, no shims, no new dependency | conforms | one-arg `store` removed outright; no pom change |
| CLAUDE.md § Sonar | fix or suppress in code with rationale | conforms | `java:S107`, `java:S9068` suppressions each with a rationale |
| CLAUDE.md § Security / never-silent-downgrade | unrecognised input fails closed | conforms | unattributed leg and null local address ignore forwarding headers |
| ADR-0066 | every gateway answer through the end rule | conforms | 503 via the existing `ErrorResponseWriter.handleGatewayRejection` path |
| CLAUDE.md § Pre-commit | gate, 80 % coverage, "a key that parses must act" | not_shown_by_reading | no run allowed; `MAX_PENDING_PER_SOURCE` is a constant with a test |

## Metrics and Anomalies

- Tokens: 68,890,262 total (spans main + sub-agent populations; 5-execute was re-entered)
- Duration: 145,772 s wall (about 40.5 h)
- Model / effort: claude-opus-5-5, effort low; plugin and executor 0.1.2036
- Anomalies: the dispatched `branch-cleanup` agent stalled on its own background merge-queue wait and was stopped; the post-merge half was done by the orchestrator. `adr-propose` (order 62) did not run before the merge and was recorded afterwards as a no-op. `lessons-capture` ran despite a lane `off` request, on explicit instruction, and filed 5 candidate lessons (all plan-marshall tooling; routed to plan-marshall's `lessons-routing` epic, see `epic.md` Decisions). `finalize-step-sync-baseline` did no work: the operator skipped the early rebase, and the rebase onto the moved base happened later with a content conflict.

## Routing and Merge Behavior

- Review: CodeRabbit raised three points on the relayed-peer attribution, all fixed on the branch at the operator's decision (fail-closed attribution, dial-time close, null-local fail-closed, port-scoped hit). Sourcery optional.
- CI/merge: all 24 PR checks green or skipped (`ci checks status --pr-number 581`). Merged through the merge queue as 3eef748b on top of PLAN-SEC-01's c85817d9. The `main` push run for 3eef748b was not checked here — Watch.
- Sonar: `new_code_issue_count=0`, `count_status=confirmed`.
- **Collision the gate did not predict:** PLAN-SEC-03's authorised expansion wrote `tls/PassthroughRelay.java`, `tls/TlsEdgeProducer.java`, `tls/SniFrontListener.java`, `tls/package-info.java`, `SniFrontListenerTest`, `TlsEdgeProducerTest` and `doc/development/tls-edge.adoc` — PLAN-SEC-01's surface — while both ran. The gate saw only the two shared doc files because the spec declared none of that. Result: a content conflict on the rebase after PLAN-SEC-01 merged, resolved by hand. The under-declaring spec's `## Expected Surface` was corrected at this landing.

## Reconciliation Actions

- [x] row `status` → `shipped` — `orchestrator queue --transition PLAN-SEC-03 --status shipped`
- [x] row `pr` stamped — `orchestrator queue --set-row PLAN-SEC-03 --field pr --value 581`
- [x] row `landing` stamped — `orchestrator queue --set-row PLAN-SEC-03 --field landing --value landings/PLAN-SEC-03.md`
- [x] row `plan_marshall_plan_id` stamped — `plan-sec-03-pending-authorization-store-fairness`
- [x] claim verdicts 0–6 persisted onto the spec — `corpus set-verdict` × 7
- [x] spec `## Expected Surface` corrected for the unpredicted collision
- [x] PLAN-SEC-04 re-grounded on 3eef748b (claim 3 contradicted and absorbed: the dial-time close already landed here; deliverable 2 re-scoped)
- [x] epic.md narrative reconciled
- [x] resume anchor updated; `queue-view.md` regenerated and committed

## Follow-Ups

- Per-source cap 256 vs the spec's 32: no rationale in the diff. Watch; the rationale belongs in the threat model or an ADR.
- No ADR for the in-process peer-attribution mechanism and the per-source quota policy: Watch.
- Layer-3 points: with the operator; not staged.
