# PLAN-V02-54: What TokenSheriff 0.9.8's Lazy Key Generators Bought — Measured, and #422 Closed on the Numbers

epic: api-sheriff-0-2-0
workstream: WS-05

> Staged plan spec — one shippable unit of work, ready for `/plan-marshall` hand-off.
> The orchestrator EMITS the command below; it never launches the plan inline.
> Staged 2026-10-31 for issue #422 ("Take the lazy key-material generators from TokenSheriff, test-duration proposal 7"). The
> operator asked that the orchestrator DRIVE this one to verify the change: the baselines below were captured by the orchestrator
> from CI on 2026-10-31 before the plan starts, and the orchestrator re-reads CI and the plan's evidence after it lands.

## Objective

#422 asked for a TokenSheriff change (key pairs generated on first use, lookup atomic) and then a version bump here with a re-measure. The
change is upstream and in the release API Sheriff pins: TokenSheriff 0.9.8 (`InMemoryKeyMaterialHandler` at tag `0.9.8` generates nothing up
front and creates a pair through `computeIfAbsent`), pinned here since #491 (`199a0cda`, 2026-10-20). What is missing is the measurement the issue
names: the per-JVM first-use time and the `surefire:test` duration before and after, with test counts and coverage confirmed unchanged. This
plan measures it under controlled conditions, records the figures, and closes #422 on them, whether the gain is large, small or nil.

## Deliverables

1. **A controlled before/after.** #491 changed one line of `pom.xml`, so `199a0cda^` (`fd77bb05`, TokenSheriff 0.9.7) and `199a0cda` (0.9.8) differ in
   the pin alone. Check both out into ad-hoc worktrees under `.plan/local/worktrees/` and build each through the executor with `--project-dir`; never `cd`
   into one. On one machine, on a quiet host (check `uptime` first: the previous measurement on this repository was spoiled by foreign load), run
   `clean test -pl api-sheriff -am` at least three times each, alternating A, B, A, B, A, B. Record per run: the `surefire:test` duration per execution
   (`default-test`, `quarkus-fork-tests`, `isolated-fork-tests`) and the total, and report the spread so a reader can see the noise band.
2. **The per-JVM first-use time, from existing reports only.** Three test classes in `api-sheriff/src/test` reference `InMemoryKeyMaterialHandler`
   directly (verify at outline, and find the classes that reach it through the generators' other entry points). Read the per-class `time` in the
   surefire XML of those classes, and of the first class each fork runs that touches it, in A and B. **No hand-made timing or verification class** (project
   rule: a measurement is a JUnit report, not a harness). State what could not be isolated.
3. **Counts and coverage unchanged.** Compare the test counts per execution at A and B (they must be equal), and per-class JaCoCo from a clean tree for
   both (`clean verify -Pcoverage -pl api-sheriff -am`, lesson `2026-10-25-22-001`: the coverage profile appends to one `jacoco.exec`, so measure each
   figure from a clean tree). A class whose coverage differs is reported by name.
4. **Record it.** Write the figures, the noise band, the commands and the hosts' load into `doc/quality-report/test-quality.adoc` (the document the
   previous run-time and count figures live in; verify-at-outline, add a short section if it carries none). Say plainly if the difference is inside the noise.
5. **Close #422 after the PR has landed.** After the merge, and CI on the merge commit green, comment on #422 with the figures and the PR, tick the remaining
   boxes in its body, and close it through the CI abstraction (`ci issue comment`, `ci issue close`). The PR names #422 WITHOUT a closing keyword.
   The issue closes on the measurement, not on a gain: a measured "no visible difference" is a complete answer to it.

Split guard: 5 deliverables, one PR. The footprint is one document and, only if a finding needs it, nothing else. No production or test code changes.

## Standing Constraints

- **No production code, no test code, no dependency change.** If the measurement shows something worth changing (for example a test that regenerates keys
  per method), report it in the landing as a follow-up; do not fix it inside this plan.
- **Executor only.** Every Maven run goes through `plan-marshall:build-maven:maven run --command-args "..." --project-dir ...`; never `mvn`, never `javac`.
  Never run two Maven builds at once in one worktree (spurious Quarkus start failures); two worktrees may run in turn, not at once, because the host load is
  the thing being measured.
- **Report what the measurement cannot see.** A figure with no spread, or measured on a loaded host, is not a figure.
- Where the project analyses its pull requests with Sonar (`resolve` reports `sonar: configured`): an open Sonar issue is fixed in a pushed commit, or
  dismissed with a stated reason recorded as `{issue-key}: {reason}` in the run's progress record and transmitted through
  `plan-marshall:workflow-integration-sonar:sonar_rest transition --issue-key {key} --transition {falsepositive|wontfix}`. The merge gate reads the
  open-issue count at the head (`unknown` for a failed read, never `0`).

## Verification by the orchestrator

The orchestrator does not build or measure (it never runs implementation builds), so it verifies at `analyze` from ground truth the plan does not control:

- **Pre-registered baselines** (read from CI by the orchestrator, 2026-10-31). `Maven Build` workflow on `main`, push runs, success only: median about
  932 s over eleven runs, range 793 to 1,479 s; the Maven `Total time` of the `build (26)` job was 08:13 min at `5589ff33` and that of `build (25)` 09:44 min at `9e95440a`. **That
  noise band is several times larger than any gain this plan can find**, so the workflow duration cannot confirm or refute the plan's figures, and the
  orchestrator will not use it for that. It is recorded only so a reader does not mistake it for evidence.
- **What the orchestrator re-derives itself.** (a) The pin: `version.token-sheriff` in the root `pom.xml` and the upstream handler at that tag (already
  confirmed 2026-10-31 for 0.9.8). (b) Test counts per execution at the merge head, from the CI log's surefire summary lines, against the figures the plan
  records. (c) That the recorded document and the PR body state the same numbers. (d) That the plan used two worktrees at the two named commits (the
  worktree paths and the executor log are in the plan's progress record), and that each configuration has three or more runs.
- **Verdict rule.** The orchestrator closes its own verification as `measured` when (a) to (d) hold and the spread is reported; as `unverified` when any of
  them cannot be read, and then leaves #422 open. A claimed gain smaller than the plan's own reported spread is recorded as "inside the noise", never as a gain.

## Claim Labels

- OBSERVED: TokenSheriff 0.9.8 carries lazy key generation — `InMemoryKeyMaterialHandler` at tag `0.9.8` declares `KEY_PAIRS` as a `ConcurrentHashMap` and creates
  a pair through `computeIfAbsent` on first request (the last upstream change to the file is the #795 commit of 2026-10-10)
- OBSERVED: API Sheriff pins 0.9.8 — root `pom.xml` `version.token-sheriff`, set by #491 (`199a0cda`, a one-line change)
- OBSERVED: #422's measurement items are unrecorded — no before/after figure exists in the repository or the issue
- OBSERVED: three test classes in `api-sheriff/src/test` reference `InMemoryKeyMaterialHandler` — `git grep -l InMemoryKeyMaterialHandler -- api-sheriff/src/test`
- HYPOTHESIS: the gain is small, because the unit run already shares its surefire forks (31 JVMs against 164 in the issue's account) and only a few classes
  reach the handler — measure, do not assume (verify-at-outline)
- HYPOTHESIS: the surefire XML of the unit run carries enough per-class timing to isolate the first-use cost; if it does not, state that the per-JVM first-use
  time could not be isolated without a measurement class, and report the per-execution figures only (verify-at-outline)
- Verify-first clause: re-read the pin and the upstream handler at outline; if the pin has moved past 0.9.8 or the handler changed, re-state the A and B commits.

## Expected Surface

- OBSERVED: `doc/quality-report/test-quality.adoc`
- No production code, test code or build file is expected to change.

## Dependencies and Sequencing

- Depends on: none. `#491` is merged.
- Overlaps with: none. A document no other staged or running plan touches.
- Adjacent to: `PLAN-V02-55` (integration-test duration, #423). The two measure different suites (unit run here, failsafe there); each needs a quiet host for its
  own build, so do not run the two plans' builds at the same time on one machine.

## Hand-Off Command

```text
/plan-marshall task="implement .plan/orchestrator/api-sheriff-0-2-0/plans/PLAN-V02-54-token-sheriff-lazy-generators-measured.md" plan_id=plan-v02-54-token-sheriff-lazy-generators-measured
```

**The explicit `plan_id` is load-bearing — do not drop it.**

## Security Findings

Fix a security finding inside this plan whenever possible; that is the preferred outcome. A finding
that is not fixed right away (out of scope, unconfirmed, deferred) stays out of git: it is never
written into a tracked file, a commit message, the PR title, body or review replies, a code comment,
the docs, or this plan's inbox and landing messages. Record it instead in
`.plan/local/security_notes.adoc` (git-ignored; create the file if it is missing and append, never
rewrite). Each entry carries the date, this plan's id, the location (file and line or component),
what was observed, and why it was not fixed. Tracked text may say only that a security note was
recorded locally, never what it says.

## Write-Boundary

The plan implementing this spec touches only its own repository source and tests. It creates and
edits NO file under `.plan/orchestrator/` other than its own `inbox/{sender}-{seq}` message, and
reports its outcome through its PR and that message. Closing #422 (deliverable 5) is the one action outside the
repository: it goes through the CI abstraction, after the merge, and nowhere else.
