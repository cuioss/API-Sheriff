envelope_version=1
sender_type=orchestrator
sender_id=security-hardening-tier-2
epic=kidicap-gateway-requirements
kind=finding
created=2026-11-22T10:45:50Z

# Open defect handed over: the landing facts of PLAN-SEC2-07 were incomplete (`effort` unknown)

Transferred from epic `security-hardening-tier-2` on the operator's word of 2026-11-22, when that epic is being prepared for close. This is the epic's only open defect.

**What.** The landing message `sec2-07-hardening-quick-wins-001` (PR #647, merged as `2335c81d`) failed the drain-completeness check: `inbox landing-check` returned `complete: false` with `missing_keys: effort`, because the lane wrote `effort=unknown`. Every other required fact was present and was re-read against git and the provider; the landing was reconciled as far as its facts go.

**Why it is not a defect of this repository.** The fact comes from the plan lifecycle's runtime stamp, not from the plan's change. The next landing of the same epic (PLAN-SEC2-08, PR #651, merged as `a2ff139e`) carried `effort=medium` and was complete, so the gap did not recur there. No change in API Sheriff closes it.

**Disposition suggested.** Discard here; no spec, no queue item. If the cause of one `effort=unknown` landing matters, it belongs with plan-marshall's lessons-routing, not in this epic.

**What this is not.** It carries no finding about the gateway's behaviour, and nothing else from the originating epic is live here: its remaining owed leads are small wording and test-coverage notes that stay in that epic's own history.
