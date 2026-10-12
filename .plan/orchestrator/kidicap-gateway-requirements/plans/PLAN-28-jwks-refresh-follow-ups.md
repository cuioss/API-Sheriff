# PLAN-28: What PLAN-24 Left Open — the Retry Path's Missing Guard, Three Record Gaps, and a Flaky Edge Test

epic: kidicap-gateway-requirements
workstream: WS-01

> Staged plan spec — one shippable unit of work, ready for `/plan-marshall` hand-off.
> Lives at `plans/PLAN-28-jwks-refresh-follow-ups.md` and is queued as one row file,
> `queue/PLAN-28.json`, in the epic ledger. The orchestrator EMITS the command below; it never launches
> the plan inline. This spec is SELF-SUFFICIENT: the emitted command is a one-line pointer and carries no
> brief.
> Staged 2026-10-12 from inbox message `jwks-unknown-kid-refresh-012.md` and from PLAN-24's landing
> (`landings/PLAN-24.md`, message `jwks-unknown-kid-refresh-015.md`).

## Objective

PLAN-24 (#430) bounded the key-rotation window. Its security re-check then found that the fix it made in
one method of `RetryingJwksLoader` was not made in the sibling method, and its landing lists several
smaller items nobody filed anywhere. This plan closes them, so that they do not become permanent by
default.

It is a small plan. One deliverable changes production code in `auth/`; one diagnoses a test that fails
intermittently on branches that do not touch it; the rest are records.

## Source

Inbox `jwks-unknown-kid-refresh-012.md` (candidate lesson, category bug, "NOT fixed in this plan") and
the Residue section of the landing message `jwks-unknown-kid-refresh-015.md`. Promoted lessons
`2026-10-12-16-012` (the flaky test) and `2026-10-12-16-013` (the approximated publish rule) carry the
background.

## Deliverables

1. **Guard `RetryingJwksLoader.runRetry` against a throwable it does not anticipate.** `runRefresh` now
   releases its state in a `finally` block on every path; `runRetry` catches three exception types and
   has no `finally`. Its own comment says what an escaping throwable costs: it "would silently end the
   retry sequence" on the scheduler thread. Give `runRetry` the same guarantee, and add a test in which
   the delegate factory throws an unanticipated throwable and a later retry still starts.
   ⚠ The sender described this as "in-flight state not released" and did not analyse the consequence.
   Settle first what state `runRetry` actually holds and what stops when it escapes (see Claim Labels);
   fix that, not the description.
2. **Leave a log record when `runRefresh` ends on an unanticipated throwable.** It releases the refresh
   and says nothing, so an operator cannot tell that refreshes are failing. Decide the level; a WARN or
   ERROR needs a `LogRecord` and a row in `doc/LogMessages.adoc`, and must not log the key id.
3. **Decide the clock for the refresh window and the grace deadline, and record the decision.** Both are
   measured on the wall clock, so a clock step shifts them by the size of the step. The triage accepted
   this as low risk on its own judgement; the operator has not. Either move the two deadlines to a
   monotonic source, or record in ADR-0063 why the wall clock stays. If the change is larger than the two
   deadlines, STOP and file a finding.
4. **Close the documentation gaps PLAN-24 reported and did not close.** Check the validation-rules summary
   in `doc/configuration.adoc` and the validation matrix in `doc/security-threat-model.adoc` for rows for
   the three new keys (`refresh_interval_seconds`, `key_rotation_grace_period_seconds`,
   `unknown_kid_refresh_window_seconds`) and add what is missing; check the sentence in
   `doc/configuration.adoc` saying BFF variants do not read `token_validation` against the code and
   correct it if stale; clear the AsciiDoc list-format warnings on ADR-0063 and ADR-0011.
5. **Dispose of the three simplify findings left unactioned**: the `@param` line on `buildDelegate`, the
   static-versus-instance shape of that helper, and one near-identical test pair in
   `RetryingJwksLoaderTest`. Fix or record why not, one line each.
6. **Diagnose `GatewayEdgeFramingCorpusTest`.** It failed twice during PLAN-24 on a branch that touches
   nothing under `gateway/edge`: `soleTransferEncodingNotExactlyChunked` answered `502` in CI on Java 26,
   and `bodylessMethodNeverCarriesABodyUpstreamOnHttp2[2]` hit its 30 s timeout locally; both were green
   on re-run. The class guards request framing, so a habit of re-running it until green would hide a real
   regression. Find the cause. If it is the test's own setup (a listener not ready, a connection not
   released), fix the test. ⛔ If it is a defect in the gateway's edge, STOP and file a finding to the
   epic inbox: that is its own plan.

**Not in this plan:** replacing the "holds the triggering key id" publish rule with an exact comparison of
key ids. That needs an accessor token-sheriff 0.9.6 does not have; the upstream request is the
operator's to file, and the epic carries it as a Watch.

Split guard: 6 deliverables — within the operator-authorized 12.

## Claim Labels

- OBSERVED: `runRefresh` releases `refreshInFlight` in a `finally` block and `runRetry` has no `finally`; `runRetry` catches `GatewayException`, `IllegalArgumentException` and `IllegalStateException` only, and its comment states that an escaping failure "would silently end the retry sequence" — read at `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/auth/RetryingJwksLoader.java` § `runRetry`, `runRefresh`, at `c013781d`
  - verdict: corroborated | checked_at: c013781d | by: kidicap-gateway-requirements/analyze | rescoped: n/a | evidence: RetryingJwksLoader at c013781d: runRetry (366) has a three-type catch and no finally; runRefresh (439) releases refreshInFlight in finally (458-466)
- OBSERVED: `GatewayEdgeFramingCorpusTest` exists at `api-sheriff/src/test/java/de/cuioss/sheriff/gateway/edge/GatewayEdgeFramingCorpusTest.java` — read by listing the tree at `c013781d`
  - verdict: corroborated | checked_at: c013781d | by: kidicap-gateway-requirements/analyze | rescoped: n/a | evidence: listed in the tree at c013781d
- HYPOTHESIS: `runRetry` holds state of its own that an escaping throwable leaves set, as the sender's wording implies. The orchestrator's read found no in-flight flag in `runRetry` itself; what is lost may be only the retry sequence. Confirm/refute at `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/auth/RetryingJwksLoader.java` § `runRetry`, `scheduleRetry`, `load` (verify-at-outline)
- HYPOTHESIS: the validation-rules summary and the threat model's validation matrix lack rows for the three new keys, and a sentence in `doc/configuration.adoc` says BFF variants do not read `token_validation` — taken from the landing message, which says these "were not checked". The orchestrator's search for that sentence did not find it. Confirm/refute at `doc/configuration.adoc` § the validation-rules summary and `doc/security-threat-model.adoc` § the validation matrix (verify-at-outline). ⛔ If nothing is missing, close deliverable 4's rows as already done.
- HYPOTHESIS: both failures of `GatewayEdgeFramingCorpusTest` come from the raw-socket test setup and not from the gateway — the sender's own guess, undiagnosed. Confirm/refute at `api-sheriff/src/test/java/de/cuioss/sheriff/gateway/edge/GatewayEdgeFramingCorpusTest.java` § the two named methods and the upstream fixture they share (verify-at-outline)
- Verify-first clause: settle deliverable 1's consequence before writing its fix — reproduce in a unit test what happens to later retries when the factory throws an unanticipated throwable. The fix and the test follow from that observation.

## Expected Surface

- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/auth/RetryingJwksLoader.java`
- OBSERVED: `api-sheriff/src/test/java/de/cuioss/sheriff/gateway/auth/RetryingJwksLoaderTest.java`
- OBSERVED: `api-sheriff/src/test/java/de/cuioss/sheriff/gateway/edge/GatewayEdgeFramingCorpusTest.java`
- OBSERVED: `doc/adr/0063-An_unknown_key_id_triggers_one_bounded_JWKS_refresh_per_issuer_and_the_refresh_cadence_is_configurable.adoc`
- OBSERVED: `doc/adr/0011-gatewayyaml_exposes_JWKS_trust_and_egress_as_neutral_names_bound_by_the_deployment.adoc`
- OBSERVED: `doc/configuration.adoc`, `doc/security-threat-model.adoc`
- HYPOTHESIS: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/config/ConfigLogMessages.java` and `doc/LogMessages.adoc` — only if deliverable 2 resolves to a WARN or ERROR record. PLAN-24's unknown-key-id WARN lives in `ConfigLogMessages`, not in `ApiSheriffLogMessages` as its spec expected (verify-at-outline)
- HYPOTHESIS: `api-sheriff/src/test/java/de/cuioss/sheriff/gateway/edge/` — other files of the edge test fixture, if the flake's cause is in a shared helper (verify-at-outline)

## Dependencies and Sequencing

- Depends on: PLAN-24 — landed (#430).
- Overlaps with: PLAN-20, PLAN-21, PLAN-26 and PLAN-27 on `doc/configuration.adoc`,
  `doc/security-threat-model.adoc` and `doc/adr/`. No main-source overlap with any of them:
  `RetryingJwksLoader` is touched by no other staged spec.
- Adds no integration suite, so it does not lengthen the native integration job.
- **If a test fails against shipped behaviour outside deliverable 1, STOP and file a finding** to the
  epic inbox rather than widening this plan.

## Hand-Off Command

```text
/plan-marshall task="implement .plan/orchestrator/kidicap-gateway-requirements/plans/PLAN-28-jwks-refresh-follow-ups.md"
```

## Write-Boundary

The plan implementing this spec touches only its own repository source and tests. It creates
and edits NO file under `.plan/orchestrator/` other than its own
`inbox/{sender}-{seq}` message — the orchestrator owns every other ledger write — and reports
its outcome through its PR and its inbox message. The inbox exception's qualifiers and the
sole sanctioned write mechanism are stated in
`persona-plan-orchestrator/standards/orchestration-model.md` § Ledger Write-Boundary.
