envelope_version=1
sender_type=plan
sender_id=plan-16-application-portal
epic=kidicap-gateway-requirements
kind=candidate-lesson
created=2026-09-22T17:13:44Z

# Candidate lesson: orchestration detection disagreed between phase transitions (not_orchestrated) and finalize (orchestrated)

- Signal source: orchestrator observation; only partly verifiable from plan logs
- Component: plan-marshall:plan-orchestrator (inbox detect / store resolution) / plan-marshall:plan-marshall (phase-transition inbox read)
- Suggested category: bug

## What happened

The orchestrator reports that the inbox pointer was classified `not_orchestrated` at phase transitions but
`orchestrated` at finalize (decision.log 17:10:48: "orchestrated=true epic=kidicap-gateway-requirements
detection=orchestrated").

Evidence available in the plan logs:
- decision.log 07:18:37 records `plan_source: .plan/local/orchestrator/kidicap-gateway-requirements/plans/PLAN-16-application-portal.md`,
  i.e. the `.plan/local/orchestrator/...` address, which the `inbox detect` contract calls the RETIRED address and
  classifies as `unrecognised_id` (recognition-only).
- Yet this step's `inbox write --slug kidicap-gateway-requirements` resolved the store to
  `/home/oliver/git/API-Sheriff/.plan/local/orchestrator/kidicap-gateway-requirements/inbox/`, so the live store for this
  epic is still under `.plan/local/orchestrator/`.

## Why it matters

Two surfaces disagree on whether the same plan is orchestrated. If phase transitions read `not_orchestrated`, mailbox
advisories delivered to `inbox/to/{plan_id}/` are never read mid-run, silently.

## Suggested corrective rule

Use one detection seam (with the same source_id input) at every call site, and reconcile the retired-address rule with
the store resolver: either `inbox detect` accepts the `.plan/local/orchestrator/` address wherever the store still
resolves there, or the epic is migrated to the tracked address. Log the detection verdict and its `detection` token at
each phase transition so a disagreement is visible.
