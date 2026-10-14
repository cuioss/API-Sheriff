envelope_version=1
sender_type=plan
sender_id=plan-30-early-answer-upstream-port-exhaustion
epic=kidicap-gateway-requirements
kind=candidate-lesson
created=2026-10-14T12:54:03Z

## Candidate lesson: deep-lane outline recorded no CERTAIN_INCLUDE assessments

**Source**: Q-Gate finding 23d874 (phase 3-outline, severity warning), resolved `taken_into_account` in-run.
**Plan**: plan-30-early-answer-upstream-port-exhaustion (PR #448, merged as b7b9869989656b9ee32c06ff80e05a930f4f7910)

### What happened

The first deep-lane outline pass traced the footprint by hand and recorded no component assessments at all. `assessment list --certainty CERTAIN_INCLUDE` returned 0 rows, and the assessments store did not exist (`findings_store_state: missing`). None of the 15 distinct affected files across deliverables 1-7 was backed by an assessment. The Q-Gate assessment-coverage and missing-coverage checks therefore had nothing to compare against, so the declared footprint was asserted rather than evidenced.

### Resolution in-run

The outline revision recorded 15 CERTAIN_INCLUDE assessments (agent `phase-3-outline`), one per affected file, each carrying its deliverable and in-scope reason. Approach item 8 of the outline now states why the first pass had none.

### Candidate rule

When the deep lane traces the footprint by hand, record one `assessment add --certainty CERTAIN_INCLUDE` per affected file before writing `solution_outline.md`. A hand-traced footprint skips the assessment store, and the Q-Gate then has no population to check coverage against.

Likely owning component: `plan-marshall:phase-3-outline` (deep-lane workflow).
