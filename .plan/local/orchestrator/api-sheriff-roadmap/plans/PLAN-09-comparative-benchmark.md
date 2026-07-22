# PLAN-09: k6 Benchmark Framework + APISIX Comparative Baseline (roadmap 04b, new)

epic: api-sheriff-roadmap
workstream: WS-02

> Staged plan spec. NEW work added by operator direction 2026-07-19; scope settled same day
> via operator decision: FULL k6 replacement of wrk (one framework for CI/per-plan
> benchmarks AND the comparison). Queue position: after PLAN-03, before PLAN-04 — PLAN-04
> needs the k6 harness for its own convention-mandated ws/grpc benchmarks.

## Objective

Replace wrk with k6 as THE benchmark framework (operator decision 2026-07-19), and on that
unified harness deliver the comparative baseline: Apache APISIX single-node standalone
deployed in the exact same structure against the exact same endpoints, with the identical
benchmark set runnable against either gateway by swapping which compose stack is started.
This plan covers the six HTTP-family aspects (unauthenticated, bearer, http2, graphql,
upload-normal 1 MB, upload-large 50 MB); the websocket and grpc aspects follow in PLAN-04
on the harness this plan builds. On-demand development runs only — NOT CI-wired for the
comparison; the migrated CI/per-plan benchmarks keep their existing pipeline role.

## Deliverables

1. **Plan doc (doc-first)**: `doc/plan/04b-comparative-benchmark.adoc` — the k6-replacement
   decision (wrk cannot serve roadmap 05's ws/grpc benchmark obligation; one toolchain over
   two; Gatling/Fortio named as rejected alternatives; the raw-throughput trade-off and the
   relative-measurement rationale; one-time baseline restart), the aspect matrix, upload-size
   rationale (1 MB / 50 MB = 10× the 5 MB streaming threshold), parity/fairness rules, swap
   mechanism, NOT-in-CI stance for comparison runs. Update `doc/plan/README.adoc`: link 04b
   and reword the benchmark convention ("WRK benchmark" → k6, lua/sh → k6 js + metadata
   conventions).
2. **k6 migration (full wrk replacement), bound to the cuioss generic format**: the repo
   consumes `de.cuioss.sheriff.token:benchmarking-common` (TokenSheriff), whose designed
   seam is `BenchmarkConverter.convert(Path) → BenchmarkData`; badges, 10-run history,
   trends, and the GitHub Pages HTML report all hang off `BenchmarkData` tool-agnostically.
   Deliver: a `K6BenchmarkConverter implements BenchmarkConverter` (in this repo's
   benchmarks module) consuming the deterministic end-of-run JSON that each k6 script emits
   via `handleSummary()` — structured JSON in, no regex text-scraping (strictly better than
   the WrkBenchmarkConverter path); wire it through the existing
   `BenchmarkResultProcessor`/gh-pages-ready flow so `benchmark-pages.py`, badges, history,
   trend and the Pages HTML continue UNCHANGED (one-time baseline restart accepted and
   noted on the pages). Re-author the four wrk benchmarks as k6 scripts; native k6
   `thresholds` replace the 02b custom error gate (breached = non-zero exit);
   `Dockerfile.wrk` replaced by a pinned k6 image; `WrkResultPostProcessor`, wrk scripts,
   lua, and image remnants DELETED (pre-1.0 — TokenSheriff's own `WrkBenchmarkConverter`
   upstream is untouched, it still serves TokenSheriff); value-asserting tests carry over
   to `K6BenchmarkConverter` (known-numbers k6 summary fixtures). The 04b doc proposes
   upstreaming `K6BenchmarkConverter` into benchmarking-common as a cross-repo follow-up
   (out of this epic's scope). Optional supplement for comparison runs: the xk6-dashboard
   self-contained per-run HTML report as an extra artifact — it does NOT replace the
   generic format (single-run only: no badges, no history, no trends, no cuioss.github.io
   consistency).
3. **APISIX reference stack**: compose stack with pinned `apache/apisix` standalone (yaml
   routes, no etcd), fronting the SAME upstreams (static nginx, go-httpbin) and the SAME
   Keycloak benchmark realm, same TLS certs; route-for-route parity for the six aspects:
   openid-connect `bearer_only` (offline JWT) mirroring Sheriff's TokenValidator route,
   graphql POST route, upload routes with the same raised body-size limit on both gateways.
   Pre-provision ws/grpc parity routes where possible so PLAN-04 only adds the benchmarks.
4. **Target-neutral harness + comparison runner**: one target parameter (default API
   Sheriff — CI runs byte-identical), `gateway_target` in all result metadata; one
   documented on-demand entry point (script or opt-in profile, never CI-bound) running the
   aspect matrix or a named subset against whichever stack is started; per-aspect
   side-by-side summary (RPS or MB/s for uploads, P50/P99) segregated from CI baselines and
   the pages ingest. upload-large: reduced concurrency (~5-10 conns), throughput+latency
   metrics, not RPS.
5. **Fairness + docs**: methodology in benchmarks/README.adoc + performance-scoring.adoc —
   single node both sides, same host/resources, same TLS, identical routes, warmup, same
   load parameters per aspect, k6 container CPU allocation guidance, relative-not-absolute
   framing, swap procedure.
6. **Documentation-tree bootstrap** (operator 2026-07-20 — cross-cutting, this plan owns the
   scaffolding because it lands next). Ground truth: `doc/README.adoc` declares its own scope
   as *design documentation* ("what will be built and how the parts fit together"); there is
   **no operator guide and no developer guide in the repo at all** today. Create:
   - `doc/user/` — task-oriented operator guide (getting started, deploy, configure, run,
     troubleshoot). Links to `configuration.adoc` as the *reference*; never duplicates it.
   - `doc/development/` — contributor guide (build, run the quality gate, run ITs, run
     benchmarks and the comparison, module layout, extension points). Matches the sibling
     TokenSheriff repo's existing `doc/development/` convention.
   - An index entry in `doc/README.adoc` distinguishing design docs from these two trees, so
     the audience split is stated where a reader first lands.
   - The **per-plan documentation convention** recorded in `doc/plan/README.adoc` as a fifth
     bullet in its `== Conventions` section (which today has exactly four: integration tests,
     benchmarks, dependency approvals, pre-1.0 rules — and **no documentation convention at
     all**), so it binds every later plan at the source rather than only in this ledger. It
     must state **three layers**, all in the same PR:
     1. **Reference** — every new/changed configuration key, default, enum value and endpoint
        lands in `configuration.adoc` (structural changes in `architecture.adoc`). Exhaustive,
        per-key, not optional.
     2. **Operator guide** — `doc/user/`, task-oriented, linking to the reference rather than
        duplicating its key tables.
     3. **Developer guide** — `doc/development/`, contributor-facing.
     Plus the anti-rubber-stamp rule: plan/variant/ADR updates do not satisfy any layer, and a
     plan with no operator- or contributor-visible delta says so explicitly.
   - **Classification fix**: `doc/README.adoc` currently files `configuration.adoc` under
     *Design Documents* ("the static, immutable YAML mapping schema"), yet it is in practice
     the operator's configuration **reference** — the most user-facing document in the repo.
     State that dual role in the index entry. Do **not** move the file: it is linked heavily
     across the variants, plan docs, and threat model, and relocating it would churn every
     cross-reference for a labelling problem.
   This plan is also the first customer: its own developer-facing content (how to run the k6
   benchmarks, how to run the APISIX comparison, the swap procedure) belongs in
   `doc/development/`, with `benchmarks/README.adoc` remaining the module-local detail.

## Expected Surface

- `benchmarks/` — WHOLE module reworked: k6 scripts replace wrk-scripts/, k6 image replaces
  Dockerfile.wrk, post-processor + tests reworked for k6 JSON, runner, docs
- `integration-tests/` — APISIX compose stack + apisix.yaml; wrk compose service → k6
- `doc/plan/` — 04b doc + README.adoc convention rewording (incl. the new per-plan
  documentation convention)
- `doc/user/` + `doc/development/` — NEW trees bootstrapped here; `doc/README.adoc` index entry
- Explicitly NOT: api-sheriff/ production code; CI workflow files; BFF; ws/grpc benchmark
  scripts (PLAN-04, on this harness)

## Dependencies and Sequencing

- Depends on: PLAN-03 (bearer route + its wrk bearer benchmark to migrate; real data plane)
- Blocks: PLAN-04 needs this harness for its convention-mandated ws/grpc benchmarks —
  PLAN-09 runs BEFORE PLAN-04
- Overlaps with: PLAN-04 (compose + benchmarks surface) and PLAN-05 (compose) — sequenced
- Scope note: 6 deliverable groups after the 2026-07-20 documentation-bootstrap fold; at but
  not over the split-guard threshold. The bootstrap is scaffolding + this plan's own dev docs,
  not a second body of work — re-evaluate at emit if it proves larger.
- Blocks (documentation): every later plan writes into the `doc/user/` + `doc/development/`
  trees this plan creates, so the scaffolding must not slip out of it.

## Hand-Off Command

```text
/plan-marshall Replace wrk with k6 as THE benchmark framework and build the APISIX comparative baseline, per the staged spec (operator decisions 2026-07-19: full k6 replacement, one toolchain; comparison on-demand only, never CI): (1) author doc/plan/04b-comparative-benchmark.adoc (doc-first) recording the k6 decision (wrk is HTTP/1.1-only and cannot serve roadmap 05's ws/grpc benchmark obligation; Gatling/Fortio rejected alternatives; raw-throughput trade-off accepted — measurements are relative; one-time baseline restart), the aspect matrix (unauth, bearer, http2, graphql, upload 1MB, upload 50MB now; ws/grpc follow in roadmap 05), upload-size rationale, fairness rules; update doc/plan/README.adoc — link 04b and reword the benchmark convention from WRK/lua to k6 + the same metadata conventions. (2) Bind k6 into the cuioss generic benchmark format via its designed seam: implement K6BenchmarkConverter implementing de.cuioss.benchmarking.common.converter.BenchmarkConverter (from the benchmarking-common dependency) consuming the deterministic JSON each k6 script emits via handleSummary(), and wire it through the existing BenchmarkResultProcessor/gh-pages-ready flow so badges, 10-run history, trends, benchmark-pages.py, and the GitHub Pages HTML report continue UNCHANGED (one-time baseline restart noted on the pages); migrate the four wrk benchmarks (three existing + plan 04's bearer benchmark) to k6 scripts with native thresholds replacing the custom error-rate gate (breached = non-zero exit); replace Dockerfile.wrk with a pinned k6 image; DELETE WrkResultPostProcessor and all wrk/lua/image remnants (pre-1.0; TokenSheriff's upstream WrkBenchmarkConverter is untouched); carry the value-asserting tests over to K6BenchmarkConverter with known-numbers k6 summary fixtures; propose upstreaming K6BenchmarkConverter to benchmarking-common in the 04b doc as a cross-repo follow-up; optionally attach the xk6-dashboard self-contained HTML report as a supplementary per-run artifact for comparison runs (it does not replace the generic format). (3) Add the APISIX compose stack (pinned apache/apisix, standalone yaml mode, no etcd) with route-for-route parity against the SAME upstreams and SAME Keycloak benchmark realm, same TLS certs: openid-connect bearer_only mirroring Sheriff's offline validation, graphql POST route, upload routes with the same raised body-size limit both sides; pre-provision ws/grpc parity routes for roadmap 05. (4) Make the harness target-neutral (one target parameter defaulting to API Sheriff, gateway_target in all metadata) and provide one documented on-demand comparison entry point (never CI-bound) running the matrix or a named aspect subset against whichever stack is started, with per-aspect side-by-side RPS-or-MBps/P50/P99 summary segregated from CI baselines and pages; upload-large runs at reduced concurrency reporting throughput+latency. (5) Document methodology and the swap procedure in benchmarks/README.adoc and benchmarks/doc/performance-scoring.adoc (single node both sides, same host, same TLS, identical routes, warmup, k6 CPU allocation, relative-not-absolute framing). (6) BOOTSTRAP THE DOCUMENTATION TREES (operator decision 2026-07-20, cross-cutting — this plan owns the scaffolding because it lands next). Ground truth: doc/README.adoc declares its own scope as DESIGN documentation ("what will be built and how the parts fit together") and the repo has NO operator guide and NO developer guide at all. Create doc/user/ — task-oriented operator guide (getting started, deploy, configure, run, troubleshoot) that links to doc/configuration.adoc as the reference and never duplicates it — and doc/development/ — contributor guide (build, quality gate, run ITs, run benchmarks and the comparison, module layout, extension points), matching the sibling TokenSheriff repo's existing doc/development/ convention. Add an index entry in doc/README.adoc distinguishing the design docs from these two trees so the audience split is stated where a reader first lands. ALSO FIX THE CLASSIFICATION: doc/README.adoc currently files configuration.adoc under Design Documents ("the static, immutable YAML mapping schema") when it is in practice the operator's configuration REFERENCE and the most user-facing document in the repo — state that dual role in its index entry, but do NOT move the file (it is linked heavily across the variants, plan docs and threat model, and relocating it would churn every cross-reference to fix a labelling problem). Record the PER-PLAN DOCUMENTATION CONVENTION in doc/plan/README.adoc as a FIFTH bullet in its == Conventions section — which today has exactly four (integration tests, benchmarks, dependency approvals, pre-1.0 rules) and NO documentation convention at all — so it binds every later plan at the source rather than only in the orchestration ledger. It must state THREE layers, all in the same PR: (1) REFERENCE — every new or changed configuration key, default, enum value and endpoint lands in configuration.adoc, structural changes in architecture.adoc, exhaustive and per-key, not optional; (2) OPERATOR GUIDE — doc/user/, task-oriented, linking to the reference rather than duplicating its key tables; (3) DEVELOPER GUIDE — doc/development/, contributor-facing. Plus the anti-rubber-stamp rule: plan-doc, variant-doc or ADR updates do NOT satisfy any of the three layers, and a plan that genuinely changes nothing an operator or contributor would do differently states that explicitly rather than silently skipping it. This plan is the convention's first customer: its own developer-facing content (running the k6 benchmarks, running the APISIX comparison, the swap procedure) goes in doc/development/, with benchmarks/README.adoc keeping the module-local detail. Branch feature/plan-04b-comparative-benchmark, one PR expected.
```

## Status Trail

- plan_marshall_plan_id: (set at launch)
- pr: (set when the PR opens)
- landing: (set when recorded at landings/PLAN-09.md)
