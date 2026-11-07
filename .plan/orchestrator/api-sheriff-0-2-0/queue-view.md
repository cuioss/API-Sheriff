<!-- GENERATED FILE — never hand-edit. Rendered from this epic's ledger (status.json, resume_anchor.md, queue/*.json) by `orchestrator regenerate-view --slug api-sheriff-0-2-0`. On a merge conflict in this file, do not merge it by hand: merge the source files, run `orchestrator regenerate-view --slug api-sheriff-0-2-0`, and `git add` the result. -->

# Queue view: API Sheriff 0.2.0 — Cleanup, Consolidation and Hardening

## START HERE

**Resume anchor**: === 2026-11-07 STATE at f438a1ab (ledger LANDED as #574; main at 774d3de1 + the land) (PARALLELISM n=3; auto_emit true). PLAN-V02-56 final-cleanup LANDED as #566 (793612f6) and was analyzed 2026-11-06: verified, all deliverables shipped. The queue is EMPTY: every plan of the epic is shipped or superseded. The epic is in its close-out stage. This anchor states current state only; per-plan history is in landings/PLAN-*.md, epic.md Decisions and the ledger's git history. ===
RUNNING: none (0 of 3 slots).
STAGED: none.
SHIPPED (45, queue rows are the authority): newest V02-56 (#566 793612f6, landings/PLAN-V02-56.md), V02-53 (#565 7a5d52ad, D2 cut, #324 open), V02-55 (#558, verified), V02-52 (#554), V02-54 (#553), V02-49 (#547-#550), and earlier. SUPERSEDED: V02-29 .. V02-39.
INBOX: empty after the V02-56 landing drain 2026-11-06 (5 messages: the landing, complete; 001-004 routed to plan-marshall lessons-routing as api-sheriff-api-sheriff-0-2-0-082, 001 a recurrence of 081's merge-gate waiver finding).
OPEN (full text in epic.md): Open Defects 6 (RESOLVED by #534) and 18 (ANSWERED) to strike at the next compaction; 4, 10 carried; 8 closed 2026-11-06. Watches added 2026-11-06: two advisory nits on main from #566; marshal.json stale (operator runs /marshall-steward); accepted BFF residuals carried to 0.3.0; demo-client format:check fails on main (no Prettier config, not wired into CI). Struck 2026-11-07: SONAR BACKLOG (#573 774d3de1, main at 0 Sonar issues, rating A). Struck 2026-11-06: Dependabot minimatch (#572 2f91ff7d, alert #5 fixed); the eight [OWNER PLAN-V02-56] Watches and RELEASE-GUARD DOCS (#563 passed the check). Watch added 2026-11-05: #324 open (key-level inventory, a 0.3.0 candidate). Struck 2026-11-05: V02-55 (c), the #558 benchmark, formatter churn, ADR-0059, kidicap PLAN-29 D6. Watches new or changed 2026-10-31: formatter churn CLOSED by operator 2026-11-05 (both Watches struck); V02-49 residue (JaCoCo comparison pre-dates restored tests; GatewayEdgeEarlyResponseResetTest failed once in a unit run under load 30; advisories done by #566); CodeRabbit's merge-risk lead on #544 is carried into V02-52 D5 (the benchmark half closed: run 41091771555 success). Older Watches unchanged: Http2StreamAbuseIT.declaredBodyReachesTheUpstreamWithItsLength (a recurrence is a suspected production defect: reproduce under ActiveProcessorCount=1 and stage a fix plan), FIRST REAL RELEASE AFTER #509 (first multi-arch publish, owes the compose-sample .env pin bump and a check-bff-login.sh run), GW-07 request-body PARTIAL (operator-named residual), ADR-0066 untested abortive-close triggers, the APISIX bearer lane, per-route connect/read timeouts inert (kidicap PLAN-29, must relate to V02-45's fixed 25 s body deadline). REVIEWERS since 2026-10-20: CodeRabbit required, Sourcery optional.

OUTSIDE PR #555 (outside contributor, "Fixes #329"): TAKEN by operator ruling 2026-11-04; re-hosted as #561 (eaf93b7c) because fork PRs get no SONAR_TOKEN; #555 closed, #329 closed. V02-53 D7 covers the remainder: endpoint-routes.adoc, the schema description, the YAML sweep, and a check of the merged rule.

NEXT ACTION (RESTART HERE): 1) Close-out stage. Run the cleanup verb (spec corpus review, ledger compaction, restart-readiness); strike Open Defects 6 and 18 there. 2) Before the cut: operator runs /marshall-steward (marshal.json stale) and re-checks build.map's four hand-added entries. 3) The release dry run (direct session, brief in this tree), then the 0.2.0 cut along the FIRST REAL RELEASE Watch checklist (multi-arch publish, compose-sample .env pin bump, check-bff-login.sh run incl. the new /auth-fronted variant). 4) land, then close. Carry into api-sheriff-0-3-0: two unfixed security leads that live ONLY in the git-ignored .plan/local/security_notes.adoc (the 2026-10-31 plan-v02-49 entry, operator decision pending, and the 2026-11-07 #573 entry, unvetted): keep them out of tracked text until vetted; Open Defects 4 and 10, the #324 Watch, the accepted BFF residuals Watch, the two-nits Watch, the demo-client format:check Watch. 5) Owed by the operator: the #325 title edit ("restated in four places"). 6) If Http2StreamAbuseIT or GatewayEdgeEarlyResponseResetTest goes red again, stop calling it a flake.
**Phase**: orchestrating
**Queue** (staged, in order):
- (empty)
- PLAN-V02-01 (WS-01) — plan=plan-v02-01-adr-0005-reversal-quarkus-adoption — PR 409 — landing=landings/PLAN-V02-01.md — status: shipped
- PLAN-V02-02 (WS-01) — plan=plan-v02-02-java-idiom-sweep — PR 198 — landing=landings/PLAN-V02-02.md — status: shipped
- PLAN-V02-03 (WS-02) — plan=plan-v02-03-documentation-restructure — PR 197 — landing=landings/PLAN-V02-03.md — status: shipped
- PLAN-V02-04 (WS-02) — plan=plan-v02-04-adr-corpus-cleanup — PR 521 — landing=landings/PLAN-V02-04.md — status: shipped
- PLAN-V02-05 (WS-03) — plan=plan-v02-05-response-hygiene — PR 526 — landing=landings/PLAN-V02-05.md — status: shipped
- PLAN-V02-06 (WS-03) — plan=plan-v02-06-enumeration-hardening — PR 528 — landing=landings/PLAN-V02-06.md — status: shipped
- PLAN-V02-07 (WS-03) — plan=plan-v02-07-threat-classification — PR 537 — landing=landings/PLAN-V02-07.md — status: shipped
- PLAN-V02-08 (WS-04) — plan=plan-v02-08-fapi-2-0-conformance — PR 377 — landing=landings/PLAN-V02-08.md — status: shipped
- PLAN-V02-09 (WS-05) — plan=plan-v02-09-token-sheriff-integration-fidelity — PR 530 — landing=landings/PLAN-V02-09.md — status: shipped
- PLAN-V02-10 (WS-05) — plan=plan-v02-10-per-client-tls-trust — PR 382 — landing=landings/PLAN-V02-10.md — status: shipped
- PLAN-V02-11 (WS-05) — plan=plan-v02-11-resource-exhaustion-test-home — PR 534 — landing=landings/PLAN-V02-11.md — status: shipped
- PLAN-V02-12 (WS-04) — plan=plan-v02-12-idp-addressing-model — PR 492 — landing=landings/PLAN-V02-12.md — status: shipped
- PLAN-V02-13 (WS-03) — plan=plan-v02-13-terminal-rejection-contract — PR 383 — landing=landings/PLAN-V02-13.md — status: shipped
- PLAN-V02-14 (WS-01) — plan=plan-v02-14-offline-config-validation — PR 387 — landing=landings/PLAN-V02-14.md — status: shipped
- PLAN-V02-15 (WS-02) — PR 516 — landing=landings/PLAN-V02-15.md — status: shipped
- PLAN-V02-16 (WS-01) — plan=plan-v02-16-image-metadata-fidelity — PR 199 — landing=landings/PLAN-V02-16.md — status: shipped
- PLAN-V02-17 (WS-02) — plan=plan-v02-17-lessons-into-source — PR 200 — landing=landings/PLAN-V02-17.md — status: shipped
- PLAN-V02-18 (WS-02) — plan=retire-doc-plan-survivors — PR 368 — landing=landings/PLAN-V02-18.md — status: shipped
- PLAN-V02-19 (WS-01) — plan=plan-v02-19-orphaned-guard-hygiene — PR 367 — landing=landings/PLAN-V02-19.md — status: shipped
- PLAN-V02-20 (WS-04) — plan=plan-v02-20-cookie-key-file-model — PR 527 — landing=landings/PLAN-V02-20.md — status: shipped
- PLAN-V02-21 (WS-05) — plan=plan-v02-21-unread-review-findings — PR 439 — landing=landings/PLAN-V02-21.md — status: shipped
- PLAN-V02-22 (WS-02) — plan=plan-v02-22-demo-client-and-doc-leftovers — PR 512 — landing=landings/PLAN-V02-22.md — status: shipped
- PLAN-V02-23 (WS-03) — plan=plan-v02-23-cookie-forwarding-policy-r2 — PR 515 — landing=landings/PLAN-V02-23.md — status: shipped
- PLAN-V02-24 (WS-01) — plan=plan-v02-24-policy-block-resolution — PR 465 — landing=landings/PLAN-V02-24.md — status: shipped
- PLAN-V02-25 (WS-01) — plan=plan-v02-25-edge-route-decomposition — PR 487 — landing=landings/PLAN-V02-25.md — status: shipped
- PLAN-V02-26 (WS-01) — plan=plan-v02-26-config-validator-split — PR 463 — landing=landings/PLAN-V02-26.md — status: shipped
- PLAN-V02-27 (WS-01) — plan=plan-v02-27-test-simplification — PR 520 — landing=landings/PLAN-V02-27.md — status: shipped
- PLAN-V02-28 (WS-01) — plan=plan-v02-28-validator-limit-rules — PR 467 — landing=landings/PLAN-V02-28.md — status: shipped
- PLAN-V02-29 (WS-01) — status: superseded
- PLAN-V02-30 (WS-01) — status: superseded
- PLAN-V02-31 (WS-01) — status: superseded
- PLAN-V02-32 (WS-01) — status: superseded
- PLAN-V02-33 (WS-01) — status: superseded
- PLAN-V02-34 (WS-01) — status: superseded
- PLAN-V02-35 (WS-01) — status: superseded
- PLAN-V02-36 (WS-01) — status: superseded
- PLAN-V02-37 (WS-01) — status: superseded
- PLAN-V02-38 (WS-01) — status: superseded
- PLAN-V02-39 (WS-01) — status: superseded
- PLAN-V02-40 (WS-01) — plan=plan-v02-40-validator-terminal-edge-protocol-rules — PR 485 — landing=landings/PLAN-V02-40.md — status: shipped
- PLAN-V02-41 (WS-01) — plan=plan-v02-41-validator-asset-forwarding-anchor-rules — PR 489 — landing=landings/PLAN-V02-41.md — status: shipped
- PLAN-V02-42 (WS-01) — plan=plan-v02-42-validator-auth-oidc-rules — PR 502 — landing=landings/PLAN-V02-42.md — status: shipped
- PLAN-V02-43 (WS-01) — plan=plan-v02-43-validator-reserved-header-topology-rules — PR 505 — landing=landings/PLAN-V02-43.md — status: shipped
- PLAN-V02-44 (WS-01) — plan=plan-v02-44-route-type-merge — PR 476 — landing=landings/PLAN-V02-44.md — status: shipped
- PLAN-V02-45 (WS-03) — plan=plan-v02-45-request-body-and-h2-buffer-bounds — PR 506 — landing=landings/PLAN-V02-45.md — status: shipped
- PLAN-V02-46 (WS-03) — plan=plan-v02-46-upstream-close-after-early-answer — PR 513 — landing=landings/PLAN-V02-46.md — status: shipped
- PLAN-V02-47 (WS-05) — plan=plan-v02-47-multi-arch-image — PR 504 — landing=landings/PLAN-V02-47.md — status: shipped
- PLAN-V02-48 (WS-02) — plan=plan-v02-48-adr-corpus-cleanup-part-2 — PR 544 — landing=landings/PLAN-V02-48.md — status: shipped
- PLAN-V02-49 (WS-01) — plan=plan-v02-49-test-simplification-part-2 — PR 550 — landing=landings/PLAN-V02-49.md — status: shipped
- PLAN-V02-50 (WS-03) — plan=plan-v02-50-enumeration-detection-substrate — PR 532 — landing=landings/PLAN-V02-50.md — status: shipped
- PLAN-V02-51 (WS-05) — plan=plan-v02-51-lane-conditional-guards — PR 538 — landing=landings/PLAN-V02-51.md — status: shipped
- PLAN-V02-52 (WS-02) — plan=plan-v02-52-adr-relocations-and-citation-rewrite — PR 554 — landing=landings/PLAN-V02-52.md — status: shipped
- PLAN-V02-53 (WS-01) — plan=plan-v02-53-plan-15-follow-up-issues — PR 565 — landing=landings/PLAN-V02-53.md — status: shipped
- PLAN-V02-54 (WS-05) — plan=plan-v02-54-token-sheriff-lazy-generators-measured — PR 553 — landing=landings/PLAN-V02-54.md — status: shipped
- PLAN-V02-55 (WS-05) — plan=plan-v02-55-it-concurrency-and-local-image-hazard — PR 558 — landing=landings/PLAN-V02-55.md — status: shipped
- PLAN-V02-56 (WS-01) — plan=plan-v02-56-final-cleanup — PR 566 — landing=landings/PLAN-V02-56.md — status: shipped

## Ordered Queue

| # | Plan | Workstream | Status | Surface (expected) | Lane |
|---|------|------------|--------|--------------------|------|
| — | (empty) | — | — | — | — |
