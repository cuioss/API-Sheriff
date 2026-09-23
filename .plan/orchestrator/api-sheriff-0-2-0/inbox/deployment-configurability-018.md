envelope_version=1
sender_type=orchestrator
sender_id=deployment-configurability
epic=api-sheriff-0-2-0
kind=candidate-lesson
created=2026-09-23T15:48:28Z

# Candidate lesson (handed off from `deployment-configurability`'s local corpus): A diagnostic doc stated supporting evidence as proof, contradicting its own presumption-plus-evidence framing

Original lesson `2026-09-23-15-002` (component `api-sheriff`).

## What happened

The new contributor page `doc/development/local-test-environment-caveats.adoc` said that the same
test being green in CI on the same commit, or green on `main`, "establishes that the tree is not the
cause". A review bot pointed out this contradicts the page's own presumption-plus-evidence framing: a
green baseline SUPPORTS the environmental hypothesis but does not PROVE non-causality, and the page
did not ask the reader to identify the foreign port holder. This slipped past the outline Q-Gate,
pre-submission self-review (clean, 0 candidates), and the security/simplify sweeps, and was caught
only by the external review bot.

## Candidate rule

Diagnostic or triage guidance docs should not state supporting evidence as proof. Word each check by
the strength of evidence it actually gives ("supports", "does not by itself establish"), and check
that each step agrees with the page's own stated framing. Self-review of prose docs could include an
explicit "evidence-strength overclaim" check — this class of defect is invisible to the usual
code-shaped self-review passes.

## Source

`deployment-configurability` PLAN-29 (PR #348), pr-comment finding `412fa1` (CodeRabbit, resolution
`fixed` via TASK-3, commit `927947c`). Fixed: the trusted-baseline step was qualified and now asks the
reader to identify the foreign process holding the port where possible. A follow-up suggestion that
would have inverted the page's "presumptively environmental" convention was declined as contrary to
plan intent.
