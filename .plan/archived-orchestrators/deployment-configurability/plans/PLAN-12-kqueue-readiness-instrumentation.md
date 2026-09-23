# PLAN-12: Instrument the kqueue readiness hypothesis (macOS-local loopback stall)

epic: deployment-configurability
workstream: WS-07

> ⛔ **TRACKING SPEC for work that will NOT run through the plan-marshall lifecycle.** By operator
> decision 2026-09-02 this follow-up is executed with Claude Code directly, from
> `.plan/temp/kqueue-readiness-instrumentation.md`. It has no plan id, no phases, no execution
> manifest and no plan-lifecycle finalize — so its `plan_marshall_plan_id` row field is
> **permanently empty by construction**, not merely unstamped, and no `analyze` drain will ever
> receive an inbox message from it.
>
> **It carries a row anyway, for exactly one reason: gate coverage.** A row is the only way this
> epic's surface-disjointness gate can see the work at all — and the corpus requires row↔spec in
> both directions, so a spec without a row would itself be a reported defect. PLAN-11 spent its whole
> first day invisible to the gate for want of this, and the ADR-0038 collision is what an unseen
> surface costs.
>
> **The authoritative brief is `.plan/temp/kqueue-readiness-instrumentation.md`** (15 KB, written
> 2026-09-02). Nothing here restates it — read it directly. The operator's command is:
> `Read .plan/temp/kqueue-readiness-instrumentation.md and work from it.`

## Objective

Instrument the one hypothesis PLAN-11 left standing and could not test: **a loopback readiness event
the macOS kqueue selector never delivers.** PLAN-11 established that all threads are idle at the
stall — acceptor and all three event loops parked in `KQueue.poll` — which rules out saturation,
deadlock and a blocked handler, and means the work never *arrived* rather than arriving late. Nothing
has instrumented that.

⛔ **This is a measurement, not a fix.** The brief's prime directive is explicit that four fixes have
already failed on unmeasured mechanisms — the `-T1` pin, the loopback-literal change, the tiered
ceilings, and the `deadPort` hold the evidence killed before it was written. A fifth would be the
same error again.

## Deliverables

Owned by the brief, which sequences them "work these in order, stop when one discriminates" —
summarised here only so a queue reader knows what is in flight:

1. OS socket-state snapshot at timeout — cheapest, and never yet run
2. A minimal reproducer outside Maven — highest value even if it does not discriminate
3. Kernel-level tracing — only after 1 and 2

## Claim Labels

- OBSERVED: the brief **exists and is complete** — `.plan/temp/kqueue-readiness-instrumentation.md`,
  15,061 bytes, written 2026-09-02, structured with an explicit `MEASURED — do not re-derive` section
  separated from `INFERRED — hypotheses, not findings`. Verified at HEAD `5948962`.
  - verdict: corroborated | checked_at: cc10ce2a2c7840162bbc087242d42cbbc3cd14c3 | by: deployment-configurability/cleanup | rescoped: n/a | evidence: .plan/temp/kqueue-readiness-instrumentation.md still present, unchanged size/mtime
- OBSERVED: it inherits PLAN-11's scope discipline rather than re-opening it — it carries forward
  *eliminated by measurement — do not re-test*, and records that **the port-reuse hypothesis is only
  partly retired** (held-listener form only). That is the same scoping PLAN-11's landing records.
  - verdict: corroborated | checked_at: cc10ce2a2c7840162bbc087242d42cbbc3cd14c3 | by: deployment-configurability/cleanup | rescoped: n/a | evidence: brief file unchanged, content-derived claims stand
- OBSERVED: it is **test-scope only** by its own constraint, and names `Awaits.java`,
  `AwaitsTest.java`, `WebSocketRelayStageTest.listenOwner` and `build-gate-discipline.adoc` as
  existing assets to build on. ⚠ It explicitly reserves the case where evidence forces a production
  change, requiring that it be *surfaced rather than absorbed*.
  - verdict: corroborated | checked_at: cc10ce2a2c7840162bbc087242d42cbbc3cd14c3 | by: deployment-configurability/cleanup | rescoped: n/a | evidence: brief file unchanged; test-scope-only constraint and named assets remain as described
- OBSERVED: it will produce **no inbox message and no plan-lifecycle landing**, because it runs
  outside the lifecycle. Reconciling it is therefore a manual `analyze` from its PR and its commits —
  there is no automatic channel. Asserted by construction of the operator's decision.
  - verdict: corroborated | checked_at: cc10ce2a2c7840162bbc087242d42cbbc3cd14c3 | by: deployment-configurability/cleanup | rescoped: n/a | evidence: structural fact by construction, no plan id ever existed for this work
- Verify-first clause: this spec is a tracking record. Settle every question of scope, method and
  stopping rule against the brief, never against this file. ⚠ The brief lives in `.plan/temp/`, which
  CLAUDE.md designates for temporary files — the same non-durability that PLAN-11's brief carried.

## Expected Surface

⚠ Declared from the brief's named assets and its test-scope-only constraint. It is the **least
certain** surface in this corpus: the work is exploratory by design, step 2 may add a reproducer whose
location is not yet decided, and no `references.json` exists to derive from because there is no plan.
Re-read it at each reconciliation.

- OBSERVED: `api-sheriff/src/test/java/de/cuioss/sheriff/gateway/testsupport/Awaits.java`
- OBSERVED: `api-sheriff/src/test/java/de/cuioss/sheriff/gateway/testsupport/AwaitsTest.java`
- OBSERVED: `api-sheriff/src/test/java/de/cuioss/sheriff/gateway/edge/WebSocketRelayStageTest.java`
- OBSERVED: `doc/development/build-gate-discipline.adoc`
- HYPOTHESIS: `api-sheriff/src/test/resources/junit-platform.properties` — named by the brief; touched only if the measurement needs a platform setting (verify-at-outline)

## Dependencies and Sequencing

- **Depends on: none.** PLAN-11 shipped the instrumentation base it builds on (PR #243 → `5948962`).
- ⚠ **Adjacent to PLAN-03, and this is the pairing to watch.** PLAN-03 modifies
  `edge/WebSocketRelayStage.java` (main source); this plan instruments
  `edge/WebSocketRelayStageTest.java` (test source). Different files, so the gate reports no overlap —
  but they are the production/test pair of one class, and a signature change on one side lands in the
  other. Treat a PLAN-03 emit while this is live as requiring a manual check the gate cannot make.
- **Surface-disjoint from PLAN-02 and PLAN-05**, the two currently in flight: PLAN-02 is
  `integration-tests/`, `build-parent/`, `doc/` and main-source config; PLAN-05 is the BFF refresh ITs
  and the realm fixture. Neither touches `api-sheriff/src/test/.../testsupport` or `.../edge`.
- ⛔ **`doc/development/build-gate-discipline.adoc` is a shared surface with consequences beyond this
  epic** — CLAUDE.md's Pre-Commit Process points at it, so an edit changes guidance every plan in this
  repository reads. PLAN-11 already rewrote it (+271 lines).

## Hand-Off Command

⛔ **No `/plan-marshall` command is owed and none must be emitted.** This plan is deliberately outside
the lifecycle; a `task=` pointer at this tracking spec would `init` a plan the operator has decided
not to run. The operator's actual command is:

```text
Read .plan/temp/kqueue-readiness-instrumentation.md and work from it.
```

## Write-Boundary

Touches only repository source and tests. ⚠ Running outside the lifecycle, it has **no inbox channel
at all** — the `inbox/{sender}-{seq}` carve-out presumes a plan id this work does not have. The
orchestrator reconciles this row manually from its PR and commits at the next `analyze`. See
`orchestration-model.md` § Ledger Write-Boundary.
