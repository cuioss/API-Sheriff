# Landing Analysis: PLAN-34 — Adapt to token-sheriff 0.9.10, delete the gateway copies

epic: kidicap-gateway-requirements
workstream: WS-05
pr: #663 (squash `5b95b60b`, merged through the merge queue; merge-group run 45631409257 success)

> Landing record for one shipped plan. Analysed 2026-11-25 by `analyze` from inbox message
> `plan-34-adapt-to-token-sheriff-0-9-10-001.md`. The message is a lead, not a fact: the PR state and the merge
> commit were re-read through the CI abstraction and git (`pr queue-state`: `pr_state: merged`,
> `merge_commit_sha 5b95b60b…`, `origin/main` HEAD is that commit).

## Deliverable Fidelity vs Spec

The spec staged D1-D7; the landing reports six deliverables (D6 dropped, D7 folded as "drop first"). Verdicts below are from the
three-layer read and the diff (`git show --stat 5b95b60b`: 84 files, +5666/-4933).

| Deliverable (spec) | Verdict | Evidence |
|--------------------|---------|----------|
| D1 native-image registration | shipped-as-specified | `TokenClientDslJsonReflection` and its test deleted; the `--initialize-at-run-time=…client.flow` arg removed (`application.properties` :329); JVM-lane guard `TokenClientNativeMetadataTest`. The native proof is a run (461 green at `3f9aa527` per the narrative) and is not shown by reading |
| D2 authorization-request seam | shipped-modified | `PushedAuthorizationRequests` and `QueryResponseModeAuthorizationRequestBuilder` deleted, `ScopedEngineFlows` rebuilt on `authorizePushed`. A second `ParClient` stays (`ScopedEngineFlows` :182) for the step-up re-drive, because the engine has no pushed step-up entry; the spec's D2 consequence and its Adjacent paragraph contradict each other |
| D3 key material and config validators | shipped-modified | `ClientSigningKey` slimmed; PEM loader kept back by decision |
| D4 refresh verification and discovery cache | shipped-as-specified, one test gap | `RefreshResultVerifier.verify` at `TokenRefreshCoordinator` :642 before `rotate`; ApiSheriff-153 added; ADR-0083/0084. The iss-consistency leg has no test of its own |
| D5 validation public seams | shipped-modified | `SignatureOnlyTokenVerifier` and `BearerCredentials` parse replaced by library seams; readiness probe, challenge builder and logout-token classes kept back |
| D6 JWKS loader | dropped | `RetryingJwksLoader` is not in the diff; the reason (the library rule can be exploited with made-up key ids to block a real rotation) is recorded only in the PR body |
| D7 helper hygiene | dropped | "low value, drop first" |

## Three-Layer Analysis

The landing was read on three layers by one read-only leaf (`execution-context-level-3`, read of git and the repository, plus
`git -C /home/oliver/git/TokenSheriff show 0.9.10:<path>` for library behaviour), and the orchestrator re-read the points marked
**re-read** before recording them. By reading: no build, no test run, no write.

- **Dispatch:** one read-only leaf per landing — reason: reading 84 merged files for the three layers is the depth the dispatch rule exists for.
- **Verdict:** layer 1 findings (3 minor, all spec-authoring); layer 2 findings (6 minor, 2 not shown by reading); layer 3 findings — 1 major (operator-accepted), 3 minor not shown by reading. No blocker.

### Layer 1 — Specified

| Finding | Claim | Verdict | Polarity | Evidence |
|---------|-------|---------|----------|----------|
| L1-1 | D2 says adopting `authorizePushed` deletes the producer's `ParClient` and its exclusion control test; the Adjacent paragraph forbids removing the `StepUpHandler` re-drive | checked | refutes | spec :264 vs :380-383; merged code keeps two `ParClient`s (`ScopedEngineFlows` :182, `BffRuntimeProducerTest` :2310/:3340) |
| L1-2 | D4 asks for a red test with a different `iss` and cites OIDC Core 12.2, but the library verifier never compares with the session's issuer, which the spec does not say | checked | refutes | spec :92; surfaced only at review (ADR-0084, threat model :3305) |
| L1-3 | The Done-when "every kept deliverable's named code is deleted" is ambiguous against D3/D5/D7 "only if" clauses and the kept-back parts; the functional review closed by operator override | checked | refutes | spec :146; landing facts 1 met / 5 met-with-residuals |
| — | Pin, ordinals (ADR 0083/0084, WARN 153) and the pom paragraph agree with `b236bf06` | checked | confirms | — |

**Layer 1 verdict:** findings — 3 (minor, authoring)

### Layer 2 — Implemented

| Deliverable | Code trace | Test that can fail | Unexplained file / unborne claim | Verdict |
|-------------|------------|--------------------|---------------------------------|---------|
| D1 | `application.properties` :329 | `TokenClientNativeMetadataTest` (non-vacuity and negative controls) | none; native proof needs a run | checked / not_shown_by_reading |
| D2 | `ScopedEngineFlows` rebuilt | `ScopedEngineFlowsTest` :445 fails if `ResponseMode.QUERY` is dropped | the browser-level Lax-cookie proof is split across tests and native ITs | checked |
| D4 refresh | `TokenRefreshCoordinator` :642 | `TokenRefreshCoordinatorTest` :3384/:3415/:3440/:3452/:3469; foreign-issuer test at :3399 fails only in `validateRefreshedIdToken` | the iss-consistency branch has no test of its own (**re-read** :3399-3403) | checked |
| D5 bindings | `BffRuntimeProducer` :798 and :827 (**re-read**) | only test-local copies: `BackchannelLogoutSignatureSeamTest` :128 (**re-read**), `UserInfoEndpointTest` :334 | re-binding either in the producer turns no JVM test red | checked |
| D4 discovery | `BffRuntimeProducer` :534 | TTL/grace driven on a self-built `CachingDiscoveryResolver` (`BffRuntimeProducerTest` :979, **re-read**); only the 30 s retry goes through the produced runtime | a ttl/grace swap would turn no test red | checked |
| Docs | `authentication-and-oidc.adoc` :252, ADR-0080 :140/:149 | — | still say "next engine use resolves" / "memoized metadata" | checked |
| Wording | `TokenRefreshCoordinator` :123, :678 | — | "foreign issuer" means an untrusted issuer; ~40 touched Java files carry import blank-line churn that landed with the gate churn | checked |
| D6 | `RetryingJwksLoader` not in the stat | — | the drop reason is recorded only in the PR body; no ADR-0063 or threat-model line | not_shown_by_reading |

**Layer 2 verdict:** findings — 6 (minor); not shown by reading — 2

### Layer 3 — Correct

| Defect | File:line | Triggering input | Effect | Severity |
|--------|-----------|------------------|--------|----------|
| L3-1 | `BackchannelLogoutReceiver` :115-125 (**re-read**: the gateway's own DEBUG line is sanitized and the exception is not attached) | an unauthenticated POST to the back-channel logout path with a JWT whose `iss` is an unknown value containing line breaks | the library's `IssuerConfigCache` logs WARN `TokenSheriff-114` with that `iss` raw (leaf read against tag 0.9.10; not re-read by the orchestrator): log forging or flooding (CWE-117), contradicting the spec's "no issuer … in a log". **Operator-accepted 2026-11-25** (threat model :3430-3453); the same library record already exists for bearer tokens | major (accepted) |
| L3-2 | `GatewayReadinessCheck` :228 (**re-read** only as far as the line) | the relaxed-hostname/trust-profile collision or an unresolvable egress profile when no other injection built the runtime first | `discoveryState.get().state()` may throw `GatewayException` outside the redacting catch (:244-256) | minor, not shown by reading |
| L3-3 | `TokenRefreshCoordinator` :643 (**re-read**) | a transient verifier failure during a JWKS outage | `catch (RuntimeException)` ends the session and revokes the successor for every refreshing session; fail-closed by design, availability under a real outage not shown | minor, not shown by reading |
| L3-4 | `BackchannelLogoutReceiver` :115 | unauthenticated logout token while an issuer's key set is still loading | request may be held up to 5 s; whether admission limits bound the amplification is not shown | minor, not shown by reading |

Confirmed by reading: the refresh verifier runs before `rotate()` builds the record and before persist, constant-time-compares `sub`, a sub-less
refreshed access token fails closed (`TokenRefreshCoordinator` :642); `BearerCredentials.extract` maps ABSENT/MALFORMED fail-closed
(`AuthenticationStage` :250). The accepted residual (session issuer not compared) is confirmed: `SessionRecord` holds no issuer, so with
several trusted issuers another trusted issuer's tokens for the same `sub` would pass.

**Layer 3 not shown by reading:** native-image boot and the fail-open legs (`invalid_grant`, `cnf.jkt`), JWKS-wait availability, ID-token validation under key-set outage.

**Layer 3 verdict:** findings — 1 (accepted) and 3 not shown by reading

### Conformance to the Project's Own Rules

| Rule source | Rule | Verdict | Evidence |
|-------------|------|---------|----------|
| CLAUDE.md Pre-1.0 | no `@Deprecated`, delete directly | conforms | no `@Deprecated` in added lines; no shim |
| CLAUDE.md Logging | CuiLogger, `%s`, exception first, LogRecord | conforms | WARN 153 `REFRESH_RESULT_REFUSED`, no arguments |
| CLAUDE.md Logging | new record in `doc/LogMessages.adoc`, asserted | conforms | row 153 added; asserted at `TokenRefreshCoordinatorTest` :3367/:3420/:3480 |
| CLAUDE.md Security | no issuer or token content in a log | deviates | the library WARN `TokenSheriff-114` (L3-1), operator-accepted |
| CLAUDE.md Code Standards | no `@SuppressWarnings` for compiler warnings | conforms | the one addition is `java:S107` with a rationale (`TokenRefreshCoordinator` :383) |
| CLAUDE.md Testing | JUnit 5, no Mockito/Hamcrest, controls | conforms | none in added lines |
| CLAUDE.md Dependencies | none without approval | conforms | neither pom in the diff |
| CLAUDE.md Javadoc | `@since`, documented | conforms | `GatewayReadinessCheck.DiscoveryState` documented |
| spec Standing Constraints | ADR ordinals; amend 0063/0074/0079/0082 | conforms | 0083, 0084 added; 0074/0079/0082 amended |
| spec Standing Constraints | replacement controls proven by a test that goes red when the library control is removed | deviates | iss-consistency, the two producer bindings, TTL/grace wiring |
| spec D5 / ADR-0074 | readiness payload keeps fixed tokens | conforms | `GatewayReadinessCheck`, library probe stays excluded |

## Metrics and Anomalies

- Tokens: 41,222,553 total. Wall clock: 95,887 s (about 26.6 h; the lane's clock, not corroborated against the session clock).
- Anomalies: `effort` is `unknown` (the run stamped none), so the drain-completeness check reads the landing as incomplete (`missing_keys: effort`). The post-merge `checks wait` hung past its 600 s tool bound with every check green and was stopped (non-gating). The functional review closed by operator override at two heads, not on its own evidence; the code review closed clean (0 blocking, 14 advisory).

## Routing and Merge Behavior

- Review: CodeRabbit, 0 unresolved threads. The landing claims `review_body_findings_total=2` / `handled=2`; the re-read at 2026-11-25T16:55Z (`review-gate --pr-number 663`) reads **11 / 0** (all `coderabbit` inline). Per the spec's classification this is `undecidable`: the gate-read time was not retained and no record created after the merge was shown, so it is recorded neither as a false claim nor as a post-merge change. `review_body_findings_handled` is **not re-verified** (the run's progress file was not read).
- CI/merge: merged through the merge queue, merge-group run 45631409257 success; squash message is the PR body per `squash_merge_commit_message: PR_BODY`.
- Sonar: landing says `sonar_open_new_issues=0`; not re-read by this analysis (no Sonar read was made).
- Collision signal: PLAN-34 and PLAN-20 shared 36 declared paths (17 live plus the deleted `PushedAuthorizationRequests` by file; the rest are tests). PLAN-20's declared surface under-declared the real overlap (rewritten tests, ADR-0083); corrected in the same act (see PLAN-20's `## Expected Surface` banner).

## Reconciliation Actions

- [x] row `status` → `shipped` — `orchestrator queue --transition PLAN-34 --status shipped`
- [x] row `pr` stamped — `663`
- [x] row `landing` stamped — `landings/PLAN-34.md`
- [x] row `plan_marshall_plan_id` stamped — `plan-34-adapt-to-token-sheriff-0-9-10`
- [x] epic.md narrative reconciled (Open Defects: incomplete landing and PLAN-34 findings; queue annotations)
- [x] PLAN-20 and PLAN-35 re-grounded against `5b95b60b`, 17 verdicts persisted, PLAN-35 D10 dropped, PLAN-20 deliverable 2 re-scoped
- [x] resume anchor updated; `queue-view.md` regenerated

## Follow-Ups

- Layer-3 L3-1 is operator-accepted already; L3-2 to L3-4 are leads: staged nowhere, recorded as Open Defects for the operator's word.
- Test gaps (iss-consistency, producer bindings, TTL/grace wiring) and the doc drift (`authentication-and-oidc.adoc` rows, ADR-0080) belong to the next fix plan: folded into PLAN-35 as a note on the sweep deliverable, only if the operator wants them in scope.
- CodeRabbit cd316e (19 test files build a `TokenValidator` without closing it) needs one shared test-setup change; no issue filed. Recorded as a Watch.
- The D6 drop reason has no durable home beyond the PR body; ADR-0063 or the threat model should carry it.
