# Landing Analysis: PLAN-V02-11 — Resource-Exhaustion Test Home

epic: api-sheriff-0-2-0
workstream: WS-05
pr: #534 (https://github.com/cuioss/API-Sheriff/pull/534)

> Landing record for one shipped plan. Written by the `analyze` verb on 2026-10-28 from the inbox messages
> `plan-v02-11-resource-exhaustion-test-home-001` (finding) and `-002` (the landing). Checked against `origin/main` at
> `c1ded6c1`, the merge commit's diff, ADR-0075 as merged, `integration-tests/pom.xml` at the merge commit, the state of
> issue #201 and the CI runs. The plan ran once under its own plan id.

## Deliverable Fidelity vs Spec

#534 changed 5 files (+759 / −3): far under the review-bot caps. There is no `api-sheriff/src/main` change, which the
spec asserted as an absence. `landing-check` reported `complete: true`; its `surface_delta` is `unmeasured` (no
declared or realized path list was supplied), so the footprint above is the diff read directly.

| Deliverable (spec) | Verdict | Evidence |
|--------------------|---------|----------|
| D1 — the decision, recorded as an ADR | shipped, narrower than the spec's adoption list | ADR-0075 (`Accepted`) puts resource-exhaustion detection in the gating Failsafe suite as shrunken-budget exhaustion tests and keeps the benchmark lane non-gating; it weighs the benchmark lane, a time-based soak test, a new soak lane and dual ownership, and applies the #427 precedent to every resource bound. It re-verified the gap against the two fixes' regression tests, as the spec's verify-first clause required, and found the gating probe already existed. Next free ordinal was 0075, so the file carries no duplicate. |
| D1 — say which adjacent open defects are adopted and declined | shipped, with three declines | **Adopted, narrowed:** Open Defect 6 (the detection gap; the detector existed, what was missing was a demonstrated failure of it). **Declined, ADR "Scope boundary":** Open Defect 7 (k6 scripts as unguarded consumers of the shared `gateway.yaml`), Open Defect 9 (`K6BenchmarkLogMessages` outside the catalogue guard), and Open Defect 14 / issue #201. The ledger had routed 7, 9 and 14 to this plan as their home, so the decline leaves them without one (see Follow-Ups). |
| D2 — implement the decision and prove it detects | shipped | `WebSocketProxyIT` gains two committed negative controls on `api-sheriff-ws-admission`, one for the relay sub-budget (two held relays) and one for the general admission budget (eight in-flight requests with withheld bodies). Each observes the probe fail at its first upgrade with a `503`, releases the budget, and observes the probe pass. `WsAdmissionActivationWiringTest` binds the overlay caps to the held counts read from `WebSocketProxyIT`. ADR-0075 states the limit honestly: the controls prove the probe can see an exhausted pool, they do not replay a defect in the relay's release path. |
| D2 — #201 as demonstrated catch, or the fix | neither | The spec required the plan to "say explicitly which of the two the plan did". It did neither and said so: ADR-0075 records #201 as "neither detected nor fixed" and "owned by the lane that runs the test". Issue #201 is still `OPEN`, last updated 2026-08-09, no comments. |
| D3 (plan-added) — developer documentation | shipped, unplanned | `doc/development/README.adoc` gains "Test Lanes: Which Lane Owns Which Defect Class" and the recipe for adding a resource bound; `declared-limit-assertion-coverage.adoc` gains the admission-budget row. The plan counts three deliverables, one more than the spec; the spec's Expected Surface listed `doc/development/`, so this is inside the declared surface. |

One claim in ADR-0075 is a lead and not a verified fact: the cause of the #201 symptom. The ADR cites the `jfr`
profile's exclusion comment in `integration-tests/pom.xml` (present since #282, 2026-09-09, untouched by #534) which says
that lane points `MtlsHandshakeIT` at the primary instance, which requires no client certificate, so the rejection legs
fail against a listener never asked to reject. That reads as a lane misconfiguration and not a gateway fail-open, but
nobody ran the test against the real mTLS instance under `-Pjfr`. The orchestrator did not either (it needs a build).

## Metrics and Anomalies

- Tokens: 13,760,713. Wall time: 48,023 s (13.3 h) for 3 deliverables. Model claude-opus-5-5, effort low. Plugin
  0.1.2028, executor 0.1.2021. About 64 % of V02-09's token figure at nearly twice its wall time.
- The local `-Pintegration-tests` run before the push was environmentally blocked; CI's integration lane was the gate.
- The pre-push gate needed a re-run after machine load dropped. Two failures were in
  `GatewayEdgeEarlyResponseResetTest`, which the plan did not touch. This is the HTTP/2 early-response reset flake
  already on the Watches list; the plan saw it only under a 1-minute load of about 30.
- Step list: every finalize step `done` except `archive-plan:pending`. `cleanup_owed=false`.

## Routing and Merge Behavior

- Review: CodeRabbit `SUCCESS`, Sourcery `SUCCESS`. One CodeRabbit thread (the general-admission control cannot wait for
  gateway-side admission) was accepted rather than fixed; it can only fail the control loudly, never pass it vacuously.
- CI/merge: all PR checks green (`build / conclusion`, `integration-tests / conclusion`, Sonar build, dependency
  review, license/cla). Merged through the merge queue as `c1ded6c1` at 2026-10-28T20:34:22Z, after #533 (`036d5e61`); no
  rebase conflict is reported.
- Sonar: the landing carries no `open_issue_count`; the figure is `unknown` here, not `0`. The Sonar gate on the PR was
  green.
- Post-merge on `main` at `c1ded6c1`, read at 20:49 UTC: Maven Build (40764480736), Demo Client E2E (40764480131) and
  Scorecard (40764480426) completed with success; Integration Tests (40764480569) was still `in_progress`, as was the
  PR-attached Performance Benchmark run (40764486714). **Both are unverified**, tracked as a Watch until they finish.

## Reconciliation Actions

- [x] row `status` → `shipped` — `orchestrator queue --transition PLAN-V02-11 --status shipped`
- [x] row `pr` stamped — `534`
- [x] row `landing` stamped — `landings/PLAN-V02-11.md`
- [x] row `plan_marshall_plan_id` already `plan-v02-11-resource-exhaustion-test-home`
- [x] epic.md narrative: Open Defect 6 retired to its resolved form; Open Defects 7, 9 and 14 marked ownerless pending the operator's decision; Watches added for the pending post-merge runs and the two plan findings
- [x] resume anchor updated; `queue-view.md` regenerated

## Follow-Ups

- **Open Defects 7, 9, 14 lost their home.** The plan declined all three in ADR-0075. Defect 14 is the fail-open
  claim on a security control and must not sit unowned. Whether #201 is a real gateway defect or a lane
  misconfiguration artifact is the open question; the exclusion comment points at the second reading. The operator
  decides the owner (verification plan, close as refuted premise, or fold into a staged plan).
- **Finding 1, fixture header overstates fidelity** (`sheriff-config-ws-admission/gateway.yaml` says "exactly two"
  differences; the overlay also lacks `allow_get_with_content_length_body` and the TLS pins). Project-local, a comment
  fix inside `integration-tests/src/main/docker/**`. Recorded as a Watch.
- **Finding 2, the general-admission control cannot wait for gateway-side admission.** Fix would be an in-flight
  admission gauge on the management interface, a production change that needs its own plan. Recorded as a Watch; it
  belongs with `EdgeAdmission` work if V02-50 or a later plan opens it.
- **Finding 3, the build daemon's default job timeout (1800 s) is below the local IT run (about 1830 s under load).**
  plan-marshall tooling, not project work. Routed to plan-marshall `lessons-routing`.
- **ADR-0075 and the ADR corpus.** The ADR corpus is now 69 records on `main`. V02-48 (staged) audits 52 of them; it
  should read ADR-0075 as a new record, not as part of its nine groups.
