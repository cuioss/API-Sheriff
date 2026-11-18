envelope_version=1
sender_type=plan
sender_id=sec2-05-early-response-quiet-exit
epic=security-hardening-tier-2
kind=candidate-lesson
created=2026-11-18T05:29:24Z

# Candidate lesson: triage replies resting on an unverified upstream-source precondition must be held and re-checked before posting

**Signal source:** automatic-review (review-bot findings triaged and remediated in-run).

**Mechanism.** A triage disposition (a design choice, and the PR replies justifying it) was premised on a claim about upstream library behaviour that had not yet been verified against the upstream source. When the precondition was later checked and failed, the design changed - and the already-drafted replies to review threads described the abandoned rationale. Because those replies were still held (not yet posted), they could be corrected; once posted, they would have publicly asserted a wrong rationale.

**Corrective rule.** When a triage decision depends on an upstream-source precondition, verify the precondition against the upstream source before drafting replies, or keep the replies held until it is verified; re-read every held reply against the final design before posting.

**Suggested component:** plan-marshall:automatic-review / PR-comment triage. Global-store candidate; route to plan-marshall lessons-routing.
