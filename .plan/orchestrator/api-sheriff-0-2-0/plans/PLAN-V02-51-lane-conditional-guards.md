# PLAN-V02-51: Settle #201 and Close the Two Guard Gaps ADR-0075 Declined

epic: api-sheriff-0-2-0
workstream: WS-05

> Staged plan spec — one shippable unit of work, ready for `/plan-marshall` hand-off.
> Lives at `plans/PLAN-V02-51-lane-conditional-guards.md` and is queued as one row file, `queue/PLAN-V02-51.json`.
> The orchestrator EMITS the command below; it never launches the plan inline.
> Staged 2026-10-28 from the landing of `PLAN-V02-11` (#534, `c1ded6c1`). ADR-0075 declined three Open Defects the
> ledger had routed to that plan (14, 7, 9); the operator ruled that one plan owns all three.
> This spec is SELF-SUFFICIENT: the emitted command is a one-line pointer and carries no brief.

## Objective

Three defects have no owner since `PLAN-V02-11` declined them. **Open Defect 14 (issue #201)** says `MtlsHandshakeIT`
fails two of three runs under `-Pjfr` only, and was filed as fail-open on a security control. Nobody has run the test
against the real mTLS instance under that lane, and the `jfr` profile's exclusion comment in
`integration-tests/pom.xml` reads as a lane misconfiguration instead. **Decide which it is, with a run, and act on the
answer.** Then close the two guard gaps of the same family: the k6 benchmark scripts are an unguarded consumer of the
gateway configuration they share with the IT suite (**Open Defect 7**), and the log-message catalogue guard walks one
module only (**Open Defect 9**).

## Deliverables

Three deliverables, one per defect. Deliberately not merged into one mechanism: they share a theme (a guard whose green
is not coverage) and nothing else.

1. **Open Defect 14 — settle #201 with a run, then fix or close.**
   Run `MtlsHandshakeIT` against the dedicated `api-sheriff-mtls` instance (port 10444, client identities from
   `generate-mtls-certificates.sh`) under `-Pintegration-tests` and under `-Pjfr`, three runs each, with the mTLS
   properties supplied to the second lane by hand. Record the pass counts. Then take exactly one branch and say which:
   - **The gateway fails open on handshake rejection** (the rejection legs fail with the properties supplied): this is a
     security fix. Fix `MtlsServerCustomizer` or the TLS wiring it configures, keep the failing run as the demonstrated
     catch, and run the full pre-commit process. A fix lands in this plan; it is not deferred.
   - **The lane is the cause** (the legs pass with the properties supplied, and fail without them): the gateway is
     correct. Make the `jfr` lane either serve the test or exclude it for a reason a test checks. The existing
     exclusion is a comment; add a guard that fails when `MtlsHandshakeIT` is selected into a lane that declares
     neither `test.mtls.port` nor the keystore properties. Close the issue with the run counts quoted.
   - **Neither explains it**: report it as a finding, keep Open Defect 14 open, name what is missing. Do not close on
     inference. "Decided but not settled" is an acceptable outcome; closing on the exclusion comment alone is not.
2. **Open Defect 7 — make the k6 consumers of the shared gateway configuration visible to a behaviour change.**
   `integration-tests/docker-compose.benchmark.yml` overlays the base compose and mounts the same
   `./src/main/docker/sheriff-config`, so the benchmark and the IT suite share one `gateway.yaml`. Enumerate which
   `benchmarks/` k6 scripts depend on which gateway key, then add the guard that fails when a key a script depends on
   changes without the script. Choose the mechanism (a contract test over a declared key-to-script table, or a check in
   `benchmark-manifest.py`) and say why; the test must be shown red against a key change with no script change.
3. **Open Defect 9 — extend the catalogue guard past one module.**
   `LogMessagesCatalogueTest` anchors on `ApiSheriffLogMessages.class.getProtectionDomain()` (line 243), so it walks
   `api-sheriff/target/classes` only. `benchmarks` carries `K6BenchmarkLogMessages`, including the `K6Benchmark-211`
   id, which no duplicate-id check protects. Generalise the guard to every module carrying a catalogue rather than
   special-casing `benchmarks`: derive the module set, do not list it. Show it red against a duplicate id planted in
   the benchmarks catalogue. Check `integration-tests/` for a catalogue before claiming the set is complete.

## Standing Constraints

- **SONAR ZERO-FINDINGS** — red is a HARD STOP.
- **NAMED LINE ITEMS** — all three deliverables are named; collapsing one into another turns this into a memo.
- **A detector nothing has seen fire is not a detector.** Each deliverable carries its demonstrated catch.
- The ADR-0075 rule applies to anything this plan adds: no soak test and no new non-gating lane.
- New test conventions from #424 bind every test this plan adds.
- A production edit is allowed in D1 only, and only on the fail-open branch. Any other production edit is a finding to
  report.
- Where the project analyses its pull requests with Sonar: an open Sonar issue is fixed in a pushed commit, or
  dismissed with a stated reason recorded as `{issue-key}: {reason}` in the run's progress record and transmitted
  through the plan-less transition verb. The merge gate reads the open-issue count at the head: `0` confirmed, or every
  remaining issue dismissed. An `undecidable` read blocks.
- PRs over 100 files or 150,000 diff characters are refused by the review bots; this plan is expected to stay far under.
- Next free ADR ordinal at `c1ded6c1` is `0076`; this plan is not expected to write one. Derive it again against
  `origin/main` before any push if it does.

## Claim Labels

- OBSERVED: issue #201 is `OPEN`, last updated 2026-08-09, no comments — read with `gh issue view 201`.
- OBSERVED: the `jfr` profile (`integration-tests/pom.xml`, profile id `jfr`, line 536) excludes `MtlsHandshakeIT` with a
  comment stating that the lane declares neither `test.mtls.port` nor the keystore properties, so the test falls back to
  the primary instance, which requires no client certificate — read at `integration-tests/pom.xml` lines 593–619.
- HYPOTHESIS: the #201 symptom is that lane misconfiguration and not a gateway fail-open — confirm/refute by the D1 runs
  against `api-sheriff-mtls` (verify-at-outline). The rationale was not run by the orchestrator.
- OBSERVED: ADR-0075 declines Open Defects 7, 9 and 14 in its "Scope boundary" section and states that #201 is
  "neither detected nor fixed" — read at `doc/adr/0075-Resource-exhaustion_detection_…adoc`.
- OBSERVED: `LogMessagesCatalogueTest` anchors on `ApiSheriffLogMessages.class.getProtectionDomain()` —
  read at `api-sheriff/src/test/java/de/cuioss/sheriff/gateway/LogMessagesCatalogueTest.java:243`.
- OBSERVED: `integration-tests/docker-compose.benchmark.yml` exists and the benchmark scripts reference it
  (`benchmarks/scripts/run-comparison.sh:153`).
- HYPOTHESIS: `integration-tests/` carries no log-message catalogue of its own — confirm/refute with a search for
  `LogMessages` classes outside `api-sheriff/` and `benchmarks/` (verify-at-outline).
- OBSERVED (absence, asserted): no test names the dependency of a k6 script on a gateway key — confirm with a search of
  `benchmarks/` and `integration-tests/src/test` for such a table (verify-at-outline).

## Expected Surface

- OBSERVED: `integration-tests/src/test/java/de/cuioss/sheriff/gateway/integration/MtlsHandshakeIT.java` — D1
- OBSERVED: `integration-tests/pom.xml` — D1, the `jfr` profile and its exclusion
- OBSERVED: `integration-tests/src/main/docker/sheriff-config-mtls/gateway.yaml` — D1, read to run the test
- OBSERVED: `integration-tests/src/main/docker/certificates/generate-mtls-certificates.sh` — D1, read to run the test
- HYPOTHESIS: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/tls/MtlsServerCustomizer.java` — D1, edited only on
  the fail-open branch (verify-at-outline)
- OBSERVED: `integration-tests/docker-compose.benchmark.yml` — D2, read
- HYPOTHESIS: `benchmarks/scripts/benchmark-manifest.py`, `benchmarks/pom.xml` — D2, only if the check lives there
  (verify-at-outline)
- HYPOTHESIS: `integration-tests/src/test/java/de/cuioss/sheriff/gateway/integration/` — D2, a new contract test
- OBSERVED: `api-sheriff/src/test/java/de/cuioss/sheriff/gateway/LogMessagesCatalogueTest.java` — D3
- OBSERVED: `benchmarks/src/main/java/de/cuioss/sheriff/gateway/k6/benchmark/K6BenchmarkLogMessages.java` — D3, read
- OBSERVED: `doc/development/` — the developer layer, if a lane rule changes

## Dependencies and Sequencing

- Depends on: none. `PLAN-V02-11` has landed and its `integration-tests/` edits are on `main`.
- Overlaps with: `PLAN-V02-50` on `integration-tests/**` and, on the fail-open branch only, the TLS wiring. The
  disjointness gate decides at emit time; the overlap prompt applies.
- Does not run beside an ADR-authoring plan unless D1 writes none (it is not expected to).
- Adjacent to: `PLAN-V02-49` (test simplification, `config/` and `edge/` tests) — this plan touches none of them.
- Adjacent to: `integration-tests/src/main/docker/sheriff-config-ws-admission/gateway.yaml`, whose header comment
  overstates its fidelity (a Watch from `PLAN-V02-11`'s finding 1). Untouched here; a reader should not re-derive it.

## Hand-Off Command

```text
/plan-marshall task="implement .plan/orchestrator/api-sheriff-0-2-0/plans/PLAN-V02-51-lane-conditional-guards.md" plan_id=plan-v02-51-lane-conditional-guards
```

**The explicit `plan_id` is load-bearing — do not drop it.**

## Finalize Boundary — the plan STOPS at the merge

Everything up to and including the merge is the plan's. **The aftermath is the ORCHESTRATOR's and MUST NOT be attempted
by the plan** — the PR-attached post-merge run and the main-branch run for the merge commit, including
`deploy-snapshot`. Report the merge and stop.

## Security Findings

Fix a security finding inside this plan whenever possible; that is the preferred outcome, and D1 may need to. A finding
that is not fixed right away (out of scope, unconfirmed, deferred) stays out of git: it is never written into a tracked
file, a commit message, the PR title, body or review replies, a code comment, the docs, or this plan's inbox and landing
messages. Record it instead in `.plan/local/security_notes.adoc` (git-ignored; create the file if it is missing and
append, never rewrite). Each entry carries the date, this plan's id, the location (file and line or component), what
was observed, and why it was not fixed. Tracked text may say only that a security note was recorded locally, never what
it says. **An unconfirmed #201 is exactly this case until D1's runs settle it**: report the run counts, not a theory.

## Write-Boundary

The plan implementing this spec touches only its own repository source and tests. It creates and edits NO file under
`.plan/orchestrator/` other than its own `inbox/{sender}-{seq}` message — the orchestrator owns every other ledger write
— and reports its outcome through its PR and its inbox message. The inbox exception's qualifiers and the sole
sanctioned write mechanism are stated in `persona-plan-orchestrator/standards/orchestration-model.md` § Ledger
Write-Boundary.
