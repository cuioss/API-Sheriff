# Landing Analysis: PLAN-19 — `auth.session_fallback` on Bearer Routes

epic: kidicap-gateway-requirements
workstream: WS-04
pr: #356 (merged as 1fa648da3cb04fd197a47e82148a1ee535a427aa)

> Landing record for one shipped plan. Lives at `landings/PLAN-19.md`. Written by the
> `analyze` verb after verifying claims against ground truth (actual code, artifacts,
> PR state) — a pasted claim is a lead, never a fact. See
> `persona-plan-orchestrator/standards/orchestration-model.md` for the analysis and
> reconciliation contract.

Source: inbox `plan-19-session-fallback-on-bearer-009.md` (`kind: landing`, `landing-check complete: true`,
no missing keys) plus the operator's report. Corroborated 2026-09-25: `ci pr view 356` reports
`state: merged` with `merge_commit_sha` 1fa648da; `ci checks status` reports 33 checks, overall `success`,
including the PR-attached post-merge benchmark (run 36059476735). 36 files in the merged diff.

## Deliverable Fidelity vs Spec

7 of 7 done across 15 tasks, with one deliverable deliberately half-delivered and handed back.

| Deliverable (spec) | Verdict | Evidence |
|--------------------|---------|----------|
| 1 `auth.session_fallback` in both schemas and `AuthConfig` | shipped-as-specified | default `false`, normal `auth` cascade |
| 2 Boot validation | shipped-as-specified | startup refuses the key on a non-bearer route and refuses it without an `oidc` block; both refusal cases covered by native ITs |
| 3 Branch resolution before the CSRF check | shipped-as-specified | any `Authorization` header takes the bearer branch; a failed or malformed one answers `401` and **never** falls back to the session — the property the spec called load-bearing |
| 4 Forwarding via `mediatedBearer` | shipped-as-specified | session branch behaves as on a `require: session` route |
| 5 Observability | **shipped-modified (half dropped, recorded)** | `sheriff_auth_branch_total{route,branch}` recorded on fallback routes only. The "branch in the access log" half was dropped because **no access log exists** — handed back as inbox finding `-001` and already dispositioned as an epic Open Defect |
| 6 Tests | shipped-as-specified | 18/18 in the new fallback IT suite, plus both startup-refusal cases |
| 7 Documentation + ADR | shipped-as-specified | ADR-0055 (unique ordinal), `configuration.adoc`, `bff-session.adoc`, `anchors.adoc`, threat-model BFF-16 |

**The honest gap this plan shipped and documented:** the SESSION branch of a `session_fallback` route runs
no scope check, exactly like every `require: session` route. Any live gateway session is admitted,
including one from a login elsewhere that never requested the route's scopes. Rather than widen scope
mid-plan, PLAN-19 stated the caveat in three places — `configuration.adoc`, `bff-session.adoc`, threat-model
BFF-16 — each telling operators not to enable `session_fallback` where scopes are the authorization
boundary for browser users, and routed the fix to PLAN-23 as finding `-002`.

## Metrics and Anomalies

- Tokens: 7,155,269 as recorded. Duration: 42,153 s wall (~11 h 42 m). ⚠ The operator reports the refine
  phase is **undercounted by ~130 K tokens** (only its first run was recorded), so the total is a floor.
- Sonar new-code issues: 0. Two finalize loop-backs: round 1 fixed 4 CodeRabbit doc/Javadoc findings and 3
  Sonar findings; round 2 was spent on a **tooling misreport**, not on a defect (below).
- Surface: declared 21 entries, realized 36 files — 19 undeclared, 6 declared-but-untouched. Back to the
  ~1.7x pattern after PLAN-18's and PLAN-22's accurate declarations. The plan's own
  `realized_footprint` recorded 55 paths because it was captured against a stale local `main`; the
  36-file figure here is the merged diff and is the one this record uses (third consecutive plan hit by
  that same stale-base defect).

## Routing and Merge Behavior

- Review: CodeRabbit and `cuioss-review-bot` **both reviewed the final commit clean** — the second plan of
  eight to achieve that. Round 2 existed only because the automatic-review participation classifier
  misreported the bot as `participated_stale` although it had covered the final commit; the operator
  reconciled it from provider evidence (the PR-Agent guide edited in place to "Review updated until
  2a7c68a", and CodeRabbit's "Already reviewed the last commit") and closed the step on that. Filed as
  lesson `2026-09-24-18-001`.
- The `/review` and `@coderabbitai review` re-triggers were posted and turned out to be unnecessary —
  consistent with the org-side PR-Agent defect already recorded: the trigger step in CLAUDE.md's workflow
  no longer fires anything in this repository.
- **The pre-merge ADR duplicate gate was again unread**, for a new reason: the installed executor
  (0.1.1719) is older than the workflow (0.1.1757) and its `manage-adr scan` emits no `duplicate_count`.
  The operator authorized the merge after a manual check — ADR-0055 is unique, and the only duplicate is
  the pre-existing ADR-0053 pair, still owned by PLAN-20. **This is the second consecutive plan to merge
  past that gate**, and the cause is now a version mismatch on top of the missing field.
- CI/merge: merge queue (squash); `cleanup_owed=false`; worktree removed, `main` clean.
- **Post-merge verification (the orchestrator's job):** the PR-attached benchmark is green (above). The
  main-branch **Maven Build** for 1fa648da is **unobserved**, as for every merge commit in this epic.
- The phase-transition mailbox probe's `not_orchestrated` reports now have a measured cause: they ran with
  the **worktree** as cwd, where `.plan/local/orchestrator` does not exist. `inbox detect` on the stored
  `source_id` resolves `orchestrated`. That is a sharper diagnosis than the four recurrences recorded so
  far and is folded onto the standing lesson.

## Reconciliation Actions

- [x] row `status` → `shipped` — `orchestrator queue --transition PLAN-19 --status shipped`
- [x] row `pr` stamped `#356`, row `landing` stamped `landings/PLAN-19.md` — `queue --set-row`
- [x] row `plan_marshall_plan_id` already stamped `plan-19-session-fallback-on-bearer` at start
- [x] epic.md reconciled: AS-side session-fallback shipped; the access-log Open Defect confirmed as the
      recorded home of deliverable 5's dropped half
- [x] **PLAN-23 re-grounded and widened** from finding `-002`: a `session_fallback` acceptance row added,
      the ready IT fixture named, and the caveat-removal made an explicit deliverable
- [x] Open Defect updated: the ADR gate is now unread for TWO reasons (missing field + executor/workflow
      version mismatch); `/marshall-steward` is the remedy and is an operator action
- [x] 8 messages dispositioned (1 finding folded into PLAN-23, 6 lessons, 1 landing)
- [x] resume_anchor updated; START-HERE and Ordered Queue regenerated

## Follow-Ups

- PLAN-23 is the queue head and now carries the `session_fallback` session branch as well. Its caveat
  removal in three documents is part of its definition of done.
- The stale note in this repository's own docs saying PLAN-20 renumbers the ADR-0053 pair remains
  accurate — PLAN-20 still owns it — but the pair is still live on `main` after two plans merged past the
  gate meant to catch it.
