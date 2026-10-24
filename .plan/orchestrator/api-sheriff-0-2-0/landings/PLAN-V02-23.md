# Landing Analysis: PLAN-V02-23 — Cookie Forwarding Policy

epic: api-sheriff-0-2-0
workstream: WS-03
pr: #515 (https://github.com/cuioss/API-Sheriff/pull/515)

> Landing record for one shipped plan. Written by the `analyze` verb on 2026-10-24 from the inbox messages
> `plan-v02-23-cookie-forwarding-policy-r2-001` … `-016` (fifteen candidate lessons and the landing).
> Checked against `origin/main` at `bd730f5a`, the PR file list and the post-merge CI runs. The plan restarted
> from this spec as `plan-v02-23-cookie-forwarding-policy-r2`; the older local plan of the same name is stale.

## Deliverable Fidelity vs Spec

#515 changed 93 files (+7,355 / −955): under the 100-file review-bot cap. Files by area: 30 main Java, 27 unit test,
5 integration test, 2 JSON schemas, 3 integration-test endpoint YAMLs, 26 documents (README included).

| Deliverable (spec) | Verdict | Evidence |
|--------------------|---------|----------|
| D1 — the `cookies` policy block and its configuration surface | shipped-as-specified | `CookiesConfig`, `CookieAllow`, schema entries in both JSON schemas, `PolicyBlock` and `CookieRules` (boot rule, `ConfigValidator` registration), `ForwardingRules`, `DocumentedSetsContractTest`. |
| D2 — one definition of the gateway's own cookies | shipped-as-specified | New `http/GatewayCookies` with `GatewayCookiesTest`, read by both directions (`ForwardPolicyStage`, `ResponseStage`). |
| D3 — request side: `Cookie` filtered by policy | shipped-as-specified | `ForwardPolicyStage` and `ConnectionHeaders` changed; `ForwardPolicyStageTest`, `ConnectionHeadersTest`. |
| D4 — response side: `Set-Cookie` limited to what the route may receive | shipped-as-specified | `ResponseStage.relayedUpstreamHeader` is the one judgement for every leading header and trailer on the HTTP and gRPC relays (Javadoc at `ResponseStage.java:112`); new `UpstreamSetCookieFilter`. |
| D5 — gateway-side same-origin check | shipped, one residual | `CsrfDefence` widened beyond session routes, `BffRuntime`, `BffRuntimeProducer`. The functional review reports 7 deliverables met and 1 met with residuals. The plan names the cause: an operator ruling removed a LogRecord item that a frozen criterion (D5.C5) still required, and the criterion was rewritten late (lesson `-011`). |
| D6 — rewrite ADR-0037 in place | shipped-as-specified | ADR-0037 is `Accepted`, 599 lines. ADR-0036, 0007, 0055 and 0067 also changed. |
| D7 — documentation | shipped-as-specified | The 26 documents include `doc/architecture.adoc`, `doc/configuration.adoc`, the three `doc/variants/` pages, `doc/user/` pages, `doc/security-threat-model.adoc` and `doc/LogMessages.adoc`. |
| D8 — end-to-end proof against `go-httpbin` | shipped-as-specified | `CookieForwardingPolicyIT`, `BffSessionCookieControlsIT`, the `cookie-policy.yaml` and `httpbin.yaml` endpoint configs. |

Per-deliverable evidence is file-level. I did not rerun the plan's tests; the plan's functional review (7 met, 1
residual, 0 not met) and CI carry the behavioural verdict.

**Breaking changes the spec required to be stated:** default `none` relays no upstream `Set-Cookie` and no browser
`Cookie`. Spec rulings were followed as written; I did not re-read each one against the diff.

## Metrics and Anomalies

- Tokens: 11,621,535. Wall time: 183,722 s (51.0 h), including the park and restart. Model claude-opus-5-5, effort low.
- Code review: 0 blocking, 7 advisory. 15 functional-review advisories. None fixed; see the Watch in `epic.md`.
- **Late fix rounds.** Three operator-approved rounds landed in finalize (a bare nameless gateway cookie, the
  activity-cookie reservation in both session modes, CodeRabbit doc wording). ADR-0037 already covers them. The
  security audit repeatedly found coverage gaps late (lesson `-007`); the details are in the git-ignored local
  security notes and are not recorded here.
- **Extra task.** TASK-15 existed because a new WARN record reused retired id `ApiSheriff-123` (lesson `-002`).
- **Rebase.** A rebase rewrote 13 of 17 commit SHAs and the 5 to 6 transition refused until the change ledger was
  re-keyed by hand (lesson `-001`).
- Sourcery refused on diff size throughout (optional). CodeRabbit's rate limit blocked review of the last three
  commits until a manual re-trigger after the hourly window.

## Routing and Merge Behavior

- Merged through the merge queue as `bd730f5a` at 2026-10-24T14:53:40Z.
- The plan reported its post-merge CI wait as `deadline_exceeded` and left verification to the orchestrator.
  Verified here: on `main` at `bd730f5a` Maven Build, Integration Tests, Demo Client E2E, Config Validation and
  Scorecard all completed with success, and the PR-attached `Run Integration Benchmarks` run passed. `deploy-snapshot`
  is skipped on pull requests; the Maven Build push run for the merge commit is the main-branch run and it passed.
- No collision with `PLAN-V02-15` (running) was reported.

## Residue and Owners

- **Two project-local lessons promoted:** `2026-10-24-15-001` (a retired LogRecord id is not free) and
  `2026-10-24-15-002` (a catalogue row needs a test that the record is emitted).
- **Plan-marshall tooling lessons routed** to the plan-marshall `lessons-routing` inbox as `-053` … `-059`, seven
  clusters covering twelve of the fifteen messages: change-ledger rebase re-key, delta equals full scope,
  CodeRabbit covered-commit signal and participation, security lens at outline, value-change sweep (two
  messages), ruling against a frozen criterion, and invented verbs (four messages, a recurrence).
- **Folded into `PLAN-V02-27` D5:** `HealthProbeTest` hard-codes `127.0.0.1:9000` (lesson `-006`); re-checked, the
  port is still hard-coded at `HealthProbeTest.java:60`.
- **Unfixed advisories:** recorded as a Watch in `epic.md`, no spec.
- **Specs updated for the landing:** `PLAN-V02-05` (its D1 now reuses `ResponseStage.relayedUpstreamHeader`),
  `PLAN-V02-27` (V02-23 has landed), `PLAN-V02-04` (ADR-0037 now 599 lines and `Accepted`).

## Reconciliation Actions

- [x] row `status` → `shipped` — `orchestrator queue --transition PLAN-V02-23 --status shipped`
- [x] row `pr` stamped — `orchestrator queue --set-row PLAN-V02-23 --field pr --value 515`
- [x] row `landing` stamped — `orchestrator queue --set-row PLAN-V02-23 --field landing --value landings/PLAN-V02-23.md`
- [x] row `plan_marshall_plan_id` already stamped (`plan-v02-23-cookie-forwarding-policy-r2`)
- [x] epic.md narrative reconciled (new Watch for unfixed advisories)
- [x] resume anchor updated in `resume_anchor.md`
- [x] `queue-view.md` regenerated

## Follow-Ups

- Unfixed advisories: Watch, no owner yet.
- The stale local plan `plan-v02-23-cookie-forwarding-policy` (4-plan, no tasks, no branch): the operator's to discard.
- `PLAN-V02-12` D3 (the `/auth`-fronted Keycloak sample) was held for this plan's cookie policy; it is now held only
  for `PLAN-V02-15`.
