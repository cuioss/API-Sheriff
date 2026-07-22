# Landing Analysis: PLAN-02 — Endpoint Anchors (roadmap 03)

epic: api-sheriff-roadmap
workstream: WS-02
pr: cuioss/API-Sheriff#74 (squash-merged as a9fd717 via merge queue)

> Landing record for one shipped plan. Written by the `analyze` verb after verifying claims
> against ground truth — a pasted claim is a lead, never a fact.

## Deliverable Fidelity vs Spec

Spec (plans/PLAN-02-endpoint-anchors.md, re-staged 2026-07-18 with the full D1–D5 breakdown)
staged 6 deliverable groups; the plan shipped exactly those 6.

| Deliverable (spec) | Verdict | Evidence |
|--------------------|---------|----------|
| D1+D2: anchor schemas, model, RouteTable resolution, posture log | shipped-as-specified | `anchors` present in gateway schema; squash commit a9fd717 "feat(config): implement endpoint anchors (D1-D5)" |
| D3: seven validation rules | shipped-modified (improved) | Review loop-back rewrote auth-mandatoriness per-route (endpoint-level check masked route overrides — a genuine ADR-0007 gap caught by review) |
| D4: unified `${VAR}` substitution, convention lookups deleted | shipped-as-specified | 0 `ENDPOINT_*_ENABLED`/`EndpointEnablementResolver` hits in main; remaining `TOPOLOGY_` hits are a filename constant + javadoc stating the lookup no longer exists |
| D5: seven hardening fixes | shipped-modified (one mechanism change) | SnakeYAML `setMaxAliasesForCollections` was inert through Jackson's bind path — alias-bomb protection reimplemented as a compose-only pre-pass, proposed as ADR-0010 (on disk) |
| Integration configs + anchor-violation negative script | shipped-as-specified | CI loop-back fixed the script's false-pass (mktemp -d unreadable by distroless non-root user); now genuinely exercises both invalid-config cases |
| Docs + ADR-0007 flip | shipped-as-specified | ADR-0007 status reads "Accepted." (verified in file) |

## Metrics and Anomalies

- Tokens: 3,579,415; execute (1.10M) and finalize (1.53M) dominate.
- Duration: 4h11m worked / 8h41m wall.
- Anomalies: 2 CI loop-backs (Sonar S3655 on guarded Optionals; the negative-config script
  passing-for-the-wrong-reason). Stray text corrupting `.plan/marshal.json` early in the run,
  removed by the lifecycle. Steward artifacts landed separately as PR #73 (ef766c9).

## Routing and Merge Behavior

- Review: 14 bot comments, all triaged+replied+resolved; 9 inline fixes, 2 operator-approved
  fix tasks (per-route auth mandatoriness; ADR-0010 pre-pass), rest declined with rationale.
- CI/merge: all green after 2 triage rounds; **squash merge confirmed** — a9fd717 has one
  parent, so the merge-queue reconfiguration after PLAN-01 works as intended. Watch closed.
- No rebase conflicts / surface collisions.

## Reconciliation Actions

- [x] status.json `plans[]` entry updated (PLAN-02 → shipped, pr=#74, landing=landings/PLAN-02.md)
- [x] epic.md queue reconciled from status.json
- [x] Open Defect added: boot-time TOCTOU (YAML pre-pass read vs bind read; needs size-bounded single-snapshot fix)
- [x] Watches added: ADR-0010 awaiting operator review; coerce type-awareness architecture hint
- [x] Watch retired: wrk-gate lesson (folded into the PLAN-03 emit — benchmarks in its scope)
- [x] resume_anchor updated
- [x] START-HERE block regenerated

## Follow-Ups

- ADR-0010 review/acceptance → operator action, tracked as a watch.
- Boot TOCTOU → Open Defect; candidate fold into a future config-touching plan (not PLAN-03,
  whose surface is the data plane).
- Destination-type-aware placeholder coercion → architecture hint, picked up by future
  planning automatically; watch until first consumed.
- `enriched.json` uncommitted on main — normal; next plan's architecture-refresh commits it.
  Not ledger-tracked.
