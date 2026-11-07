# History — api-sheriff-0-2-0

**API Sheriff 0.2.0 — Cleanup, Consolidation and Hardening.** Frozen 2026-11-07 by `close` (operator
instruction), at `main` `7f5c61af`. Scaffolded 2026-08-04. This file and the rest of the tree are the audit
record; nothing in it is live.

## Vision as pursued

Take what 0.1.0 shipped and make it **honest, current and defensible** — without adding capability.
"Done" means: the framework-agnostic architecture question is settled rather than worked around; the
code reads as current Java; the documentation and ADR corpus say what is true, once; the
reconnaissance surface is closed; the BFF meets the FAPI 2.0 Security Profile; and the
`token-sheriff` integration runs through one trust mechanism rather than a process-global override.

**Scope**: consolidation and hardening only. Every net-new capability — the deception layer, the
inventory endpoint, the Helm chart — belongs to `api-sheriff-0-3-0`. The one deliberate exception is
stated in WS-03: the per-client detection substrate is *built* here because its consumer in 0.3.0
would otherwise force a retrofit.

## Final START HERE (rendered from the ledger at close, verbatim)

**Resume anchor**: === 2026-11-07 STATE at main 7f5c61af (docs review #575 651f01ba + #576 7f5c61af merged today) (PARALLELISM n=3; auto_emit true). The queue is EMPTY: 45 shipped, 11 superseded (V02-29 .. V02-39). CLEANUP DONE and CLOSE-OUT DISPOSITIONED 2026-11-07 (epic-closeout skill, operator-approved: full close-out, no close/archive). The epic is NOT closed. This anchor states current state only; history is in landings/, settled.md, epic-closeout.md and the ledger's git history. ===
RUNNING: none. STAGED: none. INBOX: empty.
CLOSE-OUT RECORD: epic-closeout.md § "Close-out disposition — 2026-11-07" (every Open Defect and Watch with its disposition). Lessons corpus drained 26 -> 0 into archive/lessons-consumed/ (15 discards with rationale, 1 standing rule to api-sheriff-0-3-0, 10 doc rules staged as api-sheriff-0-3-0 PLAN-V03-05). Bundle index: no UNSENT row left; Round 1 delivered as plan-marshall lessons-routing api-sheriff-api-sheriff-0-2-0-083/-084/-085 (queued in that repo's ledger worktree, landed by plan-marshall's own orchestrator); Round 9 verified delivered by plan-marshall's 2026-11-02 gather sweep. Re-homed into api-sheriff-0-3-0: Open Defect 1 (was OD 4), 13 Watches, Standing Rules, WS-04 + PLAN-V03-05. Project memory consolidated 31 -> 24 files.
RELEASE-CUT ITEMS MOVED to api-sheriff-0-3-0 at close (2026-11-07, operator closed before the 0.2.5 release): the /marshall-steward run and stale architecture descriptors, FIRST REAL RELEASE AFTER #509, APISIX bearer lane, the release skill's "an ADR is immutable" line, Release Pre-Flight Knowledge, the dry-run Deferred Item. api-sheriff-0-3-0 owns the 0.2.5 release.
SECURITY: the leads this epic carried by reference now live in the git-ignored .plan/local/security_notes_verified.adoc (written 2026-11-07 outside this ledger; security_notes.adoc is empty). Keep them out of tracked text. A separate epic security-hardening-tier-1 was created 2026-11-07 by another session; untouched here.
OPERATOR HAND-OFF OWED: kidicap-gateway-requirements epic.md (framing-corpus flake entry) still points at this epic's Watch; text to repoint it at api-sheriff-0-3-0 is in epic-closeout.md. kidicap is read-only to this epic.

NEXT ACTION (RESTART HERE): none in this epic — it is being closed and archived (operator instruction 2026-11-07). Continue in api-sheriff-0-3-0. Owed outside: the kidicap repoint text in epic-closeout.md; the #325 title edit (cosmetic).
**Phase**: orchestrating
**Inbox (derived)**: 0 queued, 325 archived
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

## Final Ordered Queue (rendered, verbatim — the live queue is empty)

| # | Plan | Workstream | Status | Surface (expected) | Lane |
|---|------|------------|--------|--------------------|------|
| — | (empty) | — | — | — | — |

## Queue outcome

- **Shipped: 45** (status `shipped` / `landed`).
- **Closed unshipped: 11** — PLAN-V02-29 (superseded), PLAN-V02-30 (superseded), PLAN-V02-31 (superseded), PLAN-V02-32 (superseded), PLAN-V02-33 (superseded), PLAN-V02-34 (superseded), PLAN-V02-35 (superseded), PLAN-V02-36 (superseded), PLAN-V02-37 (superseded), PLAN-V02-38 (superseded), PLAN-V02-39 (superseded). The validator-rule
  split V02-29 .. V02-39 was superseded by the regrouped V02-40 .. V02-43, which shipped.
- **Parked: 0.**

| Plan | Slug | Workstream | Outcome | PR | Landing |
|---|---|---|---|---|---|
| PLAN-V02-01 | adr-0005-reversal-quarkus-adoption | WS-01 | shipped | #409 | `landings/PLAN-V02-01.md` |
| PLAN-V02-02 | java-idiom-sweep | WS-01 | shipped | #198 | `landings/PLAN-V02-02.md` |
| PLAN-V02-03 | documentation-restructure | WS-02 | shipped | #197 | `landings/PLAN-V02-03.md` |
| PLAN-V02-04 | adr-corpus-cleanup | WS-02 | shipped | #521 | `landings/PLAN-V02-04.md` |
| PLAN-V02-05 | response-hygiene | WS-03 | shipped | #526 | `landings/PLAN-V02-05.md` |
| PLAN-V02-06 | enumeration-hardening | WS-03 | shipped | #528 | `landings/PLAN-V02-06.md` |
| PLAN-V02-07 | threat-classification | WS-03 | shipped | #537 | `landings/PLAN-V02-07.md` |
| PLAN-V02-08 | fapi-2-0-conformance | WS-04 | shipped | #377 | `landings/PLAN-V02-08.md` |
| PLAN-V02-09 | token-sheriff-integration-fidelity | WS-05 | shipped | #530 | `landings/PLAN-V02-09.md` |
| PLAN-V02-10 | per-client-tls-trust | WS-05 | shipped | #382 | `landings/PLAN-V02-10.md` |
| PLAN-V02-11 | resource-exhaustion-test-home | WS-05 | shipped | #534 | `landings/PLAN-V02-11.md` |
| PLAN-V02-12 | idp-addressing-model | WS-04 | shipped | #492 | `landings/PLAN-V02-12.md` |
| PLAN-V02-13 | terminal-rejection-contract | WS-03 | shipped | #383 | `landings/PLAN-V02-13.md` |
| PLAN-V02-14 | offline-config-validation | WS-01 | shipped | #387 | `landings/PLAN-V02-14.md` |
| PLAN-V02-15 | bff-compose-sample | WS-02 | shipped | #516 | `landings/PLAN-V02-15.md` |
| PLAN-V02-16 | image-metadata-fidelity | WS-01 | shipped | #199 | `landings/PLAN-V02-16.md` |
| PLAN-V02-17 | lessons-into-source | WS-02 | shipped | #200 | `landings/PLAN-V02-17.md` |
| PLAN-V02-18 | retire-doc-plan-survivors | WS-02 | shipped | #368 | `landings/PLAN-V02-18.md` |
| PLAN-V02-19 | orphaned-guard-hygiene | WS-01 | shipped | #367 | `landings/PLAN-V02-19.md` |
| PLAN-V02-20 | cookie-key-file-model | WS-04 | shipped | #527 | `landings/PLAN-V02-20.md` |
| PLAN-V02-21 | unread-review-findings | WS-05 | shipped | #439 | `landings/PLAN-V02-21.md` |
| PLAN-V02-22 | demo-client-and-doc-leftovers | WS-02 | shipped | #512 | `landings/PLAN-V02-22.md` |
| PLAN-V02-23 | cookie-forwarding-policy | WS-03 | shipped | #515 | `landings/PLAN-V02-23.md` |
| PLAN-V02-24 | policy-block-resolution | WS-01 | shipped | #465 | `landings/PLAN-V02-24.md` |
| PLAN-V02-25 | edge-route-decomposition | WS-01 | shipped | #487 | `landings/PLAN-V02-25.md` |
| PLAN-V02-26 | config-validator-split | WS-01 | shipped | #463 | `landings/PLAN-V02-26.md` |
| PLAN-V02-27 | test-simplification | WS-01 | shipped | #520 | `landings/PLAN-V02-27.md` |
| PLAN-V02-28 | validator-limit-rules | WS-01 | shipped | #467 | `landings/PLAN-V02-28.md` |
| PLAN-V02-29 | validator-terminal-action-rules | WS-01 | superseded | — | — |
| PLAN-V02-30 | validator-edge-protocol-rules | WS-01 | superseded | — | — |
| PLAN-V02-31 | validator-asset-forwarding-rules | WS-01 | superseded | — | — |
| PLAN-V02-32 | validator-anchor-rules | WS-01 | superseded | — | — |
| PLAN-V02-33 | validator-access-auth-matrix | WS-01 | superseded | — | — |
| PLAN-V02-34 | validator-auth-oidc-client-rules | WS-01 | superseded | — | — |
| PLAN-V02-35 | validator-oidc-session-rules | WS-01 | superseded | — | — |
| PLAN-V02-36 | validator-session-cookie-rules | WS-01 | superseded | — | — |
| PLAN-V02-37 | validator-reserved-path-rules | WS-01 | superseded | — | — |
| PLAN-V02-38 | validator-client-jwks-path-rule | WS-01 | superseded | — | — |
| PLAN-V02-39 | validator-header-topology-rules | WS-01 | superseded | — | — |
| PLAN-V02-40 | validator-terminal-edge-protocol-rules | WS-01 | shipped | #485 | `landings/PLAN-V02-40.md` |
| PLAN-V02-41 | validator-asset-forwarding-anchor-rules | WS-01 | shipped | #489 | `landings/PLAN-V02-41.md` |
| PLAN-V02-42 | validator-auth-oidc-rules | WS-01 | shipped | #502 | `landings/PLAN-V02-42.md` |
| PLAN-V02-43 | validator-reserved-header-topology-rules | WS-01 | shipped | #505 | `landings/PLAN-V02-43.md` |
| PLAN-V02-44 | route-type-merge | WS-01 | shipped | #476 | `landings/PLAN-V02-44.md` |
| PLAN-V02-45 | request-body-and-h2-buffer-bounds | WS-03 | shipped | #506 | `landings/PLAN-V02-45.md` |
| PLAN-V02-46 | upstream-close-after-early-answer | WS-03 | shipped | #513 | `landings/PLAN-V02-46.md` |
| PLAN-V02-47 | multi-arch-image | WS-05 | shipped | #504 | `landings/PLAN-V02-47.md` |
| PLAN-V02-48 | adr-corpus-cleanup-part-2 | WS-02 | shipped | #544 | `landings/PLAN-V02-48.md` |
| PLAN-V02-49 | test-simplification-part-2 | WS-01 | shipped | #550 | `landings/PLAN-V02-49.md` |
| PLAN-V02-50 | enumeration-detection-substrate | WS-03 | shipped | #532 | `landings/PLAN-V02-50.md` |
| PLAN-V02-51 | lane-conditional-guards | WS-05 | shipped | #538 | `landings/PLAN-V02-51.md` |
| PLAN-V02-52 | adr-relocations-and-citation-rewrite | WS-02 | shipped | #554 | `landings/PLAN-V02-52.md` |
| PLAN-V02-53 | plan-15-follow-up-issues | WS-01 | shipped | #565 | `landings/PLAN-V02-53.md` |
| PLAN-V02-54 | token-sheriff-lazy-generators-measured | WS-05 | shipped | #553 | `landings/PLAN-V02-54.md` |
| PLAN-V02-55 | it-concurrency-and-local-image-hazard | WS-05 | shipped | #558 | `landings/PLAN-V02-55.md` |
| PLAN-V02-56 | final-cleanup | WS-01 | shipped | #566 | `landings/PLAN-V02-56.md` |

## Decision record

The authority is the append-only `logs/decision.log`. The curated view with rationale and rejected
alternatives is `epic.md` § Decisions. Settled narrative relocated during the epic is in `settled.md`;
per-plan outcomes are in `landings/PLAN-*.md`.

## Unresolved defects and watches — carried forward, not dropped

Every Open Defect and Watch open at close has a recorded disposition in `epic-closeout.md`
§ "Close-out disposition — 2026-11-07" (closed with evidence / re-homed / owned by kidicap / frozen). The live
ones continue in `api-sheriff-0-3-0/epic.md`:

- § Open Defects 1 — the integration-lane assertion-strength work list (was Open Defect 4).
- § Watches — 13 inherited entries plus the RELEASE-CUT CARRY: the next release is **0.2.5** and is owned by
  `api-sheriff-0-3-0` (FIRST REAL RELEASE AFTER #509 checklist, `/marshall-steward` run and stale architecture
  descriptors, APISIX bearer lane, release skill's "an ADR is immutable" line).
- § Release Pre-Flight Knowledge for the Next Cut, and the dry-run Deferred Item, both carried verbatim.
- § Standing Rules — this epic's rules and the five `api-sheriff-roadmap` rules (now their only copy).
- WS-04 / PLAN-V03-05 — ten consumed lessons whose rules belong in repository documentation.

Frozen here as history with no further action: the entries `epic-closeout.md` marks **frozen** (Sequencing
Constraints, in-body `PLAN-NN` references, the #387 declined refactor, the #532 local IT residual, the
PLAN-V02-19 upstream tooling leads, ADR-0066's two untested abortive-close triggers by operator ruling, the
untested guard-reader refusal branches).

**Outside tracked text:** security leads this epic carried by reference live in the git-ignored
`.plan/local/security_notes_verified.adoc` (written 2026-11-07 outside this ledger). They are not described
here.

**Owed to another system:** `kidicap-gateway-requirements` still points its framing-corpus flake entry at this
epic's Watch; the repoint text is in `epic-closeout.md`. kidicap is read-only to this epic.

## Close-out

- Cleanup 2026-11-07: corpus 56 rows / 56 specs reconciled; Open Defects 6, 8, 18 relocated to `settled.md`;
  restart verdict `ready`.
- Lessons corpus drained 26 → 0 into `archive/lessons-consumed/` (15 discards with rationale, 1 standing rule,
  10 repository-source rules → `api-sheriff-0-3-0` PLAN-V03-05).
- plan-marshall hand-off: `bundle-handoff-index.md` has no UNSENT row; Round 1 sent as lessons-routing
  `api-sheriff-api-sheriff-0-2-0-083` .. `-085`; Round 9 verified delivered by plan-marshall's 2026-11-02 sweep.
- Project memory consolidated 31 → 24 files.

## Closing rationale

Every plan of the epic shipped or was superseded by a plan that shipped, and the inbox is empty. The
epic's own goal — consolidation and hardening without new capability — is met to the extent the queue
defined it; what remained open is either recorded as a carried Watch or Defect in `api-sheriff-0-3-0` or
frozen here with its reason. The operator chose to close before the next release (0.2.5) instead of after
it, so release ownership moved to `api-sheriff-0-3-0` together with the pre-flight knowledge.
