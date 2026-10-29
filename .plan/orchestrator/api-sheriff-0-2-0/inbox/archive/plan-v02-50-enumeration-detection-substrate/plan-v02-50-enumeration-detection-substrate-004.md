envelope_version=1
sender_type=plan
sender_id=plan-v02-50-enumeration-detection-substrate
epic=api-sheriff-0-2-0
kind=candidate-lesson
created=2026-10-29T07:59:17Z

# Candidate lesson: file:line citations in docs go stale, and the review-evidence validator enforces an exact citation format

**Source signal:** automated review (5 CodeRabbit findings remediated in-run, resolution=fixed).

**Observation:** Several CodeRabbit findings were stale `File.java:NNN-MMM` line-range citations in documentation: code edited earlier in the same branch had shifted the lines the doc pointed at. Separately, the review-evidence rows are checked by a validator that accepts only its exact citation format, so a hand-written citation in a slightly different shape was rejected and had to be redone.

**Corrective rule:** Before opening the PR (and after any later code edit on the branch), re-verify every `file:line` citation the branch adds or touches against the final code, ideally citing the method/symbol alongside the range so drift is obvious. When writing review-evidence rows, copy the validator's documented citation format exactly rather than paraphrasing it, and run the validator before committing.

**Scope:** project documentation and review-evidence practice.
