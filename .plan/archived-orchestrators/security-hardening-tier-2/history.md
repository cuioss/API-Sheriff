# History: Security hardening - tier 2 (follow-ups)

slug: security-hardening-tier-2
closed: 2026-11-22
closing state: all eight plans shipped and analyzed; no plan running, staged or parked; the inbox empty; no open defect.

> Frozen record written by the `close` verb. `epic.md`, `settled.md`, `landings/`, `plans/`, `inbox/archive/` and `logs/` stay on disk
> untouched as the audit trail. The queue blocks below are the ones `resume-summary` rendered from the ledger at close, verbatim.

## Vision as pursued

Close the follow-up findings left after epic `security-hardening-tier-1` (archived 2026-11-14) and the operator's follow-up PR #620, from the
2026-11-14 ground-truth check of the operator's private verified-findings notes at `main` `6ee137df`. Order: the simple and documentation
changes first (PLAN-SEC2-01), the private notes re-checked against the result, then the two code plans (upstream breaker accounting,
PLAN-SEC2-02; BFF logout and source keys, PLAN-SEC2-03). The epic grew by the operator's word: a plan for every leftover (04), the
early-response quiet exit (05), a follow-up for the minor leftovers (06), hardening quick wins (07) and a last plan for the open watches
(08). Disclosure stayed mechanism-level; the triggering detail of each item lives only in the git-ignored private notes.

## Queue at close (rendered from the ledger)

START HERE block:

```text
**Resume anchor**: READY TO CLOSE (2026-11-22): PLAN-SEC2-01 to 08 all shipped and analyzed (08 = #651, a2ff139e; its ledger landed through #652, 40d67c6a); no plan row is running or staged, the inbox is empty and no Open Defect is open (the 07 incomplete-landing defect went to the kidicap-gateway-requirements inbox as security-hardening-tier-2-006 on the operator's word). Remaining: run cleanup and confirm restart verdict ready, then close ... then archive.
**Phase**: orchestrating
**Inbox (derived)**: 0 queued, 15 archived
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
```

Ordered Queue block:

```text
| # | Plan | Workstream | Status | Surface (expected) | Lane |
|---|------|------------|--------|--------------------|------|
| — | (empty) | — | — | — | — |
```

## Queue outcome

Shipped: 8. Closed unshipped: 0. Parked: 0.

| Plan | PR | Merge commit | Note |
|------|----|--------------|------|
| PLAN-SEC2-01 | #623 | `31595bca` | doc honesty and config guards |
| PLAN-SEC2-02 | #627 | `14741380` | gateway-owned upstream breaker replaced the SmallRye one |
| PLAN-SEC2-03 | #626 | `fa8dd54f` | BFF logout and source keys; the reserved-slice clause was not built |
| PLAN-SEC2-04 | #635 | `00ebea64` | the one plan for every leftover, 12 deliverables |
| PLAN-SEC2-05 | #633 | `7f09b60b` | confirmed quiet exit for the early-response end |
| PLAN-SEC2-06 | #641 | `d5288639` | wording sweep and test blind spots |
| PLAN-SEC2-07 | #647 | `2335c81d` | hardening quick wins; deliverable 4 dropped at outline, as its spec allows |
| PLAN-SEC2-08 | #651 | `a2ff139e` | open watches and leftovers; deliverable 1 shipped as a reachability test, not the specified shape |

No three-layer read found a blocker in any landing. The layer-3 reads of the weakness class (04 to 07) were reported to the operator in the
session and are held by the operator outside the repository; nothing of them is in a tracked file.

## Decisions

The dated decisions are in `epic.md` § Decisions (2026-11-19 onward) and `settled.md` § "Decisions 2026-11-14 to 2026-11-19 (settled)". The ones
that shaped the end of the epic:

- 2026-11-20 — the epic was wound down once: every then-open item went to `kidicap-gateway-requirements` as inbox messages `security-hardening-tier-2-001` to `-005`, or was retired on re-verification; PLAN-SEC2-07 was staged the same day.
- 2026-11-21 — PLAN-SEC2-08 staged as the last plan. Its first draft settled the idle-host repetitions by construction; the operator chose instead to drop that deliverable and carry the Watch as an owed lead.
- 2026-11-22 — the 08 landing analyzed (deliverable 1's observable cannot fail because `Wait.acknowledged` returns on `ended`; reachability pins the wiring instead); the Watches 08 settled were retired; the one open defect (the incomplete landing facts of 07, `effort` unknown) was transferred to the kidicap inbox as `security-hardening-tier-2-006`; the main-branch runs of the 04 to 08 merge commits were read (all green except `00ebea64`'s report-publish `git push` to `cuioss.github.io`, a push race; its 41 integration tests passed).
- 2026-11-22 — ledger land #652 was ejected from the merge queue twice with `branch_protection_failure` (merge-group checks green) and merged on the third enqueue; no cause was found.

## Unresolved defects and watches, carried forward as leads

No open defect. Five Watches stay open as owed leads; none is carried by a plan, and each is dropped, forwarded or run only on the operator's word:

1. **Idle-host repetitions of `DispatchStageTest$BodyDeadlineBehindASlowConnection` (PLAN-SEC2-06, L1-1).** The test-side race is neither shown nor refuted (claim persisted `unverifiable`); 160 loaded-host repeats showed no failure of the suspected read. It needs a host that is not loaded (a load average above 200 was measured), not a change. Retire it when someone runs the repetitions on an idle host and records the result.
2. **Per-Host wording of the `ApiSheriff-152` record (PLAN-SEC2-08 landing).** `ACTING_ACTION_CONSEQUENCE` (`EdgeRules.java:99-103`), the `ConfigLogMessages` Javadoc and `doc/LogMessages.adoc:114` say every terminated client is keyed on one source, while the source key folds the authority host (`SourceKeys.java:145-150`) and `doc/configuration/security-and-edge-hardening.adoc` already says terminated requests to the same Host are one source.
3. **`integration-tests/docker-compose.yml:944`** says "The five other gateway instances escape this only because each overlays gateway.yaml with a variant declaring no passthrough_sni" in a file of twelve `api-sheriff*` services. Whether five is the right count of the overlaying instances was not established.
4. **`EarlyResponseEndTest.lateAcknowledgementDoesNotEndTheWaitASecondTime` and its Javadoc** still read as a release-wiring guard though the test re-implements the ending in a lambda; the wiring is pinned by `InboundStreamWindowTest.endedWaitLetsGoOfItsEndingCallWhileItsPingIsStillPending`, whose mutation-red claim is the lane's run result.
5. **The `MainListenerConnectionCapIT` case `countsAConnectionOnlyOnceItsHandshakeCompletes`** was not observed running in the PR's Integration Tests log; the claim that the connection cap counts after the handshake stays `unverifiable` until the case is run once on the native image.

Also open outside this repository's ledger: the kidicap inbox holds `security-hardening-tier-2-001`, `-005` and `-006` from this sender; `-005` promised a `stream-end` marker once the epic's last leftover was forwarded, and none was filed (the operator did not ask for it).

## Closing rationale

Every plan the epic staged has shipped and been analyzed three ways; the open defect was transferred; the Watches the last plan could settle were retired and the remainder is named above. The restart readiness check read `indeterminate` at close, from one signal only: the plan-marshall registry pins 0.1.2183 and the executor is at 0.1.2173 (`registry_parity`); the five ledger signals were `ready`. Closed on the operator's word of 2026-11-22.
