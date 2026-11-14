# Landing Analysis: PLAN-SEC-04 — Edge transport bounds follow-up

epic: security-hardening-tier-1
workstream: WS-01
pr: #589 — https://github.com/cuioss/API-Sheriff/pull/589

> Landing record for one shipped plan. Written by the `analyze` verb (inbox scan, message
> `plan-sec-04-edge-transport-bounds-follow-up-001.md`) after checking the inbox claims against
> git, the merged code and the PR state through the CI abstraction.

## Deliverable Fidelity vs Spec

`deliverables_total=7`, `deliverables_done=7` (the outline split the spec's deliverable 6 into 6 and 7 — the `TlsPassthroughIT` lower bounds became their own deliverable; nothing is left uncovered). Functional review: 3 met, 4 met with residuals, 0 not met — closed by operator override at `728062ea` after the operator accepted rewriting two `WebSocketUpgradeGateTest` cases that asserted acceptance of handshakes the stack refuses. Code review: 0 blocking, 14 advisory (documentation, Javadoc and test hygiene; left as logged).

| Deliverable (spec) | Verdict | Evidence |
|--------------------|---------|----------|
| D1 — front cap fairness and visibility | shipped-modified | Per-source share `MAX_UNDECIDED_PER_SOURCE` = cap / 4 = 256, keyed on the TCP peer address (`SourceKeys` needs an HTTP request and would close a package cycle — decision recorded in the `SniFrontListener` Javadoc); rate-limited WARN `ApiSheriff-146` per refusal reason; refusal counters in `SheriffMetrics`. Tests: `warnsOncePerWindowPerReason`, `oneSourceFillingItsShareDoesNotBlockAnother` (matched control), `recordsEveryRefusalWithItsReason`, `keysTheShareOnThePeerAddress`, metric tests |
| D2 — bound the passthrough dial phase | shipped-as-specified | Explicit 10 s connect timeout (`TlsEdgeProducer.RELAY_CONNECT_TIMEOUT`); the ClientHello buffer is dropped when the client leaves during the dial; a dialing connection holds its cap slot until the dial settles (outline decision, documented). Tests: never-answering dial abandoned within the timeout (lower and upper bound), dial-phase slot tests, failed hand-off returns the slot. Residual graded non-consequential: the bound is name resolution plus the 10 s, not 10 s alone (docs corrected) |
| D3 — reset the upstream when the client goes | shipped-modified | The client-response end handler fails the relay outcome, which reaches the one existing reset site (`TerminalDispatch.relayOnEventLoop`); no second `reset()`. Tests: quiet HTTP/1.1 and HTTP/2 upstreams reset within 2 s and a follow-up is served. Deviation: the test saturates the default pool of 5, not a pool of 1 (no fixture seam reaches the pool size) — equivalent in effect |
| D4 — gate parity with the Vert.x upgrade preconditions | shipped-modified | 413 with connection close for a declared body above 8192 or a non-decimal length; 400 for an unparseable request URI; every refusal of an HTTP/1.x request declaring a body above 8192 retires the connection; first-`Connection`-line and whole-value `Upgrade` rules. No Host rule (vertx-core has none to mirror). Tests: zero upstream dials on each refusal, existing valid-upgrade control |
| D5 — passthrough relay idle policy | shipped-as-specified (option b) | Derived and fixed, documented; the "zero HTTP idle timeout shortens it" premise refuted. Derivation tests are disclosed regression pins, not failing-first |
| D6 — PLAN-SEC-01 coverage advisories | shipped-as-specified | ADR-0066 names 405/426/400/413; `onStartup` wiring, `applyIdleTimeout` branches, 503 security headers and `Set-Cookie`, slot return on deadline/close/error; `TlsPassthroughIT` lower bounds (D7 of the plan) |

**Surface vs declaration** (`git diff 6b86cde3 47e73a15`; the landing supplied no surface delta): 20 declared, 28 realized, 10 added, 2 declared-but-untouched (`tls/TlsLogMessages.java` — no such holder exists, the WARN went to `ApiSheriffLogMessages`; `doc/configuration/` — no key added; both HYPOTHESIS entries). Every added file is explained by a deliverable: D1 log record and metric (`ApiSheriffLogMessages`, `SheriffMetrics` + test, `doc/architecture/observability-and-errors.adoc`), D4 retirement and ADR-0066 Javadoc (`ConnectionRetirement`, `ResponseEnds`, `doc/development/protocol-processors.adoc`, `doc/user/protocol-routes.adoc`), D3 test adjustment (`TerminalDispatchLateBodyTimeoutTest`), extracted test helper (`RelayTestProxies`). `edge/upstream/**` and `bff/**` untouched, as required.

## Three-Layer Analysis

The landing was read on three layers by one read-only leaf, and the orchestrator re-read every point recorded as `checked` that this report relies on before writing it here. **By reading, no build, no test run, no write.**

- **Dispatch:** one read-only leaf (`execution-context-level-3`, `orchestrator.analyze` resolved via `plan.effort`) — reason: every full-ship landing gets its own leaf; 28 files, +3.4k lines across the race-sensitive front listener, relay and terminal dispatch.
- **Re-read by the orchestrator at 47e73a15:** `SniFrontListener` `claimSlot`, `refuse`, dial-phase completion and `releaseSlot`, stale trade-off Javadoc; `PassthroughRelay.relay` dial handlers; `TerminalDispatch.relayOnEventLoop` reset site; `TlsEdgeProducer.relayIdleTimeout`; `GatewayEdgeRoute` end handler and `DispatchStage.clientDeparted`; `doc/development/tls-edge.adoc` trade-off paragraph; threat-model GW-07 summary rows.
- **Verdict:** layer 1 — two premises refuted (C3 earlier, C6), one not shown; layer 2 — minor deviations and gaps; layer 3 — one major consequence and minor points, held outside tracked text and folded into PLAN-SEC-05.

### Layer 1 — Specified

Verdicts persisted onto the spec with `corpus set-verdict` (producer `security-hardening-tier-1/analyze`, `checked_at` 47e73a15); claims 2, 3 and 4 already carried equal settled verdicts from the PLAN-SEC-01 analysis and were left as recorded.

| Claim | Verdict | Polarity | Evidence |
|-------|---------|----------|----------|
| 0: cap refusal at DEBUG only, no per-source share | checked | confirms | `SniFrontListener` at parent `6b86cde3` |
| 1: slot returned at the route decision, before the dial | checked | confirms | `Incoming.settle` at `6b86cde3` |
| 2: no connect timeout on the dial client | checked | confirms | already corroborated; `relayClientOptions` at `6b86cde3` |
| 3: client close during the dial unnoticed | checked | refutes | already contradicted (PR #581 close handler) |
| 4: single reset only on relay failure | checked | confirms | already corroborated |
| 5: vertx-core 413/400 after the dial | not_shown_by_reading | none | library source not in the repo; persisted `unverifiable` |
| 6: zero HTTP idle shortens the relay idle | checked | **refutes** | `relayIdleTimeout` takes a max over inner bounds + 60 s; a zero never wins. Persisted `contradicted`, `rescoped: no` (the plan chose option b) |
| 7: verify-first — close handler can feed the one reset site | checked | confirms | one `Promise`, one `onFailure` with the only `reset()` |

**Layer 1 verdict:** findings — claim 6 refuted; claim 5 not shown by reading.

### Layer 2 — Implemented

- D1–D6 trace to code and to tests that can fail against the unfixed code; D5 and parts of D6 are regression pins, disclosed as such.
- PR-body claims checked against the diff: 256 share keyed on the peer address, 10 s connect timeout, slot held through the dial, one reset site, 413/400 rules, no new configuration key — all borne out (library parity of the gate rules not shown).
- No unexplained file.
- Gaps (minor): nothing observes the per-source map returning to empty; the D3 test uses the default pool of 5, not 1; the threat model's GW-07 SNI-front summary row was not updated with the section body.

**Layer 2 verdict:** findings — minor gaps only.

### Layer 3 — Correct

| Defect | File:line | Triggering input | Effect | Severity |
|--------|-----------|------------------|--------|----------|
| L3-D | `tls/SniFrontListener.java` (dial-phase slot) | — | A consequence of holding the slot through the dial; the docs present the trade-off. Full detail held outside tracked text, reported to the operator, recorded in the operator's local notes, folded into PLAN-SEC-05 deliverable 6 | major |
| L3-E … L3-H | `tls/SniFrontListener.java`, `tls/PassthroughRelay.java` | — | Accounting edges of the new share; held outside tracked text, folded into PLAN-SEC-05 deliverable 7 | minor |
| L3-I … L3-K | `edge/terminal/TerminalDispatch.java`, `edge/terminal/WebSocketUpgradeGate.java` | — | Not shown by reading; folded into PLAN-SEC-05 deliverable 8 as verify-first probes | minor |
| Docs | `SniFrontListener` Javadoc, `doc/development/tls-edge.adoc`, `doc/user/tls-edge.adoc`, `doc/security-threat-model.adoc` GW-07 summary row | — | Stale "one client holds all 1024 slots" text; summary row behind the section body | minor |

No defect found by reading in: slot symmetry on every exit path (exactly-once release, entry removed at count 1, all callbacks on the connection's event loop); buffered ClientHello release (single owner, dropped on client close, cleared on dial failure); the one reset site in `TerminalDispatch`; the 413 / retirement rules and their `ResponseEnds` routing; closed label cardinality of the refusal counters (no peer address as a label).

The outline-time possibility recorded by the plan (a client leaving while the dispatch waits for the upstream response head) was **refuted** by reading: the end handler reaches `DispatchStage.clientDeparted`, which resets the in-flight attempt at once and releases the admission permit.

**Layer 3 verdict:** findings — 1 major and minor points, held outside tracked text.

### Conformance to the Project's Own Rules

| Rule source | Rule | Verdict | Evidence |
|-------------|------|---------|----------|
| CLAUDE.md § Logging | CuiLogger, `%s`, exception first; LogRecord for WARN, documented | conforms | `ApiSheriff-146` in `ApiSheriffLogMessages` and `doc/LogMessages.adoc` |
| CLAUDE.md § Security | no sensitive data in logs | conforms | refusal lines name no peer address or bytes; a test asserts it |
| CLAUDE.md § Testing | JUnit 5 AAA, no Mockito/PowerMock/Hamcrest | conforms | real Vert.x sockets, `java.lang.reflect.Proxy` helpers |
| CLAUDE.md § Javadoc | public members documented, `@since` | conforms | new `Refusal`, constants documented; class-level `@since` |
| CLAUDE.md § Pre-1.0 / dependencies | no `@Deprecated`, no new dependency | conforms | old constructors replaced; no pom change |
| CLAUDE.md § Sonar | fix or suppress in code with rationale | conforms | `java:S107` suppressions with rationale; 5 PR issues fixed |
| ADR-0066 | every gateway answer through `ResponseEnds` | conforms | gate refusals and 503s via `ResponseEnds.reject` |
| spec standing constraints | `edge/upstream/**`, `bff/**` untouched; no Vert.x internal API | conforms | 28-path list; gate imports public API only |
| spec standing constraints | failing-first test per fix | conforms | D5 / parts of D6 disclosed as regression pins |
| CLAUDE.md § Javadoc accuracy | documentation stays accurate | deviates | stale trade-off texts and GW-07 summary row (Layer 3, Docs) |
| CLAUDE.md § Pre-commit | gate, `-Werror`, coverage | not_shown_by_reading | no run allowed; CI green (below) |

## Metrics and Anomalies

- Tokens: 53,913,406 total (spans main + sub-agent populations; 5-execute was re-entered)
- Duration: 87,496 s wall (about 24.3 h; worked 4 h 14 m, 6-finalize 15 h 51 m wall of which 14 h 55 m idle)
- Model / effort: claude-opus-5-5, effort low; plugin and executor 0.1.2090 (no skew)
- Anomalies: functional review closed by operator override; mid-finalize rebase onto `main` (#590) by operator choice; `archive-plan` reported `pending` in the landing facts, but the plan directory is archived (`.plan/local/archived-plans/2026-11-10-plan-sec-04-edge-transport-bounds-follow-up`).
- **Review and Sonar numbers re-read from the provider:**
  - `unresolved_review_threads`: landing 0, re-read 0 — agree.
  - `review_body_findings_total`: landing 4, re-read 6 (`runbook_lane review-gate --pr-number 589`) — **difference, recorded as a false claim.** The 6 the re-read counts are CodeRabbit inline items, which the plan's findings ledger files as `inline` (all 6 `fixed`); its 4 `review_body` items are 2 `fixed`, 2 `taken_into_account`. No finding went unhandled. Same producer mismatch as PLAN-SEC-02; routed to plan-marshall `lessons-routing` as a recurrence on `api-sheriff-security-hardening-tier-1-005`.
  - `review_body_findings_handled`: landing 4; not re-verified (no progress-file `finding:` lines in the archived plan).
  - `sonar_open_new_issues`: landing 0, re-read 5 (`sonar_rest search --pr 589`) — **difference, recorded as a false claim.** The 5 issues (`java:S4144`, `java:S3398`, `java:S2093`, `java:S7467` ×2) are all fixed in the merged code; the plan's scan summary shows they belong to the first of four PR analyses and the last three, including the final head `728062ea`, report 0. PR quality gate `OK`.

## Routing and Merge Behavior

- Review: CodeRabbit reviewed every pushed head; the last review of `728062ea` had no actionable comments. All resolvable threads answered and resolved.
- CI/merge: all 25 PR checks green or skipped (20 pass, 5 skipped; `ci checks status --pr-number 589`). Merged through the merge queue as `47e73a15`. The `main` push run for `47e73a15` was not checked here — Watch.
- Parallelization: the realized footprint shares with PLAN-SEC-02 (shipped first) only the predicted `doc/security-threat-model.adoc` and `doc/LogMessages.adoc`; both rebases reported no content conflict. No unpredicted in-epic collision; no declaration correction owed.
- Cross-epic: kidicap PLAN-29 (`ab568dab`) also touched `doc/architecture/observability-and-errors.adoc`, which this plan wrote without declaring it; the rebase merged it with no content conflict. The cross-epic Watch is retired.

## Reconciliation Actions

- [x] row `status` → `shipped` — `orchestrator queue --transition PLAN-SEC-04 --status shipped`
- [x] row `pr` stamped — `589`
- [x] row `landing` stamped — `landings/PLAN-SEC-04.md`
- [x] row `plan_marshall_plan_id` stamped — `plan-sec-04-edge-transport-bounds-follow-up`
- [x] claim verdicts 0, 1, 5, 6, 7 persisted onto the spec — `corpus set-verdict` × 5
- [x] layer-3 points and the not-shown probes folded into PLAN-SEC-05 (deliverables 6–9), its Expected Surface updated in the same edit (14 → 33 entries)
- [x] epic.md narrative reconciled (queue annotation, cross-epic Watch retired, ground-truth Watch closed)
- [x] resume anchor updated; `queue-view.md` regenerated and committed

## Follow-Ups

- PLAN-SEC-05 carries the fixes — emitted at the end of this analysis.
- The 14 code-review advisories not covered by PLAN-SEC-05 deliverable 9 (contributor-guide review-history wording, runbook wording, missing `@since` on `SniFrontListener.Refusal`, DEBUG-only failed hand-off, `ConnectionRetirement` Javadoc naming only the 413, space-only `Connection` trim not documented, a duplicated `WebSocketUpgradeGateTest` case, two `PassthroughRelayTest` fixture socket leaks) — low value; left as logged unless the operator wants them in PLAN-SEC-05.
