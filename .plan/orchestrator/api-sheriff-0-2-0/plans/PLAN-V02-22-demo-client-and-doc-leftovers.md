# PLAN-V02-22: Demo-Client and Documentation Leftovers

epic: api-sheriff-0-2-0
workstream: WS-02

> Staged plan spec — one shippable unit of work, ready for `/plan-marshall` hand-off.
> The orchestrator EMITS the command below; it never launches the plan inline.
> Source: four open items no plan owned — the remainder of GitHub issue #189 after `PLAN-V02-13`
> (#383), and epic Open Defects 2, 3 and 11.

## Objective

Close the small demo-client and documentation items that have stayed open because no plan owned
them: the demo panel and documents issue #189 still asks for, two traps in the demo client's
development start script, and one over-reaching claim in the documentation index.

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

2. **The development start script's two traps (Open Defects 2 and 3).**
   - `demo-client/scripts/start-dev-environment.sh` prints `cd demo-client && npm run test`, but
     `demo-client/playwright.config.js` requires three variables at load time that only the Maven
     execution supplies, so the printed command fails at once. Print a command that sets them, or
     have the config fall back to the development defaults when they are absent, and say which.
   - The same script gates on `/q/health/live` with an unmeasured 30-attempt budget, while
     `integration-tests/scripts/start-integration-container.sh` gates on readiness. Gate on readiness,
     as the recorded readiness contract says.

3. **Narrow the documentation index's claim (Open Defect 11).** `doc/README.adoc` says the old
   archive was "fully adapted", but the deleted `doc/archive/others/excluded.adoc` (gateways that were
   considered and not evaluated) has no live counterpart. Either narrow the sentence to what was
   adapted, or restore that list in `doc/features-analysis.adoc`. Git history holds the old file.

## Claim Labels

- OBSERVED: issue #189 is open and its last comment lists the demo-client panel, `doc/variants/01-base-gateway.adoc`, `demo-client/doc/integration-sample.adoc`, the PROHIBITED ASSERTION wording and `doc/plan/04-request-pipeline.adoc` as still open — read at GitHub issue #189 § the latest comment, 2026-10-04
- OBSERVED: `doc/plan/` does not exist on `origin/main` at `7f6375a5` — `git ls-tree origin/main doc/plan`
- OBSERVED: `start-dev-environment.sh` prints `cd demo-client && npm run test` — read at `demo-client/scripts/start-dev-environment.sh` § the closing hints
- HYPOTHESIS: `demo-client/playwright.config.js` still requires the three variables at module load — confirm/refute at `demo-client/playwright.config.js` § `required()` (verify-at-outline)
- HYPOTHESIS: `start-dev-environment.sh` still gates on `/q/health/live` — confirm/refute at `demo-client/scripts/start-dev-environment.sh` § the gateway wait (verify-at-outline)
- HYPOTHESIS: `doc/README.adoc` still carries the "fully adapted" claim — confirm/refute at `doc/README.adoc` § the archive paragraph (verify-at-outline)

## Expected Surface

- OBSERVED: `demo-client/src/main/resources/spa/index.html`, `demo-client/src/main/resources/spa/app.js` — D1
- OBSERVED: `doc/variants/01-base-gateway.adoc`, `demo-client/doc/integration-sample.adoc`, `demo-client/doc/playwright-suite.adoc` — D1
- OBSERVED: `demo-client/tests/*.spec.js` — D1, a spec for the new panel
- OBSERVED: `demo-client/scripts/start-dev-environment.sh`, `demo-client/playwright.config.js` — D2
- OBSERVED: `doc/README.adoc` — D3
- HYPOTHESIS: `doc/features-analysis.adoc` — D3, only if the list is restored (verify-at-outline)

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
