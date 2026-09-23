# WS-09: Closeout Residual Hardening

epic: deployment-configurability

## Charter

Opened 2026-09-22 by orchestrator `analyze`/`status` sweep, at operator request, after a review of every
remaining Open Defect and every parked plan found six genuinely still-open, unowned items that had never
been staged as their own plan — an operator-ruled-but-unimplemented security-preset gap, a config-coercion
gap, a realm/logout coverage gap, latent path-normalisation debt, a stale doc citation, a flaky-test ceiling,
and an undocumented project-config decision. None shares a mechanism with an existing workstream's charter
closely enough to fit inside it without diluting that charter's own closing criterion (WS-08's scope is
explicitly the outbound-TLS/cookie/trusted-proxy mechanism family; this bundle is not that). The workstream
closes when every deliverable below either ships or is explicitly declined with a recorded rationale.

## Scope

- In scope: the `SecurityProfile` `paranoid()` option; `ConfigLoader.coerce()`'s missing object/map case
  (`tls.passthrough_sni`); the integration realm's back-channel-logout unreachability; root-path (`/`)
  normalisation consolidation; the stale `doc/technical_aspects.adoc:307` version citation;
  `WebSocketRelayStageTest`'s shared 5-second teardown ceiling; `.plan/marshal.json`'s undocumented
  `re_review_on_loopback: false`.
- Out of scope: anything already shipped by PLAN-24/25/26 (re-verified before this workstream was opened —
  see the plan spec's Claim Labels); the outbound-TLS/cookie/trusted-proxy mechanism family owned by WS-08;
  new feature work not already recorded as an Open Defect at epic close.

## Plans

| Plan | Status | Notes |
|------|--------|-------|
| PLAN-28-closeout-residual-hardening | shipped (#341) | 7 deliverables, all landed |
| PLAN-29-final-gap-closure | staged | 3 deliverables — the epic's close-readiness sweep found these are the only items STILL genuinely open and actionable after re-verifying every Open Defect against HEAD. Most had already shipped (mainly via PLAN-25/#306 and PLAN-28) but were never marked resolved; two more (`GatewayEdgePipelineTest`'s flake, `TlsEdgeProducerTest`/`SniFrontListenerTest`) turned out already fixed by unrelated PRs from sibling epics, confirmed by direct code read while drafting this plan and excluded before staging |

## Sequencing and Surface Notes

- No other plan is `staged`/`launched`/`running` in this epic — PLAN-29 has no live-queue sibling to
  sequence against or collide with.
- PLAN-28 touched `SecurityProfile.java`, `ConfigLoader.java`, the integration realm JSON, root-path
  call sites, `doc/technical_aspects.adoc`, `WebSocketRelayStageTest.java`, and `.plan/marshal.json` —
  all shipped, none of which PLAN-29 revisits.
- PLAN-29 touches `TokenRefreshCoordinator.java` (new surface for this workstream),
  `BuildGateCoverageContractTest.java`, and a new testing doc for the port-collision caveat — no
  overlap with any terminal plan's declared surface.
- This is intended as the epic's LAST plan. Deliberately excluded (not owned by this epic, or not
  plan-shaped work): the ADR-0050 cross-epic collision (routed to `api-sheriff-0-2-0`), the contended
  local-verify budget under `parallelization_scope=3` (a plan-marshall/process matter), and
  `.plan/marshal.json`'s residual version drift (routine `/marshall-steward` housekeeping, not code).
