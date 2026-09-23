# Landing Analysis: PLAN-26 — Refresh failures disposed by engine kind, IdP strict rotation shipped and proven

epic: deployment-configurability
workstream: WS-04
pr: [#314](https://github.com/cuioss/API-Sheriff/pull/314) — merged through the merge queue as squash `a475cff`, 2026-09-17 03:16Z

> ✅ **First landing to reconcile from the plan's own inbox message.** PR #309 (`3c68ee4`) turned
> `default:emit-landing` back on hours before this plan started, and the channel worked: seven messages
> (`refresh-failure-dispositions-001..007`), `inbox landing-check` → `complete: true`, no missing required
> fact keys. The operator's paste and the machine facts agree on every figure checked.

## Deliverable Fidelity vs Spec

12 spec deliverables, re-cut by the outline to 8 / 26 tasks — all done.

| Deliverable (spec) | Verdict | Evidence checked at `a475cff` |
|---|---|---|
| 1-4. Classify by `Kind`, dispose per kind | ✅ shipped | New boot records: `ApiSheriff-127` *"Token refresh failed before the identity provider processed it — session kept, next attempt in…"* (`doc/LogMessages.adoc:83`) beside the retained `ApiSheriff-111` for a destroyed session. `PRE_REDEMPTION` keeps the session with a ~5 s bounded retry; `CREDENTIAL_REJECTED` ends it; `REDEEMED` ends it and revokes best-effort via the new `EndedRefreshTokens` (capped at 64 in-flight revocations) |
| 5. Reuse detection is the IdP's job, enabled in the realms | ✅ shipped | `"revokeRefreshToken": true` in **both** `integration-realm.json` and `sample-realm.json` |
| 6. Existing refresh suites honest under strict rotation | ✅ shipped | `BffTokenRefreshIT` / `BffCookieRefreshIT` green; the native-lane failure they exposed was a real gateway bug, not a test relaxation (see Anomalies) |
| 7. Cookie-mode replay ends the session, end to end | ✅ shipped — **and the expected verdict flipped on first contact with a live IdP** | `BffRefreshReuseIT` exists; its test is named `replayedCookieEndsItsSessionAndRevokesTheSuccessorGrantButNotTheUserSession` — the spec predicted "only the replayed token is revoked" and Keycloak 26.5.7 also revokes the successor grant while leaving the user session alive |
| 8. Server-mode IdP revocation ends the session | ✅ shipped, with the Keycloak semantics recorded in ADR-0046 and the threat model |
| 9. IdP-initiated logout coverage | ✅ reported | The integration realm's `refresh-client` registers no `backchannel.logout.url`, so the server-mode back-channel push is structurally unreachable there — recorded rather than papered over |
| 10. Unit tests per `Kind`, waiters, reuse model rewritten | ✅ shipped | `EndedRefreshTokensTest`, `SessionAuthenticationStageTest`, `TokenClientDslJsonReflectionTest` added |
| 11. Reversion proofs | ✅ shipped | Named reversion jobs in the message trail (e.g. `abe890bd`); the concurrency fix carries a regression test that fails without it |
| 12. Docs + ADR + scenario-guide fold | ✅ shipped | `doc/adr/0046-Refresh_failures_are_disposed_by_the_engines_failure_kind…`; `doc/user/tls-scenarios.adoc` now carries **15** `oidc_verify_hostname` / `oidc_tls_profile` mentions — the fold added at PLAN-25's landing is discharged; new `ApiSheriff-125` / `-126` records for the two keys |

### Surface fidelity — the largest expansion this epic has measured

Declared 22, realized 40: **21 added, 3 missing**.

- The added set is dominated by one cause: the **native-lane reflection bug** (candidate lesson `-001`)
  pulled in `TokenClientDslJsonReflection` + its test, and the disposition work pulled in
  `EndedRefreshTokens`, `SessionAuthenticationStage`, `EventType`, `ConfigLogMessages`, `EgressTlsConfig`,
  `gateway.schema.json` and four more test files. None was foreseeable from the spec; all are in-subject.
- ⚠ Also added: `README.adoc`, `doc/technical_aspects.adoc`, `doc/user/bff-cookie.adoc`, `bff-session.adoc`,
  `tls-edge.adoc`, `MtlsHandshakeIT.java` — documentation and a test the spec did not declare.
- Missing: `BffLogoutIT.java` and `integration-test-topology.adoc` (both HYPOTHESIS, correctly unused), and
  ⛔ `doc/adr/0046-refresh-failure-dispositions.adoc` — **the third ADR filename declaration in a row that
  named a file nobody wrote.** The spec even carried the warning from PLAN-25's identical miss. The rule is
  now proven by three instances: declare the ADR **ordinal**, never a guessed descriptive filename.

## Metrics and Anomalies

- **5,859,314 tokens / 63,196 s (17h33m)** — machine facts from the landing message; the paste's "17h33m,
  ~5.9M" agrees. `any_phase_missing_end_time=false`.
- ⚠ **The per-phase split is approximate.** `re_entered_phases` is empty although 5-execute was re-entered
  through five loop-backs; execute closes were not stamped as re-closes. Totals unaffected.
- ⛔ **A security-relevant false keep, caught only by the native lane.** `TokenClientDslJsonReflection` did
  not register `_TokenErrorResponse_DslJsonConverter`, so in the native image an IdP `400 invalid_grant`
  surfaced as `TransportException` → classified `PRE_REDEMPTION` → **a revoked or replayed session was
  kept alive**. The JVM lane was green throughout. Fixed in-run (TASK-12, `0ffdbde`) and pinned by
  `TokenClientDslJsonReflectionTest`. ⚠ **This is the sharpest finding of the round**: the very defect class
  this plan existed to remove was re-created by a native-image gap the unit lane cannot see.
- Six finalize loop-backs against a cap of five; the sixth carried a two-line test-only fix and is logged as
  a deviation. Five rounds were CodeRabbit-driven.
- Self-review / simplify / security-audit re-stamped rather than re-run on doc-only, test-only and
  rename-only deltas; six simplify findings left unapplied to keep the reviewed HEAD stable. Logged.
- The quality gate again rewrote two files the branch never touched (candidate lesson `-005`), and
  module-tests ran DEGRADED on every round.

## Routing and Merge Behavior

- Review: CodeRabbit found one Major (a back-off map race: with the map full, concurrent sessions could all
  dial the IdP before the shared retry window opened — fixed in `696c926` with an atomic claim and a
  concurrent regression test) and one Minor (concurrency tests never asserted their timeout flag — fixed in
  `a437b52`). PR-Agent: no issues. Sonar: `new_code_issue_count=0`, `count_status=confirmed`.
- CI/merge: `ci checks status --pr-number 314` → `overall_status: success`, 32 checks. Merge queue, squash,
  `cleanup_owed=false`; worktree and branch removed.
- ⚠ The final `automatic-review` record says *"unified triage pending"* while the pre-merge barrier reported
  zero pending findings and complete required-bot participation. **The record's summary text is stale; the
  barrier is the reliable signal** — worth knowing before reading a future record the same way.
- One `auth_failed` on a `gh` PR read inside archive-plan; the immediate retry succeeded and the merge state
  is correctly recorded.

## Reconciliation Actions

- [x] row `status` → `shipped`; `pr` 314; `landing` `landings/PLAN-26.md`; `plan_marshall_plan_id` stamped
- [x] Open Defect "reuse detection claimed COVERED with no mechanism" **retired** — the claim is now true and
      carries a test
- [x] Inbox drained: 7 messages, 7 archived, 0 invalid (dispositions in `epic.md` § Decisions)
- [x] Watch retired: the PLAN-25 scenario-guide fold is discharged (15 mentions in `tls-scenarios.adoc`)
- [x] New Open Defects: the ADR-filename rule (third instance), the stale `automatic-review` summary text,
      and the unreachable server-mode back-channel logout in the integration realm
- [x] START-HERE and Ordered Queue regenerated

## Follow-Ups

- **PLAN-23 is now unblocked** — every plan it waited on (PLAN-18, PLAN-25, PLAN-26) has shipped.
- The gate's standing rewrite of `SniFrontListener.java` / `LoopbackEphemeralBindArchTest.java` needs a
  `main`-side commit; folded into PLAN-23 rather than staged as a separate plan.
- The native-lane reflection class is now guarded for converters, but the general rule — *a green JVM lane
  says nothing about refresh-failure classification* — is promoted to the lessons corpus.
