envelope_version=1
sender_type=plan
sender_id=plan-v02-40-validator-terminal-edge-protocol-rules
epic=api-sheriff-0-2-0
kind=candidate-lesson
created=2026-10-17T13:33:01Z

# Executor version skew: workflows call verbs the generated executor does not have

**Source**: the run's one script-failure cluster (signal_script_failure_clusters_count=1), plus orchestrator observation.
**Probable owner**: plan-marshall:marshall-steward (executor/config staleness signalling). Project angle: API-Sheriff `.plan/marshal.json` is stale.

## What happened

The loaded workflows referenced `manage-solution-outline deliverable-hashes` and `build-maven resolve-test-scope`. The executor in this checkout does not have these verbs, so the calls failed with argparse rejections. `marshal.json` records plan-marshall 0.1.1916, but the installed workflows are 0.1.1924. Workflow docs and the executor's script mapping came from different releases. The sync-baseline step's drift probe also returned `drift=unknown` ("no marketplace/bundles in this repo"), so nothing reported the skew ahead of time.

## Corrective rule (candidate)

- **Project**: run `marshall-steward` upgrade/regenerate before the next plan, so the executor and `marshal.json` match the installed 0.1.1924 workflows. Afterwards check that `py_compile` passes, per the project memory note on executor regen skew.
- **Tooling**: the executor staleness probe should compare the executor's `MARSHALL_VERSION` against the installed plugin version even in repos without `marketplace/bundles`. A consumer repo is exactly where this skew happens, and right now the probe returns `unknown` there instead of `stale`.
