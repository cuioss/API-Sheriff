# PLAN-08: Release Readiness — 1.0 cut (roadmap 09)

epic: api-sheriff-roadmap
workstream: WS-04

> Staged plan spec. Source of truth: `doc/plan/09-release-readiness.adoc` — read in full at
> launch; this spec is a summary staged from `doc/plan/README.adoc`.

## Objective

Execute `doc/plan/09-release-readiness.adoc`: the operator configuration guide, the ADR-0005
module-extraction checkpoint, a full-surface security audit, benchmark completion with
per-variant baselines and regression thresholds, native + container hardening, the 1.0.0
release, and the flip of the pre-1.0 rules.

## Deliverables

Per the plan doc at launch. Additionally (2026-07-19): the benchmark-consolidation
deliverable incorporates the PLAN-09 comparative baseline (API Sheriff vs APISIX numbers)
into the per-variant baseline documentation.

**Documentation role changed 2026-07-20** by the standing per-plan documentation convention:
every plan from PLAN-09 onward writes into `doc/user/` and `doc/development/` as it lands, so
this plan **reviews and completes** rather than authoring the operator guide from scratch. The
audit covers all three layers against the shipped surface — `configuration.adoc` as the
exhaustive reference (every key, default and enum value present and accurate; nothing
documented that no longer exists), `doc/user/` as the guide, `doc/development/` as the
contributor tree — all reachable from `doc/README.adoc`. **Reference drift ranks highest**: a
stale `configuration.adoc` is worse than a missing guide page, because operators treat it as
the contract. Gaps are recorded as findings **attributed to the plan that should have
documented them**, so the convention's effectiveness is measurable instead of silently patched
at release time.

## Expected Surface

- `doc/**` (operator guide), `api-sheriff/` hardening, `benchmarks/` baselines, release plumbing

## Dependencies and Sequencing

- Depends on: PLAN-04 through PLAN-07 (all of roadmap 05-08)
- Overlaps with: none remaining — it is last

## Hand-Off Command

```text
/plan-marshall Execute doc/plan/09-release-readiness.adoc in full (read it and its required reading first; the design docs govern). Deliver the 1.0.0 release cut per the plan's work breakdown: operator guide, ADR-0005 checkpoint, security audit, benchmark baselines, native + container hardening, release, and the pre-1.0 rules flip. DOCUMENTATION (standing epic convention, operator 2026-07-20): every plan from roadmap 04b onward writes its operator-facing behaviour into doc/user/ and its contributor-facing behaviour into doc/development/ in its own PR, so this plan's role is REVIEW AND COMPLETION, not authoring the operator guide from scratch — the guide should already largely exist. Audit ALL THREE layers against the shipped surface: (a) doc/configuration.adoc as the exhaustive operator reference — every shipped configuration key, default and enum value present and accurate, with no key documented that no longer exists; (b) doc/user/ as the task-oriented operator guide; (c) doc/development/ as the contributor guide. Everything an operator or contributor actually needs must be covered, accurate, and reachable from doc/README.adoc. Reference drift is the highest-value thing to check here — a stale or incomplete configuration.adoc is worse than a missing guide page, because operators trust it as the contract. Treat gaps as findings against the plan that should have documented them and record which plan they came from, so the convention's effectiveness is visible rather than silently patched at the end. The plan doc's own "operator configuration guide" deliverable is reinterpreted accordingly. SONAR ZERO-FINDINGS (standing epic convention, operator 2026-07-21): the SonarCloud quality gate (project cuioss_API-Sheriff) must be GREEN before merge — a red gate is a HARD STOP, never merge over red or on a stale green while analysis is still pending; deliver ZERO new findings (new bugs = 0, new vulnerabilities = 0, new code smells = 0, security hotspots 100% reviewed, new_reliability / new_security / new_maintainability ratings all A, new coverage >= 80%); the GOAL is to FIX findings, and where a fix is genuinely not sensible an IN-CODE suppression carrying a documented rationale (e.g. // NOSONAR or @SuppressWarnings("java:SXXXX") with a justifying comment) is an acceptable way to reach zero because it stays auditable in the repo alongside the code — what is NOT acceptable is reaching zero by silent server-side won't-fix / false-positive marking; and this plan is NOT 'done' / 'all green' until the PROJECT-level gate on the merged result is green — the completion report must reflect the actual post-merge gate, not a transient PR-scoped new-code green.
```

## Status Trail

- plan_marshall_plan_id: (set at launch)
- pr: (set when the PR opens)
- landing: (set when recorded at landings/PLAN-08.md)
