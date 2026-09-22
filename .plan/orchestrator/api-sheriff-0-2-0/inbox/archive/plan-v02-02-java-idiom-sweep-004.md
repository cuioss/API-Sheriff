envelope_version=1
sender_type=plan
sender_id=plan-v02-02-java-idiom-sweep
epic=api-sheriff-0-2-0
kind=candidate-lesson
created=2026-08-09T04:18:27Z

component=plan-marshall:marshall-orchestrator
category=anti-pattern
proposed_title=A spec-asserted count is a claim, not a measurement — re-derive it from the spec's own breakdown

# A spec-asserted count is a claim, not a measurement — re-derive it from the spec's own breakdown

`plan-v02-02-java-idiom-sweep`'s spec asserted that deliverable D3 had **19**
occurrences. The measured count was **20** — and the spec's *own* per-carrier
breakdown, sitting a few lines below the headline number, also summed to 20. The 19
was an arithmetic error inside a single document, checkable against that document
alone, requiring no codebase access at all.

It was not checked. It propagated verbatim through **spec -> clarified request ->
outline**, arriving at implementation as three mutually-corroborating statements of the
same wrong number. Each downstream copy made the figure look better-established than it
was, which is exactly the mechanism that keeps this class of error alive.

The same spec's D2 seed named **one** deprecated-`host()` carrier where there were
**two**. That one was caught, but only because the outline ran an explicit re-grounding
pass — not because anything checked the seed against itself.

## Rule

1. When a plan spec states a count **and** enumerates the items it is a count of, sum
   the enumeration before using the headline figure. A disagreement is a defect in the
   spec, not an ambiguity to average over.
2. Treat every spec-supplied count and every spec-supplied carrier list as a **seed to
   verify**, never as a measurement to consume. Re-grounding is what caught the D2
   under-count and is what should have caught the D3 one.
3. Correcting the number in the plan's own artifacts is not sufficient — the **spec is
   the durable copy**, and only the epic can fix it. A plan that silently corrects
   downstream leaves the next reader to re-inherit the error.

## Provenance

First-party from the run: the corrected 20 was measured, all three downstream copies
were fixed in-flight, and the spec correction was routed back to the epic as landing
residue because the plan cannot write it.
