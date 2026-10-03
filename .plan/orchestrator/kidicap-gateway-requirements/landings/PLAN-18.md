# Landing Analysis: PLAN-18 — Route Header Matcher Defects

epic: kidicap-gateway-requirements
workstream: WS-02
pr: #346 (merged as f6067588696a63de78fbb9a32dc3c2b47c37faff)

> Landing record for one shipped plan. Lives at `landings/PLAN-18.md`. Written by the
> `analyze` verb after verifying claims against ground truth (actual code, artifacts,
> PR state) — a pasted claim is a lead, never a fact. See
> `persona-plan-orchestrator/standards/orchestration-model.md` for the analysis and
> reconciliation contract.

Source: inbox `route-header-matcher-defects-007.md` (`kind: landing`, `landing-check complete: true`, no
missing keys) plus the operator's report. Corroborated 2026-09-24: `ci pr view 346` reports
`state: merged` with `merge_commit_sha` f6067588, which is an ancestor of `origin/main` (head 84e07c7c,
a workflow bump); `ci checks status` reports 33 checks, overall `success`, including the PR-attached
post-merge benchmark (run 35917943724). Plan archived.

## Deliverable Fidelity vs Spec

3 of 3 done. Both defects the downstream reported are closed, and two more were found and fixed in-run.

| Deliverable (spec) | Verdict | Evidence |
|--------------------|---------|----------|
| 1 Header names matched as the runtime normalises them | shipped-as-specified | names lower-cased with `Locale.ROOT` at the route-compile seam, so ASCII names are effectively case-insensitive and `name: Authorization` matches; ADR-0053 records the seam |
| 2 `present: false` constrains, and fields compose | shipped-as-specified | `present: false` now requires absence and composes with `value` / `present: true` under AND |
| 3 Boot validation judges matchers as the runtime evaluates them | shipped-modified (widened) | the validator refuses a matcher carrying both `present: false` and a `value`, and refuses two contradictory matchers in one route; ADR-0054 records the rule |
| — tests | shipped-as-specified | unit tests plus an IT proving the chosen route no longer depends on declaration order |
| — docs | shipped-as-specified | `endpoint-routes.adoc`, `configuration.adoc`; ADR-0053 and ADR-0054 (both **Proposed**) |
| — added-unplanned | two real finds | **(a)** CodeRabbit found the contradictory-matcher case, now refused at boot. **(b)** The finalize security audit found the validator comparing names with `equalsIgnoreCase` while the runtime used `toLowerCase(Locale.ROOT)` — for a name like `X-İd` the validator could approve a route pair the runtime cannot tell apart. Fixed with tests |

Find (b) is the sharper of the two: it was **introduced by the review-fix commit itself** and was caught
neither by the review loop nor by the tests written for that fix — only by the security audit re-firing.

## Metrics and Anomalies

- Tokens: 4,273,671 — the cheapest plan of the six. Duration: 56,429 s wall (~15 h 40 m).
- Sonar new-code issues: 0. Local at the last code change: 3770 + 91 + 202 tests green.
- **All 5 allowed fix rounds were used** — the highest of any plan here, driven by the two in-run finds.
- Surface: declared 11 entries, realized 12 files — 3 undeclared, 2 declared-but-untouched. **The first
  accurate declaration in this epic** after five consecutive ~3–4x expansions; the small, defect-shaped
  scope is the likely reason, which is itself evidence for cutting future specs narrower.

## Routing and Merge Behavior

- Review: CodeRabbit's finding fixed; Sourcery posted a non-actionable "Approved.". **Merged under an
  operator merge authorization** (barrier-ask-override, recorded against 90c154b): PR-Agent did not answer
  two `/review` triggers on the final docs-only head and CodeRabbit was rate-limited there. The last clean
  reviews were at 3d0d6e7, and the unreviewed delta is two reworded ADR-0053 sentences addressing
  CodeRabbit's own resolved threads. The operator's standing call — earlier reviews count — is recorded
  against that exact commit. Fifth of six plans whose merge head was not freshly reviewed.
- CI/merge: merge queue; `cleanup_owed=false`; PR CI green on every push including native ITs.
- **Post-merge verification (the orchestrator's job):**
  - PR-attached **Run Integration Benchmarks** (run 35917943724) — **success** (1267 s). Corroborated.
  - main-branch **Integration Tests** run 35917940826 for f6067588 — **FAILED, infrastructure only**: the
    runner could not check out the repository ("server certificate verification failed", three times), so
    **no test executed**. This is not a code signal: the same tree passed the ITs in PR CI and in the
    merge-queue re-test. A re-run is owed and is an operator action (the orchestrator's CI access is
    read-side only).
  - main-branch **Maven Build** for f6067588 — unobserved, as for every earlier merge commit.
- A stray PR-level comment on #346 (`IC_kwDOPatrT88AAAABWb_adQ`) carries internal triage prose that
  `post_responses` transmitted verbatim as a public reply. Harmless; deletable by hand. The mechanism is
  filed as a plan-marshall lesson.

## Reconciliation Actions

- [x] row `status` → `shipped` — `orchestrator queue --transition PLAN-18 --status shipped`
- [x] row `pr` stamped `#346` — `queue --set-row`
- [x] row `landing` stamped `landings/PLAN-18.md` — `queue --set-row`
- [x] row `plan_marshall_plan_id` already stamped `route-header-matcher-defects` at start
- [x] epic.md reconciled: both downstream route-matcher defects closed and their Open Defect retired
- [x] Open Defect opened: **two ADRs share number 0053** on `main` (see Follow-Ups)
- [x] Watch updated: the owed main-branch re-run, plus the still-unobserved Maven Builds
- [x] 6 candidate lessons dispositioned (3 promoted, 1 folded as a recurrence, 2 recorded here)
- [x] resume_anchor updated
- [x] START-HERE and Ordered Queue blocks regenerated — `orchestrator compact`

## Follow-Ups

- **ADR numbering collided twice under concurrent plans, and `main` currently carries two ADR-0053s**:
  `0053-A_header_matchers_name_is_normalised…` (this PR) and
  `0053-Portal_templates_render_on_a_standalone_Qute_engine…` (#348, which itself renamed that file from
  0050 after #341 had taken 0050 — the portal ADR PLAN-16's landing recorded as ADR-0050). One of the two
  needs renumbering, and the allocator needs to stop handing the same ordinal to concurrent plans. Fixing
  it edits repository source, so it belongs to a plan or an operator, not to the orchestrator.
- ADR-0053 and ADR-0054 are **Proposed**; acceptance is an operator decision, as ADR-0050 already is.
- The validator/runtime parity lesson (find (b)) applies directly to **PLAN-19**, which adds boot rules
  over the same auth cascade — recorded as a Watch.
