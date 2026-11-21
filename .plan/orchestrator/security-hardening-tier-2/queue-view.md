<!-- GENERATED FILE — never hand-edit. Rendered from this epic's ledger (status.json, resume_anchor.md, queue/*.json) by `orchestrator regenerate-view --slug security-hardening-tier-2`. On a merge conflict in this file, do not merge it by hand: merge the source files, run `orchestrator regenerate-view --slug security-hardening-tier-2`, and `git add` the result. -->

# Queue view: Security hardening - tier 2 (follow-ups)

## START HERE

**Resume anchor**: RESTART POINT (2026-11-21): PLAN-SEC2-01 to 07 shipped and analyzed; PLAN-SEC2-08 (open watches and leftovers, last plan, 4 deliverables) is RUNNING, recorded on the operator's word. Do NOT re-emit it. Next session: resume; verify 08 against ground truth (its PR, plan lifecycle); when it lands the inbox will hold sec2-08-open-watches-and-leftovers-001. Then: analyze it (three-layer read, one leaf; re-read checked points; weakness-class items to the operator only; deliverable 4 is optional, a drop is allowed), stamp the row, retire the Watches it settled (AcknowledgementRelay wiring; the four 07 leftovers) or keep what it dropped, dispose of the incomplete-landing Open Defect (effort unknown; lane limitation, retire or route to plan-marshall lessons-routing on the operator's word), land, run cleanup, then close (history.md carries the idle-host Watch forward as an owed lead by the operator's choice) and archive. The kidicap-gateway-requirements ledger is written only on the operator's explicit word. The operator holds the layer-3 reads of the 06 and 07 landings outside the repository. The main-branch Maven Build runs of the merges were never read (no lookup by merge commit in the CI abstraction; gh directly only on the operator's word).
**Phase**: orchestrating
**Running**:
- PLAN-SEC2-08 (WS-04)
**Queue** (staged, in order):
- (empty)
- PLAN-SEC2-01 (WS-01) — plan=sec2-01-doc-honesty-and-config-guards — PR 623 — landing=landings/PLAN-SEC2-01.md — status: shipped
- PLAN-SEC2-02 (WS-02) — plan=sec2-02-upstream-breaker-accounting — PR 627 — landing=landings/PLAN-SEC2-02.md — status: shipped
- PLAN-SEC2-03 (WS-03) — plan=sec2-03-bff-logout-and-source-keys — PR 626 — landing=landings/PLAN-SEC2-03.md — status: shipped
- PLAN-SEC2-04 (WS-04) — plan=sec2-04-leftover-hardening — PR 635 — landing=landings/PLAN-SEC2-04.md — status: shipped
- PLAN-SEC2-05 (WS-04) — plan=sec2-05-early-response-quiet-exit — PR 633 — landing=landings/PLAN-SEC2-05.md — status: shipped
- PLAN-SEC2-06 (WS-04) — plan=sec2-06-follow-up-wording-and-test-gaps — PR 641 — landing=landings/PLAN-SEC2-06.md — status: shipped
- PLAN-SEC2-07 (WS-04) — plan=sec2-07-hardening-quick-wins — PR 647 — landing=landings/PLAN-SEC2-07.md — status: shipped

## Ordered Queue

| # | Plan | Workstream | Status | Surface (expected) | Lane |
|---|------|------------|--------|--------------------|------|
| 1 | PLAN-SEC2-08 | WS-04 | running | api-sheriff/src/main/java/de/cuioss/sheriff/gateway/bff/pending/PendingAuthorizationStore.java; api-sheriff/src/main/java/de/cuioss/sheriff/gateway/config/ConfigLogMessages.java; api-sheriff/src/main/java/de/cuioss/sheriff/gateway/edge/admission/ConnectionCapAudit.java; api-sheriff/src/main/java/de/cuioss/sheriff/gateway/edge/end/EarlyResponseEnd.java; api-sheriff/src/main/resources/application.properties; api-sheriff/src/test/java/de/cuioss/sheriff/gateway/config/validation/rule/EdgeRulesTest.java; api-sheriff/src/test/java/de/cuioss/sheriff/gateway/edge/end/EarlyResponseEndTest.java; doc/LogMessages.adoc; doc/architecture/tls-and-inbound-security.adoc; doc/configuration/security-and-edge-hardening.adoc; doc/security-threat-model.adoc; doc/user/edge-security.adoc; integration-tests/docker-compose.yml; integration-tests/src/main/docker/sheriff-config/gateway.yaml; integration-tests/src/test/java/de/cuioss/sheriff/gateway/integration/MainListenerConnectionCapIT.java | plan-marshall |
