# Landing Analysis: PLAN-V02-15 — A Runnable BFF Sample

epic: api-sheriff-0-2-0
workstream: WS-02
pr: #516 (https://github.com/cuioss/API-Sheriff/pull/516)

> Landing record for one shipped plan. Written by the `analyze` verb on 2026-10-24 from the inbox message
> `plan-v02-15-bff-compose-sample-001` (the landing). Checked against `origin/main` at `053e3d51`, the PR file
> list, issue #176 and the CI runs. The plan restarted nothing; it ran once under its own plan id.

## Deliverable Fidelity vs Spec

#516 changed 23 files (+1,397 / −182): under the 100-file review-bot cap. The plan counts six deliverables
and the spec five; the count is not a discrepancy. Issue #176 is CLOSED (2026-10-24T19:44:14Z) with a comment
naming #516 and `053e3d51`.

| Deliverable (spec) | Verdict | Evidence |
|--------------------|---------|----------|
| D1 — an `oidc` overlay on the sample | shipped-as-specified | `gateway.yaml` now has a server-mode `oidc` block (PAR, DPoP, `private_key_jwt` with no client secret, `__Host-sample-session`), `endpoints/demo-app.yaml` (one `require: session` route at `/app`), `endpoints/signed-out-page.yaml` and `pages/signed-out.html` as `final_redirect`. `sample-realm.json` switches `sample-client` to signed-JWT auth. Both compose files, `generate-certificates.sh` (now writes `client-auth.pem` and `dpop.pem`, gains `--if-missing`) and `start-sample.sh` changed. The plain-HTTP variant is covered in config; it is not run in CI. |
| D2 — keep the sample a sample | shipped-as-specified | No toxiproxy, go-httpbin or `it-static` outside the compose header that names them as excluded. The `gateway.yaml` header sentence is amended, not deleted: the test-scaffolding distinction stays and BFF sessions are no longer listed among what an operator does not need. |
| D3 — point the guides at it | shipped-as-specified | `doc/user/bff-session.adoc`, `bff-cookie.adoc`, `compose-sample.adoc`, `README.adoc`, `offline-config-validation.adoc` and `doc/development/compose-sample.adoc` changed. |
| D4 — prove it runs in CI | shipped, one residual | `scripts/check-bff-login.sh` is a real authorization-code round trip with a negative control, not a smoke test (its header says so). It is wired into `integration-tests.yml` and the opt-in `compose-sample` profile of `deployment/pom.xml`, and `config-validation.yml` was extended. CI runs it against the base (HTTPS) variant on the PR-built image. The plain-HTTP variant was proven locally only. |
| D5 — apply ADR-0069 | shipped-as-specified | `oidc.back_channel_origin: https://keycloak:8443` on the one public issuer identity; `ADR-0069` and `BackChannelOriginActivationWiringTest` were touched. The addressing model was not decided in the sample. |

Per-deliverable evidence is file-level. I did not run the sample or the login script. The plan's functional review
reports 5 deliverables met, 1 met with residuals, 0 not met, and the landing message does not say which one has
the residual; the plan's own residue list is the nearest evidence (CI scope, release owed).

## Metrics and Anomalies

- Tokens: 7,397,584. Wall time: 170,491 s (47.4 h). Model claude-opus-5-5, effort low. Plugin and executor 0.1.1986.
- Code review was operator-closed at `9ed3d6bf`. Re-fire waivers for simplify, the security audit, the functional
  review and the pre-push gate covered the later small CodeRabbit-driven fixes (`8c921bbd..074b7d22`), which were
  gated by `verify -Ppre-commit`, a full `verify` and CI.
- Sourcery's optional check failed on quota throughout. CodeRabbit (required) reviewed every head; the last had no
  new comments.
- One security note was recorded locally by the plan. I did not read it.

## Routing and Merge Behavior

- Merged through the merge queue as `053e3d51` at 2026-10-24T19:44:13Z. The merge_group Maven Build and
  Integration Tests runs passed.
- Post-merge on `main` at `053e3d51`, all completed with success: Maven Build, Integration Tests (run 40126644208,
  which includes the sample's login check), Config Validation, Demo Client E2E and Scorecard. The PR-attached
  `Run Integration Benchmarks` run (40126645557) passed.
- No collision with `PLAN-V02-27` (running) was reported. The overlap with `PLAN-V02-23` was accepted at init by
  the operator: the sample's `demo-app.yaml` still says the gateway-owned never-forward set withholds the browser
  `Cookie`; that set still exists on `main` (`ForwardPolicyStage`, `ConnectionHeaders`), so the sentence holds.

## Residue and Owners

- **Release owed.** `deployment/compose-sample/.env` still pins `ghcr.io/cuioss/api-sheriff:0.2.4` (unchanged by
  #516), which predates the BFF keys the sample now sets. The default sample runs only against a local build
  (`API_SHERIFF_IMAGE` override) until the release after 0.2.4. The operator decided not to cut a release in this
  plan. Owner: the first-real-release Watch in `epic.md`, which now carries the pin bump.
- **CI scope.** The plain-HTTP variant's login is proven locally only. Recorded in the same Watch.
- **Plan-marshall tooling lessons routed** to the plan-marshall `lessons-routing` inbox as `-060`
  (`fetch_findings` re-files a handled review-summary body at a new HEAD) and `-061` (recurrence of `ci --plan-id`
  "Not authenticated" after worktree removal, against `-040`). No local copy exists.
- **Spec updated for the landing:** `PLAN-V02-12` (its held D3 now waits for nothing outstanding, and reuses
  `check-bff-login.sh` and `start-sample.sh --plain-http`).

## Reconciliation Actions

- [x] row `status` → `shipped` — `orchestrator queue --transition PLAN-V02-15 --status shipped`
- [x] row `pr` stamped — `orchestrator queue --set-row PLAN-V02-15 --field pr --value 516`
- [x] row `landing` stamped — `orchestrator queue --set-row PLAN-V02-15 --field landing --value landings/PLAN-V02-15.md`
- [x] row `plan_marshall_plan_id` already stamped
- [x] epic.md narrative reconciled (V02-12 D3 hold, release Watch extended)
- [x] resume anchor updated in `resume_anchor.md`
- [x] `queue-view.md` regenerated

## Follow-Ups

- Pin bump of the sample `.env` after the first real release (Watch).
- `PLAN-V02-12` D3 (the `/auth`-fronted Keycloak variant) is no longer held by any plan, but the shipped V02-12 row
  has no staged successor. It needs a new row before it can be emitted; that is the operator's call.
