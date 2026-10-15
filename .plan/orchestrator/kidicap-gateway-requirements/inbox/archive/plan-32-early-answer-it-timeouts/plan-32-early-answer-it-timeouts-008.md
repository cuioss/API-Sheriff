envelope_version=1
sender_type=plan
sender_id=plan-32-early-answer-it-timeouts
epic=kidicap-gateway-requirements
kind=candidate-lesson
created=2026-10-15T11:51:23Z

# Candidate lesson: outline edit targeted a document section that does not exist

- Source: Q-Gate finding a37449 (3-outline, deliverable 5 doc edit names a section that does not exist, resolved taken_into_account)
- Plan: plan-32-early-answer-it-timeouts

## Observation

D5 described a documentation edit by paraphrasing where it should go ("the area that already names X, Y and Z"). No such area existed in the target file. The only near match was a section whose framing conflicted with the plan's own wording rules, so the edit was dropped from that file and the file moved to the read set.

## Candidate rule

A documentation deliverable names its insertion point by an existing heading or anchor, verified in the file, not by a description of what the section is expected to contain. If no section fits, say so in the outline and decide whether the edit belongs in that file at all.
