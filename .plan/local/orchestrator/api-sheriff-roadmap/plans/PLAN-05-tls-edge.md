# PLAN-05: TLS Edge — SNI Passthrough + mTLS (roadmap 06)

epic: api-sheriff-roadmap
workstream: WS-02

> Staged plan spec. Source of truth: `doc/plan/06-tls-edge.adoc` — read in full at launch;
> this spec is a summary staged from `doc/plan/README.adoc`.

## Objective

Execute `doc/plan/06-tls-edge.adoc`: accept-time SNI split — `passthrough_sni` connections
relayed as opaque L4 TCP to the topology-resolved backend, everything else terminated with
`mtls` client-CA verification; the two L4/L7 guards (boot collision rule, runtime
Host-smuggle 404); Toxiproxy joins the compose stack.

## Deliverables

Per the plan doc at launch.

## Expected Surface

- `api-sheriff/` accept path / TLS / L4 relay
- `integration-tests/` compose stack (Toxiproxy), TLS suites; `benchmarks/` if hot-path

## Dependencies and Sequencing

- Depends on: PLAN-03 (roadmap 04); independent of PLAN-04 (roadmap 05)
- Overlaps with: PLAN-04 shares the Vert.x transport neighborhood — check disjointness before
  a parallel launch

## Hand-Off Command

```text
/plan-marshall Execute doc/plan/06-tls-edge.adoc in full (read it and its required reading first; the design docs govern). Deliver the accept-time SNI split, mTLS termination, the two L4/L7 guards, and Toxiproxy in the compose stack, with integration tests in the same plan. DOCUMENTATION (standing epic convention, operator 2026-07-20 — applies to every plan; THREE layers, all in the SAME PR): (a) REFERENCE — every new or changed configuration key, default, enum value, and endpoint this plan introduces MUST land in doc/configuration.adoc, and structural changes in doc/architecture.adoc; these are the exhaustive per-key references and they are not optional. (b) OPERATOR GUIDE — doc/user/, task-oriented (how an operator accomplishes the thing), linking to the reference rather than duplicating its key tables. (c) DEVELOPER GUIDE — doc/development/, contributor-facing (build, test, run, extend). The doc/user/ and doc/development/ trees and the convention itself are created by roadmap plan 10 (anchor types + assets) — NOT by plan 04b, which shipped without them; follow what doc/plan/README.adoc records once plan 10 lands. Updating plan docs, variant docs or ADRs does NOT satisfy (a), (b) or (c). If this plan genuinely changes nothing an operator or a contributor would do differently, state that explicitly in the PR rather than silently skipping it. SONAR ZERO-FINDINGS (standing epic convention, operator 2026-07-21): the SonarCloud quality gate (project cuioss_API-Sheriff) must be GREEN before merge — a red gate is a HARD STOP, never merge over red or on a stale green while analysis is still pending; deliver ZERO new findings (new bugs = 0, new vulnerabilities = 0, new code smells = 0, security hotspots 100% reviewed, new_reliability / new_security / new_maintainability ratings all A, new coverage >= 80%); the GOAL is to FIX findings, and where a fix is genuinely not sensible an IN-CODE suppression carrying a documented rationale (e.g. // NOSONAR or @SuppressWarnings("java:SXXXX") with a justifying comment) is an acceptable way to reach zero because it stays auditable in the repo alongside the code — what is NOT acceptable is reaching zero by silent server-side won't-fix / false-positive marking; and this plan is NOT 'done' / 'all green' until the PROJECT-level gate on the merged result is green — the completion report must reflect the actual post-merge gate, not a transient PR-scoped new-code green.
```

## Status Trail

- plan_marshall_plan_id: (set at launch)
- pr: (set when the PR opens)
- landing: (set when recorded at landings/PLAN-05.md)
