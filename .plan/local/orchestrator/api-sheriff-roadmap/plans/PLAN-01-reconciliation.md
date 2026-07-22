# PLAN-01: Doc/Code Reconciliation & Quality Debt (roadmap 02b)

epic: api-sheriff-roadmap
workstream: WS-01

> Staged plan spec — one shippable unit of work, ready for `/plan-marshall` hand-off.
> Source of truth: `doc/plan/02b-reconciliation.adoc` (the plan doc governs; this spec stages it).

## Objective

Execute `doc/plan/02b-reconciliation.adoc` in full: bring the design documentation and the
delivered code back into agreement and clear the test/benchmark quality debt from the
2026-07-17 adversarial review, before Plan 03 builds on either. No features — every item is a
correction, a deletion, or a repair of a measurement instrument.

## Deliverables

Four deliverable groups, exactly as the plan doc's work breakdown:

1. Code-side corrections: remove `validateWholeSecondTimeouts` (+ tests, + positive
   non-whole-second boot test); flip ADR-0009 to Accepted (+ `doc/README.adoc` index);
   remove dead `jwks.source: inline` from schema/model/doc.
2. Documentation corrections: root README ports (9000 vs 8443), library-dependency claims
   fixed + deduplicated, stale sentences dropped, TokenSheriff/OAuthSheriff naming
   reconciled, LogMessages IDs un-padded, version refs (cui-java-parent, CLAUDE.md),
   metrics-port attribution, `sheriff.config.dir` + config-location facts,
   `trusted_proxies` optionality, session-store requiredness, violation-aggregation wording,
   downstream→upstream terminology sweep.
3. Test hygiene: delete vacuous tests (`ApiSheriffTest.shouldCreateInstance`,
   `ApiSheriffProducerTest`, `ValidationRuleTest`); migrate five Hamcrest-importing
   REST-assured classes to `.extract()` + JUnit; wire `verify-invalid-config-fails.sh` into
   the integration-tests profile (wire-or-delete `verify-environment.sh`); small gaps
   (`::/0` negative case, secret-value-free exception messages, classpath resource loading
   in `WrkResultPostProcessorTest`, drop unused `awaitility`).
4. Benchmark harness correctness: fix wrk counter aggregation via `setup()`/thread table in
   all three scripts, delete unused `error_count`, add error-rate run gating; replace
   `WrkResultPostProcessor` bidirectional-`contains()` matching with an exact naming
   contract, reconcile suffix handling, add value-asserting parser tests.

Optional stopgap (skip if Plan 04 is imminent): `BodyHandler` size limit + `HttpRequest`
timeout on the interim proxy.

## Expected Surface

- `doc/**` (ADRs 0008/0009 refs, README.adoc, configuration.adoc, architecture.adoc,
  technical_aspects.adoc, LogMessages.adoc), root `README.adoc`, `CLAUDE.md`
- `api-sheriff/` — `ConfigValidator` + tests, config schema files, `IssuerConfig.Jwks`,
  vacuous/Hamcrest test classes
- `integration-tests/` — pom (exec-maven-plugin wiring, awaitility removal), scripts
- `benchmarks/` — the three wrk lua/sh scripts, `WrkResultPostProcessor` + tests
- Explicitly NOT: anything `doc/plan/03-endpoint-anchors.adoc` owns

## Dependencies and Sequencing

- Depends on: none (plans 01 + 02 already delivered)
- Overlaps with: PLAN-02-endpoint-anchors only in trivial doc passages (per the 02b scope
  guard) — parallel is permissible, sequential is the default

## Hand-Off Command

```text
/plan-marshall Execute doc/plan/02b-reconciliation.adoc in full (read it and its required reading first; the plan doc governs). Bring docs and delivered code back into agreement and clear test/benchmark quality debt: (1) remove validateWholeSecondTimeouts + its tests and add a positive non-whole-second timeout boot test; flip ADR-0009 to Accepted incl. doc/README.adoc index; delete jwks.source: inline from schema, IssuerConfig.Jwks, and configuration.adoc. (2) All documentation corrections in the plan's section 3.2 (README ports 9000, library-claim dedup, naming, LogMessages IDs, version refs incl. CLAUDE.md parent-pom version, config-location facts, trusted_proxies optionality, session-store requiredness, violation-aggregation wording, upstream terminology). (3) Test hygiene per section 3.3: delete vacuous tests, migrate the five Hamcrest-importing classes to extract()+JUnit, wire verify-invalid-config-fails.sh into the integration-tests profile and wire-or-delete verify-environment.sh, close the small gaps (::/0 negative, secret-free exception messages, classpath resources in WrkResultPostProcessorTest, drop unused awaitility). (4) Benchmark harness per section 3.4: aggregate wrk counters via setup()/thread table in all three scripts, delete error_count, fail runs above an error-rate threshold; exact-match naming contract in WrkResultPostProcessor with value-asserting tests. Honor the plan's scope guard: touch nothing doc/plan/03-endpoint-anchors.adoc owns. Branch feature/plan-02b-reconciliation, one PR. Acceptance criteria are the plan doc's section 5 verbatim.
```

## Status Trail

- plan_marshall_plan_id: plan-02b-reconciliation (archived 2026-07-18)
- pr: #72 (merged, merge commit 715f19e)
- landing: landings/PLAN-01.md
