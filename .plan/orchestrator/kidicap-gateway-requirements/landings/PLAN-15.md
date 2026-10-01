# Landing Analysis: PLAN-15 — Routing and Response Headers

epic: kidicap-gateway-requirements
workstream: WS-02
pr: #320 (merged as 93a4b3e57df7b25b51b893c36fce271147468880)

> Landing record for one shipped plan. Lives at `landings/PLAN-15.md`. Written by the
> `analyze` verb after verifying claims against ground truth (actual code, artifacts,
> PR state) — a pasted claim is a lead, never a fact. See
> `persona-plan-orchestrator/standards/orchestration-model.md` for the analysis and
> reconciliation contract.

Source: inbox `routing-and-response-headers-001.md` (`kind: landing`, `complete: true`, every required
fact key supplied) plus the operator's finalize report. Corroborated by the orchestrator against git:
`93a4b3e` is on `origin/main` with subject `feat(routing): add redirects, exact routes and anchor security
headers (#320)`, 82 files changed.

## Deliverable Fidelity vs Spec

Reported 11 of 11 deliverables done. Corroborated by the merged diff at file level; per-behaviour proof
rests on the plan's own tests and CI, not re-run here.

| Deliverable (spec) | Verdict | Evidence |
|--------------------|---------|----------|
| 1 AS-3/AS-4 schema + model (`match.path`, `redirect`, conditional `base_url`) | shipped-as-specified | `endpoint.schema.json` (+66), `MatchConfig` (+78), new `RedirectConfig`, `EndpointConfig`, `RouteConfig` in 93a4b3e |
| 2 AS-3/AS-4 route table, exact-beats-prefix, boot `base_url` rule | shipped-as-specified | `RouteTableBuilder` (+158), `RouteTable`, `ResolvedRoute`, `ConfigValidator` (+669) |
| 3 AS-3 redirect dispatch + boot validation + open-redirect review | shipped-as-specified, expanded | new `RedirectStage`, new `LocationPathReview` (229 lines) and `ConfigValidatorRedirectTest` |
| 4 AS-9 `upstream.path` decision and alignment | shipped — verdict itself not re-read here | `RouteTableBuilder`, `UpstreamConfig`, `doc/user/endpoint-routes.adoc`, `doc/configuration.adoc`; the chosen semantics must be read from the merged Javadoc/ADR before PLAN-12-era assumptions are reused |
| 5 AS-11 `upstream.rewrite_location` | shipped-as-specified, expanded | new `LocationRewriter` (271 lines) + `LocationRewriterTest`, `ResponseStage` (+96) |
| 6 AS-12 `asset.index` / `asset.fallback` | shipped-as-specified | `DirectoryAssetSource` (+174), `AssetConfig`, `ResolvedAsset`, `DirectoryAssetSourceTest` (+215) |
| 7 AS-8 pipeline restructuring (anchor context, preflight kept early) | shipped-as-specified | `SecurityHeadersStage` (+297), `GatewayEdgeRoute` (+152), `DispatchStage`, `RouteSelectionStage`, `RouteRuntimeAssembler` |
| 8 AS-8 per-route headers wired + `content_security_policy` | shipped-as-specified | `SecurityHeadersConfig` (+112), `RouteRuntime` (+37), `gateway.schema.json` (+51) |
| 9 AS-8 per-header `set` / `default` mode | shipped-as-specified | `SecurityHeadersConfig`, `ResponseStage` |
| 10 Tests (unit + IT + native) | shipped-as-specified | 25 test files in the diff; IT configs and `integration-tests/scripts/verify-invalid-config-fails.sh` |
| 11 Documentation | shipped-as-specified, expanded | `doc/user/endpoint-routes.adoc`, `anchors.adoc`, `configuration.adoc`, `architecture.adoc`, `LogMessages.adoc`, ADR-0004/0007/0014, plus `security-threat-model.adoc`, `protocol-routes.adoc`, `environment-variable-overrides.adoc`, `development/declared-limit-assertion-coverage.adoc` |

## Metrics and Anomalies

- Tokens: 10,424,874 (`total_tokens`)
- Duration: 174,443 s wall (~48 h 27 m)
- Anomalies:
  - **Six PRs closed unmerged before the one that landed** (#310–#313, #316–#318) because of a repository
    CI-trigger problem (cuioss/cuioss-organization#279), including one that carried three review/Sonar
    rounds. Only #320 merged.
  - **Head-dependent finalize steps were not re-run on the final commits.** `pre-push-quality-gate`,
    `pre-submission-self-review`, `finalize-step-simplify`, `finalize-step-security-audit`, `ci-verify`,
    `automatic-review` and `sonar-roundtrip` are recorded `done` from older commits; at the merged head
    they would have re-fired. The merge rests on CI fully green at `e0e9fcf` (native ITs included), both
    review bots reviewing that commit with no actionable findings, and zero unresolved threads.
  - **`adr-propose` and `lessons-capture` skipped** — their lane is off in this plan's manifest, so this
    landing contributes no ADR proposal and no lesson.
  - **The landing facts carry `pr=#310`**, the `create-pr` step's stale record; the message's own residue
    section names #320 / 93a4b3e. The queue row was stamped with the corroborated value, **#320**.

## Routing and Merge Behavior

- Review: CodeRabbit and cuioss-review-bot both reviewed `e0e9fcf` with no actionable findings; zero
  unresolved review threads. Earlier rounds on #313 were absorbed into the branch.
- CI/merge: merged through the merge queue as 93a4b3e; `cleanup_owed=false`; worktree removed, local
  branch deleted, `main` clean.
- **Surface expansion:** declared 23 entries, realized 82 files — 37 realized paths were never declared
  (`ConfigValidator`, `RedirectStage`, `LocationRewriter`, `LocationPathReview`, `PipelineRequest`,
  `RouteSelectionStage`, `ConfigProducer`, `TopologyResolver`, `ConfigModelReflection`, `SniFrontListener`,
  10 test classes, 4 docs, 4 IT fixtures, …), and 2 declared entries were never touched
  (`config/validation/rule/` — the real site is `ConfigValidator` itself — and `benchmarks/`). Measured
  with `inbox landing-check --declared-paths … --realized-paths …` against base `93a4b3e~1`:
  `added_count: 37`, `missing_count: 2`, `state: expansion_detected`.
  **Consequence for pairing:** PLAN-15's declaration under-stated its footprint by roughly a factor of
  three, so a concurrent plan judged disjoint against it would have collided. The staged specs that share
  those now-known-real surfaces are PLAN-18 (`RouteMatcher`, `ConfigValidator`, `RouteSelectionStage`),
  PLAN-19 (`ConfigValidator`, `GatewayEdgeRoute`, pipeline) and PLAN-13 (`GatewayEdgeRoute`,
  `PipelineRequest`, `ThoroughChecksStage`) — all of them already declare those files, so no spec is
  corrected by this landing; the lesson is that `validation/rule/`-style directory guesses under-declare
  and `ConfigValidator` is the real boot-validation site.

## Reconciliation Actions

- [x] row `status` → `shipped` — `orchestrator queue --transition PLAN-15 --status shipped`
- [x] row `pr` stamped `#320` (corrected from the message's stale `#310`) — `queue --set-row`
- [x] row `landing` stamped `landings/PLAN-15.md` — `queue --set-row`
- [x] row `plan_marshall_plan_id` stamped `routing-and-response-headers` — `queue --set-row`
- [x] epic.md reconciled: PR-chain watch retired, three Open Defects opened (unfiled follow-ups,
      un-re-run finalize steps, stale `pr` fact), AS-3/4/8/9/11/12 marked shipped in the queue annotations
- [x] Open Defect opened for the seven PR-body follow-ups, none filed as issues
- [x] resume_anchor updated
