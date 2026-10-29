envelope_version=1
sender_type=plan
sender_id=plan-v02-50-enumeration-detection-substrate
epic=api-sheriff-0-2-0
kind=candidate-lesson
created=2026-10-29T07:59:28Z

# Candidate lesson: CodeRabbit hourly allowance and auto-pause need a manual re-trigger

**Source signal:** automated review step on the plan's PR.

**Observation:** With several review-fix pushes in a short window, CodeRabbit hit its hourly review allowance and auto-paused incremental reviews on the PR. It did not resume by itself on the next push; a manual trigger comment was needed to get the re-review that gates the merge.

**Corrective rule:** Batch review fixes into as few pushes as possible. After each fix push, confirm a new CodeRabbit review actually started; if the PR shows the paused / rate-limited notice, wait out the allowance and then post the manual review trigger instead of waiting for an automatic re-review that will not come.

**Scope:** project PR / review-bot workflow.
