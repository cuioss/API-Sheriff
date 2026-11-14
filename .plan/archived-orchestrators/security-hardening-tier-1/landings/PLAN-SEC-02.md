# Landing Analysis: PLAN-SEC-02 — Upstream attribution and forward headers

epic: security-hardening-tier-1
workstream: WS-02
pr: #590 — https://github.com/cuioss/API-Sheriff/pull/590

> Landing record for one shipped plan. Written by the `analyze` verb (inbox scan, message
> `sec-02-upstream-attribution-forward-headers-002.md`) after checking the inbox claims against
> git, the merged code and the PR state through the CI abstraction.

## Deliverable Fidelity vs Spec

`deliverables_total=5`, `deliverables_done=5` (the outline split the spec's 4 deliverables into 5; nothing is left uncovered). Functional review: 5 met, 0 with residuals, 0 not met. Code review: 0 blocking, 6 advisory (left as logged: README provenance wording, provenance-list completeness at unit level, a few Javadoc lines still saying "no request message", two test-Javadoc claims broader than the test binds).

| Deliverable (spec) | Verdict | Evidence |
|--------------------|---------|----------|
| D1 — gRPC idle client is client-attributed, not an upstream failure (M6) | shipped-modified | The spec's alternative was taken (operator decision at refine): a fixed 10 s first-message clock (`GrpcDispatchStage.GRPC_FIRST_MESSAGE_TIMEOUT_MILLIS`), armed at `send()`, stopped by the first forwarded byte, body end, any abort, send completion or abandon. Expiry resets the upstream request and ends as `DispatchStage.FirstMessageTimeout` (a `GatewayException`, `INBOUND_BODY_ABORTED`) — breaker-skipped, not retried; new `no-first-message` disposition on the existing `ApiSheriff-22` record; no new `EventType`. HTTP dispatches never arm it. Closes the headers-only case; the bound ends at the first byte (see Layer 3). Tests: `GrpcDispatchStageTest` idle case + matched control, `DispatchStageTest` first-message clock, `UpstreamGuardsTest`, `GrpcProxyIT.headersOnlyCallIsEndedByTheFirstMessageBound` |
| D2 — resolver inputs masked like the regenerated names (M5, X-Proxy* half) | shipped-as-specified | `ForwardPolicyStage.RESOLVER_INPUT_HEADERS` (separator-folded) = forwarding names + four `X-Proxy*`; skipped by the mode copy in every mode and masked from the resolver for an untrusted peer. Tests: `ForwardPolicyStageTest` `XProxyResolverInputs` (honouring resolver makes the mask observable; trusted-peer control) and `ResolverInputDrift` (set equality with the resolver's recorded reads) |
| D3 — withhold the provenance / URL-override class (M5) | shipped-modified | `ConnectionHeaders.REQUEST_STRIP` gains the 15 names, separator-folded, for every peer. Deviation: the class is also a `REQUEST_READMITTABLE` subset — a route may re-admit one name by naming it in its `headers_allow` positive list (forward-all and negative lists never re-admit). The spec recommended an unconditional strip; the default stays secure and the opt-in is documented in ADR-0037. Tests: `provenanceOverrideNeverCrossesUnderPermissiveModes` (canonical + `_` spelling × forward-all / negative list × trusted / untrusted, each with a positive control), `ConnectionHeadersTest`, `ProvenanceHeaderPolicyIT` |
| D4 — documentation for the changed forwarding contract | shipped-as-specified | ADR-0003, ADR-0037, ADR-0060 amendment sections; routing-and-forwarding architecture and configuration pages (withheld names listed once, bound to code by `DocumentedHeaderSetsContractTest`); threat model (GW-07 stays PARTIAL); `trust_scheme_host` now documented as accepted with no effect, and ADR-0003 forbids wiring it without the mask. Extras: README, user guides, base-gateway variant, features analysis, `tls-and-inbound-security.adoc`, `declared-limit-assertion-coverage.adoc` |

**Surface vs declaration** (`inbox landing-check` `surface_delta`, base `origin/main` = 81b46785, not stale): 13 declared, 34 realized, 22 added, 1 declared-but-untouched (`UpstreamFailureMapper`, a HYPOTHESIS entry; `RejectionReporter` took its role). Every added file is explained by a deliverable: D1 disposition, D3 schema text, D4 doc propagation, or tests and ITs that bind the production sets and bounds.

## Three-Layer Analysis

The landing was read on three layers by one read-only leaf, and the orchestrator re-read every point recorded as `checked` that this report relies on before writing it here. **By reading, no build, no test run, no write.**

- **Dispatch:** one read-only leaf (`execution-context-level-3`, `orchestrator.analyze` resolved via `plan.effort`) — reason: every full-ship landing gets its own leaf; 34 files changed, 347-line change to the race-sensitive `DispatchStage`.
- **Re-read by the orchestrator at 81b46785:** `GrpcDispatchStage` class Javadoc and `GRPC_FIRST_MESSAGE_TIMEOUT_MILLIS`; `DispatchStage` send path (clock armed after `attachBody`, stopped in `onComplete`), `claimAbort`, `onChunk`, `onInboundEnd`, `firstMessageBoundExpired`; per-attempt `bytesSent` / `subscribed` state (claim 8); `ConnectionHeaders.PROVENANCE_OVERRIDE` / `REQUEST_READMITTABLE`; threat model first-message residual (~1542-1552).
- **Verdict:** layer 1 — one premise refuted (claim 8, verify-first); layer 2 — one minor deviation (D3 re-admittable subset); layer 3 — one major residual and minor points, held outside tracked text.

### Layer 1 — Specified

Claims 0–8 persisted onto the spec with `corpus set-verdict` (producer `security-hardening-tier-1/analyze`, `checked_at` 81b46785).

| Claim | Verdict | Polarity | Evidence |
|-------|---------|----------|----------|
| 0: `FORWARDING_HEADERS` set, mode copy lets `X-Proxy*` through | checked | confirms | `ForwardPolicyStage.java` at base |
| 1: `REQUEST_STRIP` held none of the Objective names | checked | confirms | `ConnectionHeaders.java` at base |
| 2: no test mentions `x-proxy` | checked | confirms | `git grep` at base, zero hits |
| 3: no `forward` block = forward-all | checked | confirms | `Route.java:265`, `copyInboundHeadersExcept` with empty deny set |
| 4: resolver honours `X-Proxy*` only under `trustAll`, never set | checked (gateway half) | confirms | `ForwardedTrust` builds the config without `trustAll`; `trust_scheme_host` read nowhere. Library half shown only by the landing's tests running |
| 5: gRPC dispatch has no idle clock / deadline | checked | confirms | `GrpcDispatchStage` at base, `NO_BODY_IDLE_TIMEOUT` |
| 6: breaker parameters, `skipOn(GatewayException)` | checked | confirms | `UpstreamGuards.guardFor`; a skipped exception is recorded as a success |
| 7: Vert.x writes the h2 head only with the first chunk | not_shown_by_reading | none | library behaviour; persisted `unverifiable` |
| 8: verify-first — "never published" vs "published, no response" needs new synchronisation | checked | **refutes** | `DispatchStage` already tracks `bytesSent` / `subscribed` / `inboundEnded` per attempt; a byte-count attribution was available. The operator chose the time-to-first-message alternative at refine; the verify-first outcome is recorded nowhere in code or docs. Persisted `contradicted`, `rescoped: no` |

**Layer 1 verdict:** findings — claim 8 refuted (premise only; the delivered alternative was an operator choice, so no rescope).

### Layer 2 — Implemented

- D1–D4 and the three ADR amendments trace to code and to tests that can fail against the unfixed code, each with a matched control.
- PR-body claims checked against the diff: 15 names, 10 s bound, breaker-skipped, not retried (`allowsRetryAfter` refuses any `GatewayException`), HTTP never arms the clock (only the package-private constructor takes a bound), `headers_allow` re-admission, ADR-0003/0037/0060 amendments — all borne out. One qualifier: "idle clients can no longer open the breaker" holds for the zero-byte client (Layer 3).
- No unexplained file. `TerminalDispatch.java` untouched, as the spec required.
- Deviation (minor): D3 landed as a re-admittable subset instead of the recommended unconditional strip.

**Layer 2 verdict:** findings — 1 minor (D3 deviation).

### Layer 3 — Correct

| Defect | File:line | Triggering input | Effect | Severity |
|--------|-----------|------------------|--------|----------|
| L3-A | `edge/upstream/DispatchStage.java` (first-message clock) | — | The bound ends at the first forwarded byte, a residual the threat model documents publicly (GW-07 stays PARTIAL). Its full consequence is held outside tracked text, reported to the operator and recorded in the operator's local notes | major |
| L3-B, L3-C | `http/ConnectionHeaders.java`, `edge/upstream/GrpcDispatchStage.java` | — | Minor points, held outside tracked text and recorded in the operator's local notes | minor |

No defect found by reading in: the first-message clock lifecycle (armed once after `attachBody`; stopped on every end path; single-shot under one monitor with the abort CAS, so no double fire and no leak); the hand-over / departure / publish ordering (the spec's no-restructuring constraint holds — the churn is mostly Javadoc plus additive fields; the one move, `stopClocks()` before the abort read in `onInboundEnd`, preserves behaviour); separator folding of the three header sets; coherence with kidicap PLAN-29's `ab568dab` change to `DispatchStage`.

Observability: `no-first-message` shares the `INBOUND_BODY_ABORTED` counter and per-route latch with client-left and body-stream-failed.

**Layer 3 verdict:** findings — 1 major and minor points, held outside tracked text.

### Conformance to the Project's Own Rules

| Rule source | Rule | Verdict | Evidence |
|-------------|------|---------|----------|
| CLAUDE.md § Logging | CuiLogger, `%s`, exception first; LogRecord for INFO/WARN/ERROR | conforms | no new log statement; `ApiSheriff-22` reused with a disposition string |
| CLAUDE.md § Logging | log records documented | conforms | `doc/LogMessages.adoc` rows 22 and 23 updated |
| CLAUDE.md § Testing | JUnit 5 AAA, no Mockito/PowerMock/Hamcrest | conforms | real Vert.x / h2 stubs, `ManualTime` |
| CLAUDE.md § Javadoc | public/protected documented, `@since` | conforms | `FirstMessageTimeout`, the new constants and `isRequestReadmittable` documented |
| CLAUDE.md § Pre-1.0 | no `@Deprecated`, no shims, no new dependency | conforms | single re-admittable `Authorization` replaced outright; no pom change |
| CLAUDE.md § Sonar | fix or suppress in code with rationale | conforms | `java:S107` (`DispatchStage.java:1901`), `java:S2386` (`ConnectionHeaders.java:218,246`) each with a rationale |
| CLAUDE.md § Security | secure defaults, no sensitive data in logs | conforms | the 15 names withheld for every peer by default; `FirstMessageTimeout` carries no client data |
| CLAUDE.md § Testing | a key that parses must act | conforms | `trust_scheme_host` documented as inert; wiring forbidden without the mask |
| spec standing constraints | `DispatchStage` confined; `TerminalDispatch` untouched; `trust_scheme_host` not wired | conforms | see Layer 3 |
| spec D3 | recommended unconditional strip | deviates | re-admittable subset, documented in ADR-0037 |
| spec D1 | client-attributed guard timeout for an idle client | deviates | the time-to-first-message alternative covers the zero-byte client only (L3-A) |
| CLAUDE.md § Pre-commit | gate, `-Werror`, 80 % coverage, OpenRewrite markers | not_shown_by_reading | no run allowed; CI green (below) |

## Metrics and Anomalies

- Tokens: 40,945,209 total (spans main + sub-agent populations; 5-execute was re-entered)
- Duration: 50,508 s wall (about 14.0 h; 5-execute 12 h wall, of which 9 h 32 m idle)
- Model / effort: claude-opus-5-5, effort low; plugin and executor 0.1.2090 (no skew)
- Anomalies: the first CodeRabbit request hit its quota and was retried after a 90-minute wait; Sourcery declined throughout on diff size (optional). The kidicap PLAN-29 landing (#586) moved the base mid-execute; it was merged into the branch with one trivial conflict in `doc/architecture/tls-and-inbound-security.adoc`, no scope change. `archive-plan` was reported `pending` in the landing facts; the plan directory is archived (`.plan/local/archived-plans/2026-11-10-sec-02-upstream-attribution-forward-headers`).
- **Review and Sonar numbers re-read from the provider:**
  - `unresolved_review_threads`: landing 0, re-read 0 — agree.
  - `review_body_findings_total`: landing 2, re-read 13 (`runbook_lane review-gate --pr-number 590`) — **difference, recorded as a false claim.** The plan's findings ledger files the 13 CodeRabbit items the re-read counts as review-body findings under `inline` (all 13 resolved `fixed`), and its 2 `review_body` items are also `fixed`; so no finding went unhandled, but the reported count does not match what the provider shows.
  - `review_body_findings_handled`: landing 2; not re-verified (no progress-file `finding:` lines in the archived plan).
  - `sonar_open_new_issues`: landing 0, re-read 6 (`sonar_rest search --pr 590`) — **difference, recorded as a false claim.** The merged code fixes 3 (`java:S1130` ×2, `java:S5778`) and suppresses 3 with rationale (`java:S107`, `java:S2386` ×2); the PR quality gate is `OK`. The search most likely reflects the last PR analysis before the fix push, which a merged PR never re-runs.

## Routing and Merge Behavior

- Review: CodeRabbit reviewed three heads; 15 actionable comments fixed (two fix-task loop-backs, TASK-11 to TASK-13, plus inline doc edits); every thread answered and resolved.
- CI/merge: all 25 PR checks green or skipped (`ci checks status --pr-number 590`), including the post-merge benchmark run. Merged through the merge queue as 81b46785. The `main` push run for 81b46785 was not checked here — Watch.
- Parallelization: the realized footprint shares only predicted doc files with the other specs — `doc/LogMessages.adoc` and `doc/security-threat-model.adoc` with all three, plus `doc/configuration/routing-and-forwarding.adoc` with the running PLAN-SEC-04 (declared on both sides). No production file shared; no unpredicted collision; no declaration correction owed.
- Cross-epic: kidicap PLAN-29 (#586, ab568dab) landed first; the shared `DispatchStage` and configuration page merged without conflict, and `ab568dab`'s Javadoc change is intact in the merged `DispatchStage`.

## Reconciliation Actions

- [x] row `status` → `shipped` — `orchestrator queue --transition PLAN-SEC-02 --status shipped`
- [x] row `pr` stamped — `590`
- [x] row `landing` stamped — `landings/PLAN-SEC-02.md`
- [x] row `plan_marshall_plan_id` stamped — `sec-02-upstream-attribution-forward-headers`
- [x] claim verdicts 0–8 persisted onto the spec — `corpus set-verdict` × 9
- [x] epic.md narrative reconciled (queue annotation, cross-epic Watch retired, tooling-skew Watch retired)
- [x] inbox message `-001` (candidate lesson) dispositioned `discarded` — project-architecture hint handed to the operator
- [x] resume anchor updated; `queue-view.md` regenerated and committed

## Follow-Ups

- L3-A (first-message bound ends at the first byte): with the operator — staging a fix plan is the operator's call.
- `forwarded.trust_scheme_host` is parsed and read by nothing: wire it (with the mask) or remove it — operator decision, outside this epic's tier.
- Whether to widen the gRPC first-message bound beyond the first forwarded byte — operator decision (overlaps L3-A).
