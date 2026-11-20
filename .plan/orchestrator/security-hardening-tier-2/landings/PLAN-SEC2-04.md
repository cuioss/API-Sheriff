# Landing Analysis: PLAN-SEC2-04 — Leftover hardening

epic: security-hardening-tier-2
workstream: WS-04
pr: #635 (https://github.com/cuioss/API-Sheriff/pull/635), merged through the merge queue as `00ebea64`

> Landing record for one shipped plan. Written by the `analyze` verb after verifying the landing
> message against ground truth (git, the PR through the CI abstraction, the merged code). Disclosure is
> mechanism-level: possible weaknesses the read raised that are not yet vetted are reported to the
> operator in the session and in the git-ignored private notes, and are NOT written here (this file is
> tracked).

## Deliverable Fidelity vs Spec

Landing facts: `deliverables_total=12`, `deliverables_done=12`; the spec names 12 deliverables (the lane
counts 12). The landing facts block is **incomplete** (`landing-check`: `complete: false`; the three
review-thread keys `unresolved_review_threads`, `review_body_findings_total` and
`review_body_findings_handled` are `unknown`; the plan's manifest had no review-gate step). The 92-file
diff is under the 100-file reviewer cap.

| Deliverable (spec) | Verdict | Evidence |
|--------------------|---------|----------|
| 1. Forwarded-chain judgement and source-key steering | shipped-as-specified | `ForwardedClientResolution` resolves Resolved / Unresolvable / Absent; the `X-Forwarded-For` fallback is gone and a disagreeing pair is keyed on the proxy's own source (`ForwardedClientResolution.java:147`); the edge check and the resolver judge the same joined value (`BasicChecksStage.java`); the unsupported topology is stated in ADR-0003. Tests: `ForwardedClientResolutionTest`, `BasicChecksStageTest`, `SourceKeysTest` |
| 2. Logout provenance | shipped-as-specified | `CsrfDefence.provesTrustedDocumentNavigation` (`CsrfDefence.java:241-253`) refuses a `Sec-Purpose` header, a non-`navigate` mode and a non-`document` destination, and decides on a present `Origin` alone; the logout leg trusts the gateway's own origin (`withGatewayOrigin`); `ReservedPathHandler.java:171-177` hands the Fetch Metadata over. Tests: `CsrfDefenceTest`, `LogoutEndpointTest` |
| 3. Recon-trip key on the threat ledger | shipped-as-specified | `SourceTrip.requestKey` is carried from `SourceLedger.admit`; `ThreatClassifier` scores the request's own key, so a tracked `/64` and a `/48` trip land on one entry. Tests: `ThreatClassifierTest`, `SourceGateTest` |
| 4. Full-ledger behaviour and its accuracy | shipped-modified | The landing says "changed in the documentation only". That is **not borne out**: `SourceLedger.hasRoomFor` hands its sweep off the event loop (`SourceLedger.java:318-328`) and `SourceGate.onTrip` logs and meters a trip of the `unknown` source as `observe` (`SourceGate.java:380-397`, re-read). Only the full-ledger bound-or-evict direction is documentation-only (ADR-0076). The proof-test question was settled by correcting the citation, not by making `accruingSourceStaysTrackedInAFullLedger` fail against the base; ADR-0076 now says so, which is the spec's second branch |
| 5. Echo and log hygiene residuals | shipped-modified | `RuleSupport.renderForLog` / `renderForMessage` are one escaper used by the boot sink and the alias echoes (`RouteTableBuilder.java:200-207`, re-read: the alias goes through `renderForMessage`, `endpoint.id()` stays raw). `OriginValidationStage`, `UpstreamFailureMapper`'s route-id lines, `HeaderRules`, `AuthRules` and `CookieRules` are untouched or unchanged for the id sites; that is defensible if the id patterns make them unreachable, but the list of unreachable sites the Done-when puts in the landing message is **not in the message** (it points at a decision-log entry of the plan). The route-id `$` question was already pinned at the base by `ConfigLoaderTest`; the diff adds no test for it |
| 6. Reach of the key-material views and the extension issuer gate | shipped-as-specified (refuted) | Two refutations are recorded with tests: `ExtensionIssuerNamespaceGateTest` (profile-prefixed, environment and default-value keys are seen) and `TerminatedListenerTlsAuditTest` (half a PEM pair is refused before either audit runs). The third question (can the metrics collector build its validator before the gate's `StartupEvent` observer) has no new test in the diff |
| 7. Failed-relay head field set | shipped-as-specified | `TerminalDispatch` removes the fixed list plus the `-Cache-Control` suffix family, case-insensitively (`TerminalDispatch.java:142`); the choice and its reason are in the Javadoc. Test: `TerminalDispatchLateBodyTimeoutTest:148-160` with a kept-field control |
| 8. Management interface transport bounds | shipped-as-specified | `quarkus.management.limits.max-header-size=16K` shipped; `ManagementListenerTransportTest` asserts the header block, request line, the three form limits, the body and the HTTP/2 8 KiB announce on a live listener. The idle reap is stated in documentation as the Quarkus default and is not asserted by a test |
| 9. Documentation and comment debts | shipped-modified | The 1.25× rule, the `application.properties` comment, the Javadoc rationales and the `ApiSheriff-149` wording were corrected. `LogMessages.adoc` rows 141 and 143 and the `SOURCE_TRIPPED` Javadoc were not brought in line with the new source value (see L3-1) |
| 10. The request-head deadline gap, stated | shipped-as-specified | `doc/security-threat-model.adoc:1345` and the guides state no head deadline is installed and the connection cap is one counter; the leaf found both statements match the code. The drafted upstream request is in the plan's decision log, not in a tracked document |
| 11. Back-channel logout is per replica: boot WARN | shipped-as-specified | `OidcSessionRules.java:382` logs `ApiSheriff-150` when server mode and back-channel logout are both on, silent otherwise; row at `doc/LogMessages.adoc:112`; `OidcSessionRulesTest:76` with controls |
| 12. Upstream breaker attribution residuals | shipped-modified | The expired-wait rule now asks whether a body is still arriving (`UpstreamBodyTraffic`, `DispatchStage.java:1480-1489`, re-read); `UpstreamPoolSaturationTest` carries the red/green pair and the matched control; the fixtures use the gateway breaker (`UpstreamBreakerFixtures`) and `UpstreamCircuitBreakerTest` has a concurrent case; a throwing state-change consumer is logged as `ApiSheriff-151` and no longer replaces the call's result (`UpstreamCircuitBreaker.java:281`). (b) and (c) are stated in ADR-0060, GW-07 and ADR-0064 |

Surface delta (declared 55 entries, realized 92 files): 22 realized paths were never declared, 7 declared
entries were untouched. `expansion_detected`. Undeclared and explained by a deliverable: `UpstreamBodyTraffic.java` (new)
and `UpstreamClients.java` (D12), `UpstreamGuards.java`, `SheriffMetrics.java`, `SourceKeyResolver.java` (new), `SourceKey.java`,
`SourceTrip.java`, `PendingQuotaKeys.java`, `ThreatAlertDocument.java`, `ConfigValidator.java`, `ApiSheriffLogMessages.java`,
`bff/csrf/package-info.java` (D1/D2/D3/D9/D12) and their tests, `demo-client/doc/integration-sample.adoc`,
`doc/configuration/authentication-and-oidc.adoc`, `doc/development/boot-validation-rules.adoc`, `doc/user/bff-cookie.adoc`,
`doc/variants/03-bff-cookie.adoc` (documentation sweeps). Declared and untouched: `OriginValidationStage.java`, `EgressClients.java`,
`DeclaredKeyMaterialKeys.java`, `ExtensionIssuerNamespaceGate.java`, `ManagementPlainHttpAudit.java`,
`TerminatedListenerTlsAudit.java`, `endpoint.schema.json` — each matches a refutation or an "unreachable" outcome (D5, D6). The
declaration of the landed spec is not corrected (the plan is shipped). The 92 files are the diff of `00ebea64` against its first parent.

## Three-Layer Analysis

The landing was read on three layers — the spec, the deliverable trace, and the merged code — by one
read-only leaf, and the orchestrator re-read the points marked below at their `file:line`. The reading is
**by reading, no build, no test run, no write**: what only a run could show is tagged `not shown by reading`.

- **Dispatch:** one read-only leaf per landing — reason: a 12-deliverable, 92-file change whose new open-body bookkeeping, forwarded-chain resolution and logout provenance are concurrency- and trust-boundary code; reading that is the depth the dispatch rule exists for.
- **Verdict:** layer 1 findings — 2 minor/info; layer 2 findings — 3 minor; layer 3 findings — 3 minor (documentation and code rules), plus items reported to the operator only. No blocker or major defect.

### Layer 1 — Specified

The spec's claims are persisted on it (`by security-hardening-tier-2/analyze`, checked at `00ebea64`): the
head-deadline claim, the sweep-on-event-loop claim, the `unknown`-source logging claim, the accruing-test claim and the
throwing-consumer claim `corroborated`; the issuer-gate claim `contradicted` (the plan absorbed the refutation; the spec is
shipped and not re-scoped); the metrics-collector ordering claim `unverifiable`.

| Finding | Claim | Verdict | Polarity | Evidence (file:line) |
|---------|-------|---------|----------|----------------------|
| L1-1 | D10: no head deadline is installed and `max-connections` is one counter | checked | confirms — persisted `corroborated` | `doc/security-threat-model.adoc:1345`; the idle-timeout setter is the only timer (`EdgeHardeningOptions.java:328-329`) — read by the leaf |
| L1-2 | D5's premise that the route-id `$` terminator may differ from `(?![\s\S])` | checked | confirms (minor, spec form) | already pinned at the base by `ConfigLoaderTest:886-893` and `:3470-3473`, so the "settle by test" task had nothing new to write |
| L1-3 | The Expected Surface covers what the plan touches | checked | none (info) | 22 touched paths were never declared, all explained by D1/D2/D3/D9/D12 |

**Layer 1 verdict:** findings — 1 minor (L1-2), 1 info (L1-3)

### Layer 2 — Implemented

| Deliverable | Code trace | Test that can fail | Unexplained file / unborne claim | Verdict |
|-------------|------------|--------------------|---------------------------------|---------|
| 1–3, 7, 11 | see Fidelity table | `ForwardedClientResolutionTest`, `CsrfDefenceTest`, `ThreatClassifierTest`, `TerminalDispatchLateBodyTimeoutTest`, `OidcSessionRulesTest` | none | checked (test-first order not visible in a squashed commit; the plan's red runs are not shown by reading) |
| 4 | `SourceLedger.java:318-328`, `SourceGate.java:380-397` | `SourceLedgerPressureTest`, `SourceGateFullLedgerTest` | landing claim "documentation only" | checked — claim not borne out (L2-1) |
| 5 | `RuleSupport.renderForLog`, `ConfigProducer`, `RouteTableBuilder.java:200-207` | `ConfigProducerTest`, `RouteTableBuilderTest` | unreachable-site list not in the landing message; `$` question not newly tested | not_shown_by_reading for the unreachable sites (L2-2) |
| 6 | `ExtensionIssuerNamespaceGateTest`, `TerminatedListenerTlsAuditTest` | the refutation tests | third question has no test | checked for two, not shown for the third (L2-3) |
| 8 | `application.properties`, `ManagementListenerTransportTest` | live-listener assertions | idle reap stated, not asserted | checked |
| 9, 10 | doc and comment sites | the 1.25× rule has a test | none | checked |
| 12 | `UpstreamBodyTraffic`, `DispatchStage.java:1480-1489`, `UpstreamCircuitBreaker.java:281` | `UpstreamPoolSaturationTest`, `UpstreamCircuitBreakerTest:460` | none | checked |

- **L2-1 (minor).** The landing's "D4: changed in the documentation only" is false; D4 changed production code in `SourceLedger` and `SourceGate` (see the Fidelity table). The same landing's "the wording of the unknown-source key now agrees across ADR-0003, ADR-0076, ADR-0078, the threat model and the guides" is not borne out either (L3-1).
- **L2-2 (minor).** The list of deliverable-5 sites that cannot be reached is required in the landing message and is not there; it lives in the plan's decision log (entry `8fe9df`). By reading, `RouteTableBuilder.java:203,206,486,494` still format `endpoint.id()` and `route.id()` without the escaper; that is fine only if the id patterns exclude control characters, which the leaf did not re-derive.
- **L2-3 (minor).** The third D6 question has no test in the diff, and D8's idle-reap statement is a documentation claim with no assertion.

**Layer 2 verdict:** findings — 3 minor; the test-first order and the corpus runs not shown by reading

### Layer 3 — Correct

| Defect | File:line | Triggering input | Effect | Severity |
|--------|-----------|------------------|--------|----------|
| L3-1 | `doc/LogMessages.adoc:103` (rows 141, 143) and the `SOURCE_TRIPPED` Javadoc in `ApiSheriffLogMessages.java` | a trip of a source keyed on a trusted proxy's unresolved forwarding | the rows list the source as IPv4, IPv6 `/64`, `/48` or `unknown`; none names the `unresolved-via-` value `SourceKeyResolver.java:84-88` now produces and `SourceGate` logs, so an operator reading the log cannot look the value up | minor |
| L3-2 | `RuleSupport.java:44-46` and `ConfigValidationCommand.java:325,344` | a configured value with a control character, printed by the offline `--validate-config` | the `RuleSupport` Javadoc says `renderForMessage` / `renderForLog` are the single copy of the escaping; the offline printer carries its own (`?` for a control), so the claim is false and the two renderings differ | minor |
| L3-3 | `UpstreamBodyTraffic.java:58-65` | n/a | `arrivingWithin` is a public method with only `@param` / `@return` tags and no description, against the project's Javadoc rule | minor |

Re-read and holding by reading: the exactly-once release of the open-body mark (`UpstreamBodyTraffic.java:104-110`, the idempotent `end()`), the open-after-clocks order at `DispatchStage.java:1480-1489`, the three-state forwarded resolution, the trip logging (`SourceGate.java:380-397`), the sweep hand-off, the escaper's coverage, the failed-relay head list, `ApiSheriff-150` and `-151`.

Three further layer-3 reads of weakness class are reported to the operator in the session only and recorded in the git-ignored private notes (mechanism-level in the tracked follow-up spec).

**Layer 3 not shown by reading:** whether the open-body set stays bounded under sustained early-response uploads on every end path (`DispatchStage.java:1483-1488`); needs a run.

**Layer 3 verdict:** findings — 3 minor (L3-1 to L3-3) plus operator-only items; no blocker or major

### Conformance to the Project's Own Rules

| Rule source | Rule | Verdict | Evidence |
|-------------|------|---------|----------|
| CLAUDE.md § Logging | `CuiLogger`, `%s` only, exception first | conforms | `UpstreamCircuitBreaker.java:~284`; no `{}` added |
| CLAUDE.md § Logging | WARN `LogRecord`s unique and documented | conforms | `ApiSheriff-150` (`ConfigLogMessages`), `-151` (`ApiSheriffLogMessages`), rows `doc/LogMessages.adoc:112-113`; rows 141/143 stale (L3-1) |
| CLAUDE.md § Testing | JUnit 5, no Mockito/PowerMock/Hamcrest | conforms | none added; `HalfPemPair` uses `java.lang.reflect.Proxy` |
| CLAUDE.md § Dependency Management | no new dependency | conforms | no `pom.xml` in the diff |
| CLAUDE.md § Javadoc | public methods documented, `@since`, `package-info.java` | deviates | `UpstreamBodyTraffic.arrivingWithin` (L3-3) |
| CLAUDE.md § Pre-1.0 | no deprecation, no shim | conforms | no `@Deprecated`; the fallback and `provesSameOriginNavigation` removed in place |
| spec | ADR body ceiling 500 lines; ADR-0060 not grown | conforms | ADR-0060 517→517; ADR-0003/0064/0076/0078 at 170/188/423/295; no new ADR |
| CLAUDE.md § A key that parses must act | new key asserted | conforms | `quarkus.management.limits.max-header-size` asserted by `ShippedApplicationPropertiesTest` and the live 431 test |
| spec | every code change starts with a test that fails first | not_shown_by_reading | needs the plan's runs |

The orchestrator re-read these points at `00ebea64`: `CsrfDefence.java:205-253`, `ReservedPathHandler.java:160-185`,
`DispatchStage.java:1474-1495`, `UpstreamBodyTraffic.java:40-117`, `SourceGate.java:372-397`, `doc/LogMessages.adoc:101-113`,
`RuleSupport.java:40-48`, `ConfigValidationCommand.java:325,344`, `RouteTableBuilder.java:200-207`. The others (the
`UpstreamPoolSaturationTest`, `ManagementListenerTransportTest`, `ExtensionIssuerNamespaceGateTest` bodies, the ADR line counts,
`SourceLedger.java`, `ForwardedClientResolution.java`) were taken from the leaf and are recorded as read by it, not re-read.

## Metrics and Anomalies

- Tokens: 72,704,887 (the landing's `total_tokens`) — the largest plan of the epic.
- Duration: 173,580 s of wall-clock (about 48 h), including long waits on review and the merge path.
- Model: `claude-opus-5-5` with `effort=low` as the landing reports it; plugin and executor 0.1.2149.
- Anomalies: functional-review and code-review were closed by operator override (their last rounds produced only wording findings, which were fixed); 39 finalize Q-Gate findings and 8 CodeRabbit comments were fixed, most of them wording drift between the code and the prose restating it (four sweeps of one term); timing-bound tests flaked under foreign host load, and one flake was first attributed to a commit by a bisect under load, which the plan corrected in its decision log; the landing's `emit-landing` is done and `archive-plan` `pending`, normal for a landing emitted before archival.

## Routing and Merge Behavior

- Review: CodeRabbit reviewed (two review bodies: 6 and 1 actionable comments, one nitpick); Sourcery declined (the diff is over its 150,000-character limit). The landing's three review numbers are `unknown`. Re-read: `ci pr comments --pr-number 635`: 7 review threads, all resolved; the 6 rows still open are review-body and issue-comment rows, which cannot be resolved; `runbook_lane review-gate --pr-number 635` is **undecidable** on the merged head (`undecidable_read`: it could not read the CodeRabbit "Minor comments" section). `review_body_findings_total` and `review_body_findings_handled` are therefore **not re-verified**; two triage comments by the plan reply to both review bodies with dispositions.
- CI/merge: merged through the merge queue as `00ebea64`; the PR's 24 checks are green (`build / conclusion`, `integration-tests / conclusion`, both build-matrix jobs on Java 25 and 27, SonarCloud, CodeRabbit). The PR-attached post-merge run (Performance Benchmark, `Run Integration Benchmarks`) is `SUCCESS`. The main-branch Maven Build and Integration Tests runs for `00ebea64` were **not read**: the CI abstraction exposes no lookup by merge commit.
- Sonar: the landing claims `sonar_open_new_issues=0`; this analysis did not re-read the figure (the PR's SonarCloud check is green; a merged PR's count cannot be re-read, as for #623, #626, #627 and #633). Recorded as **not re-verified**, not as a `0` measured here.

## Reconciliation Actions

- [x] row `status` → `shipped` — `orchestrator queue --transition PLAN-SEC2-04 --status shipped`
- [x] row `pr` stamped — `orchestrator queue --set-row PLAN-SEC2-04 --field pr --value 635`
- [x] row `landing` stamped — `orchestrator queue --set-row PLAN-SEC2-04 --field landing --value landings/PLAN-SEC2-04.md`
- [x] row `plan_marshall_plan_id` stamped — `--value sec2-04-leftover-hardening`
- [x] epic.md narrative reconciled (queue annotation, decisions, Open Defect for the incomplete landing, Watch for the review-body findings)
- [x] re-grounding verdicts persisted on the spec (7 claims)
- [x] PLAN-SEC2-06 staged (the one follow-up plan the operator decided on 2026-11-18)
- [x] resume anchor updated in `resume_anchor.md`
- [x] `queue-view.md` regenerated

## Follow-Ups

- L2-1 to L2-3, L3-1 to L3-3, the planned wording items the landing lists, and the five minor leftovers of PLAN-SEC2-05 are collected in PLAN-SEC2-06 (staged), together with the operator-only reads as mechanism-level hypotheses to settle by test.
- The review-body findings of #635 are a Watch in `epic.md`.
- Candidate lessons: the single-sweep lesson and the timing-test attribution lesson are folded into PLAN-SEC2-06 as a constraint and a verify-first item; the owed architecture hint duplicates the second and is discarded (see the decision log).

## Addendum 2026-11-20 — review figures re-verified

The Routing section above recorded `review_body_findings_total` and `review_body_findings_handled` as **not re-verified**. They were re-verified afterwards, on the operator's request, by reading the CodeRabbit review bodies and the plan's triage replies and checking each finding against `main` at `00ebea64` (the review text is third-party; a read-only agent extracted it and every conclusion below was read in the merged code or docs by the orchestrator):

- **Review-body findings: 6, handled 5, deferred 1.** Fixed in the merged tree: the BFF-09 release-gate entry and matrix row (`doc/security-threat-model.adoc:3351,4899`), the proxy-setup text and ADR-0076 (`doc/user/edge-security.adoc:437`, `ADR-0076:235-236`), the ADR-0078 unresolved-source description (`:153-154`), and the expiry-sequencing test harness (`UpstreamPoolSaturationTest.java:385`, `VerdictAtTheExpiry`). Declined with a stated reason: deriving `CLIENT_ADDRESS_HEADERS` from cui-http (it publishes no header set; `ForwardedClientResolutionTest.HeaderSetInStepWithTheResolver` pins the constant at `:275`). Deferred to PLAN-SEC2-06's wording sweep: the threat-model sentence on who refuses `Sec-Fetch-Site: none`, which the review itself called correct as stated.
- **Inline-only threads: 6, all resolved**, each answered with a fix commit in `2f9c2fee`; not re-checked one by one.
- **The landing's `unknown` figures are therefore settled:** no false claim is recorded for them (the landing reported none), and the Watch and the Open Defect that tracked them are retired.
