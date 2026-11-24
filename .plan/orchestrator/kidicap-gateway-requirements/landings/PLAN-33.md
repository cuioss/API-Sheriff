# Landing Analysis: PLAN-33 — Adapt to token-sheriff 0.9.9 (truthful JWKS status, shared log sanitiser, doc drift)

epic: kidicap-gateway-requirements
workstream: WS-05
pr: #656 (merge commit 6ea8bf3109b190f6bb5db8f1caa28f053262b92b, merged through the merge queue)

> Landing record for one shipped plan. Written by the `analyze` verb (inbox scan, message
> `plan-33-adapt-to-token-sheriff-0-9-9-001.md`, 2026-11-24). The landing narrative is a lead, never a fact:
> every `checked` point below was either re-read by the orchestrator at its file:line (marked **re-read**) or
> is recorded as an unverified lead (marked **lead**).

## Deliverable Fidelity vs Spec

| Deliverable (spec) | Verdict | Evidence |
|--------------------|---------|----------|
| D1 Truthful JWKS status through `RetryingJwksLoader`, test first | shipped-modified | `getLoaderStatus()` is now `snapshot.get().delegate().getLoaderStatus()` (`RetryingJwksLoader.java:812-813`, **re-read**). `jwks.max_staleness_seconds` was exposed (60..604800, off when omitted): `TokenValidationRules` `JwksRefreshKey.MAX_STALENESS`, `IssuerConfig.Jwks.maxStalenessSeconds`, `TokenValidatorProducer` :448-451, the schema (**lead**, from the leaf). Readiness reads a stale-degraded issuer as not loaded and the threat model says so (**lead**). The sticky `recordOutcome` state was kept (it feeds `keySetState`); the "Why a wrapper" Javadoc was rewritten (**lead**). Red-on-main is NOT shown: the red runs live in the plan's work log, which this analysis did not read, and the PR body says the red tests landed in the same commit as their fixes, against the spec's "failing test first". |
| D2 Library `ErrorTextSanitizer` for `SignatureOnlyTokenVerifier` | shipped-as-specified | `sanitizeForLog` is gone (0 matches) and `ErrorTextSanitizer.sanitize` is used at `SignatureOnlyTokenVerifier.java:147, 157, 166` (**re-read**). Test `SignatureOnlyTokenVerifierTest.RefusalEcho` with CR, LF, U+2028, U+2029, U+202E and a 300-character tail plus a benign control (**lead**). |
| D3 Correct the `invalid_scope` documentation | shipped-as-specified | `TokenRefreshCoordinator` Javadoc, ADR-0057 (the "Telling the two apart" bullet), `doc/user/bff-session.adoc`; a repository grep for the stale wording finds none (**lead**, from the leaf). Disposition unchanged (**lead**). |
| D4 Drop `token-sheriff-client-quarkus` (operator approval) | dropped | `api-sheriff/pom.xml:121-123` still declares it (**re-read**); the `pom.xml` files are not in the diff. The PR body says it is kept for a later plan, consistent with the approval gate and the PLAN-34 entanglement. `deliverables_total=4` therefore counts D4 as dropped. |
| D5 Verify-first: refresh versus a concurrent widening | shipped-modified | The test was red, so a guard was added: `InMemorySessionStore.replaceIfPresent` became `replaceIfCurrent(basis, replacement)` returning `REPLACED`, `ABSENT` or `SUPERSEDED` under the store monitor (`InMemorySessionStore.java:235-244`, **re-read**). The scope grew beyond "smallest change" (see layer 2 and 3): `destroyIfCurrent` on the refusal path and persist-gone becoming `NO_SESSION`, both operator-ordered during review per the PR body (**lead**). |

## Three-Layer Analysis

- **Dispatch:** one read-only leaf per landing (`execution-context-level-3`, the `orchestrator.analyze` surface), because reading 51 changed files of merged code is the depth the dispatch rule exists for. The leaf wrote nothing. It disclosed that one `grep -r` sweep walked into `.plan/` and printed one line of the PLAN-33 spec copy; it opened no `.plan/` file on purpose.
- **Verdict:** layer 1 findings (one minor); layer 2 findings (two minor, one conformance deviation); layer 3 findings (one major conditional, six minor). Nothing is a blocker. The 8 points the orchestrator re-read are named inline; every other point is a lead.

### Layer 1 — Specified

| Finding | Claim | Verdict | Polarity | Evidence (file:line) |
|---------|-------|---------|----------|----------------------|
| L1-1 | The spec's Done-when asks for one test traced YAML key -> `toHttpJwksLoaderConfig` -> rejected token. The merge splits it into two links; the join (the library degrading past the bound) is exercised only at `RetryingJwksLoaderTest` with a 1 ms bound, because the minimum admitted bound is 60 s. Deleting the key still turns tests red. | checked (lead) | confirms | `TokenValidatorProducerTest.java:1226` |
| L1-2 | D4 was dropped with a recorded reason. | checked | confirms | `api-sheriff/pom.xml:121` (**re-read**) |

**Layer 1 verdict:** findings — 1 minor (L1-1).

### Layer 2 — Implemented

| Deliverable | Code trace | Test that can fail | Unexplained file / unborne claim | Verdict |
|-------------|------------|--------------------|---------------------------------|---------|
| D1 | `RetryingJwksLoader.java:812` | `degradedHandWrittenDelegateIsReportedTruthfully`, `stalenessDegradedLibraryLoaderIsReportedTruthfully`, `closedReportsTheDelegateStatus` (lead) | the red-on-main claim is not shown by reading | checked, red-first not shown |
| D2 | `SignatureOnlyTokenVerifier.java:147` | `RefusalEcho` | none | checked |
| D3 | docs | n/a (documentation) | none | checked (lead) |
| D5 | `InMemorySessionStore.java:235` | interleaving test at `TokenRefreshCoordinatorTest.java:1765`, both legs, coalesced waiter, single-flight release (lead) | none | checked |
| none | `SignatureOnlyTokenVerifier.java:149-150` (**re-read**): the logout verifier now refuses any issuer whose loader status is not `OK`, before key lookup | `DegradedIssuer` tests with a matched OK control (lead) | **explained by no deliverable**; found in review, fail-closed and tightening; the PR body admits the WARN is logged as "signature-rejected", which misdirects operators during a JWKS outage | checked |
| none | `CookieSessionBinding.destroyIfCurrent` | no direct test (**re-read**: 0 matches in `CookieSessionBindingTest`; reached only through `TokenRefreshCoordinatorTest`'s cookie-mode case, which would change outcome if it returned GONE or SUPERSEDED) | the `destroy(basis)` side effect is not asserted | checked |

Also: 6 ledger files under `.plan/orchestrator/` sit in the range `4213eb1f..6ea8bf31` but come from #659 and #660, not from #656.

**Layer 2 verdict:** findings — 2 minor (L2-1 unexplained logout-verifier refusal and its misleading WARN reason, L2-2 `destroyIfCurrent` untested directly).

### Layer 3 — Correct

| Defect | File:line | Triggering input | Effect | Severity |
|--------|-----------|------------------|--------|----------|
| L3-1 | `TokenValidatorProducerTest.java:1272` (**re-read**) | `degradedLoaderRejectsAValidTokenFailClosed` sets `degraded = true` before `validatorOver` builds the validator | proves admission refusal at build time only; whether the library issuer cache re-reads loader status on every validation, so that an issuer admitted and degraded afterwards is rejected, is tested nowhere in this repository. With `max_staleness_seconds` set, the bearer fail-closed claim of ADR 0011/0027/0063 rests on that library behaviour | major, conditional (no code error; the headline claim is unproven) |
| L3-2 | `RetryingJwksLoader.java:756` (**re-read**), `:822-824` (lead) | Javadoc says an unknown key id "can still claim a refresh that re-admits the issuer" | contradicts ADR-0063's new paragraph (no token reaches the wrapper's lookup for a degraded issuer); a test proves a path production cannot reach | minor |
| L3-3 | `RetryingJwksLoader.java:501-503` (lead, not re-read) | a first load failed, the library's own refresh recovered it (status `OK`) while `keySetState` stays `FAILED` until the wrapper's retry | `runRetry` publishes a fresh `LOADING` delegate and closes the recovered `OK` one, so status drops `OK`->`LOADING` for one fetch; fail-closed, bounded by the retry back-off, no test asserts the drop | minor |
| L3-4 | `TokenRefreshCoordinator.java:666` (lead, not re-read) | refresh loses to a widening or to a destroy | the loser's rotated refresh token is abandoned, not revoked; it never reaches the browser, but stays valid at the IdP until its lifetime ends; the gone case previously revoked it; the justification is "reasoning, not asserted by a test"; operator-accepted and documented | minor |
| L3-5 | `TokenRefreshCoordinator.java:630-632` (lead, not re-read) | a refresh loses to a superseding widening | that request gets `NO_SESSION`, so the `refresh_failure` policy (reauthenticate or 401) fires although the session lives on; persist-gone logs no WARN and no `ApiSheriff-111`; documented | minor |
| L3-6 | `SessionBinding.java:360, 367, 375` (**re-read**) | `PersistResult.written/gone/superseded` | public static factories without `@since`, against the CLAUDE.md Javadoc rule | minor (conformance) |
| L3-7 | `doc/user/bff-session.adoc:1003` and `:870`, `doc/variants/02-bff-session.adoc:208` (lead) | the PR's own advisory list | still say a logout revokes "the session it found"; also ADR 0018, ADR 0046 per-kind table, the refresh-failure table, `doc/development/bff-cookie.adoc` left open on purpose | minor |

**Layer 3 not shown by reading:**
- Whether the 0.9.10 `IssuerConfigCache` re-reads loader status on every validation (admitted-then-degraded), and whether `HttpJwksLoader` degrades exactly as the 1 ms test bound assumes: only class jars are in `~/.m2`, so this needs a run or a decompile.
- Whether the new tests are red on `main`.
- The Keycloak revocation behaviour the abandon-not-revoke decision rests on.
- Whether `ErrorTextSanitizer` escapes exactly as the D2 test's expected string asserts.
- The reverse overlap order (a refresh persists first, then the widening's presence-only `replaceAndReissueHandle` overwrites it; documented as a residual; the case where the widening grant returns no refresh token was not traced).
- The advisories not individually confirmed: ADR 0018 presence-only text, the ADR 0046 table, the refresh-failure table row, the ADR 0027 section 6 bullet, the `bff-cookie.adoc` cause list.

**Layer 3 verdict:** findings — 1 major conditional, 6 minor; nothing blocks.

### Conformance to the Project's Own Rules

| Rule source | Rule | Verdict | Evidence |
|-------------|------|---------|----------|
| CLAUDE.md | Pre-1.0: no deprecation | conforms | `replaceIfPresent` replaced outright; `REASON_SESSION_TERMINATED` removed (lead) |
| CLAUDE.md | No new dependency | conforms | pom files not in the diff (**re-read**) |
| CLAUDE.md | Logging: CuiLogger, `%s`, exception first, new id documented | conforms | no new `LogRecord`; `ApiSheriff-111` row rewritten (lead) |
| CLAUDE.md | Every public/protected method has Javadoc with `@since` | deviates | `SessionBinding.java:360, 367, 375` (**re-read**) |
| CLAUDE.md | No `@SuppressWarnings` to get green | conforms | production files only; test files not checked (lead) |
| CLAUDE.md | Testing: JUnit 5, AAA, no Mockito, matched controls | conforms | hand-written stubs (lead) |
| CLAUDE.md | Fail-closed, no sensitive data in logs or readiness | conforms | readiness tightened and non-disclosing; the library's per-request re-read is unshown (lead) |
| CLAUDE.md | A key that parses must act | conforms | deleting `max_staleness_seconds` fails `ConfigLoaderTest` and the producer test (lead); the rejected-token end is the L1-1 split |
| CLAUDE.md | Gate evidence (zero warnings, Sonar 0) | not_shown_by_reading | PR-body claims only |

## Metrics and Anomalies

- Tokens: 81,692,289 (`total_tokens`, from the landing; not re-measured).
- Duration: 158,809 s (about 44 hours) of wall time, the landing's own figure; not reconciled with the session clock.
- Anomalies: the code review and the functional review each spent 3 of 5 loop-back rounds, and the wait-region unified triage spent 3 of 5. Three load-induced build failures were accepted during the run. The merge passed on `finalize-step-sync-baseline` unconsulted, and the pre-push freshness gate answered `fresh` with `scope_cross_check: undetermined`.

## Routing and Merge Behavior

- Review: CodeRabbit. `unresolved_review_threads=0` is **re-read** (`ci pr comments --pr-number 656 --unresolved-only` on 2026-11-24 reports 9 unresolved rows, every one with an empty `thread_id`: 6 `issue_comment` and 3 `review_body`, so no resolvable thread is open). `review_body_findings_total` and `review_body_findings_handled` are `unknown` in the landing (no step parsed the review bodies), so the landing is incomplete at those two keys; `effort` is `unknown` too. Not re-verified: the handled count (no progress file read).
- CI/merge: merged through the merge queue as `6ea8bf31`; PR state read through the CI abstraction (`pr_state: merged`). The merge-group run `45364733254` was found; its conclusion was not read.
- Sonar: the landing reports `sonar_open_new_issues=0`, read at the pushed head `f9868bde`. This analysis did not re-measure it. Unverified lead.
- Pin: the landing was built against the 0.9.9 pin; `main` pins 0.9.10 since #658 (`4213eb1f`), which sits before this merge in history, so the landing did not verify the 0.9.10 behaviour of the library paths it relies on (L3-1).

## Reconciliation Actions

- [x] row `status` -> `shipped` — `orchestrator queue --transition PLAN-33 --status shipped`
- [x] row `pr` stamped — `orchestrator queue --set-row PLAN-33 --field pr --value "#656"`
- [x] row `landing` stamped — `orchestrator queue --set-row PLAN-33 --field landing --value landings/PLAN-33.md`
- [x] row `plan_marshall_plan_id` stamped — `plan-33-adapt-to-token-sheriff-0-9-9`
- [x] Open Defect for the incomplete landing (missing `effort`, `review_body_findings_total`, `review_body_findings_handled`)
- [x] layer-3 findings routed: reported to the operator in the same turn, never fixed inline; the findings join the due fix plan's scope (epic `## Decisions` 2026-11-24)
- [x] inbox message archived on consume
