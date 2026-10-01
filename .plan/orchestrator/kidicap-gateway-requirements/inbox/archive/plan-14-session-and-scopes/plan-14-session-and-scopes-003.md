envelope_version=1
sender_type=plan
sender_id=plan-14-session-and-scopes
epic=kidicap-gateway-requirements
kind=candidate-lesson
created=2026-09-21T22:25:07Z

# Candidate lesson: ci verb surface rejections (ci_verify flags, pr edit body kind)

**Source signal**: script-failure cluster during finalize of plan-14-session-and-scopes (tools-integration-ci).

**Observation**:
- The `ci_verify` run invocation requires `--pr-number`, `--provider` and `--worktree-path`; an invocation without them was rejected by argparse.
- `pr edit` requires a body prepared with `pr prepare-body --for edit`; a body slot prepared for the pr-create kind is not consumed by `pr edit`.

**Suggested corrective action**: the finalize workflow docs / canonical-invocation blocks that drive these calls should state the required flags and the `--for edit` body kind explicitly, so the first invocation is correct rather than recovered after an argparse rejection. Consider a clearer error message when a create-kind slot is offered to `pr edit`.

**Classification**: deferred to orchestrator.
