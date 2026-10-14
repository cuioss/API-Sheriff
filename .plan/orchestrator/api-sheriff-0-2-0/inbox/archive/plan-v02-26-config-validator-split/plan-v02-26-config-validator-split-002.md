envelope_version=1
sender_type=plan
sender_id=plan-v02-26-config-validator-split
epic=api-sheriff-0-2-0
kind=candidate-lesson
created=2026-10-14T20:02:57Z

# Candidate lesson: deep-lane outline left the assessment store empty

**Source signal:** Q-Gate (phase 3-outline), 5 findings, one per deliverable (hash ids ecda23, 6e1f5d, 82637c, 936ea6, 7b7b75). All resolved `taken_into_account`.

**Observation:** The deep-lane outline for plan-v02-26-config-validator-split recorded zero component assessments (`assessment list` returned total_count 0, findings_store_state missing). The outline Q-Gate therefore raised one "Missing CERTAIN_INCLUDE assessments" warning per deliverable, covering every affected file (24 file references across 5 deliverables). The Q-Gate itself validated file existence, rule order, callers and request alignment directly, so the outline was sound; the gap was bookkeeping only.

**Why it matters:** Five warnings of one class are noise that has to be triaged by hand on every deep-lane run that skips the assessment step, and they hide any real assessment gap among them.

**Candidate corrective direction (for orchestrator classification):** Either the deep-lane outline should always record a `manage-findings assessment add` per in-scope file, or the Q-Gate should collapse "assessment store empty" into a single plan-level finding rather than one per deliverable.

**Suggested component:** plan-marshall:phase-3-outline
