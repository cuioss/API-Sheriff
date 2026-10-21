# Landing Analysis: PLAN-V02-42 — ConfigValidator Split: AuthRules, OidcClientRules, OidcSessionRules

epic: api-sheriff-0-2-0
workstream: WS-01
pr: #502 (https://github.com/cuioss/API-Sheriff/pull/502)

> Landing record for one shipped plan. Written by the `analyze` verb on 2026-10-21 from the inbox message
> `plan-v02-42-validator-auth-oidc-rules-006.md` (landing). Each material claim was verified against ground truth.

## Deliverable Fidelity vs Spec

Checked against `origin/main` at `df84699d` (#502, merge queue): 17 files changed (+2,482 / −1,907), with a 293,999-byte diff.

| Deliverable (spec) | Verdict | Evidence |
|--------------------|---------|----------|
| D1 — extract the three groups | met | `AuthRules`, `OidcClientRules` and `OidcSessionRules` are in `config/validation/rule/`, each in place in `DEFAULT_RULES`. They include `validateBackChannelOrigin` (#492). `ConfigValidator` went from 1,516 to 779 lines. |
| D2 — move the tests | met | 139 test methods before and after the move, with the same bodies. `ConfigValidatorBackChannelOriginTest` and `ConfigValidatorSessionFallbackTest` were moved with `git mv`. |
| D3 — references and docs | met | The concern map and member references were updated. The outline Q-Gate also caught `doc/development/bff-session.adoc`. |
| D4 — `CLAUDE.md` § Git Workflow | met | Step 5 now reads "CodeRabbit gates the merge; Sourcery is **optional**" and records `required_bots: coderabbit`. `AGENTS.md` matches. |
| Functional review | 4/4 met | Operator override for 3 false-positive `weakened_test` findings in each of the two reviews. |

## Metrics and Anomalies

- Tokens: 5,900,684. Wall time: 21,168 s (5.9 h), run in parallel with `PLAN-V02-45` and `PLAN-V02-47` for part of it. Model: claude-sonnet-5-5.
- The test-integrity check counts assertions per path, so it read every `git mv` test move as weakened tests: 30, 29 and 111 assertions "gone". The totals were 257 = 257. This needed two operator closes. Routed to plan-marshall as `api-sheriff-api-sheriff-0-2-0-036`.

## Routing and Merge Behavior

- Merged through the merge queue.
- On `main` at `df84699d`, all of these passed: Maven Build (including deploy-snapshot), Integration Tests, Config Validation, Demo Client E2E and Scorecard. The PR-attached Run Integration Benchmarks job also passed (20 min).

## Residue and Owners

- **The concern map has no contract test.** It moves into `PLAN-V02-43`, which makes every rule public, so the test can list them (inbox `-005`).
- **PR-Agent prose survives** in `doc/development/README.adoc` and `doc/development/re-review-on-loopback.adoc`, which links to the deleted `pr-agent.yml`. Moved to `PLAN-V02-43`.
- **Refuted residue claim:** the landing message says `README.adoc` (~line 361) still names `ConfigValidator` as a rule owner. `README.adoc` at `df84699d` does not mention `ConfigValidator` at all.
- **Lessons routed to plan-marshall:**
  - `036`: the per-path `weakened_test` count.
  - `037`: gate bundles in a Maven reactor (recurrence of 005 and 022).
  - `038`: leaves without Grep (recurrence of pm-mcp-core 093).
  - `039`: Q-Gate run once leaves warnings pending (recurrence of 021).
  - `040`: the post-merge `ci checks wait` lost its cwd after worktree cleanup.
- **Base image:** Dependabot #496 moved the distroless pin to `081b140a`, which is still a multi-arch index with amd64 and arm64. `PLAN-V02-47`'s claim still holds.
