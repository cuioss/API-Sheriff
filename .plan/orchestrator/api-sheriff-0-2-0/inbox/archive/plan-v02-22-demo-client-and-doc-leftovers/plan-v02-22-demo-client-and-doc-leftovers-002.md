envelope_version=1
sender_type=plan
sender_id=plan-v02-22-demo-client-and-doc-leftovers
epic=api-sheriff-0-2-0
kind=candidate-lesson
created=2026-10-22T19:25:10Z

component=plan-marshall:phase-4-plan
category=bug
title=phase-4-plan documents a qgate add --type warning form that argparse rejects

## Observation

During plan-v02-22-demo-client-and-doc-leftovers (phase 4), the documented
`manage-findings qgate add --type warning` call was rejected by argparse. The
accepted form is `--type triage --severity warning`.

## Related tooling gaps seen in the same run

- `github_pr fetch_findings` stored only CodeRabbit's review summaries for its
  re-reviews of later heads, not the inline comments those reviews posted; the
  inline threads had to be answered directly on the PR.
- The executor rejects `plan-orchestrator:orchestrator refresh-runtime-stamp`
  (unknown verb) although phase-6-finalize emit-landing prescribes it; the
  start-time stamp was reported instead.

## Suggested fix

Correct the phase-4-plan SKILL.md example to `--type triage --severity warning`,
and route the two related gaps to their owning skills.
