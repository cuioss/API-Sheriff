# Landing Analysis: PLAN-26 — Make the Logout Record True

epic: kidicap-gateway-requirements
workstream: WS-04
pr: #592 (https://github.com/cuioss/API-Sheriff/pull/592), squash commit `ad00807d`

> Landing record for one shipped plan. Source: inbox message `plan-26-logout-truth-up-001.md`
> (`landing-check`: complete, no missing keys). The message is a lead, not a fact: the PR state, the merge
> queue, the review threads, the post-merge checks, the diff and the merged code were read independently
> below. Analysed 2026-11-10 at `origin/main` `ad00807d`.

## Deliverable Fidelity vs Spec

The spec carried four live deliverables after its re-grounding (1 narrowed to two lines, 2 decided by the
operator on 2026-10-26 as "implement inside this plan", 4 and 5; deliverable 3, the cookie-mode residual, stayed
dropped as already done). The lane reports 6 deliverables, 6 done, and the functional review reports 6 met; how
the lane split four live deliverables into six is not explained by the spec (see L1-1).

| Deliverable (spec) | Verdict | Evidence |
|--------------------|---------|----------|
| D1 correct BFF-09, say exactly what is and is not revoked | shipped-as-specified | `doc/security-threat-model.adoc` BFF-09 at :2920: Control line :2933, Status :3006, the not-revoked list :3011-3022, the residual paragraph, and the summary row :4490 (revokes the refresh token after local destruction, a failure latched as `ApiSheriff-147`; COVERED (server) with the not-revoked caveat). `doc/variants/02`, `03`, `doc/user/bff-session.adoc`, `bff-cookie.adoc` and `doc/configuration/authentication-and-oidc.adoc` :584-596 changed with it. Two small gaps, L2-5 |
| D2 bind RFC 7009 refresh-token revocation on logout | shipped-as-specified | `BffRuntimeProducer.buildLogoutEndpoint` now binds `revokeOnLogout` (:1316-1324, bound at :1474) over the shared `revokeRefreshToken`; the no-op lambda is gone. `SessionStore.destroyById` and `SessionBinding.destroy` return the destroyed record, `LogoutEndpoint` :144 revokes `destroyed.orElse(session)`. Failure semantics chosen from the spec's options: a latched WARN `ApiSheriff-147` plus a DEBUG line with exception types only (`RpInitiatedLogout.java` :249-262). ADR-0080 records the decision |
| D3 cookie-mode residual | dropped (as specified) | already an accepted residual with bound and mitigations before this plan; the landing rewrites that paragraph only because the seam stopped being a no-op |
| D4 ADR: problem+json extension-member rule | shipped-as-specified | `doc/adr/0081-A_problem_json_extension_member_…adoc` (187 lines); it records that `step_up_url` echoes the request query back to the client |
| D5 ADR: reserved-path boot rule | shipped-as-specified (amendment option taken) | `doc/adr/0018-…adoc` amended (492 lines, body about 475, under the 500 ceiling); it matches `ReservedPathRules` and records that the callback, logout, logout-return and back-channel paths are not canonical-form checked |

Unplanned or undeclared, all explained by the deliverables: five session-binding files changed signature
(`SessionStore`, `SessionBinding`, `ServerSessionBinding`, `CookieSessionBinding`, `InMemorySessionStore`) because
D2 needs the destroyed record; `DefaultTrustSourceAudit.java` (Javadoc only, it called the logout revocation a
no-op); `doc/LogMessages.adoc` (the new WARN row); the compile follow-through in three unrelated test files.
Realized footprint: 31 files (+2189/-201). `doc/LogMessages.adoc`, the five binding files and ADR-0081 were not in
the spec's Expected Surface.

## Three-Layer Analysis

The landing was read on three layers by one read-only leaf (`execution-context-level-3`, the `orchestrator.analyze`
surface), by reading only: no build, no test run, no write. The orchestrator re-read the points recorded below as
`checked` at their `file:line` in the merge tree `ad00807d` and records which it re-read. Anything only a run could
show is tagged `not shown by reading`.

- **Dispatch:** one read-only leaf — reason: reading merged code across the producer, the logout engine, the
  endpoint, three session bindings, three ADRs and the threat model is the depth the dispatch rule reserves for a
  leaf, and it keeps this landing's read out of this drain's context.
- **Verdict:** layer 1 findings (1, minor); layer 2 findings (5, minor); layer 3 findings (4, minor, one of them not
  shown by reading). No blocker, no major.
- **Re-read by the orchestrator at `file:line` on `ad00807d`:** `RpInitiatedLogout.java` :56-60, :117, :245-262;
  `LogoutEndpoint.java` :138-158; `BffRuntimeProducer.java` :1312-1326 and :1470-1478; `BffLogMessages.java` :150-156
  and :388-392; `LogoutEndpointTest.java` :488-494; `security-threat-model.adoc` :3079 and :4490;
  `variants/03-bff-cookie.adoc` :298; ADR-0080 for a link to ADR-0071 (absent). **Not re-read, recorded as the
  leaf's unverified lead:** the line numbers in `SessionStore`, the three binding classes and the three test classes
  other than the two above, the ADR line counts and the prose grep of the added ADR lines, and the
  `memoize` lock at `BffRuntimeProducer` about :1530. The leaf cited the BFF-09 summary row at :4290; the row is at
  :4490, so that line number was wrong and the claim was re-checked there.

### Layer 1 — Specified

| Finding | Claim | Verdict | Evidence (file:line) |
|---------|-------|---------|----------------------|
| L1-1 (minor) | The spec's own text disagrees with itself: D1's body says the gateway "does not revoke the access or refresh token" while D2 binds refresh revocation, reconciled only in D2's last bullet; the banner counts "3 live / 3 dropped", the split guard says 4 live, the filing says 5; the lane's `deliverables_total=6` matches none | checked, re-read | `plans/PLAN-26-logout-truth-up.md` :97-102, :54-56, :156 |
| none | The premises held at `main`: the seam was a no-op, BFF-09 over-claimed, ADR ordinal 0080 was free, no ADR covered either rule | checked (the three Claim Labels were already `corroborated` or `contradicted` at `2200ddb2`) | spec Claim Labels |
| none | Deliverable 3 was already done | checked | the accepted-residual paragraph existed before this plan |

**Layer 1 verdict:** findings — 1 (minor). The spec is shipped, so no correction is owed beyond this record. No
spec-claim row corresponds to L1-1 (it is a contradiction between deliverable prose and a banner, not a Claim
Label), so no `corpus set-verdict` was persisted; the three existing verdicts stand.

### Layer 2 — Implemented

| Deliverable | Code trace | Test that can fail | Unexplained file / unborne claim | Verdict |
|-------------|------------|--------------------|---------------------------------|---------|
| D2 binding | `BffRuntimeProducer.java` :1316-1324, :1474; `LogoutEndpoint.java` :138-158 | `BffRuntimeProducerTest.shouldRevokeThroughTheProducedLogout` (:4057) goes red if the no-op lambda returns; `shouldRevokeTheRefreshTokenOnLogout` (:3935) goes red if the helper call is dropped | none; claims (a) token held at destruction, (b) failure never blocks and logs types only, (c) `ApiSheriff-147` unique across catalogues are all borne out | checked |
| D2 integration proof | n/a | `BffLogoutIT` asserts the client is detached from the user's Keycloak session after logout (:114-170) | whether Keycloak does detach and the IT is green on the native stack needs a run | not_shown_by_reading |
| D2 cookie mode | `CookieSessionBinding.destroy` returns the presented record | `CookieSessionBindingTest` and a faked seam | no cookie-mode runtime or IT case observes the revocation request at the IdP | checked (L2-1) |
| D4 | `GatewayException` STANDARD_MEMBERS, refuse-and-copy constructor; `SessionAuthenticationStage` :377-382 | n/a (record) | none | checked |
| D5 | amendment matches `ReservedPathRules` | n/a (record) | none | checked |

Findings:

- **L2-1 (minor).** Cookie-mode revocation, claim (d), is shown only through a faked seam and the binding's unit
  tests. If the cookie-mode wiring were broken, no runtime or IT case would go red.
- **L2-2 (minor).** `LogoutEndpointTest.java` :491, `shouldRevokeNothingWhenTheSessionWasGoneAtDestruction`, asserts
  the resolved token IS revoked, so its name says the opposite of its assertion. The threat model cites the
  misnamed method at :3079 ("which keeps its original name"), so a stale name now sits in a security record.
- **L2-3 (minor).** `RpInitiatedLogout.java` :58 states "no other path that ends a session revokes anything".
  `TokenRefreshCoordinator` ends sessions and revokes best-effort (about :106-117, :765-781). `LogoutEndpoint`'s own
  list of racing paths is accurate; the Javadoc overstates.
- **L2-4 (minor).** `BffLogMessages.java` :154-155 extends a hand-written WARN range inventory by hand; the class
  Javadoc says allocation is enforced by `LogMessagesCatalogueTest`. `RpInitiatedLogoutTest` :145 asserts the WARN
  only through `resolveIdentifierString()`, so a renumber of 147 stays green while the docs, ADR-0080 and BFF-09
  quote the number.
- **L2-5 (minor).** Two small documentation gaps. The BFF-09 summary row :4490 names a failed revocation in its
  control column but the not-revoked list omits a failed revocation and a session without a refresh token, which
  the section names. `variants/03-bff-cookie.adoc` :298 says "a retained cookie ends at its next refresh" without the
  "once the revocation succeeded" and "refresh enabled" conditions the prose carries. ADR-0080 and the ADR-0018
  amendment do not link ADR-0071, which the spec asked for.

**Layer 2 verdict:** findings — 5 (minor); 1 point not shown by reading (the native IT).

### Layer 3 — Correct

| Defect | File:line | Triggering input | Effect | Severity |
|--------|-----------|------------------|--------|----------|
| L3-1 | `RpInitiatedLogout.java` :251 | one process-wide latch (`revocationFailureReported`, :117) covers a metadata-discovery failure, a transport failure and a non-success answer; a transient discovery blip at the first logout | consumes the only WARN; a later persistent failure (for example a 4xx from the revocation endpoint) stays at DEBUG for the process lifetime. The spec offered one-per-reason (the `ApiSheriff-132` precedent). ADR-0080 says an operator who sees 147 must assume later logouts fail, but not that a different cause can mask a persistent one | minor |
| L3-2 | `BffRuntimeProducer.java` :1316-1324, :1474 | cookie mode is stateless, so `CookieSessionBinding.destroy` always returns the presented record; a still-valid cookie replayed on logout inside its validity window | one synchronous revocation POST to the IdP per replay (the leaf reads a bound of about 15 s per request), and since RFC 7009 answers success for an already revoked token the replay never trips the latch; ADR-0080 does not discuss the call amplification; a per-source limit upstream is not shown here | minor |
| L3-3 | `LogoutEndpoint.java` :138-146 | two concurrent logouts of one server-mode session | the first destroy returns the record, the second returns empty and falls back to the resolved record, so one session yields two revocation POSTs; harmless (RFC 7009 section 2.2), no leak, but no test or ADR sentence names it | minor |
| L3-4 | `BffRuntimeProducer.java` :1323 and :1475 | a discovery outage during logout | `metadata.get()` is called twice per logout and the leaf reads the memoizing supplier as locking around the network call and caching nothing on failure, so concurrent logouts would queue behind the lock; ADR-0080 and `bff-session.adoc` state a per-request bound, not queueing across concurrent logouts | minor, not shown by reading |

**Layer 3 not shown by reading:** L3-4 (the lock at about :1530 was not re-read; the 5 s connect and 10 s read
defaults the docs cite are token-sheriff internals), concurrency races, native-image behaviour, CI results and the
pass or fail of the new tests.

No token leaks: the WARN is parameterless and logged without a throwable, the DEBUG line carries exception class
names only, the throwable never reaches the pre-existing `LOGGER.debug(initiationFailure, ...)` at
`LogoutEndpoint.java` :156 because revocation failures are swallowed inside `initiate`, and tests assert the
refresh token is absent from every captured record including a token-bearing cause chain and a cycle. The
revocation runs on a virtual thread after the local destroy, so a slow IdP delays only the answer, never the session
end.

**Layer 3 verdict:** findings — 4 (minor), one of them not shown by reading. All four are reported to the operator
and none is fixed inline.

### Conformance to the Project's Own Rules

| Rule source | Rule | Verdict | Evidence |
|-------------|------|---------|----------|
| CLAUDE.md | Pre-1.0: no `@Deprecated`, remove directly | conforms | none in the diff; the no-op lambda is gone; `destroyById` and `destroy` changed signature with no shim |
| CLAUDE.md | Logging: `CuiLogger`, `%s` only, exception first, `LogRecord` for WARN | conforms | `LOGGER.warn(BffLogMessages.WARN.LOGOUT_REVOCATION_FAILED)` and `%s` DEBUG lines; the WARN carries no throwable by design |
| CLAUDE.md | No secrets in logs | conforms | parameterless WARN, class names only at DEBUG, tests prove absence |
| CLAUDE.md | Document log messages in `doc/LogMessages.adoc`, WARN 100-199 | conforms | row `ApiSheriff-147` matches the Java template word for word; no executable test ties the adoc row to the code |
| CLAUDE.md | JUnit 5, AAA, no Mockito, PowerMock or Hamcrest | conforms | hand-written fakes and lambda seams |
| CLAUDE.md | A key that parses must act: a test goes red if the binding is deleted | conforms | `shouldRevokeThroughTheProducedLogout` and `BffLogoutIT`; the cookie-mode wiring only with a faked seam (L2-1) |
| CLAUDE.md | Javadoc, `@since`, thread-safety, `package-info.java` | conforms | `logout/package-info.java` updated; `LogoutEndpoint.logout` has no `@since` |
| CLAUDE.md | No OpenRewrite markers | conforms | none in the diff; the new catch keeps its `cui-rewrite:disable` with a rationale |
| CLAUDE.md | Sonar: fix by default, suppress in code with a rationale | not_shown_by_reading | not readable here; see the Sonar line below |
| `doc/development/adr-writing.adoc` | 500-line body ceiling, skeleton, no PR numbers, SHAs or dates, one ordinal per record | conforms | ADR-0018 about 475, ADR-0080 about 260, ADR-0081 about 168; no duplicate ordinal in `doc/adr` (re-read by the orchestrator); `AdrRecordLengthContractTest` not run |
| `doc/development/adr-writing.adoc` | Term list and short-sentence rule | not_shown_by_reading | stylistic; several sentences exceed about 20 words |

## Metrics and Anomalies

- Tokens: 45,599,308 reported; the lane's per-phase split was not carried.
- Duration: the lane reports `total_wall_seconds` 101249.0, about 28.1 hours. **Unverified**: this plan was recorded
  as running only minutes before its landing message arrived, so the figure cannot be reconciled with this
  session's own clock. It is kept as reported and is not used for anything. Model `claude-sonnet-5-5`, effort
  `low`, plugin and executor `0.1.2088`.
- Anomalies: two rebases onto `origin/main` because `main` moved three times during finalize (#590 and #591, then
  #589 with a real `doc/LogMessages.adoc` conflict). **#589 took `ApiSheriff-146` while this plan was open** (read
  from the landing narrative and from `ApiSheriffLogMessages.java` :328 at `ad00807d`), so the
  logout WARN landed as `ApiSheriff-147` and the plan renumbered its record, tests, ADR-0080, the BFF-09 entry and
  the operator docs in one commit. Each rebase rewrote the change ledger's shas, so later review rounds read every
  deliverable as `declared_only`; the lane ran full rounds on the live tree and all closed with zero blocking
  findings.

## Routing and Merge Behavior

- Review: CodeRabbit posted one review body (6 actionable comments, 1 review-body finding, handled per the lane);
  Sourcery was rate-limited and never reviewed (optional bot). The review threads: 6 total and none resolvable
  unresolved; the three rows `ci pr comments --unresolved-only` still lists all carry an empty `thread_id`
  (`review_body` and two `issue_comment`), which cannot be resolved. Matches the lane's `unresolved_review_threads=0`.
- CI/merge: merged through the merge queue as a squash; the merge-group run `42847220905` concluded `success`; the
  PR's checks read green. The queue offers no per-enqueue commit message, so the squash carries the PR title plus
  the platform default body.
- Post-merge: the PR-attached Performance Benchmark job (`Run Integration Benchmarks`, 1216 s) is `SUCCESS`. The
  main-branch Maven Build run for `ad00807d` (where a snapshot-deploy failure would appear) is **not readable
  through the CI abstraction**, so it stays an unverified lead (a Watch).
- Sonar: the lane reports `sonar_open_new_issues=0`; the PR's `build / sonar-build` job was `SUCCESS`. The open-issue
  count itself was not read here, so it is `unknown` for this analysis, not `0`.

## Reconciliation Actions

- [x] row `status` → `shipped` — `orchestrator queue --transition PLAN-26 --status shipped`
- [x] row `pr` stamped — `orchestrator queue --set-row PLAN-26 --field pr --value "#592"`
- [x] row `landing` stamped — `orchestrator queue --set-row PLAN-26 --field landing --value landings/PLAN-26.md`
- [x] row `plan_marshall_plan_id` stamped `plan-26-logout-truth-up` (already stamped when the plan was recorded running)
- [x] epic.md narrative reconciled: queue annotation for PLAN-26, a Decisions entry, the four layer-3 defects and the
  lane's two out-of-scope gaps recorded as Watches, the next free ADR ordinal and WARN id corrected
- [x] PLAN-26's `## Expected Surface` corrected for the footprint the gate did not predict (see Follow-Ups)
- [x] resume anchor updated in `resume_anchor.md`
- [x] `queue-view.md` regenerated

## Follow-Ups

- **Layer-3 defects L3-1 to L3-4**, all minor, none blocking: recorded together as one Watch in `epic.md`. The
  cheapest worth a plan is L3-1 (one latch per reason, as `ApiSheriff-132` does) together with L3-2 (name or bound
  the cookie-mode replay call). Staged only on the operator's word.
- **Layer-2 advisories L2-2 to L2-5** (a misnamed test cited from the threat model, a test that pins no identifier, a
  hand-kept WARN inventory, an over-broad rationale sentence, two documentation gaps, a missing ADR-0071 link):
  recorded as one Watch. They are one small documentation-and-test pass if the operator wants it.
- **Out-of-scope gaps the lane's reviews found:** the boot does not check the callback, logout, logout-return and
  back-channel-logout reserved paths for canonical form (ADR-0018 now records this), and `step_up_url` echoes the
  request's own query back to the client (ADR-0081 records this). The second bears directly on **PLAN-20**, which
  builds on the same problem+json step-up answer; it is recorded as a Watch and carried into PLAN-20's re-grounding.
- **Allocator collision.** PLAN-26 and the in-flight #589 both took WARN id 146 from the same free list (the spec's
  own banner said 146 was next free). PLAN-26's declared surface did not name `doc/LogMessages.adoc`, so the
  disjointness gate had nothing to compare; I did not read #589's spec, so I record only PLAN-26's side. Recorded as
  a Watch; the next free values are ADR **0082** and WARN **148**, to be re-derived at write time.
- **Unverified leads:** the main-branch Maven run for `ad00807d`; the lane's wall-clock figure.
