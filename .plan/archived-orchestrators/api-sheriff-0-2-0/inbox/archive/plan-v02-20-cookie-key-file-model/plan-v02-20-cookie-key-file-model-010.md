envelope_version=1
sender_type=plan
sender_id=plan-v02-20-cookie-key-file-model
epic=api-sheriff-0-2-0
kind=candidate-lesson
created=2026-10-26T22:35:12Z

# Candidate lesson (plan-marshall tooling): outline transition refused because answers changed frozen criteria

- Source: script-failure cluster, notation plan-marshall:phase-3-outline (snapshot-criteria), [ERROR] at 2026-10-26T12:33:25Z
- Suggested component: plan-marshall:phase-3-outline. Route to the plan-marshall lessons-routing inbox.

## What happened

After the operator answered the outline prompts (q1 changed the key file format to base64 text, which revised the success criteria), the transition `--completed 3-outline` was refused: `refreeze_requires_decision` - work/criteria-snapshot.json was frozen at outline approval and re-freezing needs an operator decision logged via criteria-refreeze-authorize. The authorization was logged at 12:48 and the transition went through. The same re-freeze was needed again in 4-plan (13:21).

## Corrective rule / improvement

When an outline-prompt answer is known to change success criteria, record the criteria-refreeze authorization together with the answer in the same step, instead of discovering the need from the refused transition.
