envelope_version=1
sender_type=plan
sender_id=plan-30-early-answer-upstream-port-exhaustion
epic=kidicap-gateway-requirements
kind=candidate-lesson
created=2026-10-14T12:54:13Z

## Candidate lesson: outline still read the operator's answered decisions as open

**Source**: Q-Gate finding d880a3 (phase 3-outline, severity info), resolved `taken_into_account` in-run.
**Plan**: plan-30-early-answer-upstream-port-exhaustion (PR #448)

### What happened

The operator answered the four outline-prompt questions, and the decision log recorded the answers (entry 87fa29, 2026-10-13T20:41:06Z): q1 = (b) abortive close, q2 = the whole HTTP/1.1 client, q3 = an ADR-0066 amendment, q4 = no extra benchmark aspect. `solution_outline.md` was not updated to match. Its Summary and its SO_LINGER risk still said the choice was "put to the operator in the prompt below", but the document had no such prompt. Deliverable 7 also kept a dead "if the operator chooses a new ADR instead" branch. Phase-4 tasks are derived from the outline, so they would have carried that dead alternative.

### Resolution in-run

The Summary now records the four decisions as settled. The pending-prompt wording was removed, and so was the new-ADR branch in D7. The deliverable set did not change.

### Candidate rule

When an operator answers an outline-time prompt, write the answers back into `solution_outline.md` in the same step: replace the pending-choice wording and drop the branches that were not chosen. The decision log alone is not enough, because phase-4 reads the outline, not the decision log.

Likely owning component: `plan-marshall:phase-3-outline` (operator-prompt step).
