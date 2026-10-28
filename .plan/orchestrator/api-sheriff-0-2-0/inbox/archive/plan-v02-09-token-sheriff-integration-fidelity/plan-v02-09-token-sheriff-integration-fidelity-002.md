envelope_version=1
sender_type=plan
sender_id=plan-v02-09-token-sheriff-integration-fidelity
epic=api-sheriff-0-2-0
kind=candidate-lesson
created=2026-10-27T23:24:48Z

# Candidate lesson: the pre-commit gate rewrites about 400 unrelated files and flags a record-and-rethrow catch

**Signal source:** script failures / build gate behaviour during 5-execute and 6-finalize

**Observation.**
1. Every `verify -Ppre-commit` run in this plan's worktree rewrote about 400 files that the branch did not author, changing the blank-line spacing between imports. The run still exits 0, so the rewrites show up only as a dirty tree.
2. OpenRewrite added a TODO marker (`/*~~(TODO ...)~~>*/`) to a catch block that records the error and rethrows it. The project rule says code with markers present must never be committed, so the marker had to be fixed by hand before the commit.

**Existing knowledge.** The project memory "Pre-commit Formatter Churn" says PR #415 fixed the import-spacing churn. This run saw it again, so either the fix regressed or it does not cover this module or file set.

**Possible corrective direction (for orchestrator judgement).**
- Check whether the PR #415 import-spacing fix still holds on main. If it has regressed, open a fix plan.
- Note in the gate discipline doc which catch shapes trigger the TODO marker, and how to fix them without suppressing the marker.

**Classification:** deferred to the orchestrator. This is a project lesson (API-Sheriff build configuration).
