# Landing Analysis: PLAN-V02-46 — Upstream Close After an Early Answer

epic: api-sheriff-0-2-0
workstream: WS-03
pr: #513 (https://github.com/cuioss/API-Sheriff/pull/513)

> Landing record for one shipped plan. Written by the `analyze` verb on 2026-10-22 from the inbox messages
> `plan-v02-46-upstream-close-after-early-answer-001` … `-004` (three candidate lessons and the landing).
> Each material claim was checked against `origin/main` at `e70743f7`, the PR and the CI runs.

## Deliverable Fidelity vs Spec

Checked against `origin/main` at `e70743f7` (#513, merge queue): 14 files changed (+489 / −41): one class with a
behaviour change, six Javadoc-only files, one new test class and six documents.

| Deliverable (spec) | Verdict | Evidence |
|--------------------|---------|----------|
| D1 — evaluate FIN-first | shipped-modified (decided by analysis, no variant built) | ADR-0066 Amendment A2 keeps client-wide `SO_LINGER 0` on every trigger and rejects FIN-first. The spec asked for a measurement against both variants, which was not run. The plan reports the operator chose analysis plus one Linux reproduction; that decision is the operator's own and was not re-derived here. |
| D2 — correct the reuse wording | shipped-as-specified | "frees the connection" / "free for the next" no longer appears in ADR-0060 or `doc/architecture.adoc` (`git grep` at `e70743f7`). The corrected rule is in ADR-0060 A1, `doc/architecture.adoc`, the threat model, `doc/user/endpoint-routes.adoc` and the ApiSheriff-23 row of `doc/LogMessages.adoc`; the Javadoc edits are comment-only. |
| D3 — record it | shipped-as-specified (as an amendment, since the close did not change) | ADR-0066 A2 (+173 lines) and the threat model's `gw-07-upstream-ports` section. No `EgressClients` Javadoc edit was owed, because the default close is unchanged. The ADR ordinal question never arose. |
| D4 — close the admit/drain window | shipped-as-specified | `EdgeAdmission.tryAdmit` now acquires the permit and increments `inFlight` first, then reads `draining`, and on a set flag undoes both and refuses; `drain()` sets the flag before it reads the count. New `EdgeAdmissionTest` (209 lines) pins the order. |

The spec's Expected Surface held: `edge/upstream/`, `edge/admission/`, `doc/adr/0060`, `doc/adr/0066`,
`doc/architecture.adoc` and `doc/security-threat-model.adoc` all changed, and two undeclared documents also
moved (`doc/user/endpoint-routes.adoc`, `doc/LogMessages.adoc`). Neither one is a surface another running plan
declares.

## Metrics and Anomalies

- Tokens: 5,631,308. Wall time: 21,285 s (5.9 h). Model claude-opus-5-5, effort low.
- Code review: 0 blocking, 2 advisory (both filed as candidate lessons, dispositions below).
  Functional review: 3 of 3 deliverables met, 0 residuals.
- **Review-loop waiver.** The operator waived the re-fire of simplify, security audit, functional review, code
  review and the local gate for the docs-only CodeRabbit fix at `02ad3b7`. Their verdicts stand from `4038ec7`;
  CI, ci-verify, CodeRabbit and Sonar re-ran on `02ad3b7`.
- **First merge-queue attempt ejected.** `Http2EarlyResponseResetIT.earlyAnsweredUploadsStayAnsweredWithoutGoAway`
  failed in the merge_group Integration Tests run `39812067667` (`expected: <400> but was: <390>`, the client
  reset 10 streams itself with CANCEL). The same tree was green on PR CI and on the re-enqueue (run
  `39823097862`). First observed failure of this test. Tracked as a Watch (below).
- CodeRabbit showed "Review rate limited" on the PR checks and did not block; Sourcery passed.

## Routing and Merge Behavior

- Merged through the merge queue as `e70743f7` at 2026-10-22T17:50:37Z after one re-enqueue.
- Post-merge on `main` at `e70743f7`, all passed: Maven Build (`39826205793`), Integration Tests
  (`39826205660`), Demo Client E2E, Config Validation, Scorecard. The PR-attached post-merge benchmark run
  `39826204825` also passed.
- No rebase conflict or re-verify signal was reported against the concurrent plans. The two shared documents
  (`doc/architecture.adoc`, `doc/security-threat-model.adoc`) are still shared with `PLAN-V02-23`, which edits
  other sections.

## Residue and Owners

- **Three candidate lessons**, all project-local:
  - `-001` `Http2EarlyResponseResetIT` merge-queue failure. Corroborated (run and assertion above). Folded into
    the flake Watch in `epic.md`; it carries the reproduction recipe and the owner.
  - `-002` the race case of `EdgeAdmissionTest` has no non-vacuity guard. Corroborated: the admitted count is
    computed and only printed in the failure message; no assertion needs it to be non-zero. Folded into
    `PLAN-V02-27` deliverable 5.
  - `-003` ADR-0066 A2 lists three upstream-close triggers and omits the request-body idle timeout.
    Corroborated: the `DispatchStage` Javadoc says an idle body resets the upstream request and, on HTTP/1.1,
    closes a partly sent connection abortively (ADR-0066 A1). Folded into `PLAN-V02-04`'s truth check.
- ADR sizes grew: ADR-0066 is now 527 lines and ADR-0060 520, both over the 500-line ceiling
  `PLAN-V02-04` enforces. `PLAN-V02-04`'s list is updated.
- The envoy CVE the finding cited was not cited by the plan (unverified). Nothing to carry.

## Reconciliation Actions

- [x] row `status` → `shipped` — `orchestrator queue --transition PLAN-V02-46 --status shipped`
- [x] row `pr` stamped — `orchestrator queue --set-row PLAN-V02-46 --field pr --value 513`
- [x] row `landing` stamped — `orchestrator queue --set-row PLAN-V02-46 --field landing --value landings/PLAN-V02-46.md`
- [x] row `plan_marshall_plan_id` already stamped (`plan-v02-46-upstream-close-after-early-answer`)
- [x] epic.md narrative reconciled (flake Watch extended, queue annotations)
- [x] resume anchor updated in `resume_anchor.md`
- [x] `queue-view.md` regenerated

## Follow-Ups

- `Http2EarlyResponseResetIT`: watch for a recurrence; reproduce under `-XX:ActiveProcessorCount=1` before
  calling it a flake. Owner when it recurs: whichever plan next touches `edge/`, or a dedicated fix.
- `EdgeAdmissionTest` non-vacuity guard: `PLAN-V02-27` deliverable 5.
- ADR-0066 A2 idle-timeout trigger and the two oversized ADRs: `PLAN-V02-04`.
