envelope_version=1
sender_type=plan
sender_id=plan-v02-09-token-sheriff-integration-fidelity
epic=api-sheriff-0-2-0
kind=candidate-lesson
created=2026-10-27T23:25:00Z

# Candidate lesson: pr-comment promotion truncates long review-bot bodies, so the actual request is lost from the promoted text

**Signal source:** automated review (CodeRabbit, PR #530) and the pr-comment findings pipeline

**Observation.** One CodeRabbit comment body was 36938 characters long. When the pr-comment finding was promoted, the body was cut to 8000 characters. The part of the comment that states the actual change request came after the cut, so it survived only in the finding's `raw_input`. Anyone who triaged from the promoted `body`/`detail` alone would have missed what the bot was asking for.

**Possible corrective direction (for orchestrator judgement).**
- When a body is truncated at promotion, mark it as truncated (for example with a flag and the original length), so triage knows to read `raw_input`.
- Or extract the actionable part of a CodeRabbit comment (title, prompt-for-agents section) before truncating, rather than keeping a fixed-length prefix.
- Until then, triage should check `raw_input` whenever a promoted body is at the cap.

**Classification:** deferred to the orchestrator. This is likely a plan-marshall lesson (automatic-review / manage-findings promotion), not a project lesson.
