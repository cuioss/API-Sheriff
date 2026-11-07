# Landing Analysis: PLAN-V02-50 — Enumeration Detection Substrate

epic: api-sheriff-0-2-0
workstream: WS-03
pr: #532 (https://github.com/cuioss/API-Sheriff/pull/532)

> Landing record for one shipped plan. Written by the `analyze` verb on 2026-10-29 from the inbox messages
> `plan-v02-50-enumeration-detection-substrate-001` to `-009` (nine candidate lessons) and `-010` (the landing). Checked against
> `origin/main` at `9794fa41`, the merge commit's diff and file list, ADR-0073, ADR-0076 and ADR-0077 as merged, the threat
> model rows `gw-02` and `gw-08`, the shipped substrate contract, the plan's archived functional-review verdict and decision
> log, and the CI runs. The plan ran once under its own plan id.

## Deliverable Fidelity vs Spec

#532 changed 91 files (+11,370 / −462), under the review-bot cap of 100 files; Sourcery declined it for size and CodeRabbit
paused its auto-review, so CodeRabbit was the only reviewer for the whole change. `landing-check` reported `complete: true`.
The plan counts nine deliverables against the spec's eight: the spec's deferred-findings item (D8) became three plan deliverables
(7, 8 and 9). The functional review graded 7 met and 2 met with residuals, none not met.

| Deliverable (spec) | Verdict | Evidence |
|--------------------|---------|----------|
| D1 — per-source detection substrate and D3 — its general-purpose contract | shipped | New framework-free package `detection/` (`SourceKey`, `SourceKeyResolver`, `SourceLedger`, `SourceState`, `SourceTrip`): a leaky bucket per `(source, folded host)`, weighted tagged admission, `CLEAR → TRIPPED → BLOCKED`, a query API, a raw trip record, a 65,536-key bound, IPv6 reduced to its `/64`. Configured by a new top-level `enumeration_detection` block, validated at boot in `LimitRules`. The contract `doc/development/source-detection-substrate.adoc` (208 lines) has a section naming `PLAN-V02-07` and lists what the substrate does not serve (ECS/OCSF shape, cross-node state, an exact sliding window, persistence). D1 and D3 were not split. |
| D2 — response on trip | shipped, with a default the spec did not fix | `SourceGate`, `ReconEvents`, `ErrorResponseWriter` and `EdgeAdmission` wire the edge: counted on the rejection event, not the rendered status, static resources excluded. Actions `observe`, `delay` (through `ResponseDelay`) and `block` (`429` with `Retry-After`, written before admission, so it holds no permit). **The shipped default is `observe`**: a trip logs `ApiSheriff-141` and meters, and changes nothing on the wire, because the gateway cannot tell at boot whether one address is one client or a whole NAT. `block` and `delay` are operator opt-ins. `ApiSheriff-142` reports the cardinality overflow. |
| D4 — HTTP/2 and gRPC stream-abuse bound, threat-model `gw-08` | shipped, with one residual | The spec's re-scope clause fired: per-source keying does not generalise to per-connection stream state, and the plan said so. `EdgeHardeningOptions` pins fixed constants (100 concurrent streams, a 16 KiB header-list bound that `CONTINUATION` counts toward, the Rapid-Reset guard at 200 resets per 30 s, never raised); `HTTP2-Settings` joins the request strip; the h2→h1 declared-length framing is pinned by test. `gw-08` is `COVERED` in the threat model. `gw-02` is **still `PARTIAL`**, with the open clause named: the upstream reset is pinned against an HTTP/1.1 upstream only. That is the spec's "report which clause remains open" outcome. **Residual (functional review D4.R2):** CI once saw `Http2StreamAbuseIT#rapidResetFloodEndsTheConnectionWithEnhanceYourCalm` end with `GOAWAY PROTOCOL_ERROR` instead of `ENHANCE_YOUR_CALM`. The plan traced a cause (the `SETTINGS` acknowledgement could precede the client preface) and fixed the test client in `3cc695db`; the cause is inferred from RFC 9113 ordering, not reproduced, and the later green runs do not exclude the other candidate the plan's local notes hold. |
| D5 — tests for D1 to D4 | shipped | `SourceLedgerTest`, `SourceKeyResolverTest`, `SourceGateTest`, `GatewayEdgeSourceDetectionTest`, `GatewayEdgeHttp2AbuseTest` with a raw HTTP/2 connection helper, `EnumerationDetectionIT`, `Http2StreamAbuseIT`, `EnumerationOracleIT`, `EnumerationDetectionActivationWiringTest` and a `sheriff-config-enumeration` overlay. |
| D6 — architecture documentation and ADRs | shipped | ADR-0076 (the substrate, 306 lines) and ADR-0077 (`rate_limit` kept, parsed and warned at boot, 128 lines). Both are below the 500-line ceiling and carry no duplicate ordinal; `doc/adr/` holds 71 records on `main`. The plan had planned 0074/0075, shipped 0075/0076, and #534 then took 0075, so the files on `main` are 0076 and 0077 (message `-008` still names the earlier pair). ADR-0073 gained Amendment A1. Three-layer docs and the `doc/architecture/` sub-documents (the docs split of #533 reached the branch by rebase) carry the material. |
| D7 — documentation residue of #528 | shipped | `RouteConfig`, `BffSessionFallbackIT`, `endpoint-routes.adoc`, `anchors.adoc` and `declared-limit-assertion-coverage.adoc` are in the diff. The 50 ms floor and the jitter are now **measured**, not reasoned (see D8 below). |
| D8 — deferred findings (plan D7, D8, D9) | shipped, none re-deferred per the outline | Plan D7: held uniform-404 answers draw on their own admission sub-budget (`heldAnswerCap`, one source's share an eighth of it); a full sub-budget answers `503`. Plan D8: an unexpected failure before the trust boundary is answered as the floored uniform `404` (ADR-0073 A1), the release time is computed on the event loop, the floor is measured by `EnumerationOracleIT` and overruns are metered on `sheriff_uniform_not_found_overruns_total`. **Residual (D8.R1):** the measurement covers the eight paths the outline enumerated; the declared-length fast-reject, a route-scoped filter violation, the cookie-forwarding same-origin refusal and the `protocol: grpc` route are also caller-drivable pre-trust paths and sit outside it. ADR-0073 and `gw-16` state the scope as eight. Plan D9: `RelayStartFailure` is rewritten to a deterministic shape (a 4 MiB unread upstream body and an awaited condition), the relay release path was fixed because the test showed it returned a connection too early (`UpstreamAttemptReleaseOrderTest`, 217 lines, red without the fix per the verifier), and `DispatchStage` changed (+327). The `ActiveProcessorCount=1` confirmation is the plan's claim; the orchestrator did not run it. |

## Metrics and Anomalies

- Tokens: 94,340,438. Wall time: 147,651 s (41 h) for 9 deliverables. Model claude-sonnet-5-5, effort medium. Plugin and executor
  0.1.2021. About seven times V02-11's token figure and three times its wall time.
- About six CI-triage and review-comment loop-back rounds, each re-running the gates. The functional review recorded 25 advisory
  findings and the code review 19 (6 rules cited, none blocking); all sit in the plan's decision log.
- The whole integration reactor was not run locally at the final head; CI's integration lane (JDK 25 and 26 matrix, native image)
  and targeted local native runs of the HTTP/2 abuse suite were the gate. This is the plan's residue D4.R1.
- Step list: every finalize step `done` (parsed with a last-colon split); `cleanup_owed=false`.

## Routing and Merge Behavior

- Review: CodeRabbit `SUCCESS` after manual re-triggers (its hourly allowance ran out several times and it paused auto-review);
  Sourcery `SKIPPED` (oversize, optional bot, not blocking). The PR checks are green: `build / build (25)` and `(26)`,
  `sonar-build`, `integration-tests / test`, offline configuration check, dependency review, license/cla.
- CI/merge: merged through the merge queue as `9794fa41` at 2026-10-29T07:41:11Z, on top of #534 and #533; the merge-group runs
  were green. No rebase conflict is reported, and the branch absorbed the #533 docs split by rebase.
- Sonar: the landing carries no `open_issue_count`; the figure is `unknown` here, not `0`. The Sonar build check on the PR was green.
- Post-merge on `main` at `9794fa41`, read 2026-10-29: Maven Build (40837024859, incl. `deploy-snapshot`), Integration Tests
  (40837024770), Config Validation, Demo Client E2E and Scorecard completed `success`; the PR-attached Performance Benchmark
  (40837028265) completed `success`. **All verified green.**
- Post-merge on `main` at `c1ded6c1` (the previous landing, `PLAN-V02-11`): Integration Tests (40764480569) and the PR benchmark
  (40764486714) finished `success`, so that Watch is retired.

## Reconciliation Actions

- [x] row `status` → `shipped` — `orchestrator queue --transition PLAN-V02-50 --status shipped`
- [x] row `pr` stamped — `532`
- [x] row `landing` stamped — `landings/PLAN-V02-50.md`
- [x] row `plan_marshall_plan_id` already `plan-v02-50-enumeration-detection-substrate`
- [x] epic.md narrative: the Watch for the `c1ded6c1` post-merge runs retired; the `RelayStartFailure` flake Watch retired (owner `PLAN-V02-50` D8 delivered); Watches added for the inferred `PROTOCOL_ERROR` cause, the floor measured on eight paths only, and the unrun final-tree integration suite; the pre-commit gate churn Watch recorded a recurrence
- [x] `PLAN-V02-07` spec corrected in place against the shipped substrate (claim verdict corroborated, dedup semantics, `doc/architecture/`, ordinal 0078, expected surface); `PLAN-V02-48` counts (71 records) and the docs-split pointer; `PLAN-V02-51` ordinal and dependency note
- [x] nine candidate lessons dispositioned (see Follow-Ups); resume anchor updated; `queue-view.md` regenerated

## Follow-Ups

- **`PLAN-V02-07` is unblocked.** Its hard predecessor has landed. Read the substrate contract first; the one design constraint it
  surfaces is that the substrate dedups per `CLEAR` episode, not per fixed window, so "one ALERT per window" must be restated.
- **`gw-02` stays `PARTIAL`** until the upstream reset is pinned against an HTTP/2 upstream; the threat model names the clause.
  Unowned; a candidate for a later hardening plan, not for `PLAN-V02-07`.
- **The default action is `observe`.** An operator who expects the gateway to block a scanner out of the box does not get that; the
  decision is in ADR-0076 and `doc/configuration.adoc`. Not a defect, but the next release note should say it.
- **ADR corpus is now 71 records** on `main`. `PLAN-V02-48` audits 52 of them and counts `0072` to `0077` as outside its groups.
- **Candidate lessons `-001` to `-009`:** `-001` folded (recurrence of the pre-commit gate churn Watch and the existing
  build-gate-discipline doc); `-002`, `-004` (citations half) and `-007` promoted as project lessons; `-003` folded (recurrence
  of lesson `2026-10-15-12-002` and the framing-corpus memory); `-005` and `-006` discarded (already held by the
  rate-limit-waits and 100-file-cap memories); `-008` folded (recurrence of lessons `2026-10-27-05-001` and `-002`, the
  ordinal re-check already in the main-drift memory); `-004` (validator-format half) and `-009` routed to plan-marshall
  `lessons-routing`. `-009`'s owed `architecture enrich` call was **not** made: the hint ("intermittent failures under load are
  accepted") would write a tolerance into project architecture that contradicts the project's standing rule that a CI-red
  integration test is a suspected production defect until a root cause is named.
