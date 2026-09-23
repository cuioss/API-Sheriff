# PLAN-23: Audit and fix the unit-test lane PLAN-16's pass never opened

epic: deployment-configurability
workstream: WS-07

> Staged plan spec — the emitted command is a one-line pointer, so this spec is the whole brief.
> ⛔ **Staged 2026-09-10 from the epic coverage audit.** PLAN-18's own scope note warned: *"do not
> read the exhaustive integration pass as coverage of the 29 files nobody has opened."* Nobody had.
> ⛔ **RE-SCOPED 2026-09-15 by the orchestrator's corpus revisit at `origin/main` `a2969b9`.** Moved OUT:
> former deliverables 3 (the `TlsEdgeProducerTest` / `SniFrontListenerTest` loopback residuals) and 5
> (the throw-then-assert pair) → **PLAN-18** deliverables 3-6, because they need no audit; and the
> redundant `ConfigValidatorTest` trusted-proxy methods → **PLAN-25** deliverable 7, which owns that
> test file. What remains is the open-ended audit and its fixes.
> ▶ **FOLDED IN 2026-09-16 at PLAN-18's landing**: the `Awaits.until`-then-re-assert mechanical guard
> PLAN-18 left unbuilt (its spec made it conditional on being cheap; the orchestrator verified it is).
> New deliverable 4; the former 4 and 5 are now 5 and 6. Adds no file surface — this plan already declares
> `api-sheriff/src/test/` wholesale.
> ▶ **FOLDED IN 2026-09-17 at PLAN-26's landing**: the quality gate's standing rewrite of `SniFrontListener`
> and `LoopbackEphemeralBindArchTest` (new deliverable 6; the flakiness item is now 7). **Surface UPDATED**
> (+`SniFrontListener.java`) — the first `src/main` file this plan declares.

## Objective

PLAN-16's deliverable 5 read all 56 files under `integration-tests/src/test/**` exhaustively and found
nine tests whose green said less than their name claimed. **It never opened the unit lane.** PLAN-18
fixes those nine plus the unit-lane sites already named; this plan audits the rest of
`api-sheriff/src/test/**` and fixes what the audit finds.

⛔ **This is an audit-then-fix plan, not an execution plan** — no analysis names its sites yet, which is
why it is not folded into PLAN-18.

⛔ **Do not plan against any population figure.** The union of `assertDoesNotThrow` and `assertNotNull`
over `api-sheriff/src/test/**` has been measured four times — 29 files / 87 occurrences at `497c592`,
44 / 207 at `990aebf`, 45 / 216 at `428bbec` and again at `a2969b9` — and it is a lower bound including
`import static` lines. Re-derive at outline and state the population actually covered.

## Deliverables

1. **Audit the unit lane, and say what population you covered.** Follow the method
   `doc/development/test-corpus-integrity.adoc` records — but read its §65-80 warning first:
   **marker-density ranking presupposes the defect leaves a marker, and shape (e) is invisible to every
   marker the census counts.** All nine cookie tests carried substantive-looking assertions and not one
   vacuity marker. ⛔ **Ranking alone will miss the class this epic exists to close.** State explicitly
   which files were read exhaustively and which were only ranked. ⚠ The files PLAN-18 and PLAN-25
   changed are in the population — audit them at their post-landing state.
2. **Report each site the way PLAN-16 reported its nine** — the file, the method, what is wrong, and
   **the observable the fix must reach** — and record the report in `test-corpus-integrity.adoc`.
   ⛔ **Report out-of-lane findings rather than fixing them.**
3. **Fix the reported sites.** ⚠ The plan's total is capped at 12 deliverables by operator decision; if
   the site count makes the fixing half unreviewable as one PR, stop after deliverable 2 and hand the
   report back for a successor plan rather than let the fixes hold up the audit.
4. **Land the `Awaits.until`-then-re-assert guard PLAN-18 left unbuilt.** ⛔ **Folded in 2026-09-16 at
   PLAN-18's landing.** PLAN-18 removed the last instance (`TlsEdgeProducerTest:343-344`) but its guard was
   conditional (*"if cheap"*) and `AwaitsReassertionArchTest` was the one declared path with no realized file
   — so the class is now at zero instances with nothing stopping the next one, which is exactly the shape
   archived lesson `2026-09-10-22-001` warns about (*"removing N instances without a mechanical guard leaves
   N+1 free to arrive"* — it arrived in a day).
   ✅ **The orchestrator checked feasibility before folding this in, because "cheap" was the condition that
   dropped it**: the repository already carries the mechanism. `LoopbackEphemeralBindArchTest` walks
   `TEST_SOURCE_ROOT` (`:195`, `:468-471`) in its `WildcardHostLiteralSweep` (`:788`) precisely because the
   discriminator lives in source text rather than bytecode — the same is true here, since the pattern is
   *"`Awaits.until(c)` followed by an assertion of the same `c`"*. Reuse that shape, and the
   `arch/specimen/` package for a matched positive **and** negative control, as
   `EgressTlsPostureArchTest` (PLAN-25) does.
   ⚠ **Scope check before writing it**: at `c74f5d2` there are **five** `Awaits.until` call sites tree-wide,
   all under `api-sheriff/src/test/` (`AwaitsTest:404`, `GatewayEdgeRouteTest:1102`/`:1122`,
   `WebSocketRelayStageTest:469`, `TlsEdgeProducerTest:346`) and none in `integration-tests/src/test/`.
   Re-derive that population; if the sweep turns out to be noisy rather than cheap, **record why and stop** —
   do not ship a guard that fires on ordinary waits.
5. **Prove every strengthened test by reversion, not by green** — the identical bar to PLAN-18's
   deliverable 7: a test that still passes when the behaviour it names is reverted has not been
   strengthened, whatever it now asserts.
6. **Absorb the quality gate's standing rewrite of two non-branch files.** ⛔ **Folded in 2026-09-17 from
   PLAN-26's landing** (candidate lesson `refresh-failure-dispositions-005`). Every `verify -Ppre-commit` run
   — at least six across PLAN-26, at base `3c68ee4` — finishes green and leaves
   `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/tls/SniFrontListener.java` and
   `api-sheriff/src/test/java/de/cuioss/sheriff/gateway/arch/LoopbackEphemeralBindArchTest.java` rewritten.
   Each plan then identifies, attributes and reverts the churn by hand; because `pre-push-quality-gate` is
   `mutates_source: true`, one PLAN-26 round reused an earlier gate job at the same HEAD purely to avoid
   committing it. **This plan already owns both lanes the churn lands in and runs the gate anyway, so it is
   the cheapest place to end it**: land the gate's own rewrite of those two files, or fix the
   recipe/formatter disagreement behind it, so a clean `main` stays clean after a gate run.
   ⚠ **Read the churn before committing it** — it is the gate's output, not a reviewed change; if it is not
   obviously correct (a formatter disagreement rather than a semantic edit), record that and stop rather than
   committing a rewrite nobody chose. ⚠ `SniFrontListener.java` is `src/main` — this is the only production
   file this plan touches, and it is declared below for that reason.
7. **Settle `WebSocketRelayStageTest.preservesSecurityHeadersOnHandshakeFailure`'s reported flakiness.**
   Carried unowned; check recent CI runs first, and route it out with the evidence if it has stabilised
   rather than spending effort on a test that is no longer flaky.

**Methodology inputs for deliverables 1 and 5** (from the 2026-09-11 lessons intake; originals in
`lessons-archive/` or relocated to plan-marshall's truthful-signals inbox):

- **Relocated failure mechanisms leave dead assertions.** Any refactor that moves enforcement — onto a
  throwing helper, into a `@BeforeEach`, into a constructor, from a returned status to an exception —
  owes a re-check of every assertion that depended on the old location (`2026-09-01-14-003`).
- **Review complementary test pairs as a set.** Ask *"if the mechanism both exist to protect were
  deleted, would either go red?"* (`2026-09-02-13-001`).
- **A provenance API answers "which source won", not "which file declared it".** For Quarkus build-time
  keys, `ConfigValue.getSourceName()` reports `BuildTime RunTime Fixed` whether the key came from the
  shipped file or the default; assert the packaged artifact by path, pair it with the effective value,
  and run the deletion as a negative control (`2026-09-01-15-001`; `ContextPathDefaultsTest:132` is the
  model).

## Claim Labels

- OBSERVED: the marker census over `api-sheriff/src/test/**` at `a2969b9` is 45 files / 216 occurrences
  for the union of `assertDoesNotThrow` and `assertNotNull`, unchanged from `428bbec` — re-derived by
  direct grep on 2026-09-15.
  - verdict: corroborated | checked_at: cc10ce2a2c7840162bbc087242d42cbbc3cd14c3 | by: deployment-configurability/cleanup | rescoped: n/a | evidence: re-derived census at HEAD: 44 files match assertDoesNotThrow/assertNotNull under api-sheriff/src/test/, consistent with the a2969b9-anchored count
- OBSERVED: `test-corpus-integrity.adoc` §65-80 records that shape (e) carries no marker and that ranking
  must not be read as a replacement for exhaustive reading.
  - verdict: corroborated | checked_at: cc10ce2a2c7840162bbc087242d42cbbc3cd14c3 | by: deployment-configurability/cleanup | rescoped: n/a | evidence: test-corpus-integrity.adoc still records the shape-(e)-carries-no-marker warning
- HYPOTHESIS: `WebSocketRelayStageTest.preservesSecurityHeadersOnHandshakeFailure` is still flaky in CI —
  confirm/refute against recent `Maven Build` runs through the CI abstraction § that test's failure
  history (verify-at-outline).
  - verdict: contradicted | checked_at: cc10ce2a2c7840162bbc087242d42cbbc3cd14c3 | by: deployment-configurability/analyze | rescoped: yes | evidence: PR #336 (deliverable 6): no artifact in the tree and no CI signal across the 30 most recent Maven Build runs -- WebSocketRelayStageTest.preservesSecurityHeadersOnHandshakeFailure routed out of the known-flaky reading; recorded in doc/development/test-corpus-integrity.adoc
- OBSERVED: at `c74f5d2` the tree holds five `Awaits.until` call sites, all under `api-sheriff/src/test/`
  (`AwaitsTest:404`, `GatewayEdgeRouteTest:1102` and `:1122`, `WebSocketRelayStageTest:469`,
  `TlsEdgeProducerTest:346`), and none in `integration-tests/src/test/`; `TlsEdgeProducerTest:346` no longer
  carries the redundant `assertFalse` PLAN-18 removed.
  - verdict: corroborated | checked_at: cc10ce2a2c7840162bbc087242d42cbbc3cd14c3 | by: deployment-configurability/cleanup | rescoped: n/a | evidence: this plan's own landing implements deliverable 4: AwaitsReassertionArchTest.java now exists
- OBSERVED: `LoopbackEphemeralBindArchTest` already implements a source sweep over `TEST_SOURCE_ROOT`
  (`:195`, `:468-471`, nested `WildcardHostLiteralSweep` at `:788`) with a specimen-package carve-out, so
  deliverable 4 extends an existing mechanism rather than inventing one.
  - verdict: corroborated | checked_at: cc10ce2a2c7840162bbc087242d42cbbc3cd14c3 | by: deployment-configurability/cleanup | rescoped: n/a | evidence: LoopbackEphemeralBindArchTest's TEST_SOURCE_ROOT sweep and WildcardHostLiteralSweep mechanism, reused by deliverable 4's new guard, is unaffected
- Verify-first clause: re-derive the census before planning against any number, including those above.

## Expected Surface

- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/tls/SniFrontListener.java` — deliverable 6's half of the gate churn that lives in `src/main`
- OBSERVED: `api-sheriff/src/test/` — ⛔ **a DIRECTORY declaration, deliberately and exceptionally.** The
  audit's subject is *which files in this tree have not been opened*, so the surface cannot be
  enumerated before deliverable 1 runs. It contains files PLAN-18 and PLAN-25 declare by name, which is
  why this plan is sequenced after both.
- OBSERVED: `doc/development/test-corpus-integrity.adoc`

## Dependencies and Sequencing

- ⛔ **Runs after PLAN-18 AND PLAN-25 land.** Both declare named files inside `api-sheriff/src/test/`, and
  auditing a file another plan is mid-way through changing audits a tree nobody will ship.
- ⛔ **Also runs after PLAN-26 lands** (added 2026-09-15 when PLAN-26 was staged). PLAN-26 rewrites
  `TokenRefreshCoordinatorTest` — including `shouldFailOnReuseDetection`, a test whose premise is wrong —
  and is security-bearing, so it goes first and this audit then covers the rewritten tests.
- ✅ **Surface-disjoint from PLAN-24.**
- ✅ **Not urgent.** These tests pass today; the defect is that they would keep passing if the behaviour
  broke.

## Hand-Off Command

```text
/plan-marshall task="implement .plan/local/orchestrator/deployment-configurability/plans/PLAN-23-unit-lane-vacuity-audit.md"
```

## Write-Boundary

Touches only repository source and tests. Creates and edits NO file under
`.plan/local/orchestrator/` other than its own `inbox/{sender}-{seq}` message; reports outcome
through its PR and that message. See `orchestration-model.md` § Ledger Write-Boundary.
