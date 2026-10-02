envelope_version=1
sender_type=plan
sender_id=plan-14-session-and-scopes
epic=kidicap-gateway-requirements
kind=candidate-lesson
created=2026-09-21T22:24:51Z

# Candidate lesson: baseline-reconcile merge-tree probe fails on host git 2.34

**Source signal**: script failure observed during finalize of plan-14-session-and-scopes.

**Observation**: The host git is 2.34. baseline-reconcile probes overlap with `git merge-tree` using the modern (`--write-tree`) form, which git 2.34 does not support; it fails with `usage: git merge-tree <base-tree> <branch1> <branch2>`. The resulting `no_overlap` verdict is therefore only trustworthy when `upstream_commit_count` is 0 — with upstream commits present the verdict is not backed by a real probe.

**Suggested corrective action**: baseline-reconcile should detect the git version / probe failure and report an explicit `probe_unavailable` (or equivalent) verdict instead of `no_overlap`, or fall back to a probe that works on older git. Callers should not treat `no_overlap` as authoritative when the probe errored.

**Classification**: deferred to orchestrator (plan performs no global-vs-epic classification).
