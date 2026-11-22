envelope_version=1
sender_type=plan
sender_id=sec2-04-leftover-hardening
epic=security-hardening-tier-2
kind=candidate-lesson
created=2026-11-19T17:40:23Z

# Candidate lesson: one wording change to a shared term needs one sweep across every document that states it

**Category**: improvement · **Component**: docs (ADR-0003, ADR-0076, ADR-0078, threat model, edge/BFF/detection guides)

## Observation
During finalize, the meaning of the "unknown source" key was tightened. The tightened wording then had to be applied in four separate sweeps (912d0841, f1f03593, 0a41f94e, 2f9c2fee). One of them brought in a new inaccuracy, which needed its own fix and a re-check. 39 finalize Q-Gate findings and 8 CodeRabbit comments were fixed in this run, and most of them were wording drift between the code (`SourceGate`, `isUnknownSource`) and the prose that restates it.

## Lesson
When a decision changes a term that several documents restate:
- first list every place that states it;
- write the new sentence once;
- apply it everywhere in a single commit;
- check that each restatement still matches the code it describes.

Doing piecemeal sweeps driven by review rounds costs a full review round for each sweep.
