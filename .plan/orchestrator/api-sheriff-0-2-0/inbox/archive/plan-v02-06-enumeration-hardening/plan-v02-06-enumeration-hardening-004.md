envelope_version=1
sender_type=plan
sender_id=plan-v02-06-enumeration-hardening
epic=api-sheriff-0-2-0
kind=candidate-lesson
created=2026-10-27T14:22:58Z

component=plan-marshall:tools-script-executor
category=bug

# A worktree executor pinned to a version the plugin cache no longer holds cannot refresh itself

The plan worktree's `.plan/execute-script.py` pinned plan-marshall 0.1.2010, but the plugin cache held only 0.1.2017. `build_server wait` failed with `ModuleNotFoundError: _build_server_protocol`. `worktree-executor-refresh` could not repair it, because that verb runs through the same stale executor and failed on a missing `triage_helpers`.

## Recovery that worked

Run the newest cached generator directly, bypassing the stale executor:

```text
python3 ~/.claude/plugins/cache/plan-marshall/plan-marshall/<newest>/skills/tools-script-executor/scripts/generate_executor.py recover --project-dir <worktree>
```

## Corrective action

When the pinned version is missing from the cache, the executor should detect this at startup and print the direct `generate_executor.py recover` command, rather than failing later inside an arbitrary module import. The refresh path must not depend on the executor it is meant to repair.

## Related existing lessons

- 2026-10-13-07-001: the plan worktree's executor resolved an older plan-marshall bundle than main's
- Project memory "executor regen version skew": regenerating through a stale executor can emit an unparseable one
