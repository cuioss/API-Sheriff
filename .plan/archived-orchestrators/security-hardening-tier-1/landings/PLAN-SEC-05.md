# Landing Analysis: PLAN-SEC-05 — gRPC request-stream attribution and SNI front follow-ups

epic: security-hardening-tier-1
workstream: WS-02
pr: #600 — https://github.com/cuioss/API-Sheriff/pull/600

> Landing record for one shipped plan. Written by the `analyze` verb (inbox scan, message
> `plan-sec-05-grpc-request-stream-attribution-001.md`) after checking the inbox claims against
> git, the merged code and the PR state through the CI abstraction.

## Deliverable Fidelity vs Spec

`deliverables_total=9`, `deliverables_done=9`. Two scope widenings were accepted by the operator and disclosed in the landing: the gRPC client-departure fix in `ResponseStage`, which the outline had as a read-only reference, and a late-end request-body deadline rule that also applies to plain HTTP. The landing message is `complete: false` on one key, `sonar_open_new_issues=unknown`. The lane wrote `unknown` honestly because the post-merge read could not decide (see Metrics).

| Deliverable (spec) | Verdict | Evidence |
|--------------------|---------|----------|
| D1 — first-message clock stops at a complete gRPC message | shipped-as-specified | `GrpcMessageFrames` parses the 5-byte length prefix incrementally across chunks, with no buffering. Tests: split-prefix and split-payload cases in `DispatchStageTest`; "part of its first message and stalls" in `GrpcDispatchStageTest`; `GrpcProxyIT#partialFirstMessageIsEndedByTheMessageBound` |
| D2 — a client-held stream is not charged to the upstream | shipped-modified (option a) | The verify-first clause settled option (b) as unavailable: the guard's timeout is counted before any translation can run. Option (a) shipped: a 10 s per-message bound up to half-close, a 25 s pre-response deadline capped 1 s short of the guard, and a passed deadline charged to the upstream only after a sustained 10 s upstream stall. HTTP 408 maps to gRPC `DEADLINE_EXCEEDED` (`GrpcStatusMapper`). Recorded in ADR-0060 and an ADR-0016 amendment |
| D3 — idle-client ends countable on their own | shipped-modified | Message-bound ends get their own `client-idle` disposition and counter. The pre-response-deadline end reports as `REQUEST_BODY_TIMEOUT`, shared with HTTP body timeouts, so it is not on that series |
| D4 — the M6 invariant pinned by tests | shipped-as-specified | Partial first message, complete-then-hold, matched control, steady sender, and the corrected stub control (an upstream holding a half-closed request is the guard's timeout); three `GrpcProxyIT` cases. Native-image red-first not run (self-declared) |
| D5 — documentation | shipped-as-specified | ADR-0060, ADR-0016, `tls-and-inbound-security.adoc`, `edge-security.adoc`, `LogMessages.adoc`, threat model; GW-07 rows stay PARTIAL |
| D6 — passthrough dial budget | shipped-as-specified | `MAX_DIALS_PER_TARGET` = 128 inside the 1024 cap, terminated hand-off exempt, `dial_budget` refusal and counter. Matched control present. `TlsPassthroughIT` not touched, so there is no end-to-end pin |
| D7 — exact front slot accounting | shipped-as-specified | Share claimed first, then a CAS-bounded cap increment; dial phase completed in a finally; refusal closes in a finally; IPv6 sources grouped by /64. No concurrent never-above-cap test (self-declared) |
| D8 — verify-first gate and relay-end probes | shipped-as-specified | Probes 1 and 2 (upgrade gate) were refuted by a run and kept as regression guards, with no code change. Probe 3 (HTTP/2 early-end wait) was confirmed and fixed (`TerminalDispatch` `endHandedOver`, with an HTTP/2 test) |
| D9 — stale front documentation | shipped-as-specified | Stale cap trade-off texts are gone from the `SniFrontListener` Javadoc and both TLS edge docs; the threat-model GW-07 SNI-front summary row now names the share, dial window, connect timeout, counter and dial budget |

**Surface vs declaration** (`inbox landing-check --declared-paths … --realized-paths …`, `git diff 26e34cd4 50b7e6d3`): 33 entries declared, 40 files realized, 11 added, 4 declared but untouched, `expansion_detected`. Every added file is explained:
- the D2 status mapping and its test (`GrpcStatusMapper`, `GrpcStatusMapperTest`, ADR-0016);
- the operator-accepted `ResponseStage` widening (`ResponseStage`, `ResponseStageTest`, `TerminalDispatchGrpcClientDepartureTest`);
- Javadoc-only changes (`EdgeHardeningOptions`, `EventType`, `tls/package-info.java`);
- a test helper (`RelayTestProxies`) and user docs (`edge-security.adoc`).

The four declared but untouched entries:
- `WebSocketUpgradeGate` and its test: probes 1 and 2 were refuted, as the spec allowed.
- `TlsPassthroughIT`: optional.
- `metrics/`: a spec error. No such package exists; the real class, `quarkus/SheriffMetrics.java`, was touched under its own entry.

## Three-Layer Analysis

The landing was read on three layers by one read-only leaf, and the orchestrator re-read every point this report relies on before writing it here. **By reading, no build, no test run, no write.**

- **Dispatch:** one read-only leaf (`execution-context-level-3`, `orchestrator.analyze`). Reason: every full-ship landing gets its own leaf, and this one is 40 files, +7.4k lines, across the dispatch clocks, the breaker attribution, the front listener and the terminal relay.
- **Re-read by the orchestrator at 50b7e6d3:**
  - `SniFrontListener`: `route`, `dial`, `refuse`;
  - `DispatchStage`: `resumeMessageClock`, `onMessageTimer`, `resumeClocks`;
  - `TerminalDispatch`: `relayOnEventLoop` and `startRelay`;
  - `ResponseStage.watch`.
- **Verdict:** layer 1 — one premise refuted as the spec foresaw (claim 4), two not shown, and one inconsistency between the Objective and D4; layer 2 — minor gaps; layer 3 — minor points only, held outside tracked text.

### Layer 1 — Specified

Verdicts persisted onto the spec with `corpus set-verdict` (producer `security-hardening-tier-1/analyze`, `checked_at` 50b7e6d3).

| Claim | Verdict | Polarity | Evidence |
|-------|---------|----------|----------|
| 0–3: first-message clock, gRPC stage wiring, breaker skip rule, stub control | checked | confirms | parent `26e34cd4` |
| 4: per-attempt state readable at translation time (option b) | checked | **refutes** | translation runs only after the guard exits, so the breaker has already counted the timeout. Persisted `contradicted`, `rescoped: yes`: the verify-first clause foresaw this and option (a) shipped |
| 5: prefix delimitable without buffering | checked | confirms | incremental parser, split-chunk tests |
| 6: verify-first clause recorded | checked | confirms | ADR-0060, ADR-0016 |
| 7–12: front dial window, slot claim order, dial-phase completion, refusal order, IPv6 keying, stale docs | checked | confirms | `47e73a15` vs `50b7e6d3` |
| 13, 14: gate small-body retirement, gate header-rule parity | not_shown_by_reading | none | the lane reports both refuted by a run; persisted `unverifiable` |
| 15: HTTP/2 early-end wait runs the failed-relay path on a whole answer | checked | confirms | fixed by `endHandedOver` |

Also on layer 1:
- The spec's Objective and its D4 bullet "a steady sender is not cut off" cannot both hold under option (a). The lane resolved this with a pre-response deadline, so a stream that never half-closes now ends at about 25 s while it waits for the first response head.
- The late-end deadline rule changes plain-HTTP behaviour at one timer edge, against the standing constraint "HTTP unchanged by Part A". The lane disclosed it and the operator accepted it.

**Layer 1 verdict:** findings — claim 4 refuted (absorbed), claims 13 and 14 not shown by reading, and the Objective / D4 inconsistency resolved by the deadline.

### Layer 2 — Implemented

- D1–D9 trace to code and to tests that can fail. D8's probes 1 and 2 are regression guards, disclosed as such.
- Every undeclared file is explained (see above). The spec's `metrics/` entry names a path that does not exist.
- Gaps (minor):
  - The pre-response-deadline end is not on the new client-idle series (D3).
  - The per-message bound restarts on a consumer resume as well as on a complete message, so "per complete message" in the report is not exact; the class Javadoc says so.
  - The "exact slot accounting on every exit path" claim holds except one log call (Layer 3).
  - No end-to-end dial-budget pin.
  - No concurrent cap test.
  - One lost-claim branch untested.
- Lane claims checked against the diff: the 10 s / 25 s / 29 s bounds, the 10 s stall rule, the 408 mapping, the 128 dial budget, /64 grouping and no new configuration key are all borne out.

**Layer 2 verdict:** findings — minor gaps only.

### Layer 3 — Correct

| Defect | File:line | Triggering input | Effect | Severity |
|--------|-----------|------------------|--------|----------|
| L3-L … L3-U | `edge/upstream/DispatchStage.java`, `tls/SniFrontListener.java`, `edge/terminal/TerminalDispatch.java`, `edge/error/RejectionReporter.java` | — | Timer, attribution and accounting edges of the new bounds, plus one observability gap. Two need a run to show. Full detail held outside tracked text, reported to the operator and recorded in the operator's local notes | minor |

No defect found by reading in:
- the incremental frame parser;
- the 408 → `DEADLINE_EXCEEDED` mapping;
- the dial-budget claim and release symmetry;
- the CAS cap bound;
- the dial-phase finally;
- the IPv6 key derivation;
- the gRPC departure path through `ResponseStage.watch`, which now reaches the dispatch.

The operator's private assessment was re-checked against the merged code. One entry (a gRPC departure after the half-close) is now fixed and was removed. Two entries in the same classes still apply.

**Layer 3 verdict:** findings — minor points only, held outside tracked text.

### Conformance to the Project's Own Rules

| Rule source | Rule | Verdict | Evidence |
|-------------|------|---------|----------|
| CLAUDE.md § Logging | CuiLogger, `%s`, LogRecord for WARN, documented | conforms | `LogMessages.adoc` updated |
| CLAUDE.md § Testing | JUnit 5 AAA, no Mockito/PowerMock/Hamcrest | conforms | real Vert.x stacks, matched controls |
| CLAUDE.md § Pre-1.0 / dependencies | no `@Deprecated`, no new dependency | conforms | no pom change |
| CLAUDE.md § Sonar | fix or suppress in code with rationale | conforms | `sonar-roundtrip` 0 new-code issues at `6b95ece8`; PR gate pass |
| spec standing constraints | no new configuration key; fixed constants | conforms | constants only |
| spec standing constraints | HTTP dispatch unchanged by Part A | deviates | the late-end deadline rule; disclosed and accepted by the operator |
| spec standing constraints | failing-first test per fix | conforms | D8 probes 1–2 disclosed as guards |
| CLAUDE.md § Pre-commit | gate, `-Werror`, coverage | not_shown_by_reading | no run allowed; CI green (below) |

## Metrics and Anomalies

- **Tokens:** 87,311,960 total.
- **Duration:** 225,870 s wall (about 62.7 h, which includes idle finalize waits).
- **Model / effort:** claude-opus-5-5, effort low; plugin and executor 0.1.2090 (no skew).
- **Anomalies:**
  - `emit-landing` reported `in_progress` and `archive-plan` `pending` in the landing facts, which is the expected state at emit time.
  - `branch-cleanup` recorded `head_binding_unconsulted=architecture-refresh`: the architecture refresh was not re-consulted after `9da2c232`.
- **Review and Sonar numbers re-read from the provider:**
  - `unresolved_review_threads`: landing 0, re-read 0 (`runbook_lane review-gate --pr-number 600`). Agree.
  - `review_body_findings_total`: landing 4, re-read 7. **Difference, recorded as a false claim.** The 7 are CodeRabbit's actionable inline items (6 + 1, ids `inline-1` .. `inline-7`), all in resolved threads. The landing's 4 are the non-empty CodeRabbit review bodies. No finding went unhandled. This is the third occurrence of the same producer mismatch (PLAN-SEC-02, PLAN-SEC-04); it was added to plan-marshall `lessons-routing` `api-sheriff-security-hardening-tier-1-005` by `inbox amend`.
  - `review_body_findings_handled`: landing 4; not re-verified.
  - `sonar_open_new_issues`: landing `unknown`, re-read `count_status: undecidable` (`sonar read_open_issues --pr 600`: head revision unavailable after the merge; last analysis `6b95ece8`). The two agree. The SonarCloud PR check passed. This is the one key that leaves the landing `complete: false`; it is a provider gap, not a lane omission, and is recorded in the same routed lesson.

## Routing and Merge Behavior

- **Review:** CodeRabbit reviewed every pushed head. The two late-timer suggestions were declined with rationale on the PR. Sourcery was skipped (optional).
- **CI/merge:** all 25 PR checks were green or skipped (`ci checks status --pr-number 600`). The plan merged through the merge queue as `50b7e6d3`, after a `sync-baseline` rebase over 3 upstream commits. The post-merge "Run Integration Benchmarks" passed. The `main` push runs for `50b7e6d3` all passed: Maven Build including `deploy-snapshot`, Integration Tests, Config Validation, Demo Client E2E and Scorecard.
- **Parallelization:** it ran beside kidicap PLAN-26 (#592) with 3 shared doc files (operator-approved overlap). The rebase reported no content conflict.
- **Declaration currency (staged PLAN-SEC-06):** the realized footprint overlaps 8 paths PLAN-SEC-06 declares:
  - `EdgeHardeningOptions.java`, which got Javadoc only;
  - ADR-0016, ADR-0060, `LogMessages.adoc`, `tls-and-inbound-security.adoc`, `security-threat-model.adoc`, `edge-security.adoc` and `tls-edge.adoc`.
  
  PLAN-SEC-06's doc-correction targets must be re-read against `50b7e6d3` at its outline. Its declaration stays correct, because those files are still its targets, so no surface edit is owed.

## Reconciliation Actions

- [x] row `status` → `shipped` — `orchestrator queue --transition PLAN-SEC-05 --status shipped`
- [x] row `pr` stamped — `600`
- [x] row `landing` stamped — `landings/PLAN-SEC-05.md`
- [x] row `plan_marshall_plan_id` stamped — `plan-sec-05-grpc-request-stream-attribution`
- [x] claim verdicts 0–15 persisted onto the spec — `corpus set-verdict` × 16
- [x] epic.md narrative reconciled (queue annotation, ground-truth Watch, `main` push-run Watch retired)
- [x] resume anchor updated; `queue-view.md` regenerated and committed

## Follow-Ups

- The layer-3 points (all minor) are with the operator. Staging a fix plan is the operator's call.
- PLAN-SEC-06 is now free of in-epic overlap and is emitted at the end of this analysis.
- Held back from PLAN-SEC-06 until this landing, now stageable: the upstream pool wait bound (`DispatchStage`) and the late-timeout answer (`TerminalDispatch`). Both stay in the operator's private assessment until the operator decides.
- Lane advisories left as logged: documentation wording drift (STALLED_BY_UPSTREAM message text, ApiSheriff-146 wording, ADR-0060 departure / IT-extent wording, a `TerminalDispatch` late-callback Javadoc) and the unconsulted architecture refresh.
