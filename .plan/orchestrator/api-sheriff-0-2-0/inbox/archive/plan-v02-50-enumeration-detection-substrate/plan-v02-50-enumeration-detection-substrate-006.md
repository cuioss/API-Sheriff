envelope_version=1
sender_type=plan
sender_id=plan-v02-50-enumeration-detection-substrate
epic=api-sheriff-0-2-0
kind=candidate-lesson
created=2026-10-29T07:59:38Z

# Candidate lesson: Sourcery refuses oversized PRs, so its absence is expected and not blocking

**Source signal:** automated review step on the plan's PR.

**Observation:** Sourcery declined to review the PR because the diff exceeded its size limit. Because Sourcery is configured as an optional bot (`optional_bots: sourcery`), this did not block the merge, but it left CodeRabbit as the only reviewer for the whole change.

**Corrective rule:** At outline time, estimate the PR diff size against the review-bot limits (CodeRabbit about 100 files, Sourcery about 150k diff characters) and split or trim the deliverable set when a full two-bot review matters. When Sourcery skips for size, record that as expected in the review summary rather than waiting for it, and do not treat its silence as a pending review.

**Scope:** project PR sizing / review-bot workflow.
