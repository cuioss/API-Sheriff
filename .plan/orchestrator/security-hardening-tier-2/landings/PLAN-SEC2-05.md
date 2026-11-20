# Landing Analysis: PLAN-SEC2-05 — Early-response quiet exit

epic: security-hardening-tier-2
workstream: WS-04
pr: #633 (https://github.com/cuioss/API-Sheriff/pull/633), merged through the merge queue as `7f09b60b`

> Landing record for one shipped plan. Written by the `analyze` verb after verifying the landing
> message against ground truth (git, the PR through the CI abstraction, the merged code). Disclosure is
> mechanism-level: possible weaknesses the read raised that are not yet vetted are reported to the
> operator in the session and in the git-ignored private notes, and are NOT written here (this file is
> tracked).

## Deliverable Fidelity vs Spec

Landing facts: `deliverables_total=3`, `deliverables_done=3`; the spec names 2 deliverables (the lane
counts 3). The lane's functional review closed 2 deliverables as met and 1 as met with residuals
(`deliverables_not_met=0`). The landing facts block is complete (`landing-check`: `complete: true`).

| Deliverable (spec) | Verdict | Evidence |
|--------------------|---------|----------|
| 1. A confirmed quiet exit for the early-response end | shipped-as-specified | The verify-first gate held: Vert.x 4.5.34 `HttpConnection#ping` through the public API gives a PING and an ordered acknowledgement. `EarlyResponseEnd.Quiescence` (`EarlyResponseEnd.java:167-263`) sends a PING when the window has stood unmoved for 50 ms and ends quietly only if the window is still unmoved at the acknowledgement; `NOTHING_SENDABLE` and `TIME_CAP` and the 1 s cap are kept (`:142-150`, `:198-200`). The PING goes through `InboundStreamWindow.ping()` (`InboundStreamWindow.java:178-190`). The Javadoc states the rule (`EarlyResponseEnd.java:54-77`) and the GW-08 passage of the threat model was rewritten. Test: `GatewayEdgeEarlyResponseResetTest` (200 trickling early-answered uploads, 100 ms gaps behind a 50 ms-each-way relay, limit 20) with matched controls for a continuous sender, a fast trickler and a stopped sender; `EarlyResponseEndTest` unit-tests the state machine with synthetic times |
| 2. Documentation and the framing corpus | shipped-modified | The Javadoc, GW-08, `edge-security.adoc`, the architecture page and ADR-0066 describe the confirmed exit. The SVG's `<desc>` still states the superseded rule (see Layer 3). The framing corpus result (53/53 with `-XX:ActiveProcessorCount=1` during execute) is a run result and is not shown by reading; the landing says it was not re-run in that mode on the final code |
| Added by the lane | added-unplanned | The PING bound per connection (100, the edge's stream limit), the event-loop confinement of `ping()`, the `AcknowledgementRelay`, and the move-and-return restart are review and audit fixes, each in the code and each pinned by a test (`InboundStreamWindowTest:275-333`, `EarlyResponseEndTest:124-173`) |

Surface delta (declared 9 entries, realized 14): 8 realized paths were never declared:
`InboundStreamWindow.java` (new, the PING seam and its bound), `InboundStreamWindowTest.java`,
`GatewayEdgeEarlyResponseResetTest.java` (in place of the declared `GatewayEdgeHttp2AbuseTest.java`),
`GatewayEdgeRelayFailureResetTest.java` (comment-only), ADR-0066, `doc/architecture/tls-and-inbound-security.adoc`,
the `http2-reset-and-body-timers.svg` diagram and `Http2EarlyResponseResetIT.java`. Three declared paths were
untouched: `ResponseEnds.java` (`endResponse` still calls `endWhenQuiet`, `ResponseEnds.java:132`),
`GatewayEdgeHttp2AbuseTest.java` and `doc/LogMessages.adoc` (only `LOGGER.debug` records were added). Every
undeclared path is explained by a deliverable. `expansion_detected`. The declaration of the landed spec is not
corrected (the plan is shipped). The 14 files are the diff of `7f09b60b` against its first parent.

## Three-Layer Analysis

The landing was read on three layers — the spec, the deliverable trace, and the merged code — by one
read-only leaf, and the orchestrator re-read the points marked below at their `file:line`. The reading is
**by reading, no build, no test run, no write**: what only a run could show is tagged `not shown by reading`.

- **Dispatch:** one read-only leaf per landing — reason: the change is a new transport-facing state machine with a per-connection counter and an event-loop hand-off, read against the Vert.x source; reading that is the depth the dispatch rule exists for.
- **Verdict:** layer 1 findings — 1 minor; layer 2 findings — 3 minor; layer 3 findings — 2 minor (documentation), plus items reported to the operator only. No blocker or major defect.

### Layer 1 — Specified

The four `OBSERVED` / `HYPOTHESIS` claims are persisted on the spec (`by security-hardening-tier-2/analyze`):
the two `OBSERVED` claims `corroborated` at `fa8dd54f` (10 ms / 50 ms / 1 s constants and the quiet outcome,
`EarlyResponseEnd.java:78-82,148`), the PING-hook `HYPOTHESIS` `corroborated`, the reset-counting `HYPOTHESIS`
`unverifiable`.

| Finding | Claim | Verdict | Polarity | Evidence (file:line) |
|---------|-------|---------|----------|----------------------|
| L1-1 | A PING can be sent and acknowledged from the stream's connection handler without an unsupported hook (claim 3) | checked | confirms — persisted `corroborated` | Vert.x 4.5.34 `Http2ConnectionBase#ping` / `onPingAckRead`; `InboundStreamWindow.java:186`. The Quarkus 3.40.1 resolved version was not read (re-read: `InboundStreamWindow.java:178-190`) |
| L1-2 | The transport counts every reset except `NO_ERROR` and `CANCEL` against 200 in 30 s (claim 2) | not_shown_by_reading | none — persisted `unverifiable` | the repository only states the rule (`security-threat-model.adoc:2146`); Netty's counting was not read |
| L1-3 | A PING acknowledgement proves nothing further is in flight on the stream | checked | confirms (minor, spec form) | it proves only that frames the client sent before it received the PING arrived first; a sender whose gap is just above the 50 ms check can still send between its acknowledgement and the reset, and a sender whose gap falls between 50 ms and 50 ms plus one round trip never gets a quiet exit and runs to the 1 s cap. Both limits are stated in the Javadoc, the ADR and the threat model; the Done-when covers only a sender that ends |

**Layer 1 verdict:** findings — 1 minor (L1-3); L1-2 not shown by reading

### Layer 2 — Implemented

| Deliverable | Code trace | Test that can fail | Unexplained file / unborne claim | Verdict |
|-------------|------------|--------------------|---------------------------------|---------|
| 1. Confirmed quiet exit | `EarlyResponseEnd.java:167-263`, `InboundStreamWindow.java:178-190` | `GatewayEdgeEarlyResponseResetTest:526` (200 uploads); controls at `:605`, `:639`, `:568`; `EarlyResponseEndTest` | none | checked (test-first order not visible in a squashed commit; that the trickle test fails at the old code is plausible by reading, not shown) |
| 2. Docs and corpus | Javadoc, GW-08, `edge-security.adoc:634-642`, architecture `:129-150`, ADR-0066 | the corpus run | the SVG `<desc>` | checked for the docs; the corpus result is not shown by reading |

- **L2-1 (minor).** The trickle test cannot reach the quiet exit: each upload sends four frames 100 ms apart and then ends its body, so every PING is made moved by the next frame and the wait ends only when the body ends (`NOTHING_SENDABLE`). It proves the PING defers the premature reset; it does not prove that a slow sender that keeps going is safe. Evidence: `GatewayEdgeEarlyResponseResetTest.java:186-193,526`.
- **L2-2 (minor).** The stopped-sender test asserts only that a PING reached the client (`GatewayEdgeEarlyResponseResetTest.java:592`) and sets no elapsed bound, so a 1 s cap exit with a lost acknowledgement looks like a quiet exit; `Http2EarlyResponseResetIT.java:182,214` has the same blind spot. This is the lane's own advisory, confirmed.
- **L2-3 (minor).** No test touches `AcknowledgementRelay` (`EarlyResponseEnd.java:378-398`): that `release()` drops the wait, that a delivery after the end is a no-op, or that a late acknowledgement does not end the wait twice; the PING-refused-then-retried glue is also not driven end to end. The lane's advisory, confirmed.

**Layer 2 verdict:** findings — 3 minor; the corpus result and the test-first order not shown by reading

### Layer 3 — Correct

| Defect | File:line | Triggering input | Effect | Severity |
|--------|-----------|------------------|--------|----------|
| L3-1 | `InboundStreamWindow.java:74-76`; the same sentence in `security-threat-model.adoc:2154-2157` and ADR-0066 | a wait ends by `NOTHING_SENDABLE`, the cap or a client end while its PING is still unacknowledged | the documented claim "a client that acknowledges its PINGs never reaches the bound, because each early answer waits on at most one PING" is not guaranteed: the count is decremented only when the PING future completes (`:186-187`), so a finished wait's PING stays counted until its acknowledgement, and new streams on one connection can add PINGs meanwhile; on a high-latency connection with many short slow uploads the count can pass 100. Effect: a PING refused, the check restarts, the wait runs longer or to the cap; the bound itself still holds. Documentation overstates; ADR-0066 `:355-356` ("at most 100 pending") is accurate | minor |
| L3-2 | `doc/resources/diagrams/http2-reset-and-body-timers.svg:12` | read the diagram's `<desc>` | still states the superseded rule ("50 ms passes with no DATA frame"); the visible text (`:85-86`) was updated, so description and drawing disagree | minor |

Also recorded, not defects: ADR-0066 has no alternatives-rejected entry for comparing the stream's own receive window, which the lane evaluated and did not ship (`doc/adr/0066-…adoc`, alternatives section); the compared value `min(stream window, connection window)` can read unchanged while data arrives, which is documented (`InboundStreamWindow.java:65-68`, ADR, threat model `:2166-2170`, `EdgeHardeningOptions.java:97-104`).

**Layer 3 not shown by reading:** a synchronous throw from `connection.ping(...)` after the counter increment (`InboundStreamWindow.java:182-186`) would leak one count; no trigger is read (Vert.x throws only for a payload that is not 8 bytes, fixed at `:94`). A `window.schedule` on an event loop already shut down would throw out of `look()`; shutdown only, caller guard not read. Whether the quiet exit works across real link latency, and the framing corpus, need a run.

**Layer 3 verdict:** findings — 2 minor (L3-1, L3-2); no blocker or major; release on every exit, event-loop confinement, first-in first-out acknowledgement attribution and the self-terminating timer hold by reading

### Conformance to the Project's Own Rules

| Rule source | Rule | Verdict | Evidence |
|-------------|------|---------|----------|
| CLAUDE.md § Logging | `CuiLogger`, `%s` only, exception first | conforms | `EarlyResponseEnd.java:121,321,335` |
| CLAUDE.md § Logging | INFO/WARN/ERROR as documented `LogRecord`s | conforms | only `LOGGER.debug` added; `doc/LogMessages.adoc` untouched |
| CLAUDE.md § Testing | JUnit 5, AAA, no Mockito/PowerMock/Hamcrest | conforms | zero hits in the four changed test files |
| CLAUDE.md § Dependency Management | no new dependency | conforms | no `pom.xml` in the diff |
| CLAUDE.md § Javadoc | `@since`, `package-info.java` | conforms | `package-info.java` updated; public types carry `@since` |
| CLAUDE.md § Pre-1.0 | no deprecation, no shim | conforms | no `@Deprecated`; the old rule removed in place |
| spec | no new `gateway.yaml` key; `edge/upstream/**` and `edge/terminal/**` untouched | conforms | the 14-file diff |
| spec | every code change starts with a test that fails first | not_shown_by_reading | needs a run |

The orchestrator re-read these points at `7f09b60b`: `InboundStreamWindow.java:70-78,92-95,176-190`,
`EarlyResponseEnd.java:226-232,256-262,305-313`, the SVG `<desc>` (`:12`) and its visible text (`:84-87`),
`security-threat-model.adoc:2152-2158` and `GatewayEdgeEarlyResponseResetTest.java:156-160,186-193,588-600`. The
others (Vert.x library lines, `EarlyResponseEnd.java:54-77,167-263,378-398`, the test bodies not listed) were taken
from the leaf and are recorded as read by it, not re-read.

## Metrics and Anomalies

- Tokens: 25,539,647 (the landing's `total_tokens`).
- Duration: 43,238 s of wall-clock (about 12 h), including a long wait on the merge path and review.
- Model: `claude-opus-5-5` with `effort=low` as the landing reports it; plugin and executor 0.1.2149.
- Anomalies: a CI-only intermittent PING-acknowledgement timeout (the race fixed by confining `ping()` to the event loop); a `merge_candidate_not_gated` refusal in finalize after a commit that moved HEAD (lesson candidate, routed to plan-marshall); the landing's `emit-landing` step is `in_progress` and `archive-plan` `pending` at write time, which is normal for a landing emitted before archival.

## Routing and Merge Behavior

- Review: CodeRabbit reviewed, Sourcery did not (its weekly quota, optional bot). The landing claims `review_body_findings_total=2` / `review_body_findings_handled=2`. The provider shows **3** review-body findings (two inline, one nitpick) with no disposition (`runbook_lane review-gate --pr-number 633` on the merged head: `body_findings_undisposed`). The landing's total is therefore a **false claim**; `review_body_findings_handled` is **not re-verified** (the run's progress file was not available to re-read). `unresolved_review_threads=0` is confirmed.
- CI/merge: merged through the merge queue as `7f09b60b`, merge-group run `44242298620` concluded `success`; the PR's checks are green (`build / conclusion`, `integration-tests / conclusion`, SonarCloud, CodeRabbit). The PR-attached post-merge run (Performance Benchmark, `Run Integration Benchmarks`) is `SUCCESS`. The main-branch Maven Build and Integration Tests runs for `7f09b60b` were **not read**: the CI abstraction exposes no lookup by merge commit.
- Sonar: the landing claims `sonar_open_new_issues=0`; this analysis did not re-read the figure (the PR's SonarCloud check is green; a merged PR's count cannot be re-read, as for #623, #626 and #627). Recorded as **not re-verified**, not as a `0` measured here.

## Reconciliation Actions

- [x] row `status` → `shipped` — `orchestrator queue --transition PLAN-SEC2-05 --status shipped`
- [x] row `pr` stamped — `orchestrator queue --set-row PLAN-SEC2-05 --field pr --value 633`
- [x] row `landing` stamped — `orchestrator queue --set-row PLAN-SEC2-05 --field landing --value landings/PLAN-SEC2-05.md`
- [x] row `plan_marshall_plan_id` stamped — `--value sec2-05-early-response-quiet-exit`
- [x] epic.md narrative reconciled (queue annotation for PLAN-SEC2-05, decisions, watches)
- [x] re-grounding verdicts persisted on the spec (4 claims)
- [x] resume anchor updated in `resume_anchor.md`
- [x] `queue-view.md` regenerated

## Follow-Ups

- L3-1, L3-2, the ADR-0066 alternatives entry, and the three test blind spots (L2-1 to L2-3) are small and PLAN-SEC2-04 is running, so they cannot be folded into it; whether to stage a small follow-up plan is the operator's call, recorded in `epic.md` Decisions.
- The two lessons the plan sent for plan-marshall (the finalize re-stamp and the unverified-upstream replies) are routed to the sibling lessons inbox.
- The two Vert.x and Netty facts the plan sent as lessons (`ping()` on the event loop; the stream's own receive window cannot prove quiescence) are carried by the merged code and docs: `InboundStreamWindow.java:178-181` with its test, and the documented limit of the compared value.

## Addendum 2026-11-20 — review figures re-verified

The Routing section above recorded `review_body_findings_handled` as **not re-verified** and the total as a **false claim** (landing 2, provider 3). Re-verified afterwards, on the operator's request, against `main` at `00ebea64` (the review text is third-party; a read-only agent extracted it and the orchestrator read each conclusion in the merged code):

- **The provider's 3 review-body findings are all handled:** the window-moved-while-the-PING-was-outstanding finding is fixed (`EarlyResponseEnd.java:175,203,229,260`, `movedSincePing`); the overclaim that the quiet check proves no data is in flight is fixed by documenting the limit (`doc/architecture/tls-and-inbound-security.adoc:126-150`); the two-instance PING-capacity nitpick is fixed (`InboundStreamWindowTest.java:277`, `pingBoundIsSharedByEveryViewOfTheConnection`).
- **The false claim stands as recorded** (the landing said 2 total; the provider shows 3), but its consequence is nil: every finding was handled. The Watch that tracked it is retired.
