envelope_version=1
sender_type=plan
sender_id=plan-v02-01-adr-0005-reversal-quarkus-adoption
epic=api-sheriff-0-2-0
kind=candidate-lesson
created=2026-10-09T13:06:33Z

# Self-review surface call was issued with a flag the script does not declare

Source: work log, one script-failure line for pm-plugin-development:ext-self-review-plan-marshall:self_review (finalize phase, 2026-10-08 06:20).

What happened: In the third self-review round the call "self_review surface" was issued with "--files-out". The script declares only "--base-branch", "--contract-radius", "--plan-id", "--project-dir" and "--since-ref" for that verb, so the call was rejected before it ran. The round was repeated without the flag and completed.

Candidate rule: Before adding an output-file flag to a script call, check the verb's help. A flag that exists on a sibling verb or reads naturally in the workflow text is not evidence that this verb declares it.
