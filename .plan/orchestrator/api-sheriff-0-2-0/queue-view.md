<!-- GENERATED FILE — never hand-edit. Rendered from this epic's ledger (status.json, resume_anchor.md, queue/*.json) by `orchestrator regenerate-view --slug api-sheriff-0-2-0`. On a merge conflict in this file, do not merge it by hand: merge the source files, run `orchestrator regenerate-view --slug api-sheriff-0-2-0`, and `git add` the result. -->

# Queue view: API Sheriff 0.2.0 — Cleanup, Consolidation and Hardening

## START HERE

**Resume anchor**: === 2026-11-07 STATE at main 7f5c61af (docs review #575 651f01ba + #576 7f5c61af merged today) (PARALLELISM n=3; auto_emit true). The queue is EMPTY: 45 shipped, 11 superseded (V02-29 .. V02-39). CLEANUP DONE and CLOSE-OUT DISPOSITIONED 2026-11-07 (epic-closeout skill, operator-approved: full close-out, no close/archive). The epic is NOT closed. This anchor states current state only; history is in landings/, settled.md, epic-closeout.md and the ledger's git history. ===
RUNNING: none. STAGED: none. INBOX: empty.
CLOSE-OUT RECORD: epic-closeout.md § "Close-out disposition — 2026-11-07" (every Open Defect and Watch with its disposition). Lessons corpus drained 26 -> 0 into archive/lessons-consumed/ (15 discards with rationale, 1 standing rule to api-sheriff-0-3-0, 10 doc rules staged as api-sheriff-0-3-0 PLAN-V03-05). Bundle index: no UNSENT row left; Round 1 delivered as plan-marshall lessons-routing api-sheriff-api-sheriff-0-2-0-083/-084/-085 (queued in that repo's ledger worktree, landed by plan-marshall's own orchestrator); Round 9 verified delivered by plan-marshall's 2026-11-02 gather sweep. Re-homed into api-sheriff-0-3-0: Open Defect 1 (was OD 4), 13 Watches, Standing Rules, WS-04 + PLAN-V03-05. Project memory consolidated 31 -> 24 files.
STILL LIVE HERE UNTIL THE RELEASE CUT: the /marshall-steward run (marshal.json 0.1.1986 vs plugin 0.1.2036; architecture descriptors still name doc/plan/ and the retired quality report; re-check build.map's four hand-added entries after it); FIRST REAL RELEASE AFTER #509; APISIX bearer lane; the release skill's "an ADR is immutable" line; Release Pre-Flight Knowledge (harvest into api-sheriff-0-3-0 after the cut); Deferred Item (dry run). THE NEXT RELEASE IS 0.2.5 (tags 0.2.0-0.2.4 exist; project.yml next-version 0.2.5-SNAPSHOT) — "the 0.2.0 cut" in this ledger means that release.
SECURITY: the leads this epic carried by reference now live in the git-ignored .plan/local/security_notes_verified.adoc (written 2026-11-07 outside this ledger; security_notes.adoc is empty). Keep them out of tracked text. A separate epic security-hardening-tier-1 was created 2026-11-07 by another session; untouched here.
OPERATOR HAND-OFF OWED: kidicap-gateway-requirements epic.md (framing-corpus flake entry) still points at this epic's Watch; text to repoint it at api-sheriff-0-3-0 is in epic-closeout.md. kidicap is read-only to this epic.

NEXT ACTION (RESTART HERE): 1) Operator runs /marshall-steward, then re-checks build.map's four hand-added entries. 2) Release dry run, then the 0.2.5 release along the FIRST REAL RELEASE checklist (multi-arch publish, compose-sample .env pin bump, check-bff-login.sh incl. the /auth-fronted variant). 3) After the cut: close or re-home the kept-until-cut items, harvest Release Pre-Flight Knowledge into api-sheriff-0-3-0. 4) land (ledger worktree holds this session's commits), then close, then archive. 5) Owed by the operator: the #325 title edit (cosmetic; #325 is closed). 6) If Http2StreamAbuseIT or GatewayEdgeEarlyResponseResetTest goes red again, it is tracked in api-sheriff-0-3-0's flake-family Watch: reproduce, do not re-run to green.
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
