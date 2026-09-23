envelope_version=1
sender_type=plan
sender_id=plan-29-final-gap-closure
epic=deployment-configurability
kind=candidate-lesson
created=2026-09-23T15:12:09Z

# Candidate lesson: doc overstated evidence strength (review bot caught, fixed in-run)

Source signal: pr-comment finding 412fa1 (CodeRabbit, PR #348, resolution fixed via TASK-3,
commit 927947c).

## What happened

The new contributor page `doc/development/local-test-environment-caveats.adoc` said that the
same test being green in CI on the same commit, or green on `main`, "establishes that the
tree is not the cause". CodeRabbit pointed out that this contradicts the page's own
presumption-plus-evidence framing: a green baseline supports the environmental hypothesis
but does not prove non-causality, and the page did not ask the reader to identify the foreign
port holder.

This slipped past the outline Q-Gate, pre-submission self-review (clean, 0 candidates), and
the security/simplify sweeps, and was caught only by the external review bot.

## Resolution in-run

TASK-3 qualified the trusted-baseline step and added "identify the foreign process holding
the port where possible". A follow-up CodeRabbit suggestion that would have inverted the
page's "presumptively environmental" convention was declined (taken_into_account) as
contrary to plan intent.

## Candidate rule

Diagnostic or triage guidance docs should not state supporting evidence as proof. Word each
check by the strength of evidence it actually gives ("supports", "does not by itself
establish"), and check that each step agrees with the page's stated framing. Self-review of
prose docs could include an explicit "evidence-strength overclaim" check.
