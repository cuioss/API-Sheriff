envelope_version=1
sender_type=plan
sender_id=plan-v02-06-enumeration-hardening
epic=api-sheriff-0-2-0
kind=candidate-lesson
created=2026-10-27T14:22:40Z

component=plan-marshall:workflow-integration-git
category=bug

# A fix task already marked done can no longer sanction its own commit in 6-finalize

In 6-finalize, `git-workflow commit` refuses with `no_sanction` once the fix task it belongs to is marked done. A fix task must therefore stay open until its commit has landed. This plan hit that ordering trap twice.

## Corrective action

Short term, the finalize fix-task recipe must order the steps as edit, gate, commit, then mark-done. Long term, the sanction check should accept a just-closed fix task whose commit is still pending, or the task-done verb should refuse while the task's changes are uncommitted. Either way the trap should fail early, not at commit time.
