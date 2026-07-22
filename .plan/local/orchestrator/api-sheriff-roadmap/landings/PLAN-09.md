# Landing Analysis: PLAN-09 — k6 Benchmark Framework + APISIX Comparative Baseline (roadmap 04b)

epic: api-sheriff-roadmap
workstream: WS-02
pr: cuioss/API-Sheriff#82 (squash-merged as fbf84e3 via merge queue)

> Landing record for one shipped plan. Written after verifying claims against ground truth —
> a pasted claim is a lead, never a fact.

## Deliverable Fidelity vs Spec

Spec staged 6 deliverable groups; the lifecycle grouped them into 8, all shipped and green.

| Deliverable (spec) | Verdict | Evidence (verified on main @ fbf84e3) |
|--------------------|---------|----------------------------------------|
| 1. Plan doc 04b + README convention rewording | shipped-as-specified | benchmark convention in `doc/plan/README.adoc` now reads k6 (`k6-scripts/*.js`, native thresholds, shared `handleSummary()`); 04b listed exempt |
| 2. k6 bound to the cuioss generic format | shipped-as-specified | `K6BenchmarkConverter.java` + `K6BenchmarkConverterTest.java`; `K6ResultPostProcessor`; `K6BenchmarkLogMessages`; `package-info.java` present |
| 3. Full wrk replacement | shipped-as-specified | **`git ls-files` returns ZERO tracked wrk/lua files** — complete removal verified, not merely claimed. `Dockerfile.k6` + `Dockerfile.k6-dashboard` replace `Dockerfile.wrk` |
| 4. Benchmark aspect routes | shipped-as-specified | 8 tracked aspect scripts: `gateway_health`, `health_live`, `proxied_static`, `bearer_proxied`, `http2`, `graphql`, `upload_small`, `upload_large` — the full six-aspect matrix plus the two migrated health benchmarks |
| 5. APISIX comparison stack | shipped-as-specified | `integration-tests/src/main/docker/apisix/` present alongside `keycloak/`, `certificates/`, `sheriff-config/` — same-stack parity as specified |
| 6. Target-neutral harness + on-demand entry point | shipped-as-specified | `k6-scripts/lib/target.js` (target parameterization) + `lib/summary.js` (shared handleSummary); `ComparisonSummaryWriter` + test |
| 7. xk6-dashboard supplementary artifact | shipped-as-specified | `Dockerfile.k6-dashboard` — separate image, comparison-run only, as scoped |
| 8. Methodology + swap + CI step rewording | shipped-as-specified | `benchmarks/README.adoc`, `benchmarks/doc/` |
| **6. Documentation-tree bootstrap** | **NOT DELIVERED** | `doc/user/` and `doc/development/` **do not exist**; `doc/plan/README.adoc` `== Conventions` still has four bullets with **no documentation convention**. See below. |

## The Missed Deliverable — orchestrator sequencing error, not a plan failure

**This is my error, and it should be recorded as such.** The documentation-tree bootstrap was
folded into the PLAN-09 spec on 2026-07-20 — *after* the hand-off command had been emitted and
the plan launched (the landing reports 24h59m wall clock). The plan executed the command it was
given, and it delivered 8/8 of that command faithfully. The amendment never reached it.

The failure mode is now demonstrated rather than hypothetical, and the watch recorded at
staging ("the scaffolding must not slip out of this plan") fired exactly as written. Lesson for
this epic: **amending a staged spec after its command is emitted changes nothing about what
executes.** A post-emit amendment must either re-emit to the running plan or be re-homed to a
plan that has not launched. Recorded as a standing orchestration lesson below.

Consequence: the standing three-layer documentation convention (2026-07-20) currently has **no
trees to write into** and **no convention recorded in `doc/plan/README.adoc`**. Every downstream
hand-off carries the clause and references "roadmap plan 04b" as the creator of both — those
references are now stale and must be re-pointed when the bootstrap is re-homed.

## Ground-Truth Verification Performed

- `git log` — `fbf84e3` on main, PR #82 squash-merged; `main` up to date. Confirmed.
- `git ls-files` — zero tracked `wrk`/`.lua` artifacts. The "full replacement" claim is real.
  (Stale `benchmarks/target/classes/wrk-scripts/*` are untracked build output, not source.)
- `doc/adr/` — **ADR-0012** (comparison-lane segregation) present, status **Proposed**.
  Also noted: **ADR-0011** (JWKS trust/egress neutral names) is **Accepted** and was not
  previously tracked in this epic's ledger.
- `doc/plan/README.adoc` — benchmark convention reworded to k6 as specified; documentation
  convention absent as described above.

## Metrics and Anomalies

- 5h11m worked / 24h59m wall / 5.73M tokens / 993 tool uses. Execute phase carried 18h20m idle
  against 3h8m worked — the dominant cost is wall-clock idle, not work.
- Phase 6 (finalize) burned 1.77M tokens against 1.07M for execute-adjacent phases; 406 tool
  uses in finalize vs 93 in execute. The review/verify tail is the expensive half of this plan.
- `finalize-step-simplify`: **2 edits from 6 findings** — 4 findings not acted on. Not
  necessarily wrong (findings may have been judged out of scope), but unexplained in the
  report. Recorded as a watch rather than an assumption.
- Security audit: 0 new findings (delta). Automatic review: 9 comments across 3 iterations,
  all resolved, no loop-back.
- ADR outcome: 0012 created; a proposed `k6-replaces-wrk` ADR was **declined by the operator**
  (the decision lives in the 04b plan doc instead).

## Queue Impact

- **PLAN-04 unblocked** — it needed this harness for its convention-mandated ws/grpc
  benchmarks. That dependency is now satisfied.
- PLAN-08's benchmark-consolidation deliverable inherits the APISIX comparative numbers.
- Next in queue: **PLAN-10** (anchor types + asset serving).
