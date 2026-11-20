# Landing Analysis: PLAN-SEC2-06 — Follow-up wording and test gaps

epic: security-hardening-tier-2
workstream: WS-04
pr: #641 (https://github.com/cuioss/API-Sheriff/pull/641), merged through the merge queue as `d5288639`

> Landing record for one shipped plan. Written by the `analyze` verb after verifying the landing
> message against ground truth (git, the PR through the CI abstraction, the merged code). Disclosure is
> mechanism-level: possible weaknesses the read raised that are not yet vetted are reported to the
> operator in the session only and are NOT written here (this file is tracked).

## Deliverable Fidelity vs Spec

Landing facts: `deliverables_total=5`, `deliverables_done=5`; the spec names 4 deliverables (the lane counts 5, as
for 01 to 05; no fifth deliverable is named in the spec or visible in the diff). The landing facts block is
complete (`landing-check`: `complete: true`).

| Deliverable (spec) | Verdict | Evidence |
|--------------------|---------|----------|
| 1. One sweep over every restated statement | shipped-modified | Most sites match the code (PING-bound wording, the logout proof, the `unresolved-via-` source in rows 141/143, the second escaper stated in `RuleSupport`). Four sites still disagree with the code or with the sweep's own rewrite (see L2-1 to L2-3). The sweep reached documents the spec's surface did not name (8 doc pages, 3 sources, one test) |
| 2. Test blind spots of the early-response end | shipped-as-specified | Elapsed bounds exist in `GatewayEdgeEarlyResponseResetTest` and `Http2EarlyResponseResetIT`; `AcknowledgementRelay` is driven by three cases and the PING-refused-then-retried glue by `InboundStreamWindowTest` (`refusedPingOfAnEarlyResponseEndIsRetriedOnceAPlaceIsFree`). The relay tests re-implement `Wait.finish` in a lambda, so the release wiring itself is not guarded (L2-4) |
| 3. Timing-test attribution and two untested answers | shipped-modified | (b) is settled by a test with no production change (`ExtensionIssuerNamespaceGateTest`, a probe at the slot before the gate's observer); (c) is settled by a test (`ConfigLoaderTest`: ids ending in a line terminator are refused by the id pattern, so the four `RouteTableBuilder` sites receive only pattern-matching ids). (a) is **not settled**: the idle-host repetitions were not run (the host was never idle), so the test-side race is neither shown nor refuted; the repeats surfaced a different, production-side ordering (a chunk reaching an upstream request after the deadline reset), which the lane fixed outside any deliverable. The unreachable-site list the Done-when of (c) requires is **not in the landing message** (it appears only as `RuleSupport` Javadoc prose) |
| 4. Two mechanism-level questions | shipped-as-specified | (a) `ReservedPathHandler.speculativeMarker` now also reads `Purpose` and `X-Moz` (every header line) and `CsrfDefence.provesTrustedDocumentNavigation` refuses any non-null marker; tests `shouldKeepTheSessionOnALegacyPrefetchMarker`, `shouldKeepTheSessionOnALaterRepeatedLegacyPrefetchValue` and the trusted-navigation control. (b) the open-body mark is now ended in a `finally` when the stream constructor throws (`DispatchStage.java:1486-1503`); `bodyStreamRefusedAtConstructionLeavesNoBodyOpen` with a matched control. That each new test fails first at `00ebea64` is not shown by reading (the merge is one squashed commit) |
| Added by the lane | added-unplanned | A `catch (IllegalStateException)` in the dispatch data path that drops a chunk handed to an upstream request an abort already reset (`DispatchStage.java:2877-2886`), with `ChunkAgainstTheDeadlineReset` tests and a GW-07 passage in the threat model (`doc/security-threat-model.adoc:1791-1794`) — outside the spec's threat-model scope (GW-08, BFF-09, `Sec-Fetch-Site`) |

Surface delta (declared 30 entries, realized 38 files): 12 realized paths were never declared and no declared directory
covers them: `bff/csrf/package-info.java`, `bff/runtime/BffRuntime.java`, `edge/admission/EdgeHardeningOptions.java`,
`GatewayEdgeRouteBffWiringTest.java`, and eight doc pages (`doc/architecture/tls-and-inbound-security.adoc`,
`doc/configuration/authentication-and-oidc.adoc`, `routing-and-forwarding.adoc`, `security-and-edge-hardening.adoc`,
`doc/development/source-detection-substrate.adoc`, `doc/user/bff-cookie.adoc`, `doc/user/bff-session.adoc`,
`doc/variants/02-bff-session.adoc`). Six declared entries were untouched: `ConfigValidationCommand.java`,
`RouteTableBuilder.java`, `ExtensionIssuerNamespaceGate.java` (all three refutations or unreachable outcomes,
as the spec allows), `endpoint.schema.json`, and the `bff/csrf/` and `bff/reserved/` test directories. `expansion_detected`.
The declaration of the landed spec is not corrected (the plan is shipped).

## Three-Layer Analysis

The landing was read on three layers — the spec, the deliverable trace, and the merged code — by one
read-only leaf, and the orchestrator re-read every point recorded below as `checked` at its `file:line` on `d5288639`
(the points not re-read are named as such). The three "operator only" items were re-read by the orchestrator too. The reading is **by reading, no build, no test run, no write**: what only
a run could show is tagged `not shown by reading`.

- **Dispatch:** one read-only leaf per landing — reason: 38 files across transport, BFF and configuration code, a new data-path catch and a new request-header classifier read against the project's rules; reading that is the depth the dispatch rule exists for.
- **Verdict:** layer 1 findings — 1 major, 3 minor; layer 2 findings — 1 major, 4 minor; layer 3 findings — 2 minor, 1 info, plus items reported to the operator only. No blocker.

### Layer 1 — Specified

The `HYPOTHESIS` claim of deliverable 3(a) is persisted on the spec as `unverifiable` (`by security-hardening-tier-2/analyze`, claim index 8,
at `d5288639`). The leaf read it as refuted; the orchestrator adjudicated it down, because the landing says only that 160 loaded-host repeats
showed no failure of the suspected read, which is neither a shown race nor a shown absence.

| Finding | Claim | Verdict | Polarity | Evidence (file:line) |
|---------|-------|---------|----------|----------------------|
| L1-1 (major) | The Done-when of deliverable 3(a) ("many runs on an idle host, then fix or refute") cannot be met when the host is not idle, and the spec names no fallback; the plan shipped with it owed | checked | confirms | the landing message, "Residue", first bullet (re-read) |
| L1-2 (minor) | The 3(a) hypothesis (a test-side race) does not match what the repeats found (a production ordering), for which the spec has no slot | checked | none — persisted `unverifiable` | the landing message (re-read) |
| L1-3 (minor) | The Done-when conditions otherwise agree with each other and with `main`; the D3(c) anchors are off by one (`RouteTableBuilder.java:487,495`, not 486,494) | checked | confirms | `RouteTableBuilder.java:203,206,487,495` (re-read) |
| L1-4 (minor) | The declared surface omits files a sweep legitimately touches, and the threat-model scope (GW-08, BFF-09, `Sec-Fetch-Site` only) is narrower than what deliverable 1 requires | checked | confirms | the surface delta above |

**Layer 1 verdict:** findings — 1 major, 3 minor

### Layer 2 — Implemented

| Deliverable | Code trace | Test that can fail | Unexplained file / unborne claim | Verdict |
|-------------|------------|--------------------|---------------------------------|---------|
| 1. Sweep | the sites in the table above | none (documentation) | 4 sites still disagree (L2-1 to L2-3) | checked |
| 2. Early-response tests | `EarlyResponseEnd.java:333-335,379-384` | `EarlyResponseEndTest:363-368,389-392,408-430`, `InboundStreamWindowTest` | none | checked, except that "each test fails against a broken variant" is not shown by reading |
| 3. Timing and two answers | `ExtensionIssuerNamespaceGateTest`, `ConfigLoaderTest` | the same | the GW-07 passage and the data-path catch | (b), (c) checked; (a) not settled |
| 4. Two questions | `ReservedPathHandler.java:202-211`, `CsrfDefence.java:246-259`, `DispatchStage.java:1486-1503` | `GatewayEdgeRouteBffWiringTest`, `DispatchStageTest` | none | checked; failing-first not shown by reading |

- **L2-1 (major).** The final read of deliverable 1 is not clean: the BFF-09 row of the threat model's coverage table still gives "no `Sec-Purpose`" as the whole marker rule, while the code and the rewritten BFF-09 passage refuse `Purpose` and `X-Moz` too. Evidence: `doc/security-threat-model.adoc:4921`; code `ReservedPathHandler.java:202-211`.
- **L2-2 (minor).** `CsrfDefence.provesTrustedDocumentNavigation` Javadoc still says "`Sec-Fetch-Site: none` is not a proof" with no "no `Origin`" qualifier, while the code admits a trusted `Origin` whatever `Sec-Fetch-Site` says. The sweep qualified the same sentence in three documents but not here. Evidence: `CsrfDefence.java:233` against `:249-259`.
- **L2-3 (minor).** The placeholder form `unresolved-via-<proxy address>` stays in two places (`doc/security-threat-model.adoc:4322`, ADR-0078 `:154`), while the sweep rewrote every other occurrence to "`unresolved-via-` followed by the proxy's canonical address". Not a code mismatch; the sweep is not uniform.
- **L2-4 (minor).** The relay tests do not fail if `Wait.finish` stops calling `relay.release()` (`EarlyResponseEnd.java:333-335`): `lateAcknowledgementDoesNotEndTheWaitASecondTime` re-implements the wait's ending in a lambda (`EarlyResponseEndTest.java:408-430`), and the `ended` flag in `Wait.acknowledged` would still mask a double end. The retention property the relay exists for is not guarded through the real wait.
- **L2-5 (minor).** Narrative claims the diff does not bear out: "5/5 deliverables" (4 specified); "the chunk-after-deadline-reset ordering was fixed" — the diff adds a catch that drops the chunk (`DispatchStage.java:2877-2886`) and leaves the ordering itself unchanged, so it is tolerated, not fixed; and the unreachable-site list required by deliverable 3(c) is not in the landing message.

**Layer 2 verdict:** findings — 1 major, 4 minor; failing-first order and fails-against-broken-variant not shown by reading

### Layer 3 — Correct

| Defect | File:line | Triggering input | Effect | Severity |
|--------|-----------|------------------|--------|----------|
| L3-1 | `DispatchStage.java:2869-2887` | a chunk dropped by the new catch | still counted in `bytesForwarded` / `bytesSent`, so the retry gate treats the attempt as having sent a body byte; errs toward no retry, which is safe and is stated in the Javadoc; the catch swallows any `IllegalStateException` after an abort exists, logged at DEBUG with the message only | info |
| L3-2 to L3-4 | withheld | withheld | withheld — see "Reported to the operator only" | — |

The `finally` at `DispatchStage.java:1486-1503` is correct: the open-body end is idempotent, so no double release and no leak of the mark on a refused constructor (re-read). `CsrfDefence.java:246-259` fails closed on an empty `Sec-Purpose`, a plain trusted navigation is unaffected (matched control), and header names are lower-cased before lookup.

**Layer 3 not shown by reading:** the new wall-clock bounds in the tests (900 ms unit, 500 ms IT) can only give false reds under host load, never false greens; whether they hold on an idle host is a run result. That the `ConfigLoaderTest` id cases pass is a run result (the PR's checks are green).

**Reported to the operator only:** 3 items (L3-2 to L3-4). They are not written into this tracked file. Layer 3 verdict: findings — 1 info in this file, 3 reported to the operator, no blocker.

**Layer 3 verdict:** findings — 1 info; 3 items reported to the operator only

### Conformance to the Project's Own Rules

| Rule source | Rule | Verdict | Evidence |
|-------------|------|---------|----------|
| CLAUDE.md § Pre-1.0 / Dependency | no `@Deprecated`, no new dependency | conforms | no `pom.xml` among the 38 files |
| CLAUDE.md § Logging | `CuiLogger`, `%s`, exception first | conforms | `DispatchStage.java:2884` |
| CLAUDE.md § Logging | LogRecord changes documented in `doc/LogMessages.adoc` | conforms | `ConfigLogMessages` 149, `ApiSheriffLogMessages` 141/143 against their rows |
| CLAUDE.md § Testing | JUnit 5, AAA, no Mockito/PowerMock/Hamcrest | conforms | none imported |
| junit-core | AAA without phase comments | deviates (minor, leaf-reported, not re-read) | `ExtensionIssuerNamespaceGateTest` new case uses `// Arrange -` comments, continuing a style the file already had |
| CLAUDE.md § Sonar | suppression carries a rationale | conforms | `DispatchStage.java:1497` |
| spec PLAN-SEC2-06 | standing constraints (one PR, ADR ceiling, threat-model scope) | deviates | 38 files and ADR-0066/0076 at 418/426 lines conform; the GW-07 passage and the missed BFF-09 row break the stated scope and "no disagreement" |
| spec PLAN-SEC2-06 | D3 Done-when | deviates | (a) owed; unreachable-site list not in the landing message |

## Metrics and Anomalies

- Tokens: 28,318,091 in total (the lane's measure; no per-phase outlier read).
- Duration: 84,432 s of wall time (about 23.5 h, spanning two sessions and a host that was never idle).
- Anomalies: the first merge-queue entry was ejected by `SniFrontListenerTest.returnsTheSlotOnTheError` (30 s timeout in the Java 25 leg; the `tls` package is not touched by this plan; green on the PR-head run), one retry merged as `d5288639`; `BffRefreshSingleFlightIT.concurrentRequestsShareOneRefresh` failed once under host load of about 13 and passed on its own re-run (code not touched). Both are the landing's own account and were not re-run.

## Routing and Merge Behavior

- Review: CodeRabbit posted one review with 3 inline findings; each was answered "Valid" with fix commit `dd7f320` (the test probe derives its bean population, every `Purpose` / `X-Moz` value is scanned, the IT takes its quiet-exit origin before the write) and its thread resolved. Sourcery approved. **Re-read against the provider:** `unresolved_review_threads` 0 matches. `review_body_findings_total` is **3, not 2 — a false claim** (the landing under-counts by one). `review_body_findings_handled` could not be reproduced by the review gate (it reports 0 undisposed-from-provider); the three threads carry a reply naming the fix commit, so all 3 were handled in substance, and the landing's `2` is recorded as **not re-verified** beyond that.
- CI/merge: all 25 checks of the PR green at the head, including `build (25)`, `build (27)`, `integration-tests / test`, SonarCloud and the PR-attached benchmark; merged through the merge queue (one ejection and retry, above). The main-branch runs for `d5288639` were not read (no lookup by merge commit in the CI abstraction).
- Sonar: the landing's `sonar_open_new_issues=0` is **not re-verified**: the Sonar read returned `undecidable` (`head_revision_unavailable` after the merge; analysis revision `dd7f320`, the PR head). The SonarCloud check and gate on the PR are green.

## Reconciliation Actions

- [x] row `status` → `shipped` — `orchestrator queue --transition PLAN-SEC2-06 --status shipped`
- [x] row `pr` stamped — `641`
- [x] row `landing` stamped — `landings/PLAN-SEC2-06.md`
- [x] row `plan_marshall_plan_id` stamped — `sec2-06-follow-up-wording-and-test-gaps`
- [x] epic.md narrative reconciled against the queue rows (queue annotation, decision, two Watches)
- [x] `HYPOTHESIS` claim 8 of the spec persisted `unverifiable`
- [x] L2-1 to L2-3 folded into PLAN-SEC2-07 deliverable 5, with its declared surface updated in the same act
- [x] resume anchor updated in `resume_anchor.md`
- [x] `queue-view.md` regenerated and committed with the row change

## Follow-Ups

- **L2-1 to L2-3 (four sites):** folded into PLAN-SEC2-07 deliverable 5 (the same one-commit sweep); that spec's Expected Surface gained `CsrfDefence.java` and ADR-0078, and its threat-model scope was widened to the BFF-09 coverage row and the unresolved-source passage.
- **D3(a), idle-host repetitions:** Watch in `epic.md` (owed, not settled). It needs an idle host; no plan carries it.
- **L2-4, relay tests not guarding the release wiring:** Watch in `epic.md`.
- **L1-1, L1-2, L1-4, L2-5 (spec form and narrative):** recorded here only; the plan is shipped and no spec is corrected.
