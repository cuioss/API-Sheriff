envelope_version=1
sender_type=plan
sender_id=sec2-05-early-response-quiet-exit
epic=security-hardening-tier-2
kind=candidate-lesson
created=2026-11-18T05:29:17Z

# Candidate lesson: re-stamp head_at_completion after committing a mutates_source + head_dependent settle step

**Signal source:** script failure in phase-6-finalize (pre_merge_gate refusal).

**Mechanism.** When a finalize step that is both `mutates_source` and `head_dependent` (e.g. a review-comment settle step) produces edits that the dispatcher commits, HEAD moves after that step recorded its completion. The dispatcher's item-5f head_at_completion re-stamp is what re-binds the step's completion record to the new HEAD. If that re-stamp is skipped, `pre_merge_gate` sees a step gated against a stale HEAD and refuses with `merge_candidate_not_gated`.

**Corrective rule.** After committing a mutates_source + head_dependent step's edits, always run the item-5f head_at_completion re-stamp before reaching the pre-merge gate. Treat `merge_candidate_not_gated` immediately after such a commit as a missed re-stamp, not as a real gating gap.

**Suggested component:** plan-marshall:phase-6-finalize (dispatcher item 5f / pre-merge gate). Global-store candidate; route to plan-marshall lessons-routing.
