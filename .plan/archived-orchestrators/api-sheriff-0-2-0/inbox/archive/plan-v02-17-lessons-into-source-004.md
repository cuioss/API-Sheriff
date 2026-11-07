envelope_version=1
sender_type=plan
sender_id=plan-v02-17-lessons-into-source
epic=api-sheriff-0-2-0
kind=candidate-lesson
created=2026-08-09T19:11:48Z

component=plan-marshall:phase-5-execute
category=improvement
title=A plan spec's factual premise must be re-established at execution time, not inherited

# A plan spec's factual premise must be re-established at execution time, not inherited

Deliverable D1(a) of `plan-v02-17-lessons-into-source` was specced to document that reaching the
OpenRewrite fixed point "is the durable fix nobody has done". A full-reactor gate run performed
during execution showed the repository IS at the fixed point today. The executor escalated
instead of landing the specced claim, and the shipped text records the observed state plus the
drift risk.

## Does this generalise?

Yes, and the mechanism is structural rather than particular to this deliverable.

A plan spec is authored at time T0 and executed at T1. Any spec sentence that asserts a fact
about **current repository state** — "X is not done", "Y is still broken", "no one has Z" — is a
measurement taken at T0 and shipped as an assertion at T1. Nothing in the plan pipeline
re-measures it. The gap is widest exactly where these claims are most tempting to write: a
documentation deliverable, where the claim IS the deliverable, so landing it unverified produces
a document that is confidently and permanently wrong with no test to catch it.

The distinguishing feature of this class is that the spec is not *ambiguous* — it is *precise
and false*. Clarification passes and Q-Gates check whether a deliverable is well-specified, not
whether its premise still holds. A well-specified false premise passes every gate and lands.

## Corrective action

When a deliverable's content asserts a fact about current repository or tooling state,
re-establish that fact at execution time before writing the text, using the cheapest sufficient
observation (a gate run, a targeted query, a single command). Two outcomes:

- **Premise holds** — proceed, and prefer wording that describes the observed state and the
  drift risk over wording that asserts a permanent absence.
- **Premise falsified** — escalate rather than landing the specced sentence. Rewriting the claim
  silently is also wrong: the falsification is information the plan's author and the epic need.

Do not treat the escalation as a plan failure. Landing a precise false claim is the failure; the
escalation is the mechanism working.

## Related

This is the execution-time sibling of the already-recorded observation that a gated documentation
fix must be re-derived because the gating PR can move the facts the doc describes. Same root:
a documentation claim measured at one time and shipped at another. The orchestrator should judge
whether these are one lesson or two.
