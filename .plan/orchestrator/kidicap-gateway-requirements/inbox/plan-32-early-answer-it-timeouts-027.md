envelope_version=1
sender_type=plan
sender_id=plan-32-early-answer-it-timeouts
epic=kidicap-gateway-requirements
kind=candidate-lesson
created=2026-10-15T11:52:02Z

# Candidate lesson: small per-arm samples supported a hypothesis that a large sample refuted

- Source: orchestrator-observed; plan history (the first outline's per-connection-linger design, refuted in D2, which drove two re-outlines; see Q-Gate findings 5b2431, da009b on the stale assessments it left)
- Plan: plan-32-early-answer-it-timeouts

## Observation

An early fix hypothesis (a socket-linger setting) looked supported by small diagnostic samples. A 120-run-per-arm comparison refuted it. The plan had already been outlined around the hypothesis, and unwinding it cost two re-outlines and several stale-assessment Q-Gate rounds.

## Candidate rule

For intermittent failures (low single-digit percent), set a minimum sample size per arm before an arm's result may change the outline. Size it so the expected number of failures in the control arm is clearly above zero (for example 100+ runs at about 1-5% rates). Record arm results as "n runs, k failures" and never as "fixed" or "not fixed".
