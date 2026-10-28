# PLAN-V02-49: Simplify the Tests, Part 2 — `config/`, `config/validation/`, `routing/` and the Edge Residue

epic: api-sheriff-0-2-0
workstream: WS-01

> Staged plan spec — one shippable unit of work, ready for `/plan-marshall` hand-off.
> The orchestrator EMITS the command below; it never launches the plan inline.
> Staged 2026-10-25 from the landing of `PLAN-V02-27` (#520, `6b8d88f4`), whose operator-agreed scope covered
> `edge/` and the five test gaps. This plan takes the follow-ups that landing named (FU-1 and FU-2, merged
> because both are thin) and the edge residue the landing analysis found.

## Objective

`PLAN-V02-27` simplified the `edge/` tests and closed five test gaps. The areas it left are `config/validation/`
(23 classes), `config/` (14) and `routing/` (4). They carry the same defects it removed from `edge/`:
near-duplicate tests that each assert the same behaviour through a different layer, and fixture code repeated
across classes. This plan removes the duplicates **only after carrying their extra assertions into the survivor**,
moves the repeated fixtures into `testsupport/ConfigFixtures` and one validation assertion helper, and closes the
residue the edge pass left. Nothing a test proves is lost.

## Deliverables

The inventory behind this plan is `work/test-inventory.md` of the archived plan
`.plan/local/archived-plans/2026-10-25-plan-v02-27-test-simplification/` (git-ignored, local to the operator's
machine): §2.4–2.8 and §6 list the findings. Re-derive every finding at outline: the tests moved on after
`6b8d88f4`, and a finding that no longer holds is dropped, not acted on.

1. **`config/validation/`.** Remove the 17 confirmed duplicates (V1–V17 in the inventory) after carrying each
   one's extra assertion into its survivor, and examine the 10 candidate groups. Adopt `ConfigFixtures` for the
   repeated helpers (`topologyWith` and `assertHasError` in 15 classes, anchor helpers in 6, asset helpers in 2,
   `singleRefusalAt` in 3 signatures, `issuer`/`issuerName` in 2, `repoRoot` in 3 contract tests) and add one
   validation assertion helper. Correct the `LimitRulesTest` class Javadoc: it says every case drives the full
   validator, but `GetBodyOptInDefault.shouldResolveOmittedKnobToFalse` and `.shouldResolveDeclaredKnob` call
   `SecurityDefaultsConfig.effectiveAllowGetWithContentLengthBody()` directly (#467 advisory). Move the two cases to
   `ConfigModelContractTest` or correct the sentence, and say which and why.
2. **`config/` and `routing/`.** Remove the confirmed duplicates C1, C2 (five tests), C3, R1 and R2, and examine the
   candidates C4–C17 and R3–R5; move their helpers to `ConfigFixtures`. The `RouteTableBuilderTest` lookup cases
   belong in `routing/` (no `RouteTable` test class exists). The model builders repeated in `PolicyBlockTest`,
   `RouteTableBuilderTest`, `ConfigBootPipelineTest`, `TopologyResolverTest` and `ConfigModelContractTest` move to
   `ConfigFixtures`.
3. **The edge residue of `PLAN-V02-27`.**
   - Seven classes the spec of `PLAN-V02-27` named still have no test class named for them: `TerminalDispatch`,
     `RejectionReporter`, `ConnectionRetirement`, `ResponseEnds`, `ReservedPathHandler`, `EdgeRequests`,
     `EgressClients`. For each, either add the class that holds its tests, or state that an existing class
     (`TerminalDispatch*Test`) is its home. Do not add a class that only restates another.
   - `TerminalDispatchLateBodyTimeoutTest`: the HTTP/2 counterparts and the marked-request case still call
     `ConnectionRetirement#recordRetirement` and `TerminalDispatch#retireFailedRelayConnection` directly, against
     fakes. Drive them through `retireAfterLateBodyTimeout` and `failRelay` like the HTTP/1.x cases, or state why a
     case cannot be.
   - `ResponseStage.GrpcStreamIdleBound#timeoutMillis()` is a public production method added only so a test can read
     the bound. Decide whether a package-private seam or an existing observable covers it; remove the public method
     if so. The pre-1.0 rules allow the removal.
   - `WebSocketRelayStageTest.PreUpgradeFailureEnd` (added by `PLAN-V02-04`, #521) covers the failed upstream dial
     only. The relayed upstream upgrade refusal passes through the same `ResponseEnds.endResponse` seam and has no
     test. Add it, and show it red against a bare `response.end()`.
   - `BffRuntimeProducerTest.theViewIsTheOneTheRuntimeRecordsInto` (added by #530; a code-review advisory) cannot fail
     for the recording link it names. Make it fail when the link is cut, or remove it; show it red against the cut.
   - **GW-07 request body stays PARTIAL.** The operator held the public `dispatchGrpc` hand-over of the five-minute
     bound as the named residual. Do not close it and do not change the status paragraph.
4. **Prove nothing was lost.** JaCoCo per class before and after is identical or higher for every touched class. The
   coverage profile appends to one `jacoco.exec`, so measure each figure from a clean tree:
   `clean verify -Pcoverage -pl api-sheriff -am` (lesson `2026-10-25-22-001`). Keep any comparison script the plan
   relies on inside the plan directory. Report test counts and the unit-test run time before and after; the
   landing of `PLAN-V02-27` gave no run time.

Split guard: 4 deliverables, one PR. The footprint is about 41 test classes plus `ConfigFixtures` and one helper
(FU-1 touches 23 files, FU-2 up to 18), well under the 100-file bot cap. If the diff passes 150,000 characters,
cut by area (`config/validation/`, then `config/` and `routing/`), not inside a class.

## Standing Constraints

- **Remove a duplicate only after its extra assertion lives in the survivor,** so the survivor is at least as strict.
  Report every removed pair with the assertion carried. A planned duplicate removal is declared in the PR body next
  to the survivor, because the finalize `weakened_test` check cannot tell a planned removal from a loss (it raised 11
  blocking rows on `PLAN-V02-27` that the verifier refuted each round).
- **Every shared fixture uses the records' `@Builder`** and the CUI test generator where it applies.
- **Use the simple type name wherever the file imports the type.** `verify -Ppre-commit` rewrites a fully qualified
  name into the simple one, dirties the tree after a green gate and forces a second gate run.
- A test that guards behaviour turns red when the behaviour is removed: show it red once for any case this plan
  rewrites rather than only moves.
- **Keep `src/main` untouched except for the deliverable 3 accessor decision.**

## Claim Labels

- OBSERVED: `PLAN-V02-27` (#520, `6b8d88f4`) acted on `edge/` and the five test gaps; the `config/validation/`,
  `config/` and `routing/` duplicates and the `LimitRulesTest` Javadoc fix were deferred — landing
  `plan-v02-27-test-simplification-009`, `landings/PLAN-V02-27.md`
  - verdict: corroborated | checked_at: 6b8d88f4ac24a32b55f69f947342b115cd2bb20a | by: api-sheriff-0-2-0/analyze | rescoped: n/a | evidence: the merged diff 68481922..6b8d88f4 touches edge/, testsupport/ and four production files; LimitRulesTest and every config/ and routing/ test class are absent from it
- OBSERVED: no test class named for `TerminalDispatch`, `RejectionReporter`, `ConnectionRetirement`, `ResponseEnds`,
  `ReservedPathHandler`, `EdgeRequests` or `EgressClients` — `git ls-tree -r --name-only 6b8d88f4 api-sheriff/src/test`
  finds no `{Class}Test.java` for any of them
  - verdict: corroborated | checked_at: 6b8d88f4ac24a32b55f69f947342b115cd2bb20a | by: api-sheriff-0-2-0/analyze | rescoped: n/a | evidence: name searches for the seven classes find no Test.java at 6b8d88f4
- OBSERVED: `GrpcStreamIdleBound.timeoutMillis()` is public and new in #520 — `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/edge/relay/ResponseStage.java`
- OBSERVED: `LimitRulesTest` is unchanged by #520, and its class Javadoc is the #467 advisory — not in the diff
  `68481922..6b8d88f4`
- HYPOTHESIS: the inventory's findings V1–V17, C1–C3 and R1–R2 still hold at the commit this plan starts from —
  re-read each pair (verify-at-outline)
- HYPOTHESIS: the HTTP/2 and marked-request cases of `TerminalDispatchLateBodyTimeoutTest` can be driven through
  `retireAfterLateBodyTimeout` and `failRelay` — `edge/terminal/TerminalDispatch.java` (verify-at-outline)

## Expected Surface

- OBSERVED: `api-sheriff/src/test/java/de/cuioss/sheriff/gateway/config/` — `config/`, `config/validation/` and
  `config/validation/rule/` test classes
- OBSERVED: `api-sheriff/src/test/java/de/cuioss/sheriff/gateway/routing/`
- OBSERVED: `api-sheriff/src/test/java/de/cuioss/sheriff/gateway/testsupport/` — `ConfigFixtures` and one validation
  assertion helper
- OBSERVED: `api-sheriff/src/test/java/de/cuioss/sheriff/gateway/edge/terminal/` and
  `api-sheriff/src/test/java/de/cuioss/sheriff/gateway/edge/` — the edge residue of deliverable 3
- HYPOTHESIS: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/edge/relay/ResponseStage.java` — only if the
  accessor decision removes `timeoutMillis()` (verify-at-outline)
- OBSERVED: `doc/quality-report/test-quality.adoc`, `doc/development/test-corpus-integrity.adoc` — run time, counts
  and class names the inventory cites
- No change to `doc/security-threat-model.adoc`: the GW-07 status is the operator's named residual.

## Dependencies and Sequencing

- Depends on: `PLAN-V02-27` (#520), merged.
- **Pairs with no plan whose tests sit in `config/`, `routing/` or `edge/`.** `PLAN-V02-05`, `-06`, `-07`, `-09` and
  `-20` each add tests there; two plans editing the same classes collide on the test files. The constraint that held
  for `PLAN-V02-27` holds here.
- Disjoint from `PLAN-V02-48` by surface (tests against `doc/adr/`), but see the epic constraint that no plan runs
  beside a corpus-wide ADR cleanup if it writes or re-opens an ADR.
- Adjacent to the new test conventions from #424 and `doc/development/test-corpus-integrity.adoc`: read them first.

## Hand-Off Command

```text
/plan-marshall task="implement .plan/orchestrator/api-sheriff-0-2-0/plans/PLAN-V02-49-test-simplification-part-2.md" plan_id=plan-v02-49-test-simplification-part-2
```

**The explicit `plan_id` is load-bearing — do not drop it.**

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
