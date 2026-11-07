# Landing Analysis: PLAN-V02-12 — IdP Addressing Model (second PR)

epic: api-sheriff-0-2-0
workstream: WS-04
pr: #492 (https://github.com/cuioss/API-Sheriff/pull/492); the first PR was #450 (`eff0724c`, 2026-10-14)

> Landing record for one shipped plan. Written by the `analyze` verb on 2026-10-20 from the inbox message
> `plan-v02-12-idp-addressing-model-007.md` (landing). Each material claim was verified against ground truth.

## Deliverable Fidelity vs Spec

Checked against `origin/main` at `03cb4755` (#492, merge queue). The PR changed 66 files (+3,076 / −416) with a
328,496-byte diff. The plan resumed per the operator ruling of 2026-10-18: rebase first, re-ground, then loop back
to outline for the remaining deliverables.

| Deliverable (spec) | Verdict | Evidence |
|--------------------|---------|----------|
| D1 — back-channel origin | shipped | `OidcConfig` carries `back_channel_origin`; `oidc.back_channel_allow_insecure_http` is the explicit opt-in and logs WARN `OIDC_BACK_CHANNEL_INSECURE_HTTP` (`BffRuntimeProducer`). The boot rule is `ConfigValidator.validateBackChannelOrigin`, tested in `ConfigValidatorBackChannelOriginTest`. |
| D2 — one identity, no resolver hack | shipped | No `frontendUrl` remains in any realm import. `hostResolverRule()` and `--host-resolver-rules` are gone from `demo-client`. Keycloak is 26.8.1, digest-pinned, in both compose files. The Failsafe, Playwright and compose-sample runs on 26.8.1 are the evidence. |
| D3 — `/auth`-fronted variant | held, by design | Runs after `PLAN-V02-23` and `PLAN-V02-15`. |
| D4 — reserved paths to `/bff/*` | shipped in #450 | `DEFAULT_JWKS_PATH = /bff/jwks`. |
| D5 — ADR and docs | shipped | ADR-0069 records one issuer identity reached through a configured back-channel origin. The plan also added a Dependabot Keycloak group and a Keycloak line in the release runbook. |
| Functional review | 7 met, 2 met with residual, 0 not met (over the plan's 9 outline deliverables) | Final code-review record: an operator override at `b32b4679`. The last full round, at `e38d81b1`, had 0 blocking and 2 advisory. |

## Metrics and Anomalies

- Tokens: 10,535,763. The plan reported no wall time.
- cuioss-review-bot stopped reviewing during the run (billing) and was removed from `main` in #493 and #494. The
  run's review gate was changed to `required_bots: coderabbit`. `CLAUDE.md` § Git Workflow is now stale, and
  `PLAN-V02-42` fixes it.
- token-sheriff moved to 0.9.8 during the run (#491, Dependabot).

## Routing and Merge Behavior

- Merged through the merge queue. Merge-group Maven Build and Integration Tests passed.
- After the merge, on `main` at `03cb4755`, all of these passed: Maven Build, Integration Tests, Config Validation, Demo Client E2E and Scorecard.
  The Integration Tests run the plan saw in progress has since finished green. The PR-attached Run Integration Benchmarks job also passed.

## Residue and Owners

- **`validateBackChannelOrigin` is still in `ConfigValidator`**, which is now 1,516 lines. `PLAN-V02-42` moves it into `OidcClientRules`.
- **`CLAUDE.md` names a removed reviewer.** `PLAN-V02-42` corrects the file.
- **The image is amd64-only.** Recorded as an epic Watch; the operator decides between a documentation note and a multi-arch release.
- **Advisory:** `BackChannelOriginActivationWiringTest` does not read Keycloak `entrypoint`/`env_file` overrides.
  Neither compose file uses them today, so this is not routed.
- **Specs re-grounded:**
  - `PLAN-V02-09`: token-sheriff is now 0.9.8.
  - `PLAN-V02-15`: ADR-0069 and the landed key are now the given.
  - `PLAN-V02-22`: the resolver variable is gone.
- **Lessons:**
  - The java:S2583 recurrence is merged into project lesson `2026-10-15-09-002`, the same class: Sonar's null rules under `@NullMarked`.
  - The daemon-routed build ignoring the caller's environment is routed to plan-marshall as `api-sheriff-api-sheriff-0-2-0-035`.
