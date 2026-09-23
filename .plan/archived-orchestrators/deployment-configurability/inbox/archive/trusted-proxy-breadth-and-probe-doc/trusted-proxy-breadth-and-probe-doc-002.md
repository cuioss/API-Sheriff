envelope_version=1
sender_type=plan
sender_id=trusted-proxy-breadth-and-probe-doc
epic=deployment-configurability
kind=candidate-lesson
created=2026-09-05T00:10:19Z

## Candidate: a stale manifest tier stamp silently backgrounded a 600s verification and recorded it as an error

**Signal source**: `signal_script_failure_clusters_count` — the phase-5 `verify:module-tests`
execution-log row recorded `outcome: error`.

**What happened**

The plan's frozen execution manifest carries a `step_execution_tier` table. Its row for
`verify:module-tests` was stamped `per_task`:

```
step_execution_tier[3]{step_id,tier}:
  "verify:quality-gate",orchestrator
  "verify:module-tests",per_task
  "verify:coverage",per_task
```

The live architecture resolve for the same command reports `orchestrator`. The two disagreed, and
the manifest is what the phase-5 runner read. A `per_task` tier means the Bash call runs
synchronously inside the current dispatch with the resolved `bash_timeout_seconds`; because the
stamp said `per_task` while the real command is an orchestrator-tier, multi-minute build, the call
was issued without an adequate synchronous budget and the host platform auto-moved it to the
background. The dispatch lost its synchronous return path, and the execution log recorded:

```
"verify:module-tests",5-execute,error,0,0,1355000,"2026-09-04T18:19:32.728279+00:00"
```

`duration_ms: 1355000` is ~22.6 minutes — far past any per-task budget — with `total_tokens: 0` and
`tool_uses: 0`, the signature of a call whose result never came back to the envelope that issued it.

**Why it is candidate-lesson material**

Three properties make this worth recording above the plan:

1. **The failure is not in the command.** The module tests themselves were fine; the *tier stamp*
   was wrong. So the recorded `outcome: error` names the wrong culprit, and a reader chasing it will
   look at the build first and find nothing.
2. **The manifest is frozen at compose time; the architecture resolve is live.** Any drift between
   them is invisible until a step actually fires, and it re-appears in every plan composed from the
   same stale snapshot — this is a cross-plan defect wearing a per-plan costume.
3. **The recovery is silent-lossy.** The auto-background is a host-platform heuristic, not a
   plan-marshall decision, so nothing in the plan's own logs announces "this was backgrounded" —
   only the impossible `duration_ms` against a `per_task` tier reveals it.

The generalisable shape: **a snapshotted routing fact that a live resolver also answers is a drift
surface, and a step that reads the snapshot should be able to detect that it disagrees with the
resolver.** Either the compose step re-derives the tier at fire time, or the runner cross-checks the
stamp against the resolve before issuing the call and fails loudly on disagreement rather than
letting the platform silently re-tier the work.

**Cross-plan relevance for the epic**

Every plan in `deployment-configurability` composes a manifest carrying this same table, so every
one of them inherits whatever the snapshot got wrong. If the stamp is wrong at the source, the next
plan reproduces this exact error row.

**Evidence**

- `manage-execution-manifest read --plan-id trusted-proxy-breadth-and-probe-doc` →
  `step_execution_tier` row `"verify:module-tests",per_task`
- `execution_log[0]`: `"verify:module-tests",5-execute,error,0,0,1355000,…`
- the run nonetheless completed: phases 1-init through 5-execute all `done`, PR #267 merged as
  `558a38b`
