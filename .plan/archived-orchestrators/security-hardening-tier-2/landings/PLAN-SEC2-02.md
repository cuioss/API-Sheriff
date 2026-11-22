# Landing Analysis: PLAN-SEC2-02 — Upstream breaker accounting

epic: security-hardening-tier-2
workstream: WS-02
pr: #627 (https://github.com/cuioss/API-Sheriff/pull/627), merged through the merge queue as `14741380`

> Landing record for one shipped plan. Written by the `analyze` verb after verifying the
> landing message against ground truth (git, the PR through the CI abstraction, the merged code).
> Disclosure is mechanism-level: possible weaknesses the read raised that are not yet vetted are
> reported to the operator in the session and are NOT written here (this file is tracked).

## Deliverable Fidelity vs Spec

Landing facts: `deliverables_total=5`, `deliverables_done=5`, matching the spec's 5. The lane's
functional review closed 3 deliverables as met and 2 as met with residuals (`deliverables_not_met=0`).

| Deliverable (spec) | Verdict | Evidence |
|--------------------|---------|----------|
| 1. A connection wait that runs its full bound counts against the upstream | shipped-modified | The expired wait ends as `DispatchStage.UpstreamConnectionWaitExpired` (a `TimeoutException`, not a `GatewayException`), mapped to `UPSTREAM_TIMEOUT` (504), counted by the breaker and refused by the retry predicate (`DispatchStage.java:956-959`, `allowsRetryAfter` :700-707); `poolSaturationOf` keeps only the full wait queue as saturation (:770-776). `UpstreamPoolSaturationTest.ExpiredWaitUnderLoad` (23 queued waits against a pool of one) with an answered-upstream control. The spec named `UPSTREAM_POOL_SATURATED` as the starting point; the landing chose the spec's first option |
| 2. Breaker neutrality for client-ended and queue-full outcomes | shipped-modified | The spec's verify-first gate was not run as written: the landing replaced the SmallRye breaker with a gateway-owned `UpstreamCircuitBreaker` (277 lines, `call` :146-163) between the retry and the timeout, recording success / failure / neutral, and recorded the replacement in ADR-0008, ADR-0062 and ADR-0064. `UpstreamGuardsTest.interleavedGatewayRejectionsDoNotDiluteTheWindow` and `UpstreamCircuitBreakerTest` |
| 3. A departed client releases its connection wait | shipped-partial | The dispatch now ends at once as `ClientDeparted` and the breaker records nothing (`clientDeparted` :1220-1231, `departed` future raced against the response). **Not met: the wait-queue slot is not freed** (Vert.x offers no way to withdraw a queued wait; a handed-over connection is reset unsent) — the spec's Done-when clause said it would be. The lane declared this itself and recorded it in ADR-0060. A body-carrying request departs as `INBOUND_BODY_ABORTED`, not `ClientDeparted` (:1288), and the test uses a faked client promise, not the specified "pool of one, saturated" |
| 4. Connection wait vs request-body deadline; dead branches | shipped-as-specified | The body deadline still counts from the attempt start; `DispatchStageTest.BodyDeadlineBehindASlowConnection` pins it. `UpstreamConnectionTooLate`, `GRPC_HAND_OVER_GRACE_MILLIS`, `NO_HAND_OVER_GRACE` removed, no leftover reference at `14741380`; `UpstreamGuards` Javadoc rewritten |
| 5. Documentation | shipped-modified | ADR-0060 (anchors `upstream-connection-wait`, `breaker-accounting`), ADR-0064 (`amendment-neutral-outcome`), GW-07, `edge-security.adoc`, `LogMessages.adoc`; the narrative's "no log records, identifiers or levels" is borne out: the `ApiSheriffLogMessages` and `LogMessages.adoc` edits are description-only, `EventType` edits are Javadoc-only |

Surface delta (declared 13 entries, realized 31): 15 realized paths were never declared:
`UpstreamCircuitBreaker.java` (new), `ApiSheriffLogMessages.java`, `EventType.java`,
`TerminalDispatchGrpcClientDepartureTest.java` (in `edge/terminal`), ADR-0008, ADR-0062, ADR-0064, four
`doc/architecture/` files, `doc/configuration/routing-and-forwarding.adoc`, `doc/features-analysis.adoc`,
the `downstream-client-planes.svg` diagram and `doc/user/protocol-routes.adoc`. No declared path was
untouched. `expansion_detected`: the replacement breaker of deliverable 2 is what the declaration missed.
The declaration of the landed spec is not corrected (the plan is shipped). The 31 files are the diff of
`14741380` against its first parent `a4597a2b`; the base the leaf was given (`fa8dd54f`) also spans the two
ledger PRs #628 and #629, which are not part of this landing.

## Three-Layer Analysis

The landing was read on three layers — the spec, the deliverable trace, and the merged code — by one
read-only leaf, and the orchestrator re-read the points recorded below at their `file:line` (list at the end).
The reading is **by reading, no build, no test run, no write**: what only a run could show is tagged
`not shown by reading`.

- **Dispatch:** one read-only leaf per landing — reason: the merged diff is 31 files / +3026 −816, with a 277-line new state machine and a 3559-line `DispatchStage`; reading it is the depth the dispatch rule exists for.
- **Verdict:** layer 1 findings — 3 minor; layer 2 findings — 1 major-adjacent (deliverable 3 partially met), 5 minor; layer 3 findings — 3 minor recorded here, plus items reported to the operator only (see below).

### Layer 1 — Specified

The three open `HYPOTHESIS` claims are persisted on the spec at `14741380` (`by security-hardening-tier-2/analyze`);
the five `OBSERVED` claims keep their `corroborated` verdicts from `31595bca`.

| Finding | Claim | Verdict | Polarity | Evidence (file:line) |
|---------|-------|---------|----------|----------------------|
| L1-1 | A pre-guard short-circuit can make outcomes breaker-neutral (claim 5) | checked | refutes — persisted `contradicted` | a client-ended or queue-full outcome arises during the guarded call, so no step before it can know the outcome; the lane took neither branch of the spec's verify-first clause and replaced the breaker |
| L1-2 | A pool of 5 never opens the breaker above ~0.3 req/s (claim 4) | not_shown_by_reading | none — persisted `unverifiable` | the arithmetic is not shown by reading |
| L1-3 | `UpstreamConnectionTooLate` is unreachable (claim 7) | not_shown_by_reading | none — persisted `unverifiable` | removal is complete; that no wait exceeds 10 s (HTTP/2 stream-slot wait, name resolution) is a Vert.x runtime semantic |
| L1-4 | Deliverable 3's "wait-queue slot is free" is achievable | not_shown_by_reading | none | the spec never checked that Vert.x can withdraw a queued wait; the lane says it cannot |
| L1-5 | An expired wait means the upstream holds the connections | checked | refutes (minor, spec form) | ADR-0060 itself says a dispatch holds a pooled connection while it forwards the client's body, so the pool can also be held by clients; the spec, and the ADR text the landing wrote from it, name only the upstream-held case |

**Layer 1 verdict:** findings — 3 minor (L1-1, L1-5, plus the premise behind L1-4); L1-2 and L1-3 not shown by reading

### Layer 2 — Implemented

| Deliverable | Code trace | Test that can fail | Unexplained file / unborne claim | Verdict |
|-------------|------------|--------------------|---------------------------------|---------|
| 1 | `DispatchStage.java:944-960`, `:770-776`, `:700-707` | `UpstreamPoolSaturationTest.ExpiredWaitUnderLoad` with control; expectations differ from the old 503/neutral behaviour, so they go red on revert. "Failing at `6ee137df`" was not run | none | checked |
| 2 | `UpstreamGuards.java:141-190`, `UpstreamCircuitBreaker.java` | `UpstreamGuardsTest.interleavedGatewayRejectionsDoNotDiluteTheWindow`, `UpstreamCircuitBreakerTest`. Older tests (`DispatchStageTest`, `GrpcDispatchStageTest`, `TerminalDispatchGrpcClientDepartureTest:285`, `InboundBodyDepartureTest:267`, `UpstreamPoolSaturationTest:114`) still build a SmallRye breaker with `skipOn(GatewayException)`; their "stays closed" assertions cannot tell neutral from success | ADR-0008/0062/0064 explained by the breaker replacement, not by the spec's ADR-0060-only wording | checked |
| 3 | `DispatchStage.java:1220-1231`, `:925-940` | `InboundBodyDepartureTest:296-322`: ends at once, late-handed connection reset unsent, breaker records nothing; does not assert a free queue slot (the code does not free it) | the slot clause is unmet, declared by the lane | deviates |
| 4 | removals complete | `DispatchStageTest.BodyDeadlineBehindASlowConnection` | none | checked |
| 5 | docs | n/a (`order: none`) | diagram, `features-analysis.adoc`, `routing-and-forwarding.adoc` explained by "where the old behaviour was described" | checked |

Stale wording left behind (minor): `TerminalDispatch.java:510` still says "which the breaker skips"; several
test class comments say the same; the `DispatchStage` class Javadoc (:244-247) says an attempt whose settlement
reached the dispatch before the departure "keeps its own outcome", which holds for a failure but not for a
success — `handOver` (:1485-1491) returns the departure whenever one is recorded, so a response that settled
successfully first is still refused and ends neutral (the lane's own advisory); `GrpcDispatchStage.java:57-71`
says the breaker counts an upstream timeout "in three ways" and then describes a third item with a
sub-case, which reads as four (the lane's second advisory, here judged ambiguous wording, not an error).

Evidence of verification: the PR's own CI is green on the final head (below). The lane closed its in-run code
and functional reviews by operator override for the documentation-only head `f41bb267`; the last validated
head is `0369236a`. Whether the new tests fail at `6ee137df` and whether the merged tree compiles under `-Werror`
are **not shown by reading**.

**Layer 2 verdict:** findings — 6 (1 partial deliverable: 3; 5 minor: the unreachable-slot clause, stale comments, SmallRye-breaker test fixtures that cannot tell neutral from success)

### Layer 3 — Correct

| Defect | File:line | Triggering input | Effect | Severity |
|--------|-----------|------------------|--------|----------|
| L3-1 | `DispatchStage.java:244-247` | n/a (Javadoc) | The class Javadoc overstates which outcomes keep their own result before a client departure | minor |
| L3-2 | `GrpcDispatchStage.java:57` | n/a (comment) | "three ways" against a list that reads as four | minor |
| L3-3 | `TerminalDispatch.java:510` | n/a (Javadoc) | "which the breaker skips" is stale against "neutral" | minor |

The `UpstreamCircuitBreaker` state machine was read in full and is consistent: permits are bound to a
generation so a late outcome from a superseded generation is ignored; each admitted permit settles exactly once
in `finally`; a neutral trial returns its permit; the ring buffer judges the ratio only on a full window; the
open delay is measured on a wrap-safe monotonic clock (`:146-264`).

Further layer-3 items concerning the attribution of an expired connection wait, the attribution of a client
departure, a latent state-change listener path and the ADR statements that describe them were returned by the
leaf and re-read at their lines. They are unvetted weakness-class observations, so they are reported to the
operator in the session and are not written into this tracked record. They are planned in PLAN-SEC2-04 as
verify-first items; the trigger detail is in the private notes.

**Layer 3 not shown by reading:** the departure-versus-settlement ordering depends on Vert.x `Future.andThen`
completing the returned future after its handler ran; no concurrent test of the breaker exists (thread-safety
is argued by reading); whether SmallRye's timeout interrupt reaches the breaker as `TimeoutException` or
`InterruptedException`; whether the SmallRye breaker's `ft.circuitbreaker.*` Micrometer meters existed and are
now gone (ADR-0064 says transitions still surface "as the same WARN records and metrics").

**Layer 3 verdict:** findings — 3 recorded here (minor), plus items reported to the operator only

### Conformance to the Project's Own Rules

| Rule source | Rule | Verdict | Evidence |
|-------------|------|---------|----------|
| CLAUDE.md § Pre-1.0 | no deprecation, no shim, remove dead code | conforms | grace constant, `UpstreamConnectionTooLate` and branches deleted |
| CLAUDE.md § Logging | `CuiLogger`, `%s`, `LogRecord` documented | conforms | no new log call or record |
| CLAUDE.md § Dependency Management | no new dependency, no new `gateway.yaml` key | conforms | no `pom.xml`, no config class in the diff |
| CLAUDE.md § Testing | JUnit 5, AAA, no Mockito/Hamcrest, interleaving controlled not slept | conforms | proxy fakes, futures and latches |
| CLAUDE.md § Javadoc | accurate public documentation | deviates | L3-1, L3-2, L3-3 |
| CLAUDE.md § Sonar | fix by default; a suppression carries a rationale | not shown by reading | the `java:S107` suppression on the 7-argument `GrpcDispatchStage` constructor was removed; the gate was not read for a merged PR |
| CLAUDE.md § Pre-Commit Process | `-Werror` compile, quality gate | not shown by reading | no run |
| spec Standing Constraints | production code only under `edge/upstream/` (plus registries); no `bff/**`, `edge/admission/Source*`, `detection/**` | conforms | only `edge/upstream/` main files plus Javadoc-only `EventType` and `ApiSheriffLogMessages` |
| spec D2 | verify first, STOP if a short-circuit cannot work | deviates | the gate was not exercised as written; the breaker was replaced |
| spec D3 | Done-when: wait-queue slot free for the next request | deviates | declared unmet by the lane |
| CodeRabbit file cap | PR under 100 files | conforms | 31 files |

Points re-read by the orchestrator at `14741380`: `DispatchStage.java:238-251`, `:695-776`, `:915-969`,
`:1215-1294`, `:1478-1497`; `UpstreamCircuitBreaker.java:95-277`; `GrpcDispatchStage.java:52-76`;
`TerminalDispatch.java:505-511`; `UpstreamFailureMapper.java:36-46`; ADR-0060 lines 28-40 and 280-290;
`EgressClients.java` pool options. Corrections to the leaf's lines: `UpstreamCircuitBreaker.call` is at `:146-163`
(the leaf cited `:112-115`). Not re-read: `InboundBodyDepartureTest:296-322`, `UpstreamGuards:141-190` and the
older tests' SmallRye-breaker fixtures (recorded as the leaf's reading, unverified lead).

## Metrics and Anomalies

- Tokens: 52,836,629; wall clock 99,337 s (about 27.6 h)
- Model: `claude-opus-5-5`; effort `low`; harness `claude`; plugin and executor `0.1.2135` (the lane's stamp; not refreshed here)
- Anomalies: the final head `f41bb267` is a documentation-only commit that answered CodeRabbit's last comment; the in-run code and functional reviews were closed by operator override for it; CodeRabbit's review of that head was rate-limited and its re-review refused as already on record; `archive-plan` was still pending at emission

## Routing and Merge Behavior

- Review: CodeRabbit. `runbook_lane review-gate --pr-number 627` on the merged head: `unresolved_review_threads: 0`; `review_body_findings_total: 4` against the landing's **3** — a **false claim** (the landing asserted a merge-gate state the provider does not show); `review_body_findings_handled: 0` against the landing's 3 — **not re-verified** (the dispositions live in the lane's progress file, which was not read), and the provider shows `merge_allowed: false, reason: body_findings_undisposed` for four inline CodeRabbit findings (`inline-1` to `inline-4`). The `CodeRabbit` check on the PR is `NEUTRAL` (skipped).
- CI/merge: merged through the merge queue (`merge_mechanism=merge_queue`, `action=rebased`); `ci pr view` shows `state: merged`, `merge_commit_sha: 14741380…`; the PR's 25 checks are green or skipped, including the PR-attached `Performance Benchmark` run on the final head `f41bb267` (success), `build / conclusion`, `integration-tests / conclusion` and the `SonarCloud` check (pass). The merge-group runs and the main-branch runs for the merge commit were read from the run list: `Maven Build` (push, success), `Integration Tests` (push, success), `Config Validation`, `Demo Client E2E` and `Scorecard` (success), `Maven Build` and `Integration Tests` (merge_group, success). The post-merge verification the landing left to the orchestrator is therefore done: nothing failed.
- Sonar: the landing's `sonar_open_new_issues` is `0`; `read_open_issues` on PR 627 returns `count_status: undecidable` (`head_revision_unavailable`, analysis revision `f41bb267`), so the figure is **not re-verified** — the same structural read gap already routed to the plan-marshall inbox. The PR's own `SonarCloud` check passed.

## Reconciliation Actions

- [x] row `status` → `shipped` — `orchestrator queue --transition PLAN-SEC2-02 --status shipped`
- [x] row `pr` stamped — `627`
- [x] row `landing` stamped — `landings/PLAN-SEC2-02.md`
- [x] row `plan_marshall_plan_id` stamped — `sec2-02-upstream-breaker-accounting`
- [x] epic.md narrative reconciled (queue annotations, a Watch for the review-body findings, the decision for the fold)
- [x] layer-1 verdicts persisted on the PLAN-SEC2-02 spec (claim 5 `contradicted`, claims 4 and 7 `unverifiable`)
- [x] leftovers folded into the PLAN-SEC2-04 spec (new deliverable 12, additions to deliverable 9, declared surface, sequencing)
- [x] resume anchor updated; `queue-view.md` regenerated and committed

## Follow-Ups

- The review-body count (3 claimed, 4 in the provider) is a false claim; handling of the four inline findings is **unverified** until the operator confirms it: Watch in `epic.md`.
- Attribution of an expired connection wait, attribution of a client departure, the latent listener path, the ADR statements, the SmallRye-breaker test fixtures and the unmet wait-queue clause: folded into PLAN-SEC2-04 deliverable 12.
- The stale "breaker skips" wording, the `DispatchStage` precedence Javadoc and the `GrpcDispatchStage` "three ways" comment: folded into PLAN-SEC2-04 deliverable 9.
- ADR-0060 is now 517 lines (the ceiling is 500): the plan that touches it replaces text and does not grow it.
