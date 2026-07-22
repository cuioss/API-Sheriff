# PLAN-07: Variant 3 — Cookie, Stateless (roadmap 08)

epic: api-sheriff-roadmap
workstream: WS-03

> Staged plan spec. Source of truth: `doc/plan/08-bff-cookie.adoc` — read in full at launch;
> this spec is a summary staged from `doc/plan/README.adoc`.

## Objective

Execute `doc/plan/08-bff-cookie.adoc`: the token-binding delta on the PLAN-06 foundation —
AES-256-GCM encrypted token cookie, `previous_key` rotation, single-flight refresh with the
reuse-as-failure rule, back-channel endpoint deliberately 404 (Variant 3).

## Deliverables

Per the plan doc at launch.

## Expected Surface

- `api-sheriff/` cookie token-binding on the BFF foundation
- `integration-tests/` Variant 3 suites; `benchmarks/` per-variant additions

## Dependencies and Sequencing

- Depends on: PLAN-06 (roadmap 07)
- Overlaps with: PLAN-06 surface (same BFF packages) — strictly sequential

## Hand-Off Command

```text
/plan-marshall Execute doc/plan/08-bff-cookie.adoc in full (read it and its required reading first; the design docs govern). Deliver Variant 3 (stateless encrypted-cookie token binding) per the plan's work breakdown, with integration tests in the same plan. DOCUMENTATION (standing epic convention, operator 2026-07-20 — applies to every plan; THREE layers, all in the SAME PR): (a) REFERENCE — every new or changed configuration key, default, enum value, and endpoint this plan introduces MUST land in doc/configuration.adoc, and structural changes in doc/architecture.adoc; these are the exhaustive per-key references and they are not optional. (b) OPERATOR GUIDE — doc/user/, task-oriented (how an operator accomplishes the thing), linking to the reference rather than duplicating its key tables. (c) DEVELOPER GUIDE — doc/development/, contributor-facing (build, test, run, extend). The doc/user/ and doc/development/ trees and the convention itself are created by roadmap plan 10 (anchor types + assets) — NOT by plan 04b, which shipped without them; follow what doc/plan/README.adoc records once plan 10 lands. Updating plan docs, variant docs or ADRs does NOT satisfy (a), (b) or (c). If this plan genuinely changes nothing an operator or a contributor would do differently, state that explicitly in the PR rather than silently skipping it. SONAR ZERO-FINDINGS (standing epic convention, operator 2026-07-21): the SonarCloud quality gate (project cuioss_API-Sheriff) must be GREEN before merge — a red gate is a HARD STOP, never merge over red or on a stale green while analysis is still pending; deliver ZERO new findings (new bugs = 0, new vulnerabilities = 0, new code smells = 0, security hotspots 100% reviewed, new_reliability / new_security / new_maintainability ratings all A, new coverage >= 80%); the GOAL is to FIX findings, and where a fix is genuinely not sensible an IN-CODE suppression carrying a documented rationale (e.g. // NOSONAR or @SuppressWarnings("java:SXXXX") with a justifying comment) is an acceptable way to reach zero because it stays auditable in the repo alongside the code — what is NOT acceptable is reaching zero by silent server-side won't-fix / false-positive marking; and this plan is NOT 'done' / 'all green' until the PROJECT-level gate on the merged result is green — the completion report must reflect the actual post-merge gate, not a transient PR-scoped new-code green.
```

## Status Trail

- plan_marshall_plan_id: (set at launch)
- pr: (set when the PR opens)
- landing: (set when recorded at landings/PLAN-07.md)
