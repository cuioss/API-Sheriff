# PLAN-V02-22: Demo-Client and Documentation Leftovers

epic: api-sheriff-0-2-0
workstream: WS-02

> Staged plan spec — one shippable unit of work, ready for `/plan-marshall` hand-off.
> The orchestrator EMITS the command below; it never launches the plan inline.
> Source: open items no plan owned — the remainder of GitHub issue #189 after `PLAN-V02-13`
> (#383), epic Open Defect 2, and the release guard's evidence claims in
> `doc/development/release-process.adoc` (a telemetry finding, 2026-10-07).

## Objective

Close the small demo-client and documentation items that have stayed open because no plan owned
them: the demo panel and documents issue #189 still asks for, one trap in the demo client's
development start script, and the release process document's account of what the release guard's
negative control proves.

## Deliverables

1. **The rest of issue #189.** `PLAN-V02-13` shipped the routing category and the HTML error pages;
   the issue stays open for:
   - a demo-client panel (`demo-client/src/main/resources/spa/index.html`, `app.js`) that fires each
     rejection variant and shows the status, the `Content-Type` and whether a redirect happened;
   - `doc/variants/01-base-gateway.adoc` and `demo-client/doc/integration-sample.adoc`, which still
     describe rejections without the HTML error page and the `ROUTING` category;
   - the scope wording of the PROHIBITED ASSERTION in `demo-client/doc/playwright-suite.adoc`, so it
     names the reserved endpoints it protects and no longer reads as covering the edge's own
     rejections.

   The issue comment also lists `doc/plan/04-request-pipeline.adoc`; that file no longer exists, so
   the item is already discharged — say so on the issue. **Close #189 when this lands**, naming the
   PR and merge commit; a PR body that only mentions the issue does not close it.

2. **The development start script's printed command (Open Defect 2).**
   `demo-client/scripts/start-dev-environment.sh` prints `cd demo-client && npm run test`, but
   `demo-client/playwright.config.js` requires three variables at load time that only the Maven
   execution supplies, so the printed command fails at once. Print a command that sets them, or
   have the config fall back to the development defaults when they are absent, and say which.

   Already in place, not this plan's work: the same script gates on `/health/ready` with one named
   budget (`GATEWAY_READY_ATTEMPTS=30`), and `doc/README.adoc` no longer carries the "fully adapted"
   sentence (#197 removed it with the Archived Sources row).

3. **Make the release guard's evidence claims match the evidence.** In
   `doc/development/release-process.adoc`:
   - `[#guard-refusal-predicates]` says "a refusal announces neither of them". That is false. The
     pinned guard (`cuioss-organization` `release-guard`, v0.40.0) returns a `reason` and logs it
     (`Release guard: SKIP — release.current-version unchanged at …` or `… changed to …, but a tag
     for … already exists`). What does not name the reason is the downstream rendering:
     `released-version` empty and the skipped jobs. Say that precisely.
   - `[#guard-negative-control-limits]`: the four-row signature alone cannot tell which condition
     refused, because both were true in the control run (`current-version` stayed `0.1.1`, and tag
     `0.1.1` already existed). The run's guard log can: it says `unchanged at 0.1.1`, and the guard
     checks "unchanged" before "already tagged", so a guard stuck on "changed" would have logged the
     tag reason. Rewrite "the control therefore proves the guard *refused*" and "it is the one this
     control covers" so the claims rest on that reason line, cite it as the evidence, and keep the
     stuck-on-"unchanged" caveat (a guard stuck there logs the same line).
   - The section says the checkout depth "settles the mechanism" but never states it. State it: the
     guard job checks out with `fetch-depth: 0` and `fetch-tags: true`, and the guard refuses to run
     if the parent commit is missing.
   - Cite the pinned guard by version and path, so the text has to be re-checked when the pin moves.

## Claim Labels

- OBSERVED: issue #189 is open and its last comment lists the demo-client panel, `doc/variants/01-base-gateway.adoc`, `demo-client/doc/integration-sample.adoc`, the PROHIBITED ASSERTION wording and `doc/plan/04-request-pipeline.adoc` as still open — read at GitHub issue #189 § the latest comment, 2026-10-04
  - verdict: corroborated | checked_at: 386f3f74094516d787f06dca0946825c62421b89 | by: api-sheriff-0-2-0/cleanup | rescoped: n/a | evidence: issue #189 is OPEN; its last comment lists the demo panel, the three documents and the doc/plan file as open
- OBSERVED: `doc/plan/` does not exist on `origin/main` at `7f6375a5` — `git ls-tree origin/main doc/plan`
  - verdict: corroborated | checked_at: 386f3f74094516d787f06dca0946825c62421b89 | by: api-sheriff-0-2-0/cleanup | rescoped: n/a | evidence: git ls-tree HEAD doc/plan returns nothing
- OBSERVED: `start-dev-environment.sh` prints `cd demo-client && npm run test` — read at `demo-client/scripts/start-dev-environment.sh` § the closing hints
  - verdict: corroborated | checked_at: 386f3f74094516d787f06dca0946825c62421b89 | by: api-sheriff-0-2-0/cleanup | rescoped: n/a | evidence: start-dev-environment.sh line 339 still prints cd demo-client && npm run test
- HYPOTHESIS: `demo-client/playwright.config.js` still requires the three variables at module load — confirm/refute at `demo-client/playwright.config.js` § `required()` (verify-at-outline)
  - verdict: corroborated | checked_at: 386f3f74094516d787f06dca0946825c62421b89 | by: api-sheriff-0-2-0/cleanup | rescoped: n/a | evidence: playwright.config.js routes three variables through required() at module load
- OBSERVED: `start-dev-environment.sh` gates on `/health/ready` with `GATEWAY_READY_ATTEMPTS=30`, so the readiness half of the former D2 is already done — read at `demo-client/scripts/start-dev-environment.sh` § the gateway wait
  - verdict: contradicted | checked_at: 386f3f74094516d787f06dca0946825c62421b89 | by: api-sheriff-0-2-0/cleanup | rescoped: yes | evidence: the script probes /health/ready with GATEWAY_READY_ATTEMPTS=30 (unchanged since #341); the readiness half of D2 removed, claim re-scoped
- OBSERVED: `doc/README.adoc` carries no "fully adapted" claim; #197 removed it, so the former D3 is discharged — `git grep -i adapted doc/README.adoc`
  - verdict: contradicted | checked_at: 386f3f74094516d787f06dca0946825c62421b89 | by: api-sheriff-0-2-0/cleanup | rescoped: yes | evidence: doc/README.adoc has no adapted or archive wording; #197 removed the sentence; D3 removed, claim re-scoped

- OBSERVED: control run `31256991225` (PR #196, merge `963e422`): `current-version` is `0.1.1` at the merge and at its parent, tag `0.1.1` was created 2026-08-07, and the `release / guard` log reads `Release guard: SKIP — release.current-version unchanged at 0.1.1` — read with `gh run view 31256991225 --log` and `git show 963e422:.github/project.yml`
  - verdict: corroborated | checked_at: 386f3f74094516d787f06dca0946825c62421b89 | by: api-sheriff-0-2-0/cleanup | rescoped: n/a | evidence: run 31256991225 guard log reads SKIP, release.current-version unchanged at 0.1.1; project.yml 0.1.1 at 963e422 and its parent
- OBSERVED: the guard decides "unchanged" before "already tagged" and returns a `reason` for each — read at `cuioss/cuioss-organization` `.github/actions/release-guard/release-guard.py` § `decide`, at `c43c22f9`; the guard job checks out with `fetch-depth: 0` and `fetch-tags: true` — read at `.github/workflows/reusable-maven-release.yml` § `guard`, at `8e6a0c7c` (v0.40.0, the pin in `release.yml`); re-check when the pin moves
  - verdict: contradicted | checked_at: 386f3f74094516d787f06dca0946825c62421b89 | by: api-sheriff-0-2-0/cleanup | rescoped: yes | evidence: release.yml pins v0.40.0 (8e6a0c7c), not v0.36.0; decision order, reason strings and fetch-depth 0 / fetch-tags true hold there; claim and deliverable re-scoped

## Expected Surface

- OBSERVED: `demo-client/src/main/resources/spa/index.html`, `demo-client/src/main/resources/spa/app.js` — D1
- OBSERVED: `doc/variants/01-base-gateway.adoc`, `demo-client/doc/integration-sample.adoc`, `demo-client/doc/playwright-suite.adoc` — D1
- OBSERVED: `demo-client/tests/*.spec.js` — D1, a spec for the new panel
- OBSERVED: `demo-client/scripts/start-dev-environment.sh`, `demo-client/playwright.config.js` — D2
- OBSERVED: `doc/development/release-process.adoc` — D3

## Dependencies and Sequencing

- Depends on: none.
- Overlaps with: `PLAN-V02-12`, which changes the demo client's SPA, Playwright configuration and
  specs for the reserved-path move. The disjointness gate decides at emit time; if both are staged,
  run this one first or after, never together.

## Hand-Off Command

```text
/plan-marshall task="implement .plan/orchestrator/api-sheriff-0-2-0/plans/PLAN-V02-22-demo-client-and-doc-leftovers.md" plan_id=plan-v02-22-demo-client-and-doc-leftovers
```

## Write-Boundary

The plan implementing this spec touches only its own repository source and tests. It creates and
edits NO file under `.plan/orchestrator/` other than its own `inbox/{sender}-{seq}` message, and
reports its outcome through its PR and that message.
