envelope_version=1
sender_type=plan
sender_id=plan-v02-47-multi-arch-image
epic=api-sheriff-0-2-0
kind=candidate-lesson
created=2026-10-21T18:26:13Z

component=project:release-workflow
category=improvement
bundle=project

# Scope every side-effecting workflow resource to the run attempt

A dry-run lane that pushes a registry tag per run (`dryrun-<run_id>-<arch>`) is not safe to re-run: "Re-run all jobs" reuses `github.run_id` and moves the tag off a digest an earlier attempt already pushed. If that earlier attempt failed after the push but before its digest was recorded, the version is unrecorded and the cleanup-by-recorded-digest job can no longer find it. CodeRabbit raised this on PR #504 and the fix was to include `github.run_attempt` in every dry-run tag and make the cleanup completeness check cover the exact tags of every attempt of the run, so a version it cannot attribute by digest fails the cleanup by name.

## Rule

Name every resource a re-runnable CI job creates with both `run_id` and `run_attempt`, and give the cleanup job a completeness check on those names that fails loudly instead of passing green. Do not make the cleanup delete by tag sweep; keep it deleting only what was recorded by digest, and report anything else for manual deletion.

## Evidence

CodeRabbit thread 13fca7, PR #504, `.github/workflows/release.yml` (dry-run tags and `cleanup-dry-run` job).
