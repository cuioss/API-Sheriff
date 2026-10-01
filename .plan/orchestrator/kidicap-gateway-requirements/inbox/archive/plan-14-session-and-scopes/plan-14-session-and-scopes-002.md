envelope_version=1
sender_type=plan
sender_id=plan-14-session-and-scopes
epic=kidicap-gateway-requirements
kind=candidate-lesson
created=2026-09-21T22:24:57Z

# Candidate lesson: capture-footprint defaults base_ref to a stale local main

**Source signal**: finalize of plan-14-session-and-scopes (manage-references capture-footprint).

**Observation**: `manage-references capture-footprint` defaulted `base_ref` to the local `main` branch. Local `main` was stale behind `origin/main`, so the diff base was wrong and the recorded realized footprint listed 101 files instead of the PR's actual 82. Re-running with `--base-ref <merge-base sha>` produced the correct footprint.

**Suggested corrective action**: capture-footprint should default to the merge-base against the remote-tracking base (`origin/main`), or at least warn when local `main` is behind its upstream. Until then, callers in finalize should pass `--base-ref` explicitly as the PR merge-base sha.

**Classification**: deferred to orchestrator.
