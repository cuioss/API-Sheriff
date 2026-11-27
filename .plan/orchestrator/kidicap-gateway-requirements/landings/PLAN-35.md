# Landing Analysis: PLAN-35 — Fix pass: logout and pending-store hygiene, edge cleanups, JWKS and doc follow-ups

epic: kidicap-gateway-requirements
workstream: WS-01
pr: #665 (squash `3e530b3e`, merged through the merge queue; merge-group run 45998587131 success)

> Landing record for one shipped plan. Analysed 2026-11-27 by `analyze` from inbox message
> `plan-35-fix-pass-bff-edge-jwks-hygiene-001.md`. The message is a lead, not a fact: the PR state and the merge commit were
> re-read through the CI abstraction and git (`pr queue-state`: `pr_state: merged`, `merge_commit_sha 3e530b3e…`, `origin/main` HEAD).

## Deliverable Fidelity vs Spec

The spec had eleven numbered slots; **D10 was dropped on 2026-11-25** (the verifier class it named was deleted by #663), so ten were live
(D1-D9, D11). The landing says `deliverables_total=11`, `deliverables_done=11`: that counts one outline-added item in place of the dropped
slot. By the diff, **10 of 10 live spec deliverables are done.**

| Deliverable (spec) | Verdict | Evidence |
|--------------------|---------|----------|
| D1 `Vary: Accept` on negotiated error responses | shipped-as-specified | `ErrorResponseWriter` :407-414 seeds it before the Accept branch; all three call sites carry it (`renderProblem` :643, `ReservedPathHandler` :235, `TerminalDispatch` :787) |
| D2 WebSocket relay pong handler | shipped-as-specified | pong handler removed (`WebSocketRelayStage` :526-530), the two dialer helpers merged; `pongResetsTheIdleTimer` |
| D3 per-reason logout revocation latch (ApiSheriff-147) | shipped-modified | latch per two reasons (provider discovery, revocation request), not three: `RpInitiatedLogout` :258; the three-reason premise failed at outline |
| D4 PLAN-26 layer-2 residue | shipped-modified | ADR-0080 link dropped, `destroyIfCurrent` test pins stateless behaviour, cookie IdP case moved to `BffRuntimeProducerTest` (PR body) |
| D5 pending-store fairness ADR and eviction signal | shipped-as-specified | ADR-0085, `ApiSheriff-154`, `InMemoryPendingAuthorizationStore` :175-185 |
| D6 "100 ms apart" wording | shipped-modified | `UpstreamGuards`, ADR-0064, `routing-and-forwarding.adoc` fixed; **one site missed** (below) |
| D7 PLAN-28 configuration leftovers | shipped-as-specified | `TokenValidationRules` range constants, `TokenValidationRulesJwksRefreshTest` |
| D8 five held `RetryingJwksLoader` fixes | shipped-as-specified | `settleRefresh` :734, `RefreshBasis.of` inside the shield :598-606, `runRetry` keep-OK :514-524, three new tests |
| D9 PLAN-33 JWKS findings | shipped-modified | degrade-after-admission test; the L3-3 lead was fixed rather than accepted |
| D10 logout verifier WARN reason | dropped, then partly implemented | the spec said "nothing is implemented here"; the landing implemented the residue anyway: `LogoutRejection.KEY_SOURCE_UNAVAILABLE`, `LogoutTokenVerifier.engine`, `keySourceUnavailable` (`BackchannelLogoutReceiver` :137), `ApiSheriff-112`, about 150 test lines |
| D11 hygiene sweep | shipped-as-specified | raw-value echo already rendered through `renderForMessage` in `config/validation/rule/` (no production change); `@since`, doc lag |
| (added at outline) `HtmlErrorPageIT` Vary assertions | added-unplanned | `HtmlErrorPageIT` :464-484, retargeted from `PortalIT` |

## Three-Layer Analysis

The landing was read on three layers by one read-only leaf (`execution-context-level-3`), and the orchestrator re-read the points marked **re-read** before recording them. By reading: no build, no test run, no write.

- **Dispatch:** one read-only leaf per landing — reason: reading 59 merged files for the three layers is the depth the dispatch rule exists for.
- **Verdict:** layer 1 findings (3 minor, spec-authoring); layer 2 findings (3 minor); layer 3 findings (4 minor, 0 blocker) and 4 not shown by reading.

### Layer 1 — Specified

| Finding | Claim | Verdict | Polarity | Evidence |
|---------|-------|---------|----------|----------|
| L1-1 | D10's residue is routed to "deliverable 1 (the logout-WARN neighbourhood)", but D1 is `Vary: Accept` and the logout WARN is D3; D10 also says nothing is implemented, and the landing implemented it | checked | refutes | spec :121 |
| L1-2 | Stale gating text after the banner re-ground: the surface still says `RetryingJwksLoader` was "rewritten by PLAN-34 D6" (also :104-106, :152), while the banner says D6 was dropped | checked | refutes | spec :198 vs :22-24 |
| L1-3 | "Eleven deliverables" in the header against "Ten live" in the banner; keeping the dropped slot numbered makes `deliverables_total=11` ambiguous | checked | refutes | spec :28 |
| L1-4 | D3 assumed three distinguishable revocation-failure reasons; the landing says token-sheriff-client 0.9.10 reports non-success, I/O failure and egress refusal as one `TransportException`; the code shows only that two are classified | not_shown_by_reading | none | `RpInitiatedLogout.RevocationFailureReason` |

**Layer 1 verdict:** findings — 3 (minor); not shown by reading — 1

### Layer 2 — Implemented

| Deliverable | Code trace | Test that can fail | Unexplained file / unborne claim | Verdict |
|-------------|------------|--------------------|---------------------------------|---------|
| D1-D9, D11 | see the table above | each traced to a test that can fail; the accepted removals are safe by reading (the surviving `inventedKeyIdRefreshPublishesANewlyRotatedKey` asserts a superset; `JwksKeyRotationIT` leg 5 removal drops one looser-bounds variant, a small real coverage reduction) | none orphaned | checked |
| narrative | — | — | "two scope deviations" understates the departures: D10's residue was implemented, three D4 deviations and the D9 fix-not-accept sit in the PR body | checked, refutes |
| D6 | the sweep | — | `doc/resources/diagrams/downstream-client-planes.svg` :12 `<desc>` still says "up to 2 retries, 100 ms apart" (**re-read**: found by `git grep` on `origin/main`) | checked, refutes |

**Layer 2 verdict:** findings — 3 (minor)

### Layer 3 — Correct

| Defect | File:line | Triggering input | Effect | Severity |
|--------|-----------|------------------|--------|----------|
| L3-1 | `InMemoryPendingAuthorizationStore` :178-185 (**re-read**); ADR-0085 :138 (**re-read**) | a source at its 256-record cap while other sources hold the rest | the own-oldest branch runs before the saturation check, so the at-cap source is admitted by evicting its own oldest while a source below its share is refused; ADR-0085 says new logins from "any other source" are then refused and its title says "refuses only at saturation", which does not describe the at-cap source. Landing records it as an advisory | minor |
| L3-2 | `RetryingJwksLoader` :789-801 (**re-read**) | the close of the older retired delegate throws during `publishRefreshed` | the delegate just replaced sits in the retired slot with no retirement scheduled and is not closed, and its background refresh keeps running, until the next publication or `close()`; bounded, and absent with grace 0 | minor |
| L3-3 | `SheriffMetrics` :130, :358 (**re-read**) | reading the Javadoc | still says `sheriff_session_events_total` counts BFF session lifecycle events; the edited architecture doc says the pending store is its one recorder | minor |
| L3-4 | `SessionAuthenticationStage` :370, :448 | an Accept-negotiated 302/401/403 | carries no `Vary: Accept`; out of the spec's scope (error pages), not heuristically cacheable | minor |
| L3-5 | `GatewayEdgeUniformNotFoundTest` :390 | a route-derived token joining `Vary` next to `Accept` | the uniform-404 assertion was loosened from `assertNull(Vary)` to "does not contain the tenant header name", so it would not catch another token; an equality check against `Accept` would pin it | minor |
| L3-6 | `RetryingJwksLoader` :514 | a library recovery before the first retry | `recordOutcome` records the retry's time, not the library's load time, so the WARN's "last loaded" is slightly late | minor |

**Layer 3 not shown by reading:** the Vert.x delivery of a PONG frame without a pong handler, the SmallRye 200 ms default jitter behind the UpstreamGuards wording, the `TransportException` claim, a `RuntimeException` from `getIssuerStatuses()` escaping `receive()` (**re-read** the call at `BackchannelLogoutReceiver` :137), the HEAD on-the-wire header, and whether `recordSessionEvent` can block under the store monitor (`SheriffMetrics` :362 does a meter lookup per call).

Confirmed by reading: the eviction-latch arithmetic, the per-reason logout latch (a set of two enum members, the WARN carries only the fixed token), `settleRefresh`'s `finally`, the shielded `RefreshBasis.of`, and `Vary: Accept` at every error call site.

**Layer 3 verdict:** findings — 6 (minor), 0 blocker; not shown by reading — 5

### Conformance to the Project's Own Rules

| Rule source | Rule | Verdict | Evidence |
|-------------|------|---------|----------|
| CLAUDE.md Pre-1.0 | no `@Deprecated`, delete directly | conforms | none in the diff |
| CLAUDE.md Logging | CuiLogger, `%s`, exception first, documented | conforms | WARN 154 template, 147 gains `%s`, rows in `doc/LogMessages.adoc` |
| CLAUDE.md Logging | identifiers unique | conforms | one definition each for 154 and 147 |
| CLAUDE.md Security | no sensitive data in logs | conforms | 154 and 147 carry only closed tokens |
| CLAUDE.md Testing | no Mockito/Hamcrest | conforms | hand-written stubs |
| CLAUDE.md Testing | a key that parses must act | not_shown_by_reading | no config key added; range guard test traced, a run needed |
| CLAUDE.md Javadoc | `@since`, documented | conforms | `RevocationDiscoveryException` |
| CLAUDE.md Sonar | suppress only with a rationale | conforms | the added suppressions carry one; the 0 new-issue scan is a narrative claim |
| CLAUDE.md Build | `-Werror` | not_shown_by_reading | needs a compile |
| cui-logging review rule | every new LogRecord asserted by `LogAsserts` | deviates | `ApiSheriff-154` matched only by its self-resolved identifier, the 147 test builds its expected text from the record's own template |
| java-core review rule | resources closed | conforms | one gap, L3-2 |

## Metrics and Anomalies

- Tokens: 40,182,099 total. Wall clock: 177,783 s (about 49.4 h, the lane's clock; not corroborated against the session clock).
- Anomalies: the first CI run of head `581df2da` failed only in the org's Quarkus alignment check (HTTP 429 from Maven Central), re-run green. The post-merge `sonar_open_new_issues` read was undecidable (the head branch was gone), so it is `unknown`; the pre-merge scan of `581df2da` reads 0 new-code issues, per the narrative.

## Routing and Merge Behavior

- Review: CodeRabbit, 0 unresolved threads. Re-read at 2026-11-27T16:38Z (`review-gate --pr-number 665`): **`review_body_findings_total` 9** (7 inline plus 2 nitpicks), which matches the narrative's reading. `review_body_findings_handled` reads 0 from the provider, which cannot see dispositions; **not re-verified** (the run's progress file was not read), never recorded as a difference. The landing carries both numbers as `unknown`.
- CI/merge: merged through the merge queue, merge-group run 45998587131 success. **The post-merge Maven Build and Integration Tests on `3e530b3e` were still running when the landing was written; the CI abstraction cannot read push runs on `main` (routed to plan-marshall as message 024), so this analysis did not verify them.**
- Sonar: `unknown` in the landing; not re-read here.
- Collision signal: surface delta `expansion_detected` (declared 38 paths, realized 59): 31 realized files were not declared (including `ErrorPageClassifier`, `EventType`, `BackchannelLogoutReceiver`, `LogoutRejection`, `BffRuntimeProducer`, `TokenValidationRules`, `ConfigLogMessages` and 19 test files) and 10 declared files were untouched (including `CookieSessionBinding`, `PendingAuthorizationStore`, the four `*Rules` classes, `UpstreamGuardsTest`). This is the epic's sixth measurement of declared surfaces under-stating footprints; no spec is corrected here because PLAN-35 is shipped.

## Reconciliation Actions

- [x] row `status` → `shipped`
- [x] row `pr` stamped — `665`
- [x] row `landing` stamped — `landings/PLAN-35.md`
- [x] row `plan_marshall_plan_id` already stamped — `plan-35-fix-pass-bff-edge-jwks-hygiene`
- [x] epic.md narrative reconciled (Open Defects, Watches, queue annotations)
- [x] PLAN-20 re-grounded against `3e530b3e` (7 verdicts persisted, surface and ordinals corrected)
- [x] resume anchor updated; `queue-view.md` regenerated

## Follow-Ups

- The advisory items (L3-1 wording, L3-2 retired-delegate leak, L3-3 Javadoc, L3-5 stricter 404 assertion, the missed SVG site, the two weak `LogAsserts`) are unstaged leads: recorded as Open Defects, staged only on the operator's word.
- The landing is incomplete (`review_body_findings_total`, `review_body_findings_handled`, `sonar_open_new_issues` are `unknown`): the same gap class already routed to plan-marshall.
