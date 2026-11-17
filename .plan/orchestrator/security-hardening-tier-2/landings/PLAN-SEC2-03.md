# Landing Analysis: PLAN-SEC2-03 — BFF logout and source keys

epic: security-hardening-tier-2
workstream: WS-03
pr: #626 (https://github.com/cuioss/API-Sheriff/pull/626), merged through the merge queue as `fa8dd54f`

> Landing record for one shipped plan. Written by the `analyze` verb after verifying the
> landing message against ground truth (git, the PR through the CI abstraction, the merged code).
> Disclosure is mechanism-level: possible weaknesses the read raised that are not yet vetted are
> reported to the operator in the session and are NOT written here (this file is tracked).

## Deliverable Fidelity vs Spec

Landing facts: `deliverables_total=5`, `deliverables_done=5`. The spec lists 4. The fifth is not named
by the landing narrative; the diff bears out its content: follow-up edits to three existing integration
tests that call logout without a same-origin proof (`BffCookieSessionIT`, `BffSessionIdleTimeoutIT`,
`BffSessionWebSocketIT`) and the inheritance of the `/48` aggregation by the threat ledger (ADR-0078,
`ThreatAlertDocument`, `SourceKey`), because the threat ledger shares `SourceLedger`. Neither is a spec
deliverable, so both are recorded as added-unplanned.

| Deliverable (spec) | Verdict | Evidence |
|--------------------|---------|----------|
| 1. Same-origin gate on logout | shipped-as-specified | `ReservedPathHandler` fills `Sec-Fetch-Site` / `Origin`; `CsrfDefence.provesSameOriginNavigation` (:202) reuses the one origin-trust rule (`isTrusted` :227); `LogoutEndpoint:189` judges before any session resolve and answers a `no-store`, `frame-ancestors 'none'` confirmation page (:323); tests `LogoutEndpointTest`, `CsrfDefenceTest`, `BffLogoutIT` go red on revert |
| 2. The shared `unknown` source key | shipped-modified | New shared `ForwardedClientResolution` (undeclared file) feeds both `SourceKeys` and `PendingQuotaKeys`; `unknown` is never acted on by `SourceGate:234`; boot WARN `ApiSheriff-149` (`EdgeRules:147-167`) fires only for an enabled non-observe action with non-empty `trusted_proxies`. The pending-quota treatment is the first option the spec named (key on the `X-Forwarded-For` result alone); what still resolves to `unknown` stays one shared 256 bucket, stated as such in `doc/user/bff-session.adoc:722` |
| 3. Detection ledger under pressure | shipped-modified | High-water mark and `/48` aggregation (`SourceLedger:195`, `slotFor` :329) and a fail-closed refusal of a new key under `block` / `delay` (`SourceGate:238`). **No reserved slice exists** — `source-detection-substrate.adoc:87` says none is reserved because the sweep never removes weighted keys; the spec's reserved-slice clause was incoherent (a reserve could serve only new keys) and the PR body's claim of one is not borne out |
| 4. Documentation | shipped-modified | BFF, detection, threat model BFF-09, `LogMessages.adoc` (`ApiSheriff-149`; 141-143 reworded), ADR-0076 and, beyond the spec, ADR-0078 and ten further doc / demo-client files |
| (added) integration-test and threat-ledger follow-ups | added-unplanned | see above |

Surface delta (declared 20, realized 55): 32 realized paths were never declared (the new
`ForwardedClientResolution` and its test, `SourceKey`, `ThreatAlertDocument`, `BffRuntimeProducer`,
`ApiSheriffLogMessages`, ADR-0078, nine further docs, and eleven unit/IT test files); one declared path
(`api-sheriff/src/test/.../detection/SourceLedgerTest.java`) was untouched — the pressure cases went
into two new files. `expansion_detected`. The declaration of the landed spec is not corrected (the plan is
shipped); the under-declaration is recorded as the evidence the PLAN-SEC2-02 declaration is compared
against. PLAN-SEC2-02's code surface (`edge/upstream/**`) is untouched by this landing; its shared
documents (`doc/security-threat-model.adoc`, `doc/LogMessages.adoc`) moved and must be re-read by that plan.

## Three-Layer Analysis

The landing was read on three layers — the spec, the deliverable trace, and the merged code — by one
read-only leaf, and the orchestrator re-read every point recorded as `checked` at its `file:line` before
writing it here (list below). The reading is **by reading, no build, no test run, no write**: what only a
run could show is tagged `not shown by reading`.

- **Dispatch:** one read-only leaf per landing — reason: the merged diff is 55 files / +2527 −294 and reading it is the depth the dispatch rule exists for.
- **Verdict:** layer 1 findings — 2 minor, 2 major (spec form); layer 2 findings — 2 major, 3 minor; layer 3 findings — 2 minor recorded here, further items reported to the operator only (see below).

### Layer 1 — Specified

The six labelled spec premises settle in the spec's favour and are persisted as `corroborated` on the
spec (earlier at `31595bca`, `by security-hardening-tier-2/analyze`). The HYPOTHESIS about cui-http 3.2
`reconcileSources` is persisted `unverifiable` at `fa8dd54f`: `ForwardedClientResolutionTest:53` asserts
the behaviour and the PR merged green, but the library source was not read and no run was made here.

| Finding | Claim | Verdict | Polarity | Evidence (file:line) |
|---------|-------|---------|----------|----------------------|
| L1-1 | Reserved paths run before the CSRF gate | checked | confirms | `GatewayEdgeRoute.java:723`, `:765-766` |
| L1-2 | Logout passed only the cookie header | checked | confirms | `BffRuntime.java:350` at `31595bca` |
| L1-3 | An empty forwarded resolution became `unknown` for both consumers | checked | confirms | `PendingQuotaKeys.java:111` (merged), base diff |
| L1-4 | Pending store bounds 256 per source with own-oldest eviction | checked | confirms | `InMemoryPendingAuthorizationStore.java:85,132` |
| L1-5 | Ledger bound 65,536, new key at the bound `UNTRACKED` | checked | confirms | `SourceLedger.java` at `31595bca` |
| L1-6 | Deliverable 3's reserved-slice clause is coherent | checked | refutes | `source-detection-substrate.adoc:87` — a weighted key is already tracked and never evicted, so a reserve could serve only new keys, and the spec says neither whether a new key is tracked or refused |
| L1-7 | Deliverable 3 weighs the cost of refusing a new key under `block` | checked | refutes | `SourceGate.java:238` — the spec allows refusal and does not weigh its reach |
| L1-8 | Deliverable 2's Done-when covers the pending-quota treatment | checked | refutes | the Done-when names detection tests and the WARN only (minor, spec form) |
| L1-9 | The spec scopes the threat ledger | checked | refutes | the spec names only the recon ledger and ADR-0076; `SourceLedger` is shared with the threat ledger (minor) |

**Layer 1 verdict:** findings — 4 (L1-6 and L1-7 major spec form, L1-8 and L1-9 minor)

### Layer 2 — Implemented

| Deliverable | Code trace | Test that can fail | Unexplained file / unborne claim | Verdict |
|-------------|------------|--------------------|---------------------------------|---------|
| 1 | `LogoutEndpoint.java:189-195`, `CsrfDefence.java:202-208` | `LogoutEndpointTest` (cross-site, same-origin control, confirming POST, hardened page), `CsrfDefenceTest` | none | checked |
| 2 | `ForwardedClientResolution.java`, `SourceKeys`, `PendingQuotaKeys.java:111-117`, `EdgeRules.java:147-167` | `ForwardedClientResolutionTest`, `SourceKeysTest`, `PendingQuotaKeysTest`, `EdgeRulesTest` WARN matrix | the pending-quota bucket for what still resolves to `unknown` is unchanged | checked |
| 3 | `SourceLedger.java:329-369`, `SourceGate.java:228-250` | `SourceLedgerPressureTest` aggregation cases, `SourceGateFullLedgerTest` | `accruingSourceStaysTrackedInAFullLedger` (`SourceLedgerPressureTest:194`) passes against the base ledger — weighted keys were already never evicted — so it cannot go red on revert, yet `source-detection-substrate.adoc:88` cites it as proof; the PR body claims a reserved slice that does not exist | deviates |
| 4 | docs | n/a (`order: none`) | ADR-0078 amended though the spec named ADR-0076 only (justified by the threat ledger inheriting the aggregation) | checked |

Evidence of verification: the PR's test plan cites a full unit run at `90b9edf5` and ITs at `d8f3c26c`,
both before two follow-up fix commits; no run of the merged `fa8dd54f` (including `BffLogoutIT`) is
evidenced in the repository, and the browser behaviour that the confirming POST follows the end-session
redirect is **not shown by reading**.

**Layer 2 verdict:** findings — 5 (2 major: the missing reserved slice against the claim and a revert-green test cited as proof; 3 minor: unchanged `unknown` bucket for the unresolvable remainder, ADR-0078 scope growth, unevidenced merged-head run)

### Layer 3 — Correct

| Defect | File:line | Triggering input | Effect | Severity |
|--------|-----------|------------------|--------|----------|
| L3-1 | `SourceKeys.java:44` | n/a (Javadoc) | The class Javadoc still says the key is the address the gateway regenerates `X-Forwarded-For` from; the fallback makes that false for a contested chain | minor |
| L3-2 | `BffRuntimeProducer.java:1478` | n/a (suppression) | `@SuppressWarnings("java:S107")` carries a comment that restates the seven parameters and does not argue why a parameter object is not sensible, against the Sonar rule that a suppression carries a rationale showing a fix is not sensible | minor |

Further layer-3 items concerning the logout confirmation path, the full-ledger refusal, the treatment of
the unresolved source, the threat-ledger keying and the event-loop sweep were returned by the leaf and
re-read at their lines. They are unvetted weakness-class observations, so they are reported to the
operator in the session and are not written into this tracked record. Whether any becomes a staged
fix-plan spec is the operator's decision.

**Layer 3 not shown by reading:** whether a client behind a trusted proxy can force both forwarded passes
to resolve empty (it rests on cui-http chain-walking that was not read); how many times boot or reload
invokes the validation rule that logs `ApiSheriff-149` (the doc says once at startup); the wording of
the BFF-09 paragraph for a navigation the browser marks `none`.

**Layer 3 verdict:** findings — 2 recorded here (minor), plus items reported to the operator only

### Conformance to the Project's Own Rules

| Rule source | Rule | Verdict | Evidence |
|-------------|------|---------|----------|
| CLAUDE.md § Pre-1.0 | no deprecation, no compatibility shim | conforms | old `logout(String,Instant)` signature removed, `jsonBody` renamed with no alias |
| CLAUDE.md § Logging | `CuiLogger`, `%s`, exception first; WARN range 100-199 documented | conforms | `ApiSheriff-149` in `ConfigLogMessages.java` and `doc/LogMessages.adoc` |
| CLAUDE.md § Dependency Management | no new dependency | conforms | no `pom.xml` in the diff |
| CLAUDE.md § Testing | JUnit 5, AAA, no Mockito/PowerMock/Hamcrest | conforms | files read import none |
| spec Standing Constraints | no new `gateway.yaml` key; `edge/upstream/**`, `edge/terminal/**`, `RetryingJwksLoader*`, ADR-0063 untouched | conforms | `HIGH_WATER_PERCENT` is a constant (`SourceLedger:112`); none of those paths in the diff |
| CLAUDE.md § Sonar | fix by default; a suppression carries a rationale | deviates | L3-2 |
| CLAUDE.md § Javadoc | public API documented | conforms | `ForwardedClientResolution`, `provesSameOriginNavigation`, `hasRoomFor` |
| CLAUDE.md § Pre-Commit Process | quality gate and full verify before a build-input commit | not shown by reading | the PR's runs predate the merged head |
| CLAUDE.md § Testing | minimum 80% coverage; no OpenRewrite markers | not shown by reading | build outputs; none read |
| CodeRabbit file cap | PR under 100 files | conforms | 55 files |

Points re-read by the orchestrator at `fa8dd54f`: `SourceLedger.java:322-372`,
`source-detection-substrate.adoc:80-92`, `SourceGate.java:228-250`, `PendingQuotaKeys.java:100-118`,
`bff-session.adoc:718-726`, `CsrfDefence.java:196-232`, `SourceLedgerPressureTest.java:185-215`,
`BffRuntimeProducer.java:1474-1482`, `EdgeRules.java:145-170`, `LogoutEndpoint.java:186-196,318-330`,
and the base `SourceLedger.java` `UNTRACKED` sites at `31595bca`. Not re-read: the leaf's reading of
`ForwardedClientResolution`'s chain-walking and of `ThreatClassifier`, and the test-revert judgement for
the cases other than `SourceLedgerPressureTest:194` — recorded as the leaf's reading, unverified lead.

## Metrics and Anomalies

- Tokens: unknown (the lane's `record-metrics` recorded no typed total; the measured totals stay in the plan's metrics store)
- Duration: unknown (same producer gap)
- Model: `claude-opus-5-5`; effort `low`; `refresh-runtime-stamp` returned `refreshed: false` (`model_unknown`), so harness, model and effort are the start-time self-reported stamp
- Anomalies: the facts block carries `unknown` at `total_tokens`, `unresolved_review_threads`, `review_body_findings_total`, `review_body_findings_handled` and `sonar_open_new_issues` (the lane names the missing producer for each); the functional review closed two deliverables as met with residuals (`deliverables_met_with_residuals=2`); the code review recorded two advisory findings and no blocking one; `archive-plan` was pending at emission.

## Routing and Merge Behavior

- Review: CodeRabbit. `runbook_lane review-gate --pr-number 626` on the merged head: `unresolved_review_threads: 0`; `review_body_findings_total: 2`, `review_body_findings_handled: 0`, `merge_allowed: false, reason: body_findings_undisposed` (two inline CodeRabbit findings, `inline-1` and `inline-2`). The landing carried `unknown` at all three keys, so this is no false claim; the provider's record shows two findings with no disposition, and the dispositions the lane kept in its own record were not read, so their handling is **not re-verified**.
- CI/merge: merged through the merge queue (`merge_mechanism=merge_queue`); `ci pr view` shows `state: merged`, `merge_commit_sha: fa8dd54f…`. The queue's merge-group run was **not independently re-read here**.
- Sonar: the landing's `sonar_open_new_issues` is `unknown`; its own step fact `step.sonar-roundtrip.new_code_issue_count=0` (`count_status=confirmed`) is the lane's reading. `read_open_issues` on PR 626 returns `count_status: undecidable` (`head_revision_unavailable`, analysis revision `69e43639`), so the figure is **not re-verified**.

## Reconciliation Actions

- [x] row `status` → `shipped` — `orchestrator queue --transition PLAN-SEC2-03 --status shipped`
- [x] row `pr` stamped — `626`
- [x] row `landing` stamped — `landings/PLAN-SEC2-03.md`
- [x] row `plan_marshall_plan_id` stamped — `sec2-03-bff-logout-and-source-keys`
- [x] epic.md narrative reconciled (queue annotations, Open Defect for the incomplete landing, Watches)
- [x] layer-1 verdict persisted on the PLAN-SEC2-03 spec (claim 4, `unverifiable`); the six other claims keep their `corroborated` verdicts
- [x] resume anchor updated; `queue-view.md` regenerated and committed

## Follow-Ups

- The landing is incomplete (missing `total_tokens`, `unresolved_review_threads`, `review_body_findings_total`, `review_body_findings_handled`, `sonar_open_new_issues`): Open Defect in `epic.md`.
- Two CodeRabbit inline findings without a provider-side disposition, and the Sonar figure for the merged PR: Watches in `epic.md`.
- L1-6 / L2: the reserved-slice claim in the PR body and the revert-green test `SourceLedgerPressureTest:194` cited as proof in `source-detection-substrate.adoc:88`: candidate fold into a later plan on the operator's word.
- L3-1 (`SourceKeys` Javadoc) and L3-2 (S107 rationale): same.
- PLAN-SEC2-02 amends `doc/security-threat-model.adoc` and `doc/LogMessages.adoc`, which this landing has just rewritten; it re-reads them at `fa8dd54f` before it edits.
