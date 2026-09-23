envelope_version=1
sender_type=plan
sender_id=trusted-proxy-breadth-and-probe-doc
epic=deployment-configurability
kind=candidate-lesson
created=2026-09-05T00:09:55Z

## Candidate: a doc paragraph asserted the opposite of the three sentences that followed it

**Signal source**: `signal_automated_review_count` — remediated-in-run pr-comment finding `b8f5e6`
(CodeRabbit, PR #267, `doc/user/environment-variable-overrides.adoc:94`), resolution `fixed`,
remediated on-branch by TASK-005.

**What happened**

The plan authored a paragraph in `doc/user/environment-variable-overrides.adoc` stating that moving
`QUARKUS_MANAGEMENT_PORT` *"also moves the target of the image's baked HEALTHCHECK, which does not
follow it"*. The next three sentences of the same paragraph state the opposite and are correct: the
probe is hard-coded to `9000`, reads no configuration at all, and therefore does not follow the
management port anywhere.

The two readings cannot both hold. Read the ordinary way — the target being the address the probe
aims at — the first sentence asserts the target moves; the rest assert nothing moves. CodeRabbit
caught it as a functional-correctness issue and proposed the corrected wording; TASK-005 on the same
branch rewrote the sentence to say that moving the port moves the management interface *away from*
the target of the baked HEALTHCHECK, which does not follow it.

**Why it is candidate-lesson material**

This is the slipped-then-caught class: the defect was authored, survived the plan's own verification
sweep, and was found only by an external review bot. The mechanism is worth the orchestrator's
attention because it is not a typo — it is a *self-contradiction inside one paragraph*, which no
build, test, or link check can observe. The paragraph read plausibly sentence-by-sentence; only
reading it as a whole exposed the contradiction.

The generalisable shape: **prose that asserts a behaviour and then explains the mechanism is two
claims, and the two are checkable against each other.** A documentation deliverable whose body
states both a consequence and the mechanism producing it should be re-read as a pair before it is
considered done — the same "assert the claim against its own stated mechanism" check the plan
applied to code and did not apply to the adjacent prose.

**Cross-plan relevance for the epic**

`deployment-configurability` is by nature an epic whose deliverables are largely *statements about
what a knob does*. Every plan in it authors override/behaviour prose of exactly this shape, so this
is a recurrence risk across the epic rather than a one-off in this plan.

**Evidence**

- finding `b8f5e6`, type `pr-comment`, resolution `fixed`, responded `2026-09-04T21:51:07Z`
- reviewed commit `ca0e5cecd8cb46115e811e011897acacd1d3e609`
- fix task: TASK-005 on `feature/trusted-proxy-breadth-and-probe-doc`
- landed as PR #267, merge commit `558a38b`
