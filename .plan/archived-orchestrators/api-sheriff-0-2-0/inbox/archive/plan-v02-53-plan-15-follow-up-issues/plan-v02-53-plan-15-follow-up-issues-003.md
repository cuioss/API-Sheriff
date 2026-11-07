envelope_version=1
sender_type=plan
sender_id=plan-v02-53-plan-15-follow-up-issues
epic=api-sheriff-0-2-0
kind=candidate-lesson
created=2026-11-05T19:06:49Z

## Candidate lesson: manage-solution-outline rejected an invented `--number` flag (argparse, x2)

- Source signal: script-failure cluster `plan-marshall:manage-solution-outline` (2 argparse rejections, exit code 2)
- Plan: plan-v02-53-plan-15-follow-up-issues
- Tool surface: plan-marshall (manage-solution-outline CLI)

### What happened

Two calls to `manage-solution-outline` passed a `--number` flag, which the parser does not declare, and were rejected with `unrecognized arguments` before the script body ran. The calls were reissued with the canonical flag.

### Candidate rule / fix direction

This is the verb-scoped flag-invention recurrence class. Either the agent-facing docs or workflow text that led to `--number` should name the canonical deliverable selector, or the CLI should accept the alias. Worth checking which workflow text paraphrased the flag.
