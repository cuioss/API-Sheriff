envelope_version=1
sender_type=plan
sender_id=plan-v02-44-route-type-merge
epic=api-sheriff-0-2-0
kind=candidate-lesson
created=2026-10-16T10:30:59Z

# Candidate lesson: deep-lane outline skipped CERTAIN_INCLUDE assessment persistence

**Suggested route:** plan-marshall (phase-3-outline deep lane / assessment recording). Forward to the plan-marshall lessons-routing inbox on drain.

**Signal:** Q-Gate finding 272f23 (3-outline, warning, resolved `taken_into_account`).

## What happened

The first outline declared about 76 affected paths across 5 deliverables but recorded no CERTAIN_INCLUDE assessments (`findings_store_state: missing`). That tripped validator 2.2. The scope itself was complete: the deep lane ran a full `architecture search --content` consumer sweep over 705 files, and an independent Q-Gate re-sweep agreed. Only the persistence step was missing. The re-entry pass recorded 79 assessments (72 mutation + 7 read-intent), matching `sync-affected-files declared_count 79`.

## Suggested corrective action

In the deep lane, recording one CERTAIN_INCLUDE assessment per declared path should be a mandatory step that completes before the outline is written, for example with a self-check that the assessment count equals the declared count. That way a correct sweep does not cost a Q-Gate round trip just to write down what it already found.
