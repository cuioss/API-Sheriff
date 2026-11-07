# Landing Analysis: PLAN-V02-06 — Enumeration Hardening (the existence oracle half)

epic: api-sheriff-0-2-0
workstream: WS-03
pr: #528 (https://github.com/cuioss/API-Sheriff/pull/528)

> Landing record for one shipped plan. Written by the `analyze` verb on 2026-10-27 from the inbox messages
> `plan-v02-06-enumeration-hardening-001` to `-007` (candidate lessons) and `-008` (the landing). Checked against
> `origin/main` at `c957ee51`, the merge commit's diff, and the CI runs. The plan ran once under its own plan id.
> **It shipped three of the spec's nine deliverables.** The other four are `PLAN-V02-50`.

## Deliverable Fidelity vs Spec

#528 changed 98 files (+5,338 / −773): two under the 100-file review-bot cap, which is why the operator split the plan at
outline. Fourteen are production files under `api-sheriff/src/main`; the rest are tests, integration-test overlays,
documentation, the demo client and two ADR files (the new `0073` and the amended `0016`, plus five more amended records).

| Spec deliverable | Verdict | Evidence |
|------------------|---------|----------|
| D1 — uniform `404` for untrusted sources | shipped | `edge/error/UniformNotFound` holds `CLOSED_SET` (the pre-trust rejections: `METHOD_NOT_ALLOWED`, `PATH_NOT_ALLOWED`, `SECURITY_FILTER_VIOLATION`, `PARAMETER_LIMIT_EXCEEDED`, `CONTENT_TOO_LARGE`, `CSRF_REJECTED`, `TOKEN_MISSING`, `TOKEN_INVALID`); the key `uniform_not_found` defaults on; ADR-0073 (302 lines, `Accepted`) records the decision. Authenticated callers keep their honest codes. |
| D2 — response-timing uniformity | shipped, residual | `edge/end/ResponseDelay` holds the answer on a Vert.x timer, with a 50 ms floor, jitter and a step rule for an answer that overruns the floor. The floor and the jitter are reasoned, not measured; ADR-0073 says so. |
| D3 — per-client 404-rate detection substrate | **not shipped** | `git grep` finds no per-source counter on `main`. Owner: `PLAN-V02-50` D1. |
| D4 — response on trip | **not shipped** | Owner: `PLAN-V02-50` D2. `ResponseDelay` is the reusable non-blocking delay primitive. |
| D5 — tests | shipped for D1, D2, D9 | Pre-trust integration assertions moved from the honest codes to the uniform `404`; the rest is `PLAN-V02-50` D5. |
| D6 — architecture documentation and ADR | shipped for D1, D2, D9 | ADR-0073 and the amendments to `0007`, `0016`, `0023`, `0037`, `0049`, `0055`, `0059`. The ADR for the per-source mechanism is `PLAN-V02-50` D6. |
| D7 — the substrate contract | **not shipped** | Owner: `PLAN-V02-50` D3. |
| D8 — HTTP/2 and gRPC stream-abuse bound | **not shipped** | `gw-08` is still `GAP` (`doc/security-threat-model.adoc`), and nothing in `api-sheriff/src/main` handles `HTTP2-Settings`. Owner: `PLAN-V02-50` D4. |
| D9 — `rate_limit` says what it is | shipped | `ApiSheriff-140` is the boot WARN (`doc/LogMessages.adoc`); "planned, not implemented" appears in `README.adoc`, `doc/configuration.adoc`, `doc/architecture.adoc`, `doc/user/endpoint-routes.adoc`, `doc/user/README.adoc` and ADR-0016. |

The functional review ended with 4 deliverables met, 3 met with residuals and 0 not met (of the 7 the plan carried).
Code review: 0 blocking, 3 advisory.

## Metrics and Anomalies

- Tokens: 57,768,839. Wall time: 95,081 s (26.4 h) for 7 deliverables. Model claude-opus-5-5, effort low. Plugin and
  executor 0.1.2010.
- Main took ADR-0072 (#527) while the plan was in finalize and the branch also added `0072`. Local gates passed because
  the branch base was older; the duplicate showed on the CI merge ref. The plan renumbered its record to **ADR-0073**.
  Two commit messages on the squashed branch still say `0072`. A plan-marshall tooling lesson; routed.
- A code-review step accepted a review-bot fix to a security mechanism, and the next round found it had partly weakened
  the property. A project lesson; promoted.
- A flaky test, not caused by this plan: `GatewayEdgeRouteTest$RelayStartFailure.relayThatFailsToStartReleasesTheUpstreamResponse`
  failed once in the CI sonar-build isolated fork (run 40504680597) and passed on rerun.
- The plan's local executor was pinned to a plugin version the cache no longer held; recovered with the cache's newest
  generator.
- The plan recorded security notes locally (`.plan/local/security_notes.adoc`, entries dated 2026-10-26 and
  2026-10-27). None of their content is in this record.

## Routing and Merge Behavior

- Merged through the merge queue as `c957ee51` at 2026-10-27T14:06:31Z. The squash message is the repository default
  (PR title plus PR body), so the commit body carries the PR summary.
- Post-merge on `main` at `c957ee51`, all completed with success: Maven Build (40517976231), Integration Tests
  (40517975913), Config Validation, Demo Client E2E and Scorecard. The PR-attached Performance Benchmark run
  (40517977735, head `2f847d86`) passed. The plan had not checked these; the orchestrator did.
- **Side fix #529** (`c9c8479c`, 2026-10-27T08:09Z, docs-only, operator): states the one-sided offline key-file check
  precisely in the `OidcClientRules.nameSameFile` Javadoc and `doc/user/offline-config-validation.adoc`. It closes the
  two statements that `PLAN-V02-20`'s landing gave to `PLAN-V02-48` D12. Post-merge CI on `c9c8479c` green.

## Residue and Owners

- **Spec split.** D3, D4, D7, D8 are `PLAN-V02-50`, staged 2026-10-27. `PLAN-V02-07` is re-pointed to it (hard
  predecessor). Cross-epic consumers named the old plan: `PLAN-V03-01` in `api-sheriff-0-3-0` (told through that epic's
  inbox), and kidicap `PLAN-21` and `PLAN-30` (read-only here; the wording is handed to the operator).
- **Deferred review points.** The review point(s) the operator deferred to the follow-up are in the git-ignored security
  notes. `PLAN-V02-50` D8 reads them at outline.
- **ADR proposal.** "A planned configuration key the gateway does not enforce is kept, parsed and warned at boot": no ADR
  records it yet. `PLAN-V02-50` D6(b).
- **Five documentation advisories** (`RouteConfig` Javadoc, the `BffSessionFallbackIT` Javadoc, `endpoint-routes.adoc`,
  `anchors.adoc`, the `bff-overlap.yaml` citation): the plan reported them; I confirmed the `RouteConfig` wording and
  the citation target and did not re-derive the other three. Owner: `PLAN-V02-50` D7.
- **Flaky `RelayStartFailure` test:** `PLAN-V02-50` D8, and a Watch in `epic.md`.
- **Seven amended ADRs** are in `PLAN-V02-48`'s groups; its spec now says to keep their content and state the amended
  rule as current fact.
- **Candidate lessons.** `-006` (a bot fix to a security mechanism) is a project lesson, 2026-10-27-14-001. `-007` is a
  defect and a Watch, not a lesson. `-001` and `-003` (gates on the wrong tree or key), `-002` and `-005` (two finalize
  traps) and `-004` (an executor pinned to a missing version) are plan-marshall tooling and were routed to
  `lessons-routing` as three findings.

## Reconciliation Actions

- [x] row `status` → `shipped` — `orchestrator queue --transition PLAN-V02-06 --status shipped`
- [x] row `pr` stamped — `orchestrator queue --set-row PLAN-V02-06 --field pr --value 528`
- [x] row `landing` stamped — `orchestrator queue --set-row PLAN-V02-06 --field landing --value landings/PLAN-V02-06.md`
- [x] row `plan_marshall_plan_id` already stamped
- [x] `PLAN-V02-50` staged: row added and spec written (WS-03)
- [x] `PLAN-V02-07` spec re-pointed to `PLAN-V02-50`
- [x] `PLAN-V02-48` spec: #529 closes its two extra D12 statements; seven amended ADRs noted; record count 67
- [x] `epic.md` narrative reconciled (decision, Watch, workstream table, the `rate_limit` decision)
- [x] resume anchor updated in `resume_anchor.md`
- [x] `queue-view.md` regenerated

## Follow-Ups

- `PLAN-V02-50` (`enumeration-detection-substrate`), staged; then `PLAN-V02-07`.
- Free slots: two of three (only `PLAN-V02-05` runs).
