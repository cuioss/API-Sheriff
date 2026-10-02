# Landing Analysis: PLAN-16 — Application Portal and HTML Error Pages (AS-1, AS-2)

epic: kidicap-gateway-requirements
workstream: WS-03
pr: #343 (merged as 69b322b572c0734c7bc59c5b8d81a55f64539382)

> Landing record for one shipped plan. Lives at `landings/PLAN-16.md`. Written by the
> `analyze` verb after verifying claims against ground truth (actual code, artifacts,
> PR state) — a pasted claim is a lead, never a fact. See
> `persona-plan-orchestrator/standards/orchestration-model.md` for the analysis and
> reconciliation contract.

Source: inbox `plan-16-application-portal-013.md` (`kind: landing`, `landing-check complete: true`, no
missing keys) plus the operator's own report. Corroborated 2026-09-22: `ci pr view 343` reports
`state: merged` with `merge_commit_sha` 69b322b, which is `origin/main`'s head; 70 files, and
`ci checks status` reports 35 checks, overall `success`. Plan archived at
`.plan/local/archived-plans/2026-09-22-plan-16-application-portal`.

## Deliverable Fidelity vs Spec

9 of 9 reported done; the merged diff carries a matching implementation for each.

| Deliverable (spec) | Verdict | Evidence |
|--------------------|---------|----------|
| 1 `portal` config block + boot validation | shipped-as-specified | `PortalConfig`, `CatalogConfig`, `GatewayConfig`, `gateway.schema.json`, new `config/validation/rule/PortalRules.java` |
| 2 Reserved path dispatch; portal-without-OIDC decided | shipped-as-specified | `ReservedPathRegistry`, `PortalEndpoint`; decision: works without OIDC |
| 3 Catalog resolution (enabled, `order`, `title`) | shipped-as-specified | `PortalCatalog`, `EndpointConfig` |
| 4 Template rendering, built-in + `template_dir` | shipped-modified | `PortalRenderer`, `PortalPageModel`, Qute added to `api-sheriff/pom.xml` — the one new dependency, operator-approved. Template assets are served through a normal asset route rather than a portal-specific mechanism |
| 5 Response envelope (CSP, nosniff, cache) | shipped-as-specified | `PortalResponseEnvelope`, `SecurityHeadersStage`; hardened default CSP `default-src 'self'; base-uri 'none'; form-action 'self'; frame-ancestors 'none'`; `cache_seconds` applies only to session-less pages, a session response is never cached |
| 6 AS-2 `portal.error_pages` + content negotiation | shipped-as-specified | `PortalNotice`, `GatewayEdgeRoute` rendering; HTML only when `Accept` asks for `text/html` |
| 7 Gateway-originated vs relayed-upstream classification | shipped-as-specified | new `portal/ErrorPageClassifier.java` with tests; a relayed upstream body is never replaced |
| 8 Tests (unit, IT, native, escaping, toggles) | shipped-as-specified | portal/HTML-error ITs incl. `HtmlErrorPageIT`; native ITs green |
| 9 Documentation, log messages, threat model, ADR | shipped-as-specified | `doc/user/` portal page, `configuration.adoc`, `LogMessages.adoc` (`ApiSheriffLogMessages`, `ConfigLogMessages`), threat-model entries HTML-01..HTML-06, ADR-0050 (**status Proposed** — acceptance is an operator decision) |

Operator decisions recorded as built: Qute as the sole new dependency; portal works without OIDC;
`cache_seconds` → `max-age` for session-less pages only; template assets via a normal asset route;
hardened default CSP; the username is always shown.

## Metrics and Anomalies

- Tokens: 8,066,501. Duration: 35,822 s wall (~9 h 57 m) — the fastest plan of the four so far.
- Sonar new-code issues: 0.
- Two finalize loop-backs, both closed:
  1. Review triage of 18 findings (6 CodeRabbit, 12 Sonar) → TASK-18..20, all fixed.
  2. `GatewayEdgePipelineTest.metersDisallowedVerbUnderItsRoute` — a metering timing race inherited from
     #320 — surfaced on CI only → TASK-21; the test now awaits the meter instead of reading it eagerly.
- One review suggestion **declined with a recorded rationale**: CodeRabbit asked for
  `PortalPageModelTest`'s expected key names to be derived from the constants. Declining is right — the
  literal names are the contract operator templates bind to, so deriving them would let a renamed key pass
  the test while breaking every template.
- `archive-plan:pending` in the reported steps was in flight at emit time; the archived plan directory
  exists, so it completed.

## Routing and Merge Behavior

- Review: CodeRabbit's 6 findings fixed; `cuioss-review-bot` reviewed the final commit 695f72f (test-only)
  after CodeRabbit ran out of hourly quota; Sourcery (optional) refused on diff size. Every comment
  answered. **Fourth consecutive plan** whose merge head was not freshly reviewed by CodeRabbit — here the
  unreviewed delta is test-only and the required bot did cover it.
- CI/merge: merge queue; `cleanup_owed=false`; `main` clean and up to date.
- **Post-merge verification (the orchestrator's job):**
  - PR-attached **Run Integration Benchmarks** (run 35758705252) — **success** (1341 s). Corroborated; the
    plan could not wait for it.
  - main-branch **Maven Build** for 69b322b (incl. `deploy-snapshot`) — **not verifiable through the CI
    abstraction**, which reads PR-attached runs only (`checks status --head main` → "no pull requests
    found for branch main"). Carried as a Watch, same as 3e3addc.
- **Surface expansion:** declared 19 entries, realized 70 files — 37 undeclared, 1 declared-but-untouched;
  `state: expansion_detected`, base `origin/main` 69b322b, not stale. **Fourth landing in a row at roughly
  3.5x.** The pattern is now stable enough to treat as a property of this epic's spec granularity rather
  than as a per-plan authoring lapse.

## Reconciliation Actions

- [x] row `status` → `shipped` — `orchestrator queue --transition PLAN-16 --status shipped`
- [x] row `pr` stamped `#343` — `queue --set-row`
- [x] row `landing` stamped `landings/PLAN-16.md` — `queue --set-row`
- [x] row `plan_marshall_plan_id` stamped `plan-16-application-portal` — `queue --set-row`
- [x] epic.md reconciled: AS-1 and AS-2 shipped; WS-03 has no further staged plan
- [x] Watch opened for the unverified main-branch Maven Build of 69b322b
- [x] Watch on surface under-declaration updated (fourth measurement)
- [x] Open Defects opened: ADR-0050 awaiting acceptance; `marshal.json` staleness + ignored
      `build.queue.max_slots=2`; the two known product gaps (no `Vary: Accept`, no 504 page unit test)
- [x] 12 candidate lessons dispositioned (7 promoted to the plan-marshall store, 5 recorded here)
- [x] resume_anchor updated
- [x] START-HERE and Ordered Queue blocks regenerated — `orchestrator compact`

## Follow-Ups

- **Product gaps carried in the PR** (neither is blocking, both are real): error responses that can be
  negotiated to HTML or JSON send no `Vary: Accept` (the HTML variant is `no-store`); no unit test covers
  the 504 upstream-timeout error page. Small; candidates to fold into a later WS-02/WS-03 plan rather than
  a plan of their own.
- **ADR-0050 is Proposed** — accepting it is an operator decision; the orchestrator does not edit
  repository source.
- Project config housekeeping (operator actions, not orchestrator writes): `marshal.json` is older than
  the installed plan-marshall (`/marshall-steward`), and its `build.queue.max_slots=2` is ignored in favour
  of the machine-wide cap of 5 (`manage-build-server config migrate`).
