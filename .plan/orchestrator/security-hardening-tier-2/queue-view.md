<!-- GENERATED FILE — never hand-edit. Rendered from this epic's ledger (status.json, resume_anchor.md, queue/*.json) by `orchestrator regenerate-view --slug security-hardening-tier-2`. On a merge conflict in this file, do not merge it by hand: merge the source files, run `orchestrator regenerate-view --slug security-hardening-tier-2`, and `git add` the result. -->

# Queue view: Security hardening - tier 2 (follow-ups)

## START HERE

**Resume anchor**: READY TO CLOSE (2026-11-22): PLAN-SEC2-01 to 08 all shipped and analyzed (08 = #651, a2ff139e; its ledger landed through #652, 40d67c6a); no plan row is running or staged, the inbox is empty and no Open Defect is open (the 07 incomplete-landing defect went to the kidicap-gateway-requirements inbox as security-hardening-tier-2-006 on the operator's word). Remaining: run cleanup and confirm restart verdict ready, then close (history.md carries the owed leads: the idle-host Watch of DispatchStageTest BodyDeadlineBehindASlowConnection, and the four 08 owed leads under Watches: per-Host wording of the ApiSheriff-152 record, the 'five other gateway instances' comment at integration-tests/docker-compose.yml:944, the old lambda test Javadoc in EarlyResponseEndTest, the unobserved cap IT case), file the sender stream-end marker in the kidicap inbox (security-hardening-tier-2-005 promised it; do it only on the operator's word), then archive. The kidicap-gateway-requirements ledger is written only on the operator's explicit word. The operator holds the layer-3 reads of the 06 and 07 landings outside the repository. The main-branch runs of the 04-08 merge commits were read on 2026-11-22 (all green; 00ebea64 failed only its report-publish push).
**Phase**: orchestrating
**Queue** (staged, in order):
- (empty)
- PLAN-SEC2-01 (WS-01) — plan=sec2-01-doc-honesty-and-config-guards — PR 623 — landing=landings/PLAN-SEC2-01.md — status: shipped
- PLAN-SEC2-02 (WS-02) — plan=sec2-02-upstream-breaker-accounting — PR 627 — landing=landings/PLAN-SEC2-02.md — status: shipped
- PLAN-SEC2-03 (WS-03) — plan=sec2-03-bff-logout-and-source-keys — PR 626 — landing=landings/PLAN-SEC2-03.md — status: shipped
- PLAN-SEC2-04 (WS-04) — plan=sec2-04-leftover-hardening — PR 635 — landing=landings/PLAN-SEC2-04.md — status: shipped
- PLAN-SEC2-05 (WS-04) — plan=sec2-05-early-response-quiet-exit — PR 633 — landing=landings/PLAN-SEC2-05.md — status: shipped
- PLAN-SEC2-06 (WS-04) — plan=sec2-06-follow-up-wording-and-test-gaps — PR 641 — landing=landings/PLAN-SEC2-06.md — status: shipped
- PLAN-SEC2-07 (WS-04) — plan=sec2-07-hardening-quick-wins — PR 647 — landing=landings/PLAN-SEC2-07.md — status: shipped
- PLAN-SEC2-08 (WS-04) — plan=sec2-08-open-watches-and-leftovers — PR 651 — landing=landings/PLAN-SEC2-08.md — status: shipped

## Ordered Queue

| # | Plan | Workstream | Status | Surface (expected) | Lane |
|---|------|------------|--------|--------------------|------|
| — | (empty) | — | — | — | — |
