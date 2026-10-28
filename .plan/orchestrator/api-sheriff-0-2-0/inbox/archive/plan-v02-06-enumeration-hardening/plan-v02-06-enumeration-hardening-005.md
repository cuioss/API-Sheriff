envelope_version=1
sender_type=plan
sender_id=plan-v02-06-enumeration-hardening
epic=api-sheriff-0-2-0
kind=candidate-lesson
created=2026-10-27T14:23:06Z

component=plan-marshall:phase-6-finalize
category=anti-pattern

# The functional-review author put prose into the evidence array, and the validator rejected the verdict

The functional-review author wrote free-text explanations into the verdict's `evidence` entries. The validator treats every `evidence` entry as a path that must exist at the graded head, so it rejected the verdict with `evidence ... does not exist at the graded head`. That cost the one allowed inline repair.

## Corrective action

`evidence` holds only repository paths (optionally with a line anchor) that exist at the graded head. Reasoning belongs in the verdict's narrative field. The author prompt should state this rule with one valid and one invalid example. The validator error should name the field that is meant for prose.
