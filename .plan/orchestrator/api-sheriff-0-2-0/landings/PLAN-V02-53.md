# Landing Analysis: PLAN-V02-53 — The Seven PLAN-15 Follow-Up Issues, #323 to #329, and Their Closure

epic: api-sheriff-0-2-0
workstream: WS-01
pr: #565 (https://github.com/cuioss/API-Sheriff/pull/565), merged through the merge queue as `7a5d52ad` on 2026-11-05

> Written 2026-11-05 by `analyze` (inbox drain, message `plan-v02-53-plan-15-follow-up-issues-010.md`,
> `landing-check` complete: true). The PR state, the issue states, the `main` CI runs and the D1/D5 code claims were re-read by the
> orchestrator rather than copied from the message.

## Verdict: verified, with one deliverable cut under the split guard

Six of the eight deliverables shipped. D2 (#324, key-level declared-limit inventory) was cut under the spec's own split guard, which
names it the first cut: about 1,000 (file, key) rows over 22 limit-bearing descriptors. #324 stays open with that reason in a comment.
D8 (closing the issues) was done after the merge and after `main` CI went green, as the spec required.

## Deliverable Fidelity vs Spec

| Deliverable (spec) | Verdict | Evidence |
|--------------------|---------|----------|
| D1 #323 one same-origin normalisation | shipped | `PendingAuthorizationRecord.sameOrigin` now refuses percent-encoded separators (Javadoc l. 194-202 at `7a5d52ad`). #323 closed |
| D2 #324 limit-shaped keys at key granularity | **cut** (split guard) | #324 OPEN; the closure comment states the cut. Carried as a Watch for 0.3.0 |
| D3 #325 one home for the anchor-coverage rule | shipped | #325 closed. **Its title still says "four places"**: a title edit is outside the CI abstraction, so it is owed by the operator |
| D4 #326 mirrored IT fixtures cannot drift | shipped | #326 closed |
| D5 #327 one slash-stripping utility | shipped | `http/PathSlashes.java` is new and is used in six production files; no private slash-stripping helper is left in `api-sheriff/src/main` (`git grep` at `7a5d52ad`). #327 closed |
| D6 #328 native assertion for the redirect `no-store` | shipped | #328 closed |
| D7 #329 remainder after #561 | shipped | `endpoint-routes.adoc` and the schema description updated; the committed-YAML sweep found zero hits; the merged rule was re-proven red. #329 was already closed by #561, and was commented |
| D8 close the issues after the merge | shipped | #323, #325, #326, #327, #328 `CLOSED`; #324 `OPEN` (D2 cut); #329 `CLOSED` (by #561). The PR carries no closing keyword |

## Orchestrator verification

- #565 `MERGED` as `7a5d52ad`: 30 files, +1,016 / -282. That is under the review-bot caps.
- `main` push runs at `7a5d52ad`: Maven Build `42088184655` success, Integration Tests `42088184509` success. The PR-attached post-merge
  benchmark `42088188951` succeeded.
- **This also settles `PLAN-V02-55` verification (c).** Two `main` Integration Tests runs after `d70d2f16` exist: `954d7d88`
  (`41984810982`, archive `integration-tests-2026-11-05-0726-3130`, 382 completed, 0 failures) and `7a5d52ad` (`42088184509`, archive
  `integration-tests-2026-11-05-1909-0550`, 385 completed, 0 failures). The five moved classes and the control pass on both, with these
  `testsuite` times in seconds:

  | Class | Baselines | `954d7d88` | `7a5d52ad` |
  |---|---|---|---|
  | `BffSessionIdleTimeoutIT` | 194.6 / 196.3 / 196.3 | 164.3 | 161.8 |
  | `BffTokenRefreshIT` | 46.9 / 48.7 / 49.1 | 49.4 | 48.3 |
  | `BffFapiControlsIT` | 24.6 / 25.8 / 26.0 | 28.8 | 26.2 |
  | `BffClientSecretModeIT` | 24.2 / 25.2 / 25.6 | 28.8 | 26.7 |
  | `BffTlsRelaxationIT` | 33.5 / 34.4 / 35.1 | 37.0 | 34.4 |
  | `BffRefreshReuseIT` (control) | 69.0 / 76.4 / 77.7 | 75.6 | 73.8 |

  No class more than doubled, and the idle-timeout overlap holds at about 32 s. `landings/PLAN-V02-55.md` is now verified.

## Metrics and Anomalies

- Tokens: 23,926,749. Wall time: 35,264 s (about 9.8 h). Model `claude-opus-5-5`, effort `low`, plugin/executor 0.1.2036.
- Functional review: 5 met, 1 met with residuals, 0 not met. Code review: 0 blocking, 4 advisory.
- One finalize loop-back: Sonar `java:S1192` in the new `PathSlashes`, fixed in `dbc9e02f`. The operator's re-review waiver was
  refused by the merge gate (`merge_candidate_not_gated`), so the audit and both reviews were re-run (routed as a plan-marshall lesson).
- `landing-check` could not compute a surface delta (`declared_not_supplied_and_realized_not_supplied`).
- The pre-commit gate rewrote `TerminalActionRulesRedirectTest.java` on every run, and the plan reverted it each time. The file's last
  change on `main` is `eaf93b7c` (#561, the re-hosted fork commits, which never ran the local gate), and #565 does not touch it. So this
  is one file that arrived unformatted, not the closed churn class coming back. It is folded into `PLAN-V02-56` D9.

## Routing and Merge Behavior

- Merged by the merge queue with the repository's default squash message, which includes the CodeRabbit summary and no closing keyword.
- `adr-propose` was skipped by design: ADR authoring is reserved to the epic's ADR-corpus plans.
- `archive-plan:pending` in `steps` is the plan's own last step, not an owed cleanup (`cleanup_owed=false`).

## Follow-up candidates (four code-review advisories, nothing blocking) — folded into `PLAN-V02-56` D7

1. The `PathSlashes` class Javadoc overstates its reach: `UpstreamAssetSource.upstreamUri` still trims one slash itself.
2. `http/package-info.java` overstates it the same way.
3. The `LocationPathReview` Javadoc says every reader uses `pathPortion`. `LocationRewriter`, the absolute branch of `sameOrigin` and
   `PortalRules` do not.
4. `ReturnTargetScopes` keeps a second copy of the encoded-separator and dot-segment checks behind `sameOrigin`, which is unreachable.

## Reconciliation Actions

- [x] row `status` → `shipped`; `pr` 565; `landing` `landings/PLAN-V02-53.md`; `plan_marshall_plan_id` already stamped at start
- [x] `landings/PLAN-V02-55.md` verdict → verified; the verification (c) Watch is struck
- [x] `epic.md`: decision added; Watch added for #324 (key-level inventory, 0.3.0); the operator owes the #325 title edit
- [x] `PLAN-V02-56` spec: the four advisories folded into D7 and the redirect-test formatting into D9, with the Expected Surface updated in the same edit
- [x] candidate lessons: 001 and 002 are local and are recurrences of standing rule 3 (recorded there); 003-006, 008 and 009 are routed
  to plan-marshall `lessons-routing`; 007 is folded into `PLAN-V02-56` D9
- [x] all ten inbox messages archived after persistence
