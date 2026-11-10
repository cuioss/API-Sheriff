# Epic: Security hardening - tier 1

slug: security-hardening-tier-1

> Hand-written narrative for one epic under `.plan/orchestrator/{slug}/`. The layout and
> authority contract live in the central standard — see
> `persona-plan-orchestrator/standards/orchestration-model.md`. The ledger JSON files
> (`status.json`, the `queue/{PLAN-ID}.json` rows) and `resume_anchor.md` are the machine
> authority; any statement here that conflicts with them is stale prose.
>
> START HERE and the Ordered Queue are not in this file. They live in the generated,
> git-tracked `queue-view.md` next to it (see the Persist / Stop-Resume Contract in that
> standard). `queue-view.md` is never hand-edited. A merge conflict in it is never merged by
> hand: merge the source files, run
> `python3 .plan/execute-script.py plan-marshall:plan-orchestrator:orchestrator regenerate-view --slug security-hardening-tier-1`
> on the merged tree, and `git add` the result.

## Vision

Fix the six tier-1 findings from the 2026-11-07 ground-truth check of the private security notes:
a code-reading verification of every recorded finding against `main` at `7f5c61af`, with a
severity, an effort/risk size (S/M/L/XL) and an absolute priority (severity value × coverage ÷
effort/risk cost). Tier 1 is every fix part scoring at least 100: the best security effect per
unit of effort and change risk. Five of the six are Medium; the sixth (relay-failure upstream
reset) is Medium in deployments that proxy unbounded streaming responses. The epic is done when
all planned work has shipped, each with regression tests that fail before the fix, and the
tracked documentation that overclaimed for these surfaces has been corrected.

The six findings, by priority rank:

| Rank | Finding | Severity | Effort | Plan |
|------|---------|----------|--------|------|
| 1 | M7 — SNI passthrough front listener has no ClientHello deadline and no connection cap | Medium (passthrough deployments) | S | PLAN-SEC-01 |
| 2 | M2 — WebSocket route dials the upstream before validating the client upgrade; on HTTP/2 the stream is never answered | Medium (High with a `require: none` WebSocket route) | M | PLAN-SEC-01 |
| 3 | M4 — anonymous login-initiation flood evicts other users' live pending-authorization records | Medium | M | PLAN-SEC-03 |
| 4 | M5 — client-sent provenance / URL-override headers cross to the upstream under the default forward mode | Medium | M | PLAN-SEC-02 |
| 5 | M6 — an idle gRPC client is charged to the upstream circuit breaker | Medium (gRPC routes) | M | PLAN-SEC-02 |
| 6 | C3 — after the response hand-over, a departed client never resets the upstream exchange | Low; Medium with streaming upstreams | S | PLAN-SEC-01 |

Out of scope (tier 2 and below, kept in the operator's private assessment): the header-read
deadline / connection cap for the main listener, the upstream pool wait bound, back-channel
logout across replicas, the early-response quiet rule, the `/auth`-fronted sample, and every
Low finding.

## Queue annotations

- PLAN-SEC-01 — edge transport. Holds `TerminalDispatch` for both the WebSocket gate and the relay-failure reset, which is why those two findings share a plan rather than serialising two plans on one class. SHIPPED 2026-11-08 as PR #582 (c85817d9) — see `landings/PLAN-SEC-01.md`. The realized surface grew from 12 declared to 28 files (new `WebSocketUpgradeGate`, `ResponseEnds`, `TlsEdgeProducer`, ITs, tls/protocol docs); `doc/LogMessages.adoc` was NOT touched (no new INFO/WARN/ERROR record), so of the two files it shared with the others only `doc/security-threat-model.adoc` was actually written.
- PLAN-SEC-02 — upstream dispatch + forward policy. Touches `DispatchStage` / `GrpcDispatchStage` and `ForwardPolicyStage` / `ConnectionHeaders`; disjoint from PLAN-SEC-01's production files by declaration (verify at outline: the gRPC attribution must not need `TerminalDispatch`).
- PLAN-SEC-03 — BFF pending-authorization store. Declared disjoint from both other plans in production code, but the operator-authorised relayed-peer expansion wrote PLAN-SEC-01's `tls/` files; see the Watch below. SHIPPED 2026-11-09 as PR #581 (3eef748b) — see `landings/PLAN-SEC-03.md`.
- PLAN-SEC-02 — held back on 2026-11-09 by the operator at the overlap prompt (2 shared doc files with the then-running PLAN-SEC-03), emitted after PLAN-SEC-03 merged. SHIPPED 2026-11-10 as PR #590 (81b46785) — see `landings/PLAN-SEC-02.md`. M5 closed; M6 closed for the client that sends no request byte (the threat model keeps GW-07 PARTIAL). Realized surface 34 files vs 13 declared, all doc/test propagation; only predicted doc files shared with the other plans.
- PLAN-SEC-04 — staged 2026-11-09 from the PLAN-SEC-01 landing analysis: follow-up hardening of the new transport bounds plus the PLAN-SEC-01 test-coverage advisories (folded here, so that Watch is retired). Mechanism-level spec by operator choice; details in the operator's git-ignored local notes. Same `tls/` + `edge/terminal/` surface as the shipped PLAN-SEC-01; shares only `doc/security-threat-model.adoc` and `doc/LogMessages.adoc` with PLAN-SEC-02 / PLAN-SEC-03. SHIPPED 2026-11-10 as PR #589 (47e73a15) — see `landings/PLAN-SEC-04.md`. Realized surface 28 files vs 20 declared, all explained (log record, metric, retirement Javadoc, doc propagation); rebased over PLAN-SEC-02 with no content conflict.
- PLAN-SEC-05 — staged 2026-11-10 on the operator's word from the PLAN-SEC-02 landing analysis (L3-A): finish M6 — the first-message bound covers a complete gRPC message, and a request stream the client holds open is not charged to the upstream. Mechanism-level spec; details in the operator's git-ignored local notes. Production surface `edge/upstream/**` + `edge/error/RejectionReporter`; shares only `doc/security-threat-model.adoc` and `doc/LogMessages.adoc` with PLAN-SEC-04. Held by the operator until PLAN-SEC-04 landed; at that landing's analysis PLAN-SEC-04's leftovers were folded in as Part B (deliverables 6–9: front dial budget, front accounting robustness, verify-first gate / relay-end probes, stale docs), widening the surface to `tls/**` and `edge/terminal/**` (14 → 33 declared entries). 9 deliverables, above the split threshold, kept as one plan on the operator's fold instruction; split into two serial PRs only if it would exceed CodeRabbit's 100-file cap.
- All three plans share `doc/security-threat-model.adoc` and `doc/LogMessages.adoc` (the latter declared as a HYPOTHESIS in PLAN-SEC-01 / PLAN-SEC-02, only if a new LogRecord is needed). Two shared files per pair means `next` puts the overlap to the operator. Both are append/row-local documentation edits in different sections, so a parallel launch risks at most a trivial doc rebase; no production file is shared.

## Decisions

- 2026-11-07 — Disclosure: the operator chose FULL DETAIL for the tracked plan specs (severity, mechanism, attack shape, evidence), over the mechanism-only alternative. Rationale: the findings are verified by code reading and are being fixed now; the specs must be self-sufficient for the executing plans. The remaining tier-2..4 findings stay in the git-ignored private notes.
- 2026-11-07 — Plan cut: 3 plans, chosen by the operator over 5 and 6, to minimise PR count (CLAUDE.md "minimise the number of PRs"). Each plan is at most 5 deliverables, inside the 12-deliverable ceiling authorised for this project.
- 2026-11-07 — Parallelization scope 2 (project default accepted by the operator).
- 2026-11-07 — Sequencing: PLAN-SEC-01 and PLAN-SEC-03 first (highest priority, mutually disjoint); PLAN-SEC-02 fills the next free slot.
- 2026-11-09 — PLAN-SEC-01 landing review: the operator chose to record the 5 layer-3 points in the git-ignored local security notes AND stage PLAN-SEC-04 (mechanism-level spec) for them; PLAN-SEC-01's test-coverage advisories are folded into PLAN-SEC-04.
- 2026-11-09 — PLAN-SEC-03's 5 candidate lessons are plan-marshall tooling defects; routed to the sibling plan-marshall repo's `lessons-routing` epic inbox (`../plan-marshall`, `.plan/local/worktrees/_orchestrator/.plan/orchestrator/lessons-routing/inbox/`) as `api-sheriff-security-hardening-tier-1-001` (finding, recurrence of `api-sheriff-api-sheriff-0-2-0-079`) to `-004`. Local messages archived as discarded-by-routing.
- 2026-11-09 — PLAN-SEC-03 landing review: its layer-3 points were recorded in the operator's local security notes and reported; none staged.
- 2026-11-10 — PLAN-SEC-02 landing review: its layer-3 points (one major residual of M6, two minor) were recorded in the operator's local security notes and reported; staging a fix plan awaits the operator's word. Its candidate lesson (`-001`, a Sonar `java:S2386` architecture hint for the api-sheriff module) was discarded from this epic: applying it writes the tracked `.plan/project-architecture/`, outside the ledger, so the `architecture enrich insight --module api-sheriff` call was handed to the operator. The 2026-11-08 tooling-skew Watch is retired: PLAN-SEC-02 ran plugin and executor 0.1.2090 (aligned) and merged through the queue with no gate refusal.
- 2026-11-10 — PLAN-SEC-02's landing reported review-body and Sonar counts that the provider re-read does not reproduce (2 vs 13; 0 vs 6). Both are classification/staleness artifacts (all findings were handled; the code addresses all six Sonar issues), recorded in the landing report as the analyze contract requires; the tooling side is routed to plan-marshall's `lessons-routing` inbox as `api-sheriff-security-hardening-tier-1-005`.
- 2026-11-10 — PLAN-SEC-02 L3-A (M6 residual): the operator chose to stage a fix plan → PLAN-SEC-05. At the overlap prompt (2 shared doc files with the running PLAN-SEC-04) the operator held it back: wait for PLAN-SEC-04 to land and fold its leftovers into PLAN-SEC-05 before emitting. The epic therefore no longer closes when PLAN-SEC-04 ships.

- 2026-11-10 — PLAN-SEC-04 landing review: layer 1 refuted claim 6 (a zero HTTP idle timeout never shortens the derived relay idle; the plan kept it derived and fixed); layer 3 found one major consequence (the dial window draws on the shared front cap) and minor accounting edges, plus three gate / relay-end points not shown by reading and stale documentation. All recorded in the operator's local security notes and, per the operator's standing instruction of 2026-11-10, folded into PLAN-SEC-05 as Part B with its Expected Surface updated in the same edit. The plan's outline-time possibility about a client leaving during the upstream head wait was refuted by reading. The 14 code-review advisories outside PLAN-SEC-05 deliverable 9 are left as logged.
- 2026-11-10 — PLAN-SEC-04's landing repeated the review-body / Sonar count differences (4 vs 6; 0 vs 5), again artifacts (all handled; the Sonar search returned the first of four PR analyses). Recorded as false claims in the landing report as the contract requires; the recurrence and the new evidence were added to the routed `api-sheriff-security-hardening-tier-1-005` by `inbox amend` (revision 1) instead of a second message.
- 2026-11-10 — inbox `plan-sec-04-edge-transport-bounds-follow-up-001.md` (landing, complete): reconciled. PLAN-SEC-05 released for emission.

## Open Defects

- None yet.

## Watches

- The ground-truth check was a code reading, not a reproduction. Each plan writes a failing regression test FIRST; a test that cannot be made to fail against the unfixed code refutes the finding and loops the plan back to re-scope — re-check at each landing. PLAN-SEC-01 (2026-11-08): all three findings (M7, M2, C3) held, each with a test that fails without its fix; two spec premises were refuted and absorbed by the shipped code (claims 3 and 6 — the WebSocket GET-only gate and the "Vert.x answers every invalid HTTP/1.1 upgrade" premise). PLAN-SEC-03 (2026-11-09): M4 held, tests fail against the unfixed store. PLAN-SEC-02 (2026-11-10): M5 and M6 held, each with a failing-first test; the verify-first premise of deliverable 1 was refuted by reading (the needed per-attempt state already existed), but the operator had already chosen the alternative at refine. PLAN-SEC-04 (2026-11-10): D1–D4 held with failing-first tests (the quiet-upstream reset was confirmed by a failing probe); D5's premise refuted, kept derived and fixed. Outstanding: PLAN-SEC-05.
- 2026-11-08 — `main` push runs for c85817d9 (PLAN-SEC-01), 3eef748b (PLAN-SEC-03) and 81b46785 (PLAN-SEC-02, added 2026-11-10) and 47e73a15 (PLAN-SEC-04, added 2026-11-10) (Maven Build incl. `deploy-snapshot`) not verified at analyze time: the CI abstraction looks runs up by PR or head, not by merge commit. Retire once all are confirmed green.
- 2026-11-09 — Gate blind spot observed: PLAN-SEC-03's operator-authorised scope expansion wrote `tls/PassthroughRelay`, `tls/TlsEdgeProducer`, `tls/SniFrontListener`, two `tls/` tests and `doc/development/tls-edge.adoc` while PLAN-SEC-01 held them; the gate could not see it because a mid-run scope expansion does not update the staged spec's declaration. Rebase conflict resolved by hand. PLAN-SEC-03's Expected Surface was corrected at landing. Lesson for pairing: a run-time scope expansion that crosses into another in-flight plan's package should be raised to the orchestrator (inbox finding) so the pairing is re-judged.
- 2026-11-09 — PLAN-SEC-03 follow-ups not staged: per-source cap shipped as 256 (spec proposed 32) with no rationale in the diff; no ADR for the in-process peer-attribution mechanism or the per-source quota policy.
