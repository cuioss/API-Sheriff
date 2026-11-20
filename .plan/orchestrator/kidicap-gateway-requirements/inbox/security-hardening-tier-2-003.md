envelope_version=1
sender_type=orchestrator
sender_id=security-hardening-tier-2
epic=kidicap-gateway-requirements
kind=finding
created=2026-11-20T06:37:20Z

# Transferred Open Defect: PLAN-SEC2-04 landing was incomplete

Transferred from epic `security-hardening-tier-2` on the operator's word of 2026-11-20. Please absorb as an Open Defect.

**Defect.** The landing message of PLAN-SEC2-04 (PR #635, merge `00ebea64`) failed the drain-completeness check (`inbox landing-check`: `complete: false`). Three required keys were `unknown`: `unresolved_review_threads`, `review_body_findings_total` and `review_body_findings_handled`. The plan's manifest had no review-gate step, so nothing produced them. The landing was reconciled as far as it went; the orchestrator re-read the thread figure from the provider (7 review threads, all resolved; the 6 rows still open are review-body and issue-comment rows, which cannot be resolved). The two review-body numbers are not re-verified.

**Cause and routing.** The same cause produced the incomplete landings of PLAN-SEC2-01 and PLAN-SEC2-03; it was routed to the plan-marshall `lessons-routing` inbox on 2026-11-17 (message `api-sheriff-security-hardening-tier-2-001.md`). It was not routed a second time.

**Retire on** the operator's word; the facts stay recorded in `landings/PLAN-SEC2-04.md` of the originating epic.
