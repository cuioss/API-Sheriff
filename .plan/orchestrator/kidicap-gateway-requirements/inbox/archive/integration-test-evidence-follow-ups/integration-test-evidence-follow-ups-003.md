envelope_version=1
sender_type=plan
sender_id=integration-test-evidence-follow-ups
epic=kidicap-gateway-requirements
kind=candidate-lesson
created=2026-10-12T21:08:52Z

# Candidate lesson: a self-review blocking finding forces a further review round

Source: 6-finalize Q-Gate finding cf93b2 (rule pre-submission-self-review-state, fixed in-run, evidenced by 75f19e91), producer pm-plugin-development:ext-self-review-plan-marshall.

## What happened

The pre-submission self-review read the adoc at line 952, the test and the plan diff (counts matched, full scope) and found that the plan had touched the false claim's sentence (see sibling candidate d4e3af). The promoted finding made the blocking set non-empty, so the round decided "no" and a further round was owed. The fix landed in 75f19e91 and both findings resolved evidence-gated.

## Candidate rule

This is the procedural consequence of d4e3af rather than an independent defect: a touched-but-unverified claim costs a full extra self-review round at finalize. Worth recording only as reinforcement of d4e3af (verify claims about a guard when the guard's scope changes, before finalize).

Classification deferred to the orchestrator.
