# PLAN-V02-27: Simplify the Tests After the Edge/Config Refactor

epic: api-sheriff-0-2-0
workstream: WS-01

> Staged plan spec — one shippable unit of work, ready for `/plan-marshall` hand-off.
> The orchestrator EMITS the command below; it never launches the plan inline.
> Staged 2026-10-14 by operator decision: the refactor (`PLAN-V02-24`, `-25`, `-26`) is proven by the
> existing tests unchanged; once it has merged, a separate plan simplifies those tests.

## Objective

The refactor deliberately leaves the tests as they were — moved with the code, not rewritten — so that
"the existing suite still passes" proves the refactor kept behaviour. Afterwards the tests carry the old
structure: positional and hand-built fixtures, tests of one behaviour spread over classes that no longer
match the production code, near-duplicates that each grew one extra assertion. Simplify them against the
new structure without weakening what they prove.

## Deliverables

1. **Inventory first**, scoped to the refactored areas: `config/` resolution and model, `edge/` and its
   new sub-packages, `routing/`, `config/validation/`. List duplicate and near-duplicate tests, tests whose
   class no longer matches the production class they exercise, fixture code repeated across classes, and
   tests that construct far more configuration than the behaviour under test needs. Settle the scope with
   the operator at outline.
2. **Align test classes with production classes** (one test class per extracted class; the rule classes under `config/validation/rule/` already have theirs, so this is the `edge/` sub-packages)
   and move shared fixtures into a small set of builders, using the records' `@Builder`.
3. **Remove duplicates only after carrying their extra assertions into the survivor**, so the surviving
   test is at least as strict. Report removed pairs.
   Known item: `LimitRulesTest`'s class Javadoc says every case drives the full validator, but two
   `GetBodyOptInDefault` cases do not (#467 advisory).
4. **Prove nothing was lost:** JaCoCo per class before and after is identical or higher for every touched
   area; integration tests are touched only where the inventory shows a genuine duplicate. Report test
   counts and the unit-test run time before and after.
5. **Close the five test gaps `PLAN-V02-45` (#506), `PLAN-V02-46` (#513) and `PLAN-V02-23` (#515) left.** Each
   closes with a test that turns red when the behaviour is removed.
   - **GW-07 request-body status is PARTIAL** (`doc/security-threat-model.adoc` § `gw-07-request-body`,
     ~1519–1522). No test asserts two steps of `TerminalDispatch#dispatchGrpc` (`edge/terminal/TerminalDispatch.java:352`):
     that the dispatch function it passes to `relayGrpcCall` (~378) hands the watched request stream to the
     dispatch (`TerminalDispatchGrpcStreamIdleTest` supplies its own), and the fixed five-minute bound
     `dispatchGrpc` builds (`ResponseStage.GrpcStreamIdleBound.on(ctx.vertx())`, ~365). `GrpcProxyIT` runs both
     through real calls but asserts neither. Assert both, then update the status paragraph to what the tests justify.
   - **The late-timeout context-data cases drive the new methods directly.**
     `TerminalDispatchLateBodyTimeoutTest.lateTimeoutRetirementLeavesTheContextDataAlone` calls
     `ConnectionRetirement#recordRetirement` (`edge/end/ConnectionRetirement.java:155`) itself;
     `failedRelayAfterLateTimeoutClosesTheConnection` and `failedRelayAfterLateTimeoutWithWrittenHeadStillCloses`
     call `TerminalDispatch#retireFailedRelayConnection` (~773) themselves. Drive these cases through the
     production callers `retireAfterLateBodyTimeout` (~669–670) and `failRelay` (~744).
   - **No test reaches the per-route log latch of `ApiSheriff-24` and `ApiSheriff-25`.**
     `RejectionReporter#logRequestBodyDeadline` (`edge/error/RejectionReporter.java:239–247`) and
     `TerminalDispatch#logGrpcStreamIdleTimeout` (~405–414) log the first occurrence per route at INFO and every
     repeat at DEBUG; the tests assert only a first occurrence. Assert that a repeat on the same route stays at
     DEBUG and that one on another route is logged at INFO.
   - **The race case of `EdgeAdmissionTest` has no non-vacuity guard.**
     `EdgeAdmissionTest.admissionRacingTheDrainIsNeverAdmittedAfterTheDrainReturned` (new in #513) counts the
     rounds in which `tryAdmit` succeeded but asserts nothing about the count, so a run that refuses every round
     passes without exercising the order it pins. Assert a non-zero admitted count, then show the case red with
     the access order of `EdgeAdmission#tryAdmit` reverted locally (a throwaway revert, not committed).

   - **`HealthProbeTest` hard-codes `127.0.0.1:9000`.** `shouldReturnOneWhenPortRefusesConnection` and
     `shouldReturnZeroWhenPortAccepts` (`api-sheriff/src/test/java/de/cuioss/sheriff/gateway/HealthProbeTest.java`,
     `PROBE_PORT = 9000` at line 60) fail when a foreign process holds that port; it did during #515 and cost a
     triage round. Bind an ephemeral port on `127.0.0.1` for the accepting case and use `UnreachablePort` for the
     refusing case. If the probe must read a configured port, inject the allocated one.

Split guard: 5 deliverables. Keep each PR under 100 files and 150,000 diff characters; split by area
(`config`, `edge`, `validation`) if needed.

## Claim Labels

- OBSERVED (partial; the full inventory is deliverable 1): the validator split already left one test class per rule class (`config/validation/rule/*RulesTest.java`; `AuthRules`, `OidcClientRules`, `TerminalActionRules` and `TokenValidationRules` each split across two classes), while the edge split did not. Eleven `GatewayEdge*Test` classes plus `ReservedBodyCeilingTest` stay in `edge/`. `TerminalDispatch`, `RejectionReporter`, `ErrorResponseWriter`, `ConnectionRetirement`, `ResponseEnds`, `ReservedPathHandler`, `EdgeRequests` and `EgressClients` have no test class of their own (at `e70743f7`; `EdgeAdmission` gained `EdgeAdmissionTest` in #513). Duplicates are unconfirmed (verify-at-outline).
  - verdict: corroborated | checked_at: e70743f785d35264374d9d42a8a13a58cb961d32 | by: api-sheriff-0-2-0/analyze | rescoped: n/a | evidence: At e70743f7 EdgeAdmission has EdgeAdmissionTest (#513); the other eight listed classes still have no own test class; bullet refreshed

## Expected Surface

- HYPOTHESIS: `api-sheriff/src/test/java/de/cuioss/sheriff/gateway/config/`, `edge/`, `routing/`, `config/validation/` (verify-at-outline)
- HYPOTHESIS: `integration-tests/src/test/java/` only for genuine duplicates (verify-at-outline)
- OBSERVED: `api-sheriff/src/test/java/de/cuioss/sheriff/gateway/edge/terminal/` (`TerminalDispatchGrpcStreamIdleTest`, `TerminalDispatchLateBodyTimeoutTest`), `api-sheriff/src/test/java/de/cuioss/sheriff/gateway/edge/GatewayEdgeRequestBodyIdleTimeoutTest.java`, `api-sheriff/src/test/java/de/cuioss/sheriff/gateway/config/validation/rule/LimitRulesTest.java`, `api-sheriff/src/test/java/de/cuioss/sheriff/gateway/edge/admission/EdgeAdmissionTest.java`, `api-sheriff/src/test/java/de/cuioss/sheriff/gateway/HealthProbeTest.java`, `doc/security-threat-model.adoc` (GW-07 status); `integration-tests/src/test/java/de/cuioss/sheriff/gateway/integration/GrpcProxyIT.java` (read) — deliverables 3 and 5

## Dependencies and Sequencing

- Depends on: `PLAN-V02-24` (#465), `PLAN-V02-44` (#476), `PLAN-V02-25` (#487) and the whole validator split (`PLAN-V02-26` #463, `PLAN-V02-28` #467, `PLAN-V02-40` … `PLAN-V02-43` #485/#489/#502/#505) — all merged at `00293178`. The residue in deliverable 5 comes from `PLAN-V02-45` (#506, merged) and `PLAN-V02-46` (#513, `e70743f7`, merged; it edited `edge/upstream/`, `edge/admission/` and `doc/security-threat-model.adoc`).
- `PLAN-V02-23` (#515, `bd730f5a`) has landed; it added tests in the same areas (93 files, +7,355). Inventory from `bd730f5a`.
- Holds no feature plan; but a feature plan running at the same time in these areas collides on tests,
  so pair it only with plans off `config/` and `edge/`.

## Hand-Off Command

```text
/plan-marshall task="implement .plan/orchestrator/api-sheriff-0-2-0/plans/PLAN-V02-27-test-simplification.md" plan_id=plan-v02-27-test-simplification
```

## Security Findings

Fix a security finding inside this plan whenever possible; that is the preferred outcome. A finding
that is not fixed right away (out of scope, unconfirmed, deferred) stays out of git: it is never
written into a tracked file, a commit message, the PR title, body or review replies, a code comment,
the docs, or this plan's inbox and landing messages. Record it instead in
`.plan/local/security_notes.adoc` (git-ignored; create the file if it is missing and append, never
rewrite). Each entry carries the date, this plan's id, the location (file and line or component),
what was observed, and why it was not fixed. Tracked text may say only that a security note was
recorded locally, never what it says.

## Write-Boundary

The plan implementing this spec touches only its own repository source and tests. It creates and
edits NO file under `.plan/orchestrator/` other than its own `inbox/{sender}-{seq}` message, and
reports its outcome through its PR and that message.
