envelope_version=1
sender_type=plan
sender_id=plan-v02-01-adr-0005-reversal-quarkus-adoption
epic=api-sheriff-0-2-0
kind=candidate-lesson
created=2026-10-09T13:06:28Z

# The same wrong flag was passed to get-deliverable four times in one run

Source: work log, four script-failure lines for plan-marshall:manage-solution-outline:manage-solution-outline (execute phase).

What happened: The call "manage-solution-outline get-deliverable" was issued with "--number". The script declares "--deliverable-number", so the call was rejected before it ran. This happened four times, at 10:46, 11:53, 12:17 and 13:53 on 2026-10-07, each time at the start of a new execute hand-off. Each time the caller corrected itself and continued, so no work was lost, but the first correction did not reach the later hand-offs.

Candidate rule: A flag correction made inside one hand-off is lost when the next one starts. Where the execute workflow reads a deliverable, it should carry the exact call with "--deliverable-number", so each new hand-off copies it instead of guessing.
