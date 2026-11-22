# Landing Analysis: PLAN-SEC2-07 — Hardening quick wins and wording corrections

epic: security-hardening-tier-2
workstream: WS-04
pr: #647 (https://github.com/cuioss/API-Sheriff/pull/647), merged through the merge queue as `2335c81d`

> Landing record for one shipped plan. Written by the `analyze` verb after verifying the landing
> message against ground truth (git, the PR through the CI abstraction, the merged code). Disclosure is
> mechanism-level: possible weaknesses the read raised that are not yet vetted are reported to the
> operator in the session only and are NOT written here (this file is tracked).

## Deliverable Fidelity vs Spec

Landing facts: `deliverables_total=5`, `deliverables_done=5`; the spec names 5 deliverables, of which the lane dropped
deliverable 4 at outline (the spec allows it: "optional; drop at outline if it needs more than a constant and a map"), so
four shipped and "5 of 5 done" overstates by one. The landing facts block is **incomplete** (`landing-check`:
`complete: false`, `missing_keys: effort` — the lane reported `effort=unknown`).

| Deliverable (spec) | Verdict | Evidence |
|--------------------|---------|----------|
| 1. The matched SNI value in the passthrough audit log | shipped-as-specified | `SniFrontListener.route` normalizes once, matches on the key and hands the key to the relay (`SniFrontListener.java:739-747`); `PassthroughRelay` logs that label only (`:221`). `SniFrontListenerTest` routes three server names padded with space, CR, LF and tab and asserts the record carries the configured host (`:150-169`), plus the exact-host control |
| 2. A tighter body bound on the management listener | shipped-as-specified | `quarkus.management.limits.max-body-size=32K` (`application.properties:256`); `ManagementListenerTransportTest` on a live listener: 413 at 32 KiB + 1, 200 at 32 KiB, seven probe routes 200 (`:251-259`); `ShippedApplicationPropertiesTest` pins the literal (`:231`); a native-image IT (`ManagementBodyBoundIT`) was added. Deleting the key reddens the shipped-properties test and the live-listener test |
| 3. A boot warning for two configuration combinations behind the SNI front | shipped-as-specified | `EdgeRules.validatePassthroughSourceCollapse` (`:348-392`), registered in `ConfigValidator`; identifier 152 is the next free one (`ConfigLogMessages.java:709-715`); `EdgeRulesTest` pins block, delay, an IPv4 loopback range and `::1/128`, with silent matched controls (`:288-309`); the record and the consequence are in `doc/LogMessages.adoc` and `doc/configuration/security-and-edge-hardening.adoc` |
| 4. A per-source bound on a passthrough target's dial budget | dropped (as the spec allows) | the PR body: dropped at outline because it needs more than a constant and a map; the hypothesis is neither shown nor refuted. `PassthroughRelayTest`, declared for it, was not touched |
| 5. One sweep over every restated statement | shipped-modified | Every site the spec names is corrected and agrees with the code (the connection-cap sentences, the internal port, ADR-0003 and ADR-0076, BFF-02, `ApiSheriff-146`, ADR-0043 and the codec Javadoc, the revocation sentence, `idp.yaml`, and the four sites 06 left: BFF-09 coverage row, `CsrfDefence` Javadoc, the two `unresolved-via-` placeholders). The final read is not clean: three restatements outside the declared surface still state the old phrasing (L2-1) |
| Added by the lane | added-unplanned | the `ApiSheriff-146` record now takes a fourth argument, the stage of its own refusal reason (`SniFrontListener.java` `Refusal.stage`; `ApiSheriffLogMessages`, `doc/LogMessages.adoc`), a CodeRabbit-driven change that the spec's D5 did not name (it asked for a wording correction only); the integration test bounds its TCP connect; `health/group` and `health/well` joined the probe-route lists |

Surface delta (declared 25 entries, realized 35 files): 11 realized paths were never declared and no declared directory covers
them: `ConfigValidator.java`, `ConnectionCapAudit.java`, `EdgeHardeningOptions.java`, `TlsEdgeProducer.java`,
`SingleSourceTlsContractTest.java`, `LocalStackTls.java`, `ManagementBodyBoundIT.java`, and four doc pages
(`doc/configuration/tls-and-egress.adoc`, `doc/development/boot-validation-rules.adoc`,
`doc/user/environment-variable-overrides.adoc`, `doc/variants/03-bff-cookie.adoc`); each is a comment, test-support, integration-test or
documentation consequence of deliverables 1 to 5. One declared entry was untouched: `PassthroughRelayTest.java` (deliverable 4,
dropped). `expansion_detected`. The declaration of the landed spec is not corrected (the plan is shipped).

## Three-Layer Analysis

The landing was read on three layers — the spec, the deliverable trace, and the merged code — by one
read-only leaf, and the orchestrator re-read every point recorded below as `checked` at its `file:line` on `2335c81d`
(the leaf's findings not re-read are named as such). The reading is **by reading, no build, no test run, no write**: what only
a run could show is tagged `not shown by reading`.

- **Dispatch:** one read-only leaf per landing — reason: 35 files across the SNI front, configuration validation, the BFF refresh path and twelve documents, a new boot rule and a wording sweep read against the code each sentence describes; reading that is the depth the dispatch rule exists for.
- **Verdict:** layer 1 findings — 2 info; layer 2 findings — 4 minor, 1 info; layer 3 findings — 1 minor, 1 info, plus 1 item reported to the operator only. No blocker, no major.

### Layer 1 — Specified

The four `HYPOTHESIS` claims are persisted on the spec (`by security-hardening-tier-2/analyze`, `checked_at 2335c81d`): the management-bound
claim (index 10), the BFF-02 pending store (index 12) and the refresh-token claim (index 13) `corroborated`; the dial-budget claim (index 11)
`unverifiable`, because deliverable 4 was dropped and nothing shows or refutes it. The leaf returned the management-bound claim as `not shown
by reading` (its runtime half); the orchestrator persisted `corroborated` on the first-party ground truth of the PR checks, in which the
live-listener test passes (`build (25)`, `build (27)` green on the head).

| Finding | Claim | Verdict | Polarity | Evidence (file:line) |
|---------|-------|---------|----------|----------------------|
| L1-1 (info) | H3 holds: one pending-authorization store is built for both modes and consumed once | checked | confirms | `BffRuntimeProducer.java:597`, `CallbackEndpoint.java:336` (re-read) |
| L1-2 (info) | H4 holds: a refresh without an ID token keeps the previous one; the active scope set is replaced by the response scope | checked | confirms | `TokenRefreshCoordinator.java:823,859` (re-read) |
| L1-3 (minor) | The "one commit" and "failing first" conditions of the spec cannot be audited from a squash merge; and `ApiSheriff-146`'s arity change is outside the sweep's wording-only brief | not shown by reading | none | the merge is one squashed commit |

**Layer 1 verdict:** findings — 2 info, 1 minor not shown by reading

### Layer 2 — Implemented

| Deliverable | Code trace | Test that can fail | Unexplained file / unborne claim | Verdict |
|-------------|------------|--------------------|---------------------------------|---------|
| 1. SNI value | `SniFrontListener.java:739-747`, `PassthroughRelay.java:221` | `SniFrontListenerTest:150-169` | none | checked; failing-first not shown by reading |
| 2. Body bound | `application.properties:256` | `ManagementListenerTransportTest:251-259`, `ShippedApplicationPropertiesTest:231`, `ManagementBodyBoundIT` | none | checked; the native-image IT was not run here |
| 3. Boot warning | `EdgeRules.java:348-392`, `ConfigLogMessages.java:709-715` | `EdgeRulesTest:288-309` | none | checked |
| 4. Dial bound | — | — | dropped as the spec allows | not shipped |
| 5. Sweep | the sites in the table above | none (documentation) | 3 restatements still state the old phrasing (L2-1) | checked |

- **L2-1 (minor).** The sweep's final read is not clean; three restatements of corrected statements were not reached. `integration-tests/docker-compose.yml:425` and `integration-tests/src/main/docker/sheriff-config/gateway.yaml:81` still call the 8444 listener the "internal loopback port" after the development page was corrected to "bound on all interfaces"; the `PendingAuthorizationStore` Javadoc (`:28`) still says a cookie variant can supply an implementation where the sealed record travels in the cookie and no server map exists, which the corrected BFF-02 text and `BffRuntimeProducer` contradict. None of the three files was in the declared surface.
- **L2-2 (minor).** The boot-rule tests pin only `127.0.0.1/32` and `::1/128`: the overlap branch for a broader covering range (`0.0.0.0/1`, `::/1`) and the skip of a malformed entry are not pinned, although the code handles both (`EdgeRules.java:369-382`). The `127.0.0.0/8` case exists only as a control without passthrough (`EdgeRulesTest:~303`).
- **L2-3 (minor).** The corrected cap statement ("counts a connection on the HTTPS listener only after its TLS handshake has completed"), repeated in `application.properties`, `doc/user/edge-security.adoc`, the threat model, `ConnectionCapAudit` and the `ApiSheriff-148` Javadoc, is pinned by no test in the diff and rests on Quarkus and Vert.x internals. Not shown by reading.
- **L2-4 (minor).** 11 touched files were not declared and one declared file was untouched (see the surface delta).
- **L2-5 (info).** Narrative claims the diff cannot bear out: "5 of 5 deliverables" (four shipped, one dropped), "the sweep is a single commit" and "each behaviour change started with a failing test" (a squash merge shows neither), and the native-image run and the post-merge runs were not observed by the lane.

**Layer 2 verdict:** findings — 4 minor, 1 info; failing-first order not shown by reading

### Layer 3 — Correct

| Defect | File:line | Triggering input | Effect | Severity |
|--------|-----------|------------------|--------|----------|
| L3-1 | `ConfigLogMessages.java:712` | the `ApiSheriff-152` record, for either combination | the template states both consequences ("an acting detection action blocks or delays all of them at once, and a loopback trusted proxy trusts every client's own forwarded headers") for whichever combination the argument names; the document row carries the "while no loopback address is a trusted proxy" qualifier, the log line does not. Wording only | minor |
| L3-2 | `doc/adr/0043-…adoc:12,249` | the ADR summary | the summary says the two conditions "do not hold" while its own list concedes a repeated ID token (`:76`); the closing question speaks of a party that does not hold the session, so the decision's wording is consistent, only the summary reads tighter than the body. Adjudicated down from the leaf's "refutes" | info |
| L3-3 | withheld | withheld | withheld — see "Reported to the operator only" | — |

The boot rule is correct as read: only the loopback relay peer reaches detection and the forward stage behind the front, `trusted_proxies` takes CIDR literals only, a trust-all range is refused before the rule, an IPv4-mapped IPv6 entry becomes an IPv4 literal and an over-long prefix is refused as malformed (`EdgeRules.java:348-392`). The only sink of the SNI value in the passthrough path is the INFO record, and it now receives the key; the other records carry fixed tokens or no server name. The open items of the 06 landing (the legacy prefetch marker, `UpstreamAttempt.abandon`) are untouched by this diff.

**Layer 3 not shown by reading:** that `ManagementBodyBoundIT` passes on the native image, and whether the corrected cap statement holds under a real TLS handshake race, are run results. The PR checks are green.

**Reported to the operator only:** 1 item (L3-3). It is not written into this tracked file. Layer 3 verdict: findings — 1 minor and 1 info in this file, 1 reported to the operator, no blocker.

**Layer 3 verdict:** findings — 1 minor, 1 info; 1 item reported to the operator only

### Conformance to the Project's Own Rules

| Rule source | Rule | Verdict | Evidence |
|-------------|------|---------|----------|
| CLAUDE.md § Pre-1.0 / Dependency | no `@Deprecated`, no new dependency | conforms | no `pom.xml` among the 35 files; the `146` template arity changed directly |
| CLAUDE.md § Logging | `CuiLogger`, `%s`, exception first, range, documented | conforms | identifier 152 in the WARN range with `%s` only and a fixed-token argument; row in `doc/LogMessages.adoc`; the `146` row updated |
| CLAUDE.md § Testing | JUnit 5, AAA, no Mockito/PowerMock/Hamcrest | conforms | none imported in the diff |
| CLAUDE.md § Testing | a key that parses is a key that acts | conforms | deleting `max-body-size` reddens the shipped-properties test and the live-listener test |
| CLAUDE.md § Javadoc | public and protected API documented | conforms | `validatePassthroughSourceCollapse` and `Refusal.stage()` documented; no new package |
| CLAUDE.md § Sonar | suppression carries a rationale | conforms | no `@SuppressWarnings` or `NOSONAR` added |
| CLAUDE.md § Security | no raw client data in logs | conforms | the audit record carries the configured host; `ApiSheriff-152` names a fixed token, never an address |
| CLAUDE.md § Pre-Commit | quality gate and verify for a footprint with Java and properties | not shown by reading | the diff cannot show that they ran; the PR's `build` checks are green |
| spec PLAN-SEC2-07 | standing constraints (one PR, ADR ceiling, no new key) | conforms | 35 files; ADR-0003 (176), 0043 (268), 0076 (433), 0078 (295) lines; no `gateway.yaml` key added |
| spec PLAN-SEC2-07 | D5 Done-when ("a final read finds no disagreement") | deviates | three restatements remain (L2-1) |

## Metrics and Anomalies

- Tokens: 29,559,439 in total (the lane's measure; no per-phase outlier read).
- Duration: 129,316 s of wall time (about 35.9 h, spanning several sessions).
- Anomalies: the lane closed the functional review and the code review by operator decision after several rounds that found only wording restatements; Sourcery declined its second review on a diff-size budget (the `Sourcery review` check is green on the head). `effort` is `unknown` in the landing facts. None of this was re-run.

## Routing and Merge Behavior

- Review: CodeRabbit reviewed the PR; the provider shows 13 inline review threads (32 comments), all resolved, and 3 `review_body` rows that carry no thread. **Re-read against the provider:** `unresolved_review_threads` 0 matches. `review_body_findings_total` is **13 at the review gate against the landing's 3 — a false claim by the rule** (the number the landing gives equals the three `review_body` rows; the gate counts the inline findings too, and every one of those is in a resolved thread). `review_body_findings_handled` could not be reproduced (the review gate reports 0 undisposed-from-progress-file because no progress file applies); the threads carry replies naming their fix commits, so the landing's `3` is recorded as **not re-verified** beyond that.
- CI/merge: all 25 checks of the PR green at the head, including `build (25)`, `build (27)`, `integration-tests / test`, SonarCloud and the PR-attached benchmark; merged through the merge queue as `2335c81d`. The main-branch runs for `2335c81d` were not read (no lookup by merge commit in the CI abstraction).
- Sonar: the landing's `sonar_open_new_issues=0` is **not re-verified**: the Sonar read returned `undecidable` (`head_revision_unavailable` after the merge; analysis revision `b45193c`, the PR head). The SonarCloud check and gate on the PR are green.

## Reconciliation Actions

- [x] row `status` → `shipped` — `orchestrator queue --transition PLAN-SEC2-07 --status shipped`
- [x] row `pr` stamped — `647`
- [x] row `landing` stamped — `landings/PLAN-SEC2-07.md`
- [x] row `plan_marshall_plan_id` stamped — `sec2-07-hardening-quick-wins`
- [x] `HYPOTHESIS` claims 10, 11, 12 and 13 of the spec persisted (`corroborated`, `unverifiable`, `corroborated`, `corroborated`)
- [x] epic.md narrative reconciled against the queue rows (queue annotation, decision, Open Defect for the incomplete landing, the leftover Watch)
- [x] resume anchor updated in `resume_anchor.md`
- [x] `queue-view.md` regenerated and committed with the row change

## Follow-Ups

- **L2-1 (three restatements), L2-2 (boot-rule test gap), L3-1 (`ApiSheriff-152` wording):** one Watch in `epic.md`; no plan carries them. The operator decides whether they are staged, forwarded or accepted.
- **L2-3 (cap statement unpinned), L2-4, L2-5, L1-3, L3-2:** recorded here only; the plan is shipped and no spec is corrected.
- **Deliverable 4 (per-source dial bound):** dropped by the lane as the spec allows; recorded here, no Watch (nothing shows a defect).
- **Incomplete landing facts (`effort` unknown):** Open Defect in `epic.md`.
