# Landing Analysis: PLAN-V02-07 — Threat Classification and Loud Signal

epic: api-sheriff-0-2-0
workstream: WS-03
pr: #537 (https://github.com/cuioss/API-Sheriff/pull/537)

> Landing record for one shipped plan. Written by the `analyze` verb on 2026-10-30 from the inbox message
> `plan-v02-07-threat-classification-001` (the landing). Checked against `origin/main` at `996f36bd`, the merge commit's file
> list and stat, the PR body, the CI runs (the PR-branch runs at three heads, the merge-group runs, the push runs on `main`
> and the PR-attached benchmark), the archived plan's functional-review verdict, and the code and ADR on `main`. The plan ran
> once under its own plan id. `landing-check` reported `complete: false` (missing key `effort`, carried as `unknown`).

## Deliverable Fidelity vs Spec

#537 changed 47 files (+5,205 / −269): 17 main-source and resource files, 12 unit-test files, 2 integration-test files, the
`config-validation.yml` workflow, one integration descriptor and 14 documentation files. Six of six deliverables shipped; the plan's
own functional review counts four met and two met with residuals, none consequential. CodeRabbit reviewed clean at the final head
(`5988f533`); Sourcery declined the PR as over its 150,000-character limit (optional bot, not blocking).

| Deliverable (spec) | Verdict | Evidence |
|--------------------|---------|----------|
| D1 — weighted anomaly score over `EventCategory`, accumulated per source in a leaky-bucket window on the `PLAN-V02-50` substrate | shipped | `ThreatClassifier` owns a separate `SourceLedger` instance with its own window, threshold and action `OBSERVE`. `ThreatWeights` maps each `EventCategory` to a weight with an exhaustive switch (no default arm): `INPUT_VALIDATION`, `ROUTING`, `AUTHENTICATION`, `AUTHORIZATION` carry the configured weights and `UPSTREAM`, `RATE_LIMITING`, `CONFIGURATION` are fixed at 0 (read at `ThreatWeights.java:144-148`). Routing and filter violations are weighted apart, as the spec asked (defaults 1 and 10). `SourceKeys` is a new stage that resolves the source key once per request, so the score does not depend on `enumeration_detection.enabled`. `ErrorResponseWriter.handleGatewayRejection` feeds each rejection under its true event. |
| D2 — two-tier signal, one ALERT per episode, recon trip is an input not a second alert | shipped, **dedup promise stated: one ALERT per episode** | `doc/LogMessages.adoc` (`ApiSheriff-143`) and ADR-0078 state that the ledger trips a source at most once per episode and re-arms only after the score drained to zero, which is the substrate's behaviour the spec told the outline to restate rather than assume. `SourceGate` hands its recon trip to the classifier as one more weighted input (`weights.recon_trip`, default 50) and "never raises this record by itself". Tested by `GatewayEdgeThreatClassificationTest::scannerTrippingBothLedgersRaisesOneAlertCarryingTheReconTrip` and `ThreatClassifierTest::reconTripAddsItsWeightWithoutAnAlert`. |
| D3 — ECS/OCSF-shaped structured emission | shipped, **ECS only**; the OCSF mapping is documented, not emitted (ADR-0078 rejects emitting both) | `ThreatAlertDocument` renders through `GatewayJson` (ADR-0062), no new dependency. Fields: `event.kind: alert`, `event.category`, `event.severity: 47`, `source.address` (and `source.ip` for an IPv4 source), `url.domain` for a declared host, MITRE ATT&CK `TA0043` / `T1595`, and the `api_sheriff.threat` breakdown of score, threshold, window and the weight per event and per category. ECS version pinned at `8.17.0` in `ThreatAlertDocument.ECS_VERSION`. The record never carries the raw `Host`, the path or any header. |
| D4 — config for weights, window and threshold, secure defaults | shipped | New top-level `threat_classification` block (`ThreatClassificationConfig`, `GatewayConfig`, `gateway.schema.json`), boot validation in `LimitRules` (bounds, negative weights, effective positive weights, the `recon_trip` dependency on enumeration detection), native reflection in `ConfigModelReflection`. Defaults on `main`: threshold 100, window 300 s, weights 10 / 1 / 2 / 2, recon trip 50. The block has no `action` member: the ledger only observes. |
| D5 — tests | shipped | Unit tests for every new class and the changed edge and config classes; `GatewayEdgeThreatClassificationTest` (edge wiring); `ThreatClassificationIT` and `ThreatClassificationActivationWiringTest` against a dedicated `sheriff-config-threat` stack. Covered by name: a scan crosses the threshold and raises exactly one ALERT, `singleMalformedRequestIsBenign`, `disabledClassificationRaisesNothingAndLeavesReconUnchanged`, `scoreIsKeptWithoutEnumerationDetection`, `threatFeedChangesNoAnswer`. |
| D6 — architecture documentation and ADR | shipped | ADR-0078 (`0078-Per-source_threat_classification_raises_one_ECS_alert_per_episode_from_its_own_ledger.adoc`, Accepted, 283 lines, under the 500-line ceiling) is on `main`; no duplicate ordinal (72 records). Architecture (`observability-and-errors.adoc`, `tls-and-inbound-security.adoc`, README), `configuration.adoc`, `doc/user/endpoint-routes.adoc`, development docs (second consumer in `source-detection-substrate.adoc`, boot-validation rules, limit-assertion coverage, integration-test topology) and the `LogMessages.adoc` record, including the single-node caveat and the documented residual that a looping client can cross the threshold like a scanner. |

**Two things the spec did not predict.** (a) The log catalogue gained two records, not one: `ApiSheriff-143` for the ALERT and `ApiSheriff-144` for the
ledger-overflow case (the ledger tracks at most 65,536 sources; a further source is left untracked, cannot raise an alert, and is counted on
`sheriff_threat_untracked_total`); the spec named only the ALERT. (b) The action on the threat ledger is fixed to `OBSERVE` and has no configuration member at all, narrower than
the spec's "own weights, window, threshold and action"; ADR-0078 records it. Neither is a defect.

**Declared surface vs realised.** The spec's Expected Surface listed `events/EventType.java`, `events/GatewayEventCounter.java`,
`edge/error/RejectionReporter.java` and `edge/error/ReconEvents.java` as touched; none is in the merge diff. The plan fed the classifier from
`ErrorResponseWriter`, `GatewayEdgeRoute` and `SourceGate` instead, and added `.github/workflows/config-validation.yml` and
`integration-tests/src/main/docker/sheriff-config-threat/gateway.yaml`, which the spec did not declare. The spec is shipped, so the
declaration is no longer read by the gate and no correction is made. No plan ran beside V02-07, so no collision resulted.

## Metrics and Anomalies

- Tokens: 40,417,005. Wall time: 94,577 s (about 26.3 h) for 6 deliverables. Model claude-sonnet-5-5 (self-reported at plan start; the
  landing-time refresh could not measure it), **effort `unknown`** (the reason `landing-check` reports `complete: false`; a second landing
  in a row, see Open Defect 18). Plugin 0.1.2032, executor 0.1.2032. About 109% of V02-51's tokens and 157% of its wall time, on twice the deliverables.
- Loop-back budgets spent: functional-review 3, automatic-review 3, ci-verify 1. Functional review: 2 deliverables met with residuals, 8 advisory
  findings. Code review: 1 advisory, 0 blocking.
- Step list: every finalize step `done` except `adr-propose` (`skipped`, lane off; ADR-0078 was authored in-plan as D6) and `archive-plan`
  (`pending` when the landing was written; the archived plan directory now exists, so it ran afterwards). Parsed with a last-colon split; no
  namespaced element carried a non-`done` outcome. `step.branch-cleanup.merge_mechanism=merge_queue`, `merge_state=merged`, `cleanup_owed=false`.
- Plan-marshall tooling friction, routed by the operator session to `lessons-routing` as `-074`, `-075` and `-076` before the landing:
  CodeRabbit's rewritten finished-review summary no longer carries the participation marker the gate keys on (so `automatic-review` closed by a
  WARNING-logged force-done at two heads); `loop-back admit` spends a ceiling round on a HEAD-advance re-fire; the build-freshness gate goes stale
  after a reverted `verify -Ppre-commit` rewrite. Nothing further to route.

## Routing and Merge Behavior

- Review: CodeRabbit `SUCCESS` with its summary reading "reviewed" at `5988f533` and "No actionable merge-blocking issue"; Sourcery declined
  (oversize, optional). The review threads the CI abstraction reports are all non-resolvable comments (`issue_comment`, empty `thread_id`):
  the Sourcery decline, CodeRabbit's summary and two operator `@coderabbitai review` triggers with their replies (the first rate-limited, the
  second completed). Zero resolvable review threads.
- **Merge under an operator barrier override.** The pre-merge review barrier did not pass on its own (`merge_participation_unproven`, gap class
  review-barrier-gap, `unproven_bots=coderabbit`) and the operator chose "Merge anyway (record reason)" at 2026-10-30T06:07:51Z, bound to HEAD
  `5988f533`. I checked that this head is the one that merged. The reason is the tooling gap routed as `-074`, not an unreviewed change.
- Merge: through the merge queue as `996f36bd` (merged 06:10Z; merge-group Maven Build `41012529564` and Integration Tests `41012529462` green).
- CI at the three PR heads: the pull-request Integration Tests run passed at attempt 1 at `929e0fdc`, `54a3af18` and `5988f533`; Maven Build passed at
  all three; Config Validation **failed at the first head `929e0fdc`** (fixed by the `config-validation.yml` change that registers the new
  `sheriff-config-threat` standalone set, green at the later heads); Sonar: the landing reports `new_code_issue_count=0`, which is the plan's figure, not
  one I read from Sonar.
- Post-merge on `main` at `996f36bd`, read 2026-10-30: Maven Build (`41014925099`, incl. `deploy-snapshot`), Integration Tests (`41014924852`), Config
  Validation (`41014924447`), Demo Client E2E (`41014924361`) and Scorecard (`41014924857`) completed `success`. **The PR-attached Performance
  Benchmark (`41014927604`, job "Run Integration Benchmarks") completed `success`**; the landing reported it as unobserved because its post-merge
  wait hit its deadline, and it is now observed. All verified green. (The CI abstraction has no run-by-commit verb, so these runs were listed with a
  read-only `gh run list`, as at V02-51.)
- The functional review's D6 residual (the branch was based on `aed51c28`, so it never ran `LogMessagesCatalogueTest` as V02-51 rewrote it, and the
  `ApiSheriff-143` entries met that guard only in the merge-queue build) is closed: the merge-group and push Maven Builds on `996f36bd` passed.

## Reconciliation Actions

- [x] row `status` → `shipped` — `orchestrator queue --transition PLAN-V02-07 --status shipped`
- [x] row `pr` stamped — `537`
- [x] row `landing` stamped — `landings/PLAN-V02-07.md`
- [x] row `plan_marshall_plan_id` stamped — `plan-v02-07-threat-classification`
- [x] epic.md narrative: the Pre-commit gate churn Watch got a recurrence note; one Watch added for the unverified ECS shape; Open Defect 18 updated (second
  landing without `effort`, routed); no defect opened for the barrier-override merge, because its cause is the routed tooling gap
- [x] `PLAN-V02-48` and `PLAN-V02-49` specs corrected in place (record count, the retired V02-07 dependency text); `corpus surfaces` re-read
- [x] resume anchor updated; `queue-view.md` regenerated

## Follow-Ups

- **ECS shape is asserted, not validated.** The spec's verify-first clause asked that the field set be scoped against the actual ECS schema and
  "validate against a real consumer". ADR-0078 pins `ECS_VERSION` at `8.17.0` and `ThreatAlertDocumentTest` asserts the document's fields by name; I found no
  test that checks the document against an ECS schema artifact or a consumer. Held as a Watch, not a defect: the field names are the published ECS
  names, and the cost of being wrong is a SIEM mapping that needs a tweak.
- **Single-node, in-process state** is the documented design (ADR-0078); a SIEM correlates across nodes. Nothing owed.
- **The default is `enabled: true`.** The block observes only, so nothing changes on the wire for an operator who ignores it; the one new effect is a
  WARN `ApiSheriff-143` line and a `sheriff_threat_alerts_total` meter when a source crosses the threshold. Noted for the release notes, not a defect.
- **Security note.** The plan recorded a git-ignored local security note. Its content is not read into any tracked file; tracked text says only that it exists.
- **Overlap with `PLAN-V03-01`** (honeypot, `api-sheriff-0-3-0`) is now live: both touch the event system and the substrate, and the spec says never concurrent.
  Its half of the sequencing is the other epic's to read; nothing to do here.
- **Unowned and unchanged:** `gw-02` stays `PARTIAL`; the `rate_limit` key stays parsed and warned at boot.
