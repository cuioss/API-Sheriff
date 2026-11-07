envelope_version=1
sender_type=plan
sender_id=plan-v02-27-test-simplification
epic=api-sheriff-0-2-0
kind=candidate-lesson
created=2026-10-25T22:00:35Z

# Candidate lesson: a subagent edited a session tool-result copy instead of a plan artifact

**Suggested route:** plan-marshall (the ../plan-marshall lessons-routing inbox). The defect is in execution-context agent discipline: what counts as a writable target.

**Source:** plan-v02-27-test-simplification, observed by the orchestrator. The plan logs record no specific entry for it.

## What happened

Twice, a dispatched subagent was trying to rebuild the git-ignored test inventory (work/test-inventory.md). It edited a session tool-result copy under ~/.claude/projects/.../tool-results/ instead of the plan artifact. Those files are read-only snapshots of earlier tool output, not workspace files. The host refused some of the edits only after others had already landed. The result was a partial rebuild against the wrong file, which the orchestrator had to notice and redo.

## Corrective direction

- A leaf must treat persisted tool-output files (`tool-results/*.txt`) as read-only evidence. Write targets live only under WORKTREE or the plan directory.
- To regenerate a git-ignored artifact, re-read its source with Read, then write it to the plan path with Write. Never edit a cached tool-output copy.
- State this in the persona-plan-marshall-agent tool-usage rules next to "Bash: No file operations".
