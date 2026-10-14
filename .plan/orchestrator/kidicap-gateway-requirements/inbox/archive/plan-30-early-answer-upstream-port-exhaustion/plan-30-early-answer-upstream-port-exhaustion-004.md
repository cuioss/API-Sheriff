envelope_version=1
sender_type=plan
sender_id=plan-30-early-answer-upstream-port-exhaustion
epic=kidicap-gateway-requirements
kind=candidate-lesson
created=2026-10-14T12:54:36Z

## Candidate lesson: invented verb `manage-findings qgate query` (argparse rejection)

**Source**: script-failure cluster (`plan-marshall:manage-findings:manage-findings`), one of the two distinct failing notations behind `signal_script_failure_clusters_count: 2`.
**Plan**: plan-30-early-answer-upstream-port-exhaustion

### What happened

An agent called `manage-findings qgate query ...`. `query` is not a `qgate` sub-verb, so argparse rejected the call (exit 2) before the script body ran. The real `qgate` verbs are `add`, `clear`, `list`, `resolve`, `resolve-by-rule`, and `resolve-evidenced`. The caller wanted the read verb, `qgate list`.

### Candidate rule

This is the verb-paraphrase recurrence signature: a plausible read verb (`query`) was used in place of the canonical `list`. Copy the sub-verb from `manage-findings` SKILL.md § Canonical invocations, not from workflow prose. Note that `manage-findings` is in `_EXCLUDED_SKILLS` for `manage-invocation-invalid`, so no build-time rule catches a bad `manage-findings` invocation in a doc. If a workflow doc prompted the paraphrase, the `manage-findings-invocation-invalid` rule (currently analyze-only, not in the quality-gate roster) is the structural guard to consider promoting.

Likely owning component: `plan-marshall:manage-findings`. A routing candidate for `pm-plugin-development:recipe-fix-argparse-rejection`.
