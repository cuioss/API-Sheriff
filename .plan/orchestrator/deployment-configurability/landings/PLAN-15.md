# Landing Analysis: PLAN-15 — Trusted-Proxy Breadth Thresholds and the Probe Doc Correction

epic: deployment-configurability
workstream: WS-05
pr: [#267](https://github.com/cuioss/API-Sheriff/pull/267) — merged as `558a38b`

> Second consecutive inbox-delivered landing, and the second to pass `inbox landing-check` with
> `complete: true` / `missing_keys[0]`. Every claim corroborated first-party before recording.
> **This landing vindicates the merge decision that created the plan** — see Deliverable Fidelity.

## Deliverable Fidelity vs Spec

Merged footprint: **10 files, +121 / −26**.

| Deliverable (spec) | Verdict | Evidence |
|--------------------|---------|----------|
| 1. Settle the broad-prefix thresholds and record why | shipped-as-specified | `ConfigValidator.java:129-130` now read `BROAD_PREFIX_IPV4 = 16` / `BROAD_PREFIX_IPV6 = 48`, verified by direct read at `558a38b` |
| 2. Implement the settled thresholds | **merged into deliverable 1** | The plan shipped 3 deliverables against the spec's 4, folding settle+implement into one. The spec's split was artificial — a threshold is not settled until it is written |
| 2′. Give the reasoning an operator-facing home | added-unplanned, correct | `doc/configuration.adoc` (+28), `doc/LogMessages.adoc`, `ConfigLogMessages.java`. The spec's HYPOTHESIS that the log record might need re-wording **was confirmed** |
| 3. Test the boundary with matched controls | shipped-as-specified | `ConfigValidatorTest.java` (+45); five new parameterized controls, and the plan reports that reverting **either** constant alone turns a test red — the epic's "a key that parses is not a key that acts" rule, satisfied per-family |
| 4. Correct the `HealthProbe` Javadoc | **shipped-expanded, 2 sites → 6** | See below |

### ⛔ The spec understated its own footprint — the fourth such event, but the FIRST one the spec itself handled correctly

PLAN-15 declared the `HEALTHCHECK`-override claim at two sites with a HYPOTHESIS that a third might
exist. Outline verification found it at **six**. The plan corrected all six and raised
`scope_estimate` to `multi_module`.

✅ **This is the pattern working, not failing.** The spec carried an explicit instruction — *"a third
site joins deliverable 4 rather than becoming another plan"* — written precisely because this epic
had already measured three under-declaration events. The instruction fired, the plan followed it, and
no work was orphaned into a follow-up. Compare PLAN-06, where an unresolved location declared as a
directory produced a silent gate blind spot with no instruction to catch it.

**Corroborated first-party**: a repo-wide search for `"must override the image"` across
`api-sheriff/src` and `doc/` returns **zero matches**. The claim is eradicated, not merely reduced.

| Direction | Count | Entries |
|---|---|---|
| Declared and realized | **5 of 5** | every declared entry landed — including both HYPOTHESIS doc entries and the test directory |
| Realized, never declared | 5 | `ConfigLogMessages.java`, `doc/user/container-image.adoc`, `doc/user/context-path.adoc`, `doc/user/environment-variable-overrides.adoc`, `doc/user/tls-edge.adoc` |

⚠ The undeclared five are all downstream of the six-site discovery, so they are the *consequence* of
a correctly-handled widening rather than a mis-declaration. The Expected Surface has been corrected
to the realized footprint regardless — the corpus must not carry a declaration measured wrong.

✅ **The directory-entry warning paid off.** The spec flagged its
`test/…/config/validation/` entry as the exact "exact class TBD" shape that made PLAN-06 gate-blind,
and instructed resolving it at outline. It resolved inside the declared directory to
`ConfigValidatorTest.java` — no drift.

## Metrics and Anomalies

- Tokens **4,157,420**; wall **27,399 s (7h37m)**; **2h41m worked**.
- ⛔ **One execution-log row reads `error` and the command did not fail.**
  `"verify:module-tests",5-execute,error,0,0,1355000` — a stale manifest tier stamp (`per_task` for
  an orchestrator-tier build) caused the platform to background a ~22.6-minute call, losing its
  return path. Zero tokens, zero tool uses, impossible duration. Promoted to lesson
  `2026-09-05-07-001` so the next reader does not chase the build.
- ⚠ `finalize-step-sync-baseline` rebased over **1** upstream commit — `3fca05c`, cui-java-parent
  **1.6.1 → 1.6.2**. See Reconciliation.

## Routing and Merge Behavior

- **Review was the long pole, and both required bots needed manual work.** CodeRabbit quota-refused,
  then needed an explicit trigger, then needed a *full* review because its incremental pass would not
  advance. PR-Agent needed a separate `/review`. Both satisfied at final HEAD before the barrier ran.
  Folded as the third occurrence on lesson `2026-09-02-22-003`.
- **Sourcery never reviewed** — hard quota, ~22h. Optional, so it correctly did not gate. Recorded as
  a real coverage gap: "did not gate" is not "was covered".
- **CI/merge**: merged via the queue as `558a38b`; main clean, worktree removed.
- ⚠ **`required_bots` is STILL broken at `.plan/marshal.json:115`** (`coderabbit,cuioss-review-bot`),
  and `re_review_on_loopback: false` at `:108` is confirmed as the cause of CodeRabbit's silence.

## Reconciliation Actions

- [x] row `status` → `shipped`; `pr` `267`, `landing` `landings/PLAN-15.md`,
      `plan_marshall_plan_id` `trusted-proxy-breadth-and-probe-doc` stamped
- [x] PLAN-15's `## Expected Surface` corrected to the realized 10-file footprint
- [x] **Inbox drained: 5 scanned, 5 archived, 0 invalid, 0 archive-failed.** 001 `folded`,
      002 `promoted`, 003 `discarded`, 004 `folded`, 005 `reconciled`
- [x] The `HealthProbe`/ADR-0039 contradiction is eradicated repo-wide. ⚠ It was never an Open
      Defect — PLAN-10's landing staged it as PLAN-14, which PLAN-15 superseded and shipped — so
      there is nothing to retire in `## Open Defects`, and this row records the close-out rather
      than a retirement
- [x] **Watch RETIRED** — the unexercised cui-java-parent bump. PLAN-15's gate ran green on
      **1.6.2** (202 + 1926 tests), so the `-Werror` risk from 1.5.11 → 1.6.1 → 1.6.2 is discharged
- [ ] **Issue #256 is resolved by deliverable 1 and should be closed — NOT done here.** Closing it is
      outward-facing and was not asked for, so it is flagged for the operator rather than actioned:
      `ci issue close --issue 256`. The fix is verified on main (`BROAD_PREFIX_IPV4 = 16`), so the
      issue is stale as it stands
- [x] New Open Defect opened for the outstanding ADR candidate

## Follow-Ups

1. ⛔ **The declined ADR is the one open item, and it needs an epic decision.** `adr-propose` scanned
   all 39 corpus ADRs and found **no** coverage of `trusted_proxies` breadth or the warn-vs-reject
   threshold. Draft title: *"The trusted_proxies breadth warning is thresholded at one
   operator-provisioned network, and its un-warned residual is declared"*. ✅ **The decline was
   correct**: the plan's declared scope was met, and adding a file post-review would have re-staled
   both required bots and forced another trigger/review/triage cycle — which, given this PR's review
   history, was a real cost. The draft is preserved in the plan's decision log. ⚠ **This is a
   decision with rejected alternatives and no ADR** — exactly what the corpus exists for. Ordinal
   `0040` is next free.
2. ⚠ **Two now-redundant tests left in place.** `shouldAcceptTightlyScopedCidrs` and
   `shouldWarnBroadButNotTotalCidr` in `ConfigValidatorTest` are subsumed by the new parameterized
   `broadPrefixThresholdControls`. Removing them would have reached outside the changeset's
   line-level scope. Recorded by the simplify pass; a fold candidate for any future
   `ConfigValidatorTest` work, not worth a plan.
3. ⛔ **`required_bots` — third landing in a row that pays for it.** Unchanged at
   `.plan/marshal.json:115`.
4. ⚠ **`re_review_on_loopback: false` deserves its own decision.** If it is intentional, the loop-back
   path owes an explicit trigger for *every* required bot rather than leaving it to be discovered.
