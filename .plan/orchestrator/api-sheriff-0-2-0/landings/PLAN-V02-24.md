# Landing Analysis: PLAN-V02-24 — One Resolution Mechanism for Every Policy Block

epic: api-sheriff-0-2-0
workstream: WS-01
pr: #465 (https://github.com/cuioss/API-Sheriff/pull/465)

> Landing record for one shipped plan. Written by the `analyze` verb on 2026-10-15 from the inbox message
> `plan-v02-24-policy-block-resolution-006.md` (landing), after verifying each material claim against
> ground truth.

## Deliverable Fidelity vs Spec

Checked against `origin/main` at `9e05a3d6` (#465, merge queue, 51 files, +3,421 / −671). The spec suggested
two PRs (part (a) neutral, part (b) breaking). Both shipped in one PR, which stayed under the review-bot
limits.

| Deliverable (spec) | Verdict | Evidence |
|--------------------|---------|----------|
| D1 — inventory and normalisation proposal, decided at outline | shipped | ADR-0067 lists the twelve operator decisions on scope sets and on the WARN opt-in. |
| D2 — one generic mechanism | shipped | `config/PolicyBlock.java` (new): one descriptor per block and one resolver. `RouteTableBuilder`, `ConfigValidator` and `RouteMatchingRules` resolve through it. The per-block `resolve*` / `effective*` helpers and `RuleSupport.resolveAnchor` are deleted. Per the PR, no existing test was changed to make part (a) pass. |
| D3 — apply the normalisation (breaking) | shipped | Only two scopes widened: `security_filter` and `forward` gain the **endpoint** scope (`endpoint.schema.json`). Every other block keeps its scopes, with a reason in the `_policy_block_scopes` table in `doc/user/anchors.adoc`: `auth` has no gateway scope, `allowed_methods` no route scope, `security_headers` stays at anchor and gateway, `upstream_defaults` at gateway and endpoint, `websocket` at route. Three boot readers now see the endpoint scope. |
| D4 — override WARN = "both declare", derived | shipped | `ApiSheriff-101` is derived from the descriptor opt-in set `{allowed_methods, security_filter}`. The auth floor stays in `ConfigValidator`. ADR-0067 says it is derivable and leaves the move to whichever of V02-24 and V02-26 merges second. V02-24 merged second but did not move it; that now falls to `PLAN-V02-32`. |
| D5 — `@Builder`, positional tests migrated | shipped | `@Builder` added to the remaining `config/model` scope and policy-block records; positional test constructions migrated. |
| D6 — declare once, register once | shipped | `PolicyBlockSchemaContractTest` binds the descriptors to both schemas and to the scope table. `PolicyBlockTest` covers the resolver. |
| D7 — docs and ADR | shipped | ADR-0067 (new); amendments to ADR-0007 (A2), ADR-0036 (A1) and ADR-0024 (note). `anchors.adoc` states the one rule. `doc/development/README.adoc` gains "Adding a Policy Block". The threat model gains `_residual_policy_block_replacement`. |

**Realized footprint.** Within the declared surface, plus three boot readers (`ConfigBootPipeline`,
`ConfigLoader`) and `RouteMatchingRules` / `RuleSupport`. That last part comes from rebasing onto #463
(V02-26), which `main` already had.

## Metrics and Anomalies

- Tokens: 10,395,666. Wall time: 18h45m (67,488 s), the longest plan in this epic so far. Sonar: 0 new-code issues.
- The rebase onto #463 at sync-baseline needed hand resolution: the edits were moved into the extracted rule
  classes. This was an accepted risk recorded at refine (candidate `-005`).
- The final re-fire ran functional and code review only as delta rounds over 2 files. That was an operator
  decision, logged at WARNING.
- CodeRabbit caught four defects no local gate found. One was a boot reader that hand-listed scopes. Three
  were docs restating the block inventory or the old scope labels. All were fixed in the run (candidates
  `-001` to `-003`).

## Routing and Merge Behavior

- Merged through the merge queue. The merge-group `Maven Build` and `Integration Tests` runs succeeded.
- After the merge, on `main` at `9e05a3d6`, these all succeeded: Maven Build, Integration Tests, Config
  Validation, Demo Client E2E and Scorecard. The PR-attached benchmark run also succeeded.
- ADR-0067 is used, so the next free ordinal is 0068.

## Residue and Owners

- **`ApiSheriff-101` does not name the replacing scope** (finding `a49cb1`, deferred by the operator during
  finalize). Since the endpoint scope became legal, the record still says "Route %s overrides anchor %s %s
  policy" even when the endpoint block did the replacing. This is about audit-trail accuracy, not a bypass.
  → `PLAN-V02-25`, D6.
- **Two doc advisories.** `doc/development/README.adoc` ~354–356 says "every run fails", but the offline
  `--validate-config` run never reaches the body-limit reader. The `ConfigBootPipeline` Javadoc (~192)
  overstates what a newly gained scope reaches without an edit. → `PLAN-V02-25`, D6.
- **Auth-floor derivation.** → `PLAN-V02-32`.
- **The open validator slice must rebase.** #467 (`PLAN-V02-28`) is CONFLICTING with `main` after this
  merge. That is expected under the rebase rule, and the slice specs now state the post-V02-24 state.
