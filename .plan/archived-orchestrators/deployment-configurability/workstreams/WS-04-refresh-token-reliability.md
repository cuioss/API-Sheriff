# WS-04: BFF Refresh-Token Reliability

epic: deployment-configurability

## Charter

A user working with the system normally reported that an exception was logged at refresh time. No
error text, stack trace or reproduction steps are available. The BFF's transparent near-expiry
refresh — the path that fires while a user is simply working — has **no test that ever forces it to
run**: `BffSessionMediationIT` concedes in its own comment that the realm's 900s access-token
lifespan keeps the token inside validity for the whole test, so it exercises session continuity
rather than refresh. This workstream builds the missing trigger and uses it to attempt the
reproduction. It closes when the refresh path is exercised against real Keycloak and the attempt has
produced either a captured exception or a bounded negative result naming what was exercised.

## Scope

- In scope: a short-access-token-lifespan Keycloak fixture; an integration test that actually drives
  `TokenRefreshCoordinator.refresh(...)` through the live BFF edge; assertions on its three outcome
  kinds; and correcting the unsupported claim in `BffSessionMediationIT`'s comment.
- Out of scope: FIXING whatever the reproduction surfaces — a fix cannot be scoped before the defect
  is characterised, so the follow-up plan is staged by a later `analyze`. The `TokenRefreshCoordinator`
  unit suite, which already exists; this workstream adds the integration layer above it.

## Relationship to the rest of the epic

Off the epic's original charter — a defect investigation with no deployment-configuration content —
folded in at the operator's direction on the retired TokenSheriff epic and carried across. Its
surface (BFF refresh, realm fixture, one IT) is disjoint from every other row here.

## Plans

| Plan | Status | Notes |
|------|--------|-------|
| PLAN-05-bff-refresh-integration-coverage | staged | Short-lifespan fixture, an IT that forces the refresh, reproduction attempt |

## Sequencing and Surface Notes

- Queued last by inherited operator placement, not by dependency. Surface-disjoint from every other
  row, so it is the epic's most promotable plan — and the only one answering a live user report.
- The realm import directory is mounted wholesale by the compose stack, so adding a realm or client
  needs no compose change, which keeps this clear of PLAN-01's and PLAN-02's compose surface.
