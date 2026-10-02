# Landing Analysis: PLAN-14 — Session and Scopes (AS-5, AS-6, AS-14)

epic: kidicap-gateway-requirements
workstream: WS-04
pr: #337 (merged as 3e3addcfab382b28651c153fcbc95d796b5f3a26)

> Landing record for one shipped plan. Lives at `landings/PLAN-14.md`. Written by the
> `analyze` verb after verifying claims against ground truth (actual code, artifacts,
> PR state) — a pasted claim is a lead, never a fact. See
> `persona-plan-orchestrator/standards/orchestration-model.md` for the analysis and
> reconciliation contract.

Source: inbox `plan-14-session-and-scopes-006.md` (`kind: landing`, `landing-check complete: true`, no
missing keys). Corroborated 2026-09-22: `ci pr view 337` reports `state: merged`, `merge_commit_sha`
`3e3addcfab382b28651c153fcbc95d796b5f3a26`, which is `origin/main`'s head; 82 files, +5422/−863. The
plan is archived at `.plan/local/archived-plans/2026-09-21-plan-14-session-and-scopes`.

## Deliverable Fidelity vs Spec

The landing reports 8/8 plan deliverables done; the spec's 10 numbered items (1–7, 9–11) were grouped into
those 8 at outline. Verdicts below are against the spec numbering, evidence from the merged diff and the
PR body.

| Deliverable (spec) | Verdict | Evidence |
|--------------------|---------|----------|
| 1 AS-5 `auth.token_relay` | shipped-as-specified | `AuthConfig`, schema, `SessionAuthenticationStage`; plus an unplanned boot rule rejecting `headers_allow: [Authorization]` on a relay-off route (in `ConfigValidator`) |
| 2 AS-6 return URL + `oidc.login.default_return_url` | shipped-as-specified | `LoginFlow`, `OidcConfig`, `PendingAuthorizationRecord`; `DEFAULT_RETURN_URL` also removed from `StepUpCoordinator` |
| 3 AS-14 engine seam + ADR | shipped-as-specified | new `ScopedEngineFlows` (one client configuration per scope set on the unchanged engine), ADR-0048 |
| 4 AS-14 `endpoint.scopes`, `required_scopes` removed, boot rule | shipped-as-specified | `EndpointConfig`, both schemas, `RouteTableBuilder` (`neededScopes` per route), `ResolvedRoute`; plus RFC 6749 scope-token schema pattern |
| 5 AS-14 `needed(request)` incl. `/auth/login?returnUrl=` | shipped-as-specified | new `ReturnTargetScopes`, `LoginInitiationEndpoint` |
| 6 AS-14 bearer-only enforcement, `403 insufficient_scope` | shipped-as-specified | `AuthenticationStage`; session routes no longer pre-check; policy recorded as ADR-0049 |
| 7 AS-14 active-scope refresh (set `A`) | shipped-modified | session record and sealed cookie carry `A`; the cookie payload grew 9→10 fields and its format version was reset to `1` — pre-upgrade cookies are rejected (`payload-format`, ApiSheriff-113), so users log in again once. A breaking change the spec did not name; acceptable under the pre-1.0 rule |
| 9 Tests AS-5/AS-6 | shipped-as-specified | session ITs carry token-relay and return-URL cases |
| 10 Tests AS-14 | shipped-as-specified | new `BffEndpointScopesIT`, `BearerScopeIT`, scope-preserving refresh in `BffTokenRefreshIT`; IT configs migrated off `required_scopes`; k6 `bearer_proxied.js` now requests `openid profile email` |
| 11 Documentation | shipped-modified | `bff-session.adoc`, `endpoint-routes.adoc`, `configuration.adoc`, `security-threat-model.adoc` (new BFF-15) as specified, plus `bff-cookie.adoc` (user + development), three variant docs, `architecture.adoc`; `doc/LogMessages.adoc` NOT touched — OBSERVED: the merged main-source diff adds no LogRecord (`git diff 3e3addc^1 3e3addc` over `api-sheriff/src/main/**/*.java`, no `LogRecordModel`/`identifier(` additions), so nothing was owed there |
| — added-unplanned | security fix | `PendingAuthorizationRecord.sameOrigin` rejects any control character: a decoded `?returnUrl=/%09/evil.com` previously passed as `/<TAB>/evil.com`, which browsers normalise to `//evil.com` (open redirect). Found during the finalize security audit |

## Metrics and Anomalies

- Tokens: 8,545,882 (spans populations). Duration: 39,493 s wall (~10 h 58 m), 3 h 42 m worked; execute
  4 h 20 m wall and finalize 5 h 26 m wall, both mostly idle.
- Far faster than PLAN-13 (~73 h) and PLAN-15 (~48 h) at a similar token spend.
- `adr-propose` and `lessons-capture` both RAN this time (ADR-0049 proposed; five candidate lessons filed) —
  the standing Open Defect about those lanes being off is resolved by this landing.
- Sonar new-code issues: 0 (`step.sonar-roundtrip.new_code_issue_count=0`).
- `archive-plan:pending` in the reported steps is the step still in flight when the message was written; the
  archived plan directory exists, so it completed.

## Routing and Merge Behavior

- Review: CodeRabbit reviewed every code commit but NOT the final doc-only commit d341796 (ADR-0049 +
  architecture link) because of its 1-review/hour limit — required-bot participation was still satisfied.
  Sourcery (optional) refused on diff size (6,285 changed lines). Third plan in a row whose merge head was
  not fully bot-reviewed; here the unreviewed delta is `.adoc` only.
- Head-dependent finalize verdicts (simplify, security audit, self-review, quality gate) were carried across
  the rebase onto cc10ce2 (range-diff 13/15 identical, 2 differing only in import context) and recorded at
  5f6f7a9 — a smaller currency gap than PLAN-15's, and argued rather than silent.
- CI/merge: merge queue; `cleanup_owed=false`. `ci checks status --pr-number 337`: 31 checks, overall
  `success`.
- **Post-merge verification (the orchestrator's job):**
  - PR-attached **Run Integration Benchmarks** (run 35661424860) — the plan's wait timed out on it; now
    **success** (1202 s). Corroborated.
  - main-branch **Maven Build** for 3e3addc (incl. `deploy-snapshot`) — **unverifiable** through the CI
    abstraction (it reads PR-attached runs only; the push run is not attached to #337). Recorded as a Watch.
- **Surface expansion:** declared 24 entries, realized 82 files — 33 undeclared, 2 declared-but-untouched
  (`config/validation/rule/`, `doc/LogMessages.adoc`); `state: expansion_detected`, base `origin/main`
  3e3addc, not stale. Third landing in a row at roughly 3x. The undeclared files cluster in `bff/cookie/`,
  `edge/RouteRuntimeAssembler`, `routing/RouteRuntime`, `ConfigValidator` and the config tests — surfaces
  PLAN-19/PLAN-20 also touch.
- `corpus declaration-currency` against the 8 other specs: every staged spec overlaps this footprint
  (PLAN-16 23 files, PLAN-17 8, PLAN-18 19, PLAN-19 38, PLAN-20 41, PLAN-21 22). None of their spec texts
  cite a symbol PLAN-14 removed (`required_scopes`, `DEFAULT_RETURN_URL` — grep, no hits), but their claims
  were grounded at 3abc370 and are not re-checked against 3e3addc.

## Reconciliation Actions

- [x] row `status` → `shipped` — `orchestrator queue --transition PLAN-14 --status shipped`
- [x] row `pr` stamped `#337` — `queue --set-row`
- [x] row `landing` stamped `landings/PLAN-14.md` — `queue --set-row`
- [x] row `plan_marshall_plan_id` stamped `plan-14-session-and-scopes` — `queue --set-row`
- [x] epic.md reconciled: AS-5, AS-6, AS-14 shipped; ADR/lessons-lane defect resolved; PLAN-19/PLAN-20
      dependency on PLAN-14 satisfied
- [x] Watch opened for the unverified main-branch Maven Build of 3e3addc
- [x] Watch on surface under-declaration updated (third measurement)
- [ ] five candidate lessons — Promote attempted, refused `wrong_store` (they are plan-marshall
      components); disposition pending operator decision
- [x] resume_anchor updated
- [x] START-HERE and Ordered Queue blocks regenerated — `orchestrator resume-summary`

## Follow-Ups

- PLAN-20 (session-route scope step-up on upstream `insufficient_scope`) — already staged; its hard
  dependency (active scope set `A`) is now met. ADR-0049's deferred alternative names it.
- Cookie-size measurement in the bff-cookie docs was taken on the nine-field layout; re-measure on the
  ten-field payload — small, recorded as a Watch, candidate to fold into PLAN-20 (which touches
  `SessionRecord` again).
