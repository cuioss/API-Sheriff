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
all three plans have shipped, each with regression tests that fail before the fix, and the
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

- PLAN-SEC-01 — edge transport. Holds `TerminalDispatch` for both the WebSocket gate and the relay-failure reset, which is why those two findings share a plan rather than serialising two plans on one class.
- PLAN-SEC-02 — upstream dispatch + forward policy. Touches `DispatchStage` / `GrpcDispatchStage` and `ForwardPolicyStage` / `ConnectionHeaders`; disjoint from PLAN-SEC-01's production files by declaration (verify at outline: the gRPC attribution must not need `TerminalDispatch`).
- PLAN-SEC-03 — BFF pending-authorization store. Disjoint from both other plans in production code.
- All three plans share `doc/security-threat-model.adoc` and `doc/LogMessages.adoc` (the latter declared as a HYPOTHESIS in PLAN-SEC-01 / PLAN-SEC-02, only if a new LogRecord is needed). Two shared files per pair means `next` puts the overlap to the operator. Both are append/row-local documentation edits in different sections, so a parallel launch risks at most a trivial doc rebase; no production file is shared.

## Decisions

- 2026-11-07 — Disclosure: the operator chose FULL DETAIL for the tracked plan specs (severity, mechanism, attack shape, evidence), over the mechanism-only alternative. Rationale: the findings are verified by code reading and are being fixed now; the specs must be self-sufficient for the executing plans. The remaining tier-2..4 findings stay in the git-ignored private notes.
- 2026-11-07 — Plan cut: 3 plans, chosen by the operator over 5 and 6, to minimise PR count (CLAUDE.md "minimise the number of PRs"). Each plan is at most 5 deliverables, inside the 12-deliverable ceiling authorised for this project.
- 2026-11-07 — Parallelization scope 2 (project default accepted by the operator).
- 2026-11-07 — Sequencing: PLAN-SEC-01 and PLAN-SEC-03 first (highest priority, mutually disjoint); PLAN-SEC-02 fills the next free slot.

## Open Defects

- None yet.

## Watches

- The ground-truth check was a code reading, not a reproduction. Each plan writes a failing regression test FIRST; a test that cannot be made to fail against the unfixed code refutes the finding and loops the plan back to re-scope — re-check at each landing.
