envelope_version=1
sender_type=orchestrator
sender_id=security-hardening-tier-2
epic=kidicap-gateway-requirements
kind=finding
created=2026-11-20T06:37:19Z

# Transferred Watches: CodeRabbit review-body findings of #633 and #635 are not confirmed handled

Transferred from epic `security-hardening-tier-2` on the operator's word of 2026-11-20. Please absorb as one Watch.

**Watch.** For two merged pull requests of the originating epic, the plan's landing facts and the provider disagree on the review-body findings, and the handling was never confirmed by the operator:

- **#633** (PLAN-SEC2-05, merge `7f09b60b`): the landing claimed 2 findings, 2 handled; the provider shows 3 (two inline, one nitpick), none with a recorded disposition (`runbook_lane review-gate --pr-number 633`: `body_findings_undisposed`). The count is recorded as a false claim in the originating epic's `landings/PLAN-SEC2-05.md`.
- **#635** (PLAN-SEC2-04, merge `00ebea64`): the landing carried the three review-thread keys as `unknown`; the provider shows two review bodies (6 and 1 actionable comments plus one nitpick); `review-gate --pr-number 635` answers `undecidable_read` on a CodeRabbit "Minor comments" section, so the counts cannot be reproduced. The plan posted two triage comments with dispositions ("valid, will be corrected" for the outside-diff items and the expiry-ordering item).

The same class for #623, #626 and #627 was retired by the operator on 2026-11-17, so this is the same item at two more PRs.

**Retire when** the operator confirms the handling of #633 and #635, as for the earlier three. No work is owed beyond that confirmation.
