# PLAN-V03-05: Consumed 0.2.0 Lessons into Documentation

epic: api-sheriff-0-3-0
workstream: WS-04

> Staged plan spec — one shippable unit of work, ready for `/plan-marshall` hand-off.
> Staged 2026-11-07 by the `api-sheriff-0-2-0` close-out (`epic-closeout` step 3, "repository source"
> disposition). The ten lessons below were consumed from the repository's lessons corpus; their verbatim
> bodies are archived at `.plan/orchestrator/api-sheriff-0-2-0/archive/lessons-consumed/{lesson-id}.md`.
> This spec is the only live carrier of their rules until this plan lands.

## Objective

Write ten engineering rules learned during API Sheriff 0.2.0 into the repository documentation that
governs the matching work, so a later contributor finds them where they work instead of in a closed
orchestrator ledger. Every change is prose in an existing `*.adoc` page; nothing is built or tested
differently.

## Deliverables

1. **`doc/development/test-corpus-integrity.adoc` — race, timing and POM-wiring tests.** Add a new
   section "Race and timing tests" (sibling of the fitness-function sections) carrying two rules, and a note
   under `=== The four control legs`:
   - (lesson `2026-10-26-11-001`) A latch or barrier release is not a simultaneous start. Release race
     threads through a spin barrier so both are running when a round starts (`EdgeAdmissionTest` is the
     reference); measure whether the race was exercised (a vacuity control); run rounds until enough
     exercised it, up to a cap, rather than a fixed count; after any change to the release mechanism, show
     with a mutation that the test still catches its target. A "different" variant derived from a
     generated value is constructed to differ (invert every letter), never drawn.
   - (lesson `2026-10-15-12-002`) Before pushing a test with a concurrency or timing assertion, also run it
     locally at low parallelism (`-XX:ActiveProcessorCount=1` or `2`); its failure messages report the
     gateway-side view and the stub-side (upstream) view separately, so a CI failure says whose expectation
     broke.
   - (lesson `2026-11-03-08-001`, under "The four control legs") A structural guard over `pom.xml` wiring
     that answers "will Maven run this execution, before that one?" asserts the full run predicate in its
     first version: bound to the expected goal; bound to the exact phase the consumer uses, compared by
     equality (not membership in a hand-copied lifecycle list); not `<skip>true</skip>`; declared before the
     dependent execution when both share a phase. Each condition gets a negative specimen in one table-style
     test. `ComposeFixtureImageBuildWiringTest` is the reference.
2. **`doc/development/build-gate-discipline.adoc` — sampling and the escape refusal.**
   - (lesson `2026-10-15-12-003`) In `== Two rules about reading a check's output`, add a third rule and
     retitle the section to "Three rules…": a small sample does not discriminate an intermittent failure.
     Before any comparison arm may change a diagnosis or an outline, fix a per-arm sample size large enough
     that the control arm is expected to fail clearly more than zero times (100+ runs at a 1-5 % rate), and
     record each arm as "n runs, k failures", never as "fixed". Precedent: a `SO_LINGER 0` hypothesis looked
     supported on small samples and was refuted at 120 runs per arm (5/120 vs 3/120). Update every inbound
     reference to the old section title.
   - (lesson `2026-10-06-08-002`) In the paragraph "Two mechanics support it" of the loopback-bind sweep,
     add: the sweep also refuses any genuine Unicode escape in a guarded source, because javac translates it
     before lexing (JLS 3.3) and it could hide a `listen` selector; a test that needs a control or special
     character builds it at runtime (`String.valueOf((char) 0)`, a named `char` constant), never with a
     `\uXXXX` escape.
3. **`doc/development/boot-validation-rules.adoc` § `== Adding a rule`** (lesson `2026-09-24-15-010`).
   Add a step: if the refusal narrows a rule the operator documentation states permissively
   (`doc/configuration/**`, `doc/user/**`), update that statement in the same change, naming what is
   refused and the remedy the refusal message gives. A documented permissive rule is read as exhaustive,
   and a security refusal is the case an operator least expects. The step also covers refusals outside
   `ValidationRule`, such as the `ConfigLoader` substitution guard.
4. **Security and architecture wording.**
   - (lesson `2026-10-22-05-002`) `doc/security-threat-model.adoc`, after the status vocabulary in "How to
     use this document": add "Wording a status". COVERED only when tests drive the production wiring end to
     end; a test that supplies its own dispatch function proves the unit, so the entry names the substituted
     steps and stays PARTIAL. Distinguish "run by no test" from "asserted by no test", and check the
     integration suites before claiming a path is not run. Copy red-before-fix counts from the recorded red
     proof. A threat id's status is restated in the entry, the validation-matrix row and the ADR: grep the id
     and change every restatement in the same commit.
   - (lesson `2026-10-22-05-005`) `doc/architecture/runtime-and-dependencies.adoc`, threading bullets,
     extending "Inbound-socket affinity": `RoutingContext` data belongs to the client connection's event
     loop. A callback that can run on another loop (a timer armed on the upstream side, a late completion of
     the upstream leg) never writes it; it writes only to thread-safe structures shared by both sides, or
     hops to the client context (`Context.runOnContext`). Reviews of edge code ask, for every callback,
     which loop it runs on.
5. **Release and Sonar process pages.**
   - (lesson `2026-10-18-13-001`) `doc/development/sonar-quality-gate.adoc` § `== The plan's footprint
     governs in both directions`: new subsection "A move into a new file makes the moved body new code".
     Sonar's new-code period counts every line of a new file as new, so an extract-class or move-only
     refactor turns the moved methods' existing debt (java:S3776, java:S135 and similar) into gate-blocking
     new-code findings. The extracting deliverable lists "Sonar new-code clean-up of moved methods" in its
     scope and fixes them before the PR is opened.
   - (lesson `2026-10-21-19-001`) `doc/development/release-process.adoc` § `== Container image tags`: a
     change to what a release publishes is documented with its starting release. List the released versions
     that predate it; qualify every sentence, recipe and decision table that names the new behaviour "since
     release X"; verify each recipe against an existing older tag
     (`docker buildx imagetools inspect ghcr.io/cuioss/api-sheriff:<old-version>`) as well as the new shape.

Five deliverables, within the split guard. Each deliverable's Done-when: the rule is present at the named
section, stated in the page's own voice (not pasted as a lesson), with no rule contradicting it elsewhere in
the same page.

## Standing Constraints

- Documentation-only: every changed file is `*.adoc`, so `CLAUDE.md` § Pre-Commit Process skips the Maven
  gate. A deliverable that turns out to need a non-`.adoc` change is split out, not absorbed.
- Re-read each target section at HEAD before writing: the line numbers and section titles above were read at
  `main` `7f5c61af` (2026-11-07) and the docs were restructured by #575/#576 that same day.
- An anchor or section-title change updates every inbound `<<anchor>>` / `xref:` in the same PR.
- Where the project analyses its pull requests with Sonar (`resolve` reports `sonar: configured`): an
  open Sonar issue is fixed in a pushed commit, or dismissed with a stated reason recorded as
  `{issue-key}: {reason}` in the run's progress record and transmitted through the plan-less
  transition verb, `plan-marshall:workflow-integration-sonar:sonar_rest transition --issue-key {key}
  --transition {falsepositive|wontfix}`. A dismissal the run cannot transmit is a blocker, never a
  silent pass. The merge gate reads the open-issue count at the head — `0` confirmed, or every
  remaining issue dismissed — and an `undecidable` read blocks.

## Claim Labels

- OBSERVED: none of the ten rules is stated in repository documentation at `7f5c61af` — checked per lesson by
  the 2026-11-07 close-out triage (each lesson's evidence is in its archived copy).
  - Verify-first clause: re-check each rule against its target page at outline; a rule already present is
    dropped from the plan, not duplicated.
- OBSERVED: every target file and section named in the Deliverables exists at `7f5c61af` — read at
  `doc/development/build-gate-discipline.adoc:609`, `boot-validation-rules.adoc:174`,
  `sonar-quality-gate.adoc:67`, `release-process.adoc:271`, `test-corpus-integrity.adoc:383`.
- HYPOTHESIS: lesson `2026-10-15-12-003`'s retitle changes an anchor that other pages link to — confirm/refute
  with a content search for the section's anchor (verify-at-outline).

## Expected Surface

- OBSERVED: `doc/development/test-corpus-integrity.adoc`
- OBSERVED: `doc/development/build-gate-discipline.adoc`
- OBSERVED: `doc/development/boot-validation-rules.adoc`
- OBSERVED: `doc/security-threat-model.adoc`
- OBSERVED: `doc/architecture/runtime-and-dependencies.adoc`
- OBSERVED: `doc/development/sonar-quality-gate.adoc`
- OBSERVED: `doc/development/release-process.adoc`
- HYPOTHESIS: `doc/**` pages linking to the retitled `build-gate-discipline.adoc` section (verify-at-outline)

## Security Findings

None at staging. A finding uncovered during this plan and not fixed by it goes to the git-ignored
`.plan/local/security_notes_verified.adoc` and to the operator, never into the PR or a tracked file.

## Dependencies and Sequencing

- Depends on: none.
- Overlaps with: none of PLAN-V03-01, -02, -04 (all product surfaces).
- Adjacent to: `doc/development/test-corpus-integrity.adoc` § `open-work-list`, which this epic's Open
  Defect 1 points at; this plan adds sections and does not touch that table.

## Hand-Off Command

```text
/plan-marshall task="implement .plan/orchestrator/api-sheriff-0-3-0/plans/PLAN-V03-05-lessons-into-docs.md"
```

## Write-Boundary

The plan implementing this spec touches only its own repository source and tests. It creates
and edits NO file under `.plan/orchestrator/` other than its own
`inbox/{sender}-{seq}` message — the orchestrator owns every other ledger write — and reports
its outcome through its PR and its inbox message. The inbox exception's qualifiers and the
sole sanctioned write mechanism are stated in
`persona-plan-orchestrator/standards/orchestration-model.md` § Ledger Write-Boundary.
