# WS-03: BFF login availability

epic: security-hardening-tier-1

> Charter document for one workstream — a coherent slice of the epic with its own goal
> and surface. Lives at `workstreams/WS-NN-{slug}.md` and is tracked in the
> `workstreams[]` field of the epic header, `status.json`. See
> `persona-plan-orchestrator/standards/orchestration-model.md` for the tier contract.

## Charter

Make the BFF's pending-authorization store fair under load: a flood of anonymous login
initiations from one source must not evict other users' in-flight login, step-up or widening
records, and a store full of live records must refuse new initiations instead of silently
breaking someone else's login.

## Scope

- In scope: `bff/pending/**`, the three record producers (`bff/login/LoginFlow`, `bff/login/SessionWidening`, `bff/refresh/StepUpCoordinator`), the source plumbing they need, `quarkus/BffRuntimeProducer`, the refusal's event and log record, their tests, and the threat-model / BFF docs for the pending store.
- Out of scope: a general login-initiation rate limiter (`rate_limit` stays planned), logout CSRF and back-channel logout (other tiers), the session store.

## Plans

| Plan | Status | Notes |
|------|--------|-------|
| PLAN-SEC-03-pending-authorization-store-fairness | staged | Expired-first purge; per-source quota; refuse at capacity instead of evicting live records |

## Sequencing and Surface Notes

- Disjoint from WS-01 and WS-02 in production code; shares only `doc/security-threat-model.adoc` and `doc/LogMessages.adoc`.
