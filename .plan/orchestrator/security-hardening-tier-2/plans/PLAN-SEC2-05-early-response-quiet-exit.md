# PLAN-SEC2-05: Early-response quiet exit

epic: security-hardening-tier-2
workstream: WS-04

> Staged plan spec — one shippable unit of work, ready for `/plan-marshall` hand-off. Lives at
> `plans/PLAN-SEC2-05-early-response-quiet-exit.md` and is queued as one row file,
> `queue/PLAN-SEC2-05.json`. The orchestrator EMITS the command below; it never launches the plan
> inline. This spec is SELF-SUFFICIENT except for the security detail it deliberately keeps out of
> tracked text (see Security Findings).

## Objective

Stop the gateway's early-response end from making the transport send counted stream resets. When the
gateway answers a request before the request body has finished, it holds the end of the response back
until request data stops arriving, and it takes a window that has not moved for 50 ms to mean nothing is
on the way. A client whose DATA frames arrive further apart than that gets its stream reset while data
is still in flight, and the transport counts those resets against the connection. This plan replaces the
time-based quiet test with a confirmed one, so a slow sender no longer costs the connection its reset
budget, and keeps the existing 1 s cap.

Disclosure is mechanism-level. Background: `.plan/local/security_notes_verified.adoc` entry C2 (the
impact depends on the deployment shape, which stays in the notes). **Read it first.** Local only; never
quote it into tracked text.

## Deliverables

1. **A confirmed quiet exit for the early-response end.** Replace the 50 ms unmoved-window test with one
   the peer confirms: once the request body stops, send a PING on the connection and treat its
   acknowledgement as proof that nothing further is in flight on the stream; keep the 1 s time cap and
   every existing outcome other than the quiet one (`NOTHING_SENDABLE`, `TIME_CAP`). State the rule in
   the Javadoc and in the GW-08 passage of the threat model.
   - Verify first: confirm that the transport exposes a way to send a PING from the stream's
     connection handler and to observe its acknowledgement without a supported hook the gateway lacks
     (Quarkus 3.40.1 / Vert.x 4.5); if it does not, STOP this deliverable, record why in the landing
     message, and keep deliverable 2.
   - Done when: a test where DATA frames arrive more than 50 ms apart and the stream is answered early
     200 or more times keeps the connection, failing at the `main` the plan starts from
     (`order: test-first`); a sender that stops at once, and one that sends continuously, still get the
     response (matched controls); the 1 s cap still ends a stream that never goes quiet.
   - Skills: pm-dev-java:java-core, pm-dev-java:java-quarkus, pm-dev-java:java-security, pm-dev-java:junit-core
   - Persona: security-expert
2. **Documentation and the framing corpus.** Update `EdgeHardeningOptions` Javadoc, GW-08 and the
   edge-security user documentation to describe the confirmed exit; keep the framing tests free of the
   known flake (run the framing corpus with a single processor, as the project's flake note requires,
   and record the result).
   - Done when: each place that described the 50 ms rule describes the confirmed exit (`order: none`);
     the framing corpus runs green with `ActiveProcessorCount=1`.
   - Skills: pm-documents:ref-asciidoc, pm-documents:ref-documentation, pm-dev-java:javadoc
   - Persona: documenter

## Standing Constraints

- Pre-1.0 rules: no deprecation, no compatibility shims.
- **No new dependency.** Logging via `CuiLogger`, `%s`, exception first; any new record is a `LogRecord`
  in `doc/LogMessages.adoc` with the next free identifier at the time of editing.
- Tests: JUnit 5, AAA, no Mockito/PowerMock/Hamcrest. Every code change starts with a test that FAILS
  against the unchanged code; a test that cannot be made to fail refutes the premise — record and drop.
- No new `gateway.yaml` key.
- The HTTP/2 framing corpus has a known flake history: reproduce with `ActiveProcessorCount=1` before
  attributing a failure to this change.
- Documentation states the mechanism only.
- Do not touch `edge/upstream/**` or `edge/terminal/**`.
- One PR, under CodeRabbit's 100-file cap. Revert unrelated formatter churn.
- Sonar: fix, or dismiss only on the operator's selection; `undecidable` blocks.

## Security Findings

Possible weaknesses this plan finds and does not fix on its branch go ONLY into a dated entry appended
to the git-ignored `.plan/local/security_notes.adoc` (plan id, location, what, why not fixed) — never
into the PR, commits, docs or the inbox message.

## Claim Labels

- OBSERVED: the early-response end looks at the stream every 10 ms, takes an unmoved window of 50 ms to mean nothing is on the way, and caps the hold at 1 s — read at `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/edge/end/EarlyResponseEnd.java` § `POLL_MILLIS`, `QUIET_MILLIS`, `MAX_WAIT_MILLIS`, `Outcome next` (main `fa8dd54f`)
  - verdict: corroborated | checked_at: fa8dd54f | by: security-hardening-tier-2/analyze | rescoped: n/a | evidence: EarlyResponseEnd at fa8dd54f: POLL_MILLIS=10 (:78), QUIET_MILLIS=50 (:80), MAX_WAIT_MILLIS=1000 (:82); the 50 ms rule was replaced by #633
- OBSERVED: the quiet outcome is reached when the sendable window has not changed for `QUIET_MILLIS` — read at `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/edge/end/EarlyResponseEnd.java` § `next` (main `fa8dd54f`)
  - verdict: corroborated | checked_at: fa8dd54f | by: security-hardening-tier-2/analyze | rescoped: n/a | evidence: EarlyResponseEnd at fa8dd54f: the quiet outcome is documented (:50) and decided in Outcome next (:148) on an unchanged window for QUIET_MILLIS
- HYPOTHESIS: the transport counts every reset it sends except `NO_ERROR` and `CANCEL` against a 200-in-30 s limit, so a late DATA frame on a stream the gateway reset produces a counted `STREAM_CLOSED` reset — confirm/refute at Netty `Http2MaxRstFrameLimitEncoder` and Vert.x `HttpServerWorker` by a test that drives the trickle (verify-at-outline)
  - verdict: unverifiable | checked_at: 7f09b60b83d9b8c57ff8e5321787eab28a82fb52 | by: security-hardening-tier-2/analyze | rescoped: n/a | evidence: the repository only states the 200-in-30s counting rule (security-threat-model.adoc:2146, edge-security.adoc:634-642); Netty counting was not read and a run is needed
- HYPOTHESIS: a PING can be sent and its acknowledgement observed from the stream's connection handler without an unsupported hook — confirm/refute at `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/edge/end/ResponseEnds.java` § the reset path and the Vert.x `HttpConnection.ping` API (verify-at-outline)
  - verdict: corroborated | checked_at: 7f09b60b83d9b8c57ff8e5321787eab28a82fb52 | by: security-hardening-tier-2/analyze | rescoped: n/a | evidence: Vert.x 4.5.34 Http2ConnectionBase#ping (:458-472) and onPingAckRead (:271-277) give a PING and an ordered ack through the public HttpConnection#ping, used at InboundStreamWindow.java:186; the Quarkus 3.40.1 resolved version was not read
- Verify-first clause: re-measure the notes' trickle case at the `main` the plan starts from before scoping on it; a refutation loops back to re-scope.

## Expected Surface

- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/edge/end/EarlyResponseEnd.java`
- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/edge/end/ResponseEnds.java`
- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/edge/end/package-info.java`
- OBSERVED: `api-sheriff/src/test/java/de/cuioss/sheriff/gateway/edge/end/EarlyResponseEndTest.java`
- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/edge/admission/EdgeHardeningOptions.java`
- OBSERVED: `api-sheriff/src/test/java/de/cuioss/sheriff/gateway/edge/GatewayEdgeHttp2AbuseTest.java`
- OBSERVED: `doc/security-threat-model.adoc` — GW-08 only
- OBSERVED: `doc/user/edge-security.adoc`
- HYPOTHESIS: `doc/LogMessages.adoc` — only if the confirmed exit adds a record (verify-at-outline)

## Dependencies and Sequencing

- Depends on: PLAN-SEC2-02 shipped (it edits `doc/security-threat-model.adoc` and `doc/LogMessages.adoc`).
- Overlaps with: PLAN-SEC2-04 on `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/edge/admission/EdgeHardeningOptions.java`, `doc/security-threat-model.adoc` and `doc/user/edge-security.adoc` — the gate puts the overlap to the operator, or the plan runs after PLAN-SEC2-04.
- Adjacent to: `edge/upstream/**` and `edge/terminal/**` (untouched).

## Hand-Off Command

```text
/plan-marshall task="implement .plan/orchestrator/security-hardening-tier-2/plans/PLAN-SEC2-05-early-response-quiet-exit.md"
```

## Write-Boundary

The plan implementing this spec touches only its own repository source and tests, and appends to the
git-ignored `.plan/local/security_notes.adoc` under Security Findings. It creates and edits NO file
under `.plan/orchestrator/` other than its own `inbox/{sender}-{seq}` landing message — the
orchestrator owns every other ledger write — and reports its outcome through its PR and that inbox
message. See `persona-plan-orchestrator/standards/orchestration-model.md` § Ledger Write-Boundary.
