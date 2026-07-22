# Landing Analysis: PLAN-01 — Doc/Code Reconciliation & Quality Debt (roadmap 02b)

epic: api-sheriff-roadmap
workstream: WS-01
pr: cuioss/API-Sheriff#72 (merged; merge commit 715f19e)

> Landing record for one shipped plan. Written by the `analyze` verb after verifying claims
> against ground truth (actual code, artifacts, PR state) — a pasted claim is a lead, never
> a fact.

## Deliverable Fidelity vs Spec

Spec (plans/PLAN-01-reconciliation.md) staged four deliverable groups + the optional stopgap.
The plan lifecycle expanded them to 17 deliverables + 4 review-fix tasks — all shipped.

| Deliverable (spec) | Verdict | Evidence |
|--------------------|---------|----------|
| Group 1: whole-second rule removed, ADR-0009 Accepted, jwks inline deleted | shipped-as-specified | `grep validateWholeSecondTimeouts` → 0 hits; `"inline"` absent from `api-sheriff/src/main`; ms-precision + `::/0` tests per finalize report |
| Group 2: documentation corrections (ports, library claims, naming, IDs, versions, config facts, terminology) | shipped-as-specified | CLAUDE.md parent-pom now `1.5.1` (verified in file); finalize deliverables 3–10 all green |
| Group 3: test hygiene (vacuous tests, Hamcrest, invalid-config wiring, small gaps) | shipped-as-specified | 0 Java files import `org.hamcrest`; `verify-invalid-config-fails.sh` wired as exec execution in `integration-tests/pom.xml:184`; `verify-environment.sh` deleted (wire-or-delete satisfied) |
| Group 4: benchmark harness (wrk counter aggregation, error-rate gate, exact-match post-processor + value tests) | shipped-as-specified | main history: `d3a8669` (WRK_MAX_ERROR_RATE validation + socket-error gating), `dbdb43c` (Optional in WrkResultPostProcessor) |
| Optional stopgap (ProxyRoute body cap + timeout) | shipped-as-specified | Deliverable 17 + review-fix non-positive-timeout guard; explicitly confirmed in scope during the plan |
| Review-fix additions (unplanned) | added-unplanned | 5 bot findings fixed (TASK-024..027), incl. README management port bound to 127.0.0.1 (`bab9534`); 4 declined with replies |

## Metrics and Anomalies

- Tokens: 3,034,667 total; 6-finalize dominates (1.70M — review round-trip + re-fired gates).
- Duration: 3h10m worked / 14h9m wall (10h58m idle).
- Anomalies: 5-execute worked-time (52m) undercounts — envelope-3 usage lost to a session
  interrupt; totals are a floor. Gemini false-positive ("IO needs an import", JEP 512
  auto-import) recorded as an architecture hint so future runs don't re-litigate it.

## Routing and Merge Behavior

- Review: 9 bot comments (CodeRabbit + Gemini); 5 fixed via loop-back, 4 declined with
  replies; re-fire found 0 new. All resolved.
- CI/merge: all checks green (JDK 25+26, integration tests, sonar-build, dependency review,
  CodeRabbit); merged via the platform merge queue. **Merge-method mismatch**: the repo's
  merge queue produced a MERGE commit (25 branch commits landed individually on main,
  confirmed by local history), while the configured `pr_merge_strategy` expects squash —
  recorded as a watch pending operator decision (reconcile via /marshall-steward → Merge
  Queue, or accept MERGE).
- No rebase conflicts / re-verify signals — no surface-collision input for future pairing.

## Reconciliation Actions

- [x] status.json `plans[]` entry updated (PLAN-01 → shipped, pr=#72, landing=landings/PLAN-01.md)
- [x] epic.md queue reconciled from status.json
- [x] Watch opened: merge-queue MERGE vs configured squash `pr_merge_strategy`
- [x] resume_anchor updated
- [x] START-HERE block regenerated

## Follow-Ups

- Merge-strategy mismatch → epic Watch (operator decision; remedies logged by finalize).
- Lesson 2026-07-18-11-001 (wrk gate false-green on unreachable target; Lua files outside
  the Maven verify surface) — already captured in the lessons store by the plan lifecycle;
  no orchestrator action needed beyond noting it feeds future benchmark-touching plans.
