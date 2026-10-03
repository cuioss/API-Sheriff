envelope_version=1
sender_type=plan
sender_id=plan-16-application-portal
epic=kidicap-gateway-requirements
kind=candidate-lesson
created=2026-09-22T17:13:22Z

# Candidate lesson: manage-solution-outline get-deliverable invoked with --number instead of --deliverable-number

- Signal source: script-failure cluster 2 of 2 (work.log 09:54:25, [ERROR] script_failure)
- Component: plan-marshall:manage-solution-outline (get-deliverable) / plan-marshall:phase-5-execute (caller)
- Suggested category: anti-pattern

## What happened

After the deliverable-2 commit, phase-5-execute called
`plan-marshall:manage-solution-outline:manage-solution-outline get-deliverable ... --number N` and got exit_code=2,
argparse_rejection: "`--number` is not declared for ... get-deliverable: ['deliverable-number', 'plan-id']".

## Why it matters

A verb-paraphrase flag name (`--number`) invented from narrative instead of the canonical typed-ID flag
(`--deliverable-number`). Same class as the documented "never invent script subcommands/flags" recurrence signatures.

## Suggested corrective rule

Use `get-deliverable --plan-id {plan_id} --deliverable-number {N}`. Check whether any phase-5-execute / execute-task
workflow prose says "deliverable number" in a way that invites `--number`, and quote the canonical flag verbatim there.
