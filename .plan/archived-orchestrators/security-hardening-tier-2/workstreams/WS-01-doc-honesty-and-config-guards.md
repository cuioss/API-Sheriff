# WS-01: Doc honesty and config guards

epic: security-hardening-tier-2

## Charter

Make tracked documentation and Javadoc state what the code does, and add the cheap configuration
guards and header hygiene the 2026-11-14 ground-truth check found missing. Low-risk changes that
ship first, before the code plans.

## Scope

- In scope: doc/Javadoc corrections, the HTTP/2 header-list bound, configuration refusals and
  log-echo hygiene in `config/validation/rule/**`, the management plain-HTTP audit predicate, the
  failed-relay head's CDN cache fields.
- Out of scope: breaker accounting and dispatch behaviour (WS-02), BFF logout and source keying
  (WS-03).

## Plans

| Plan | Status | Notes |
|------|--------|-------|
| PLAN-SEC2-01-doc-honesty-and-config-guards | staged | docs, h2 bound, issuer-key refusal, config echo hygiene, management audit, CDN fields |

## Sequencing and Surface Notes

- Runs first and alone. Touches Javadoc only in `edge/upstream/DispatchStage` and `edge/terminal/TerminalDispatch`, which PLAN-SEC2-02 changes in code later.
