# Landing Analysis: PLAN-V02-22 — Demo-Client and Documentation Leftovers

epic: api-sheriff-0-2-0
workstream: WS-02
pr: #512 (https://github.com/cuioss/API-Sheriff/pull/512)

> Landing record for one shipped plan. Written by the `analyze` verb on 2026-10-22 from the inbox messages
> `plan-v02-22-demo-client-and-doc-leftovers-001` (landing) and `-002` (candidate lesson). Each material claim
> was checked against `origin/main` at `abf911dc`, the PR, issue #189 and the CI runs.

## Deliverable Fidelity vs Spec

Checked against `origin/main` at `abf911dc` (#512, merge queue): 19 files changed (+1,085 / −83).

| Deliverable (spec) | Verdict | Evidence |
|--------------------|---------|----------|
| D1 — the rest of issue #189 | shipped-as-specified | Rejection panel in `spa/app.js` and `spa/index.html`, a new `06-rejection-panel.spec.js`, and the corrected `doc/variants/01-base-gateway.adoc`, `demo-client/doc/integration-sample.adoc` and `playwright-suite.adoc` (PROHIBITED ASSERTION scope). `doc/plan/` is absent on `main`. Issue #189 is CLOSED (2026-10-22T19:21:54Z) with a comment naming #512 and `abf911dc`. |
| D2 — the start script's printed command | shipped-as-specified | The script now prints `(cd demo-client && PLAYWRIGHT_BASE_URL_SERVER=… PLAYWRIGHT_BASE_URL_COOKIE=… npm run test)` from the gateway rows it started, and refuses when a row supplies none (line 366). The spec's "print a command that sets them" option was taken. |
| D3 — release guard evidence claims | shipped-as-specified | `doc/development/release-process.adoc` quotes the guard's `unchanged at …` reason line, names `fetch-depth: 0` and `fetch-tags: true`, and cites `reusable-maven-release.yml@7115714` (v0.42.0), which matches the pin in `release.yml` line 132. |
| added, unplanned | — | New workflow `.github/workflows/release-guard-docs.yml` (+270) and `ReleaseGuardPinDocsContractTest` (+323) that fail a PR which moves the guard pin without a re-check of the section. Also `.claude/skills/release/SKILL.md`, `doc/security-threat-model.adoc`, two further documents, and a cookie-mode overlay in `integration-tests/src/main/docker/` that enables `portal.error_pages` so both Playwright projects see the same negotiated page. None of these were in the spec's Expected Surface. |

The plan counts six deliverables; the spec has three, with sub-items. The count is not a discrepancy.

## Metrics and Anomalies

- Tokens: 8,537,648. Wall time: 49,733 s (13.8 h). Model claude-opus-5-5, effort low.
- Code and functional reviews were closed by operator decision after round 8, on the grounds that the scope
  had become workflow-only. CodeRabbit re-reviewed every later head and confirmed the fixes. Neither review's
  final verdict is in the landing facts; they carry no counts.
- The runtime stamp was not refreshed at landing: the executor (0.1.1977) rejected `refresh-runtime-stamp` as an
  unknown verb, while the newest cached plugin is 0.1.1986. The start-time stamp was reported instead.
- `github_pr fetch_findings` stored only CodeRabbit's review summaries for two re-reviews, not the four inline
  comments; the plan replied to those on the PR directly.

## Routing and Merge Behavior

- Merged through the merge queue as `abf911dc` at 2026-10-22T19:07:23Z. Both merge_group runs passed.
- Post-merge on `main` at `abf911dc`, all passed: Maven Build, Integration Tests, Demo Client E2E, Config
  Validation, Scorecard. The PR-attached post-merge benchmark run completed successfully.
- No collision with `PLAN-V02-23` or `PLAN-V02-46` was reported.

## Residue and Owners

- **Candidate lesson `-002`** is about plan-marshall tooling only. It is routed to the plan-marshall
  `lessons-routing` inbox as messages `-051` (the `qgate add --type warning` recurrence, a `finding`) and
  `-052` (the `fetch_findings` gap and the `refresh-runtime-stamp` observation). No local copy exists.
- **New Watch:** the release-guard docs check fails the first pin move until the section is re-checked.
- **Still open from `PLAN-V02-21`:** ADR-0031 still says "seven gateway instances", and the start script
  keeps a literal `120` for the Keycloak wait. The plan edited the script and did not take either. The Watch
  now says so and names no owner.
- Open Defect 2 and the issue #189 Watch are closed and moved to `settled.md`.

## Reconciliation Actions

- [x] row `status` → `shipped` — `orchestrator queue --transition PLAN-V02-22 --status shipped`
- [x] row `pr` stamped — `orchestrator queue --set-row PLAN-V02-22 --field pr --value 512`
- [x] row `landing` stamped — `orchestrator queue --set-row PLAN-V02-22 --field landing --value landings/PLAN-V02-22.md`
- [x] row `plan_marshall_plan_id` already stamped
- [x] epic.md narrative reconciled (Open Defect 2 and the #189 Watch relocated, guard-claims bullet updated, V02-21 leftovers Watch corrected, new Watch)
- [x] resume anchor updated in `resume_anchor.md`
- [x] `queue-view.md` regenerated

## Follow-Ups

- Pin-bump check: first Renovate or manual bump of the release-guard pin (Watch).
- ADR-0031 "seven" and the literal `120`: no owner (Watch).
