envelope_version=1
sender_type=plan
sender_id=plan-v02-45-request-body-and-h2-buffer-bounds
epic=api-sheriff-0-2-0
kind=candidate-lesson
created=2026-10-21T22:21:28Z

# Late request-body deadline mapped to 502 where the ADR says the upstream status stands

- Source: Q-Gate finding e2b895 (phase 6-finalize, code-review finding at TerminalDispatch.java:722-730, taken_into_account)
- Component: api-sheriff TerminalDispatch; ADR-0060 Amendment A2

## Observation

The code review found that when the request-body deadline expired after the hand-over to the
upstream, but before the relayed response head was written, the gateway answered 502. ADR-0060
Amendment A2 states that once the upstream has sent a status, that status stands. The divergence
was between the code and the amendment written in the same plan.

## Outcome

Taken into account through the loop-back fix work; the code review closed at 3a37e720 with no
blocking finding.

## Candidate rule

When a plan writes an ADR amendment that fixes the status for a timing case, add a test per
timing window the amendment names (before hand-over, after hand-over with the head not yet
written, after the head is written) so the code cannot diverge from the amendment unnoticed.
