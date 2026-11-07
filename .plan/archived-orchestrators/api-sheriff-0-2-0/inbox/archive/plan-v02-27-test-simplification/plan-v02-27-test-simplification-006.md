envelope_version=1
sender_type=plan
sender_id=plan-v02-27-test-simplification
epic=api-sheriff-0-2-0
kind=candidate-lesson
created=2026-10-25T22:00:46Z

# Candidate lesson: the pre-commit gate rewrites a fully qualified HttpMethod into its simple name in files that already import it

**Suggested route:** project (stays local to API Sheriff). This concerns the OpenRewrite recipe set in the `-Ppre-commit` build. A recurrence also belongs in the pre-commit formatter churn memory.

**Source:** plan-v02-27-test-simplification. Decision-log entries 0abb39, 863570 and c4b57f.

## What happened

`verify -Ppre-commit` twice rewrote two fully qualified `io.vertx.core.http.HttpMethod` references to the simple name `HttpMethod`. Both rewrites were in GatewayEdgeRouteTest.java, a branch-authored file that already imports the class. The rewrite preserves meaning, so it was kept, as CLAUDE.md requires for branch-authored files. But each occurrence dirtied the tree after a green gate. Each forced a settlement commit and a second gate run. It also blocked the TASK-12 chain-tail commit while the gate build was still running in the worktree.

## Corrective direction

- When authoring a test that already imports a type, never write that type fully qualified. Use the simple name, so the gate has nothing to rewrite.
- Run the gate, then check `git status --porcelain` before cutting the commit. That way a rewrite lands in the same commit, not in a follow-up settlement commit.
