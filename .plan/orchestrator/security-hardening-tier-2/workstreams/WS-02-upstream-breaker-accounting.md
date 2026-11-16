# WS-02: Upstream breaker accounting

epic: security-hardening-tier-2

## Charter

Make the per-upstream circuit breaker count what the upstream did and nothing else: an upstream
that never answers must open it, and outcomes caused by the client or by local saturation must
neither dilute nor trip it. Closes the residuals of the bounded connection wait shipped in #620.

## Scope

- In scope: `edge/upstream/**` (guards, dispatch, failure mapping, departure handling), their
  tests, ADR-0060 and the GW-07 passages of the threat model.
- Out of scope: edge admission, BFF, detection (WS-03); documentation-only fixes (WS-01).

## Plans

| Plan | Status | Notes |
|------|--------|-------|
| PLAN-SEC2-02-upstream-breaker-accounting | staged | hung-upstream breaker, breaker neutrality, departure wake-up, wait/deadline attribution |

## Sequencing and Surface Notes

- After PLAN-SEC2-01 and the notes re-check. May run beside PLAN-SEC2-03 (disjoint production code; two shared doc files).
