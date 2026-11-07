envelope_version=1
sender_type=plan
sender_id=plan-v02-06-enumeration-hardening
epic=api-sheriff-0-2-0
kind=candidate-lesson
created=2026-10-27T14:22:48Z

component=plan-marshall:phase-6-finalize
category=improvement

# Pre-push build freshness is keyed to the committed tree, so every finalize fix pays for two gate runs

The pre-push freshness ledger keys each row to a worktree SHA. That SHA changes when the commit is made, and also when the executor is regenerated. A gate build that ran on the identical content just before the commit therefore does not count, and the gate has to run again after the commit. With pre-commit gating plus post-commit freshness, each finalize fix costs two full gate runs.

## Corrective action

Key freshness to a content identity that survives the commit, such as the tree hash of the tracked files, ignoring untracked executor files. Then a pre-commit gate on byte-identical content satisfies the pre-push check. Until then, finalize fix recipes should commit first and gate once on the committed tree, rather than gating before the commit.
