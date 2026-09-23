# Landing Analysis: PLAN-05 — BFF Refresh Integration Coverage

epic: deployment-configurability
workstream: WS-04
pr: [#282](https://github.com/cuioss/API-Sheriff/pull/282) — merged as `b5369ca` (merge queue)

> ⛔ **Shipped WITH a regression, deliberately left in place.** `main` is red on `Demo Client E2E`
> from this merge, at operator direction, pending the remediation now staged as **PLAN-16**. This
> record is written that way rather than as a clean landing, because the plan's own message
> `…-007.md` **supersedes its landing claim** on exactly that point.

## Deliverable Fidelity vs Spec

Six deliverables against the spec's five — the set was re-derived at outline and grew.

| Deliverable | Verdict |
|---|---|
| 1. Short-lifespan OIDC client + test user in the integration realm | shipped — ⚠ note it took a **new instance**, not the in-realm client-attribute route TokenSheriff's prior art recommended |
| 2. `sheriff-config-refresh` overlay, `api-sheriff-refresh` instance, inventory row | shipped, added-unplanned |
| 3. Topology document + SVG redrawn for the tenth instance | shipped, added-unplanned |
| 4. All three `RefreshOutcome.Kind` branches driven through the live BFF edge | shipped-as-specified — the spec's deliverable 3 |
| 5. Two unsupported prose claims in the IT suite corrected | shipped-as-specified — the spec's deliverable 4 |
| 6. Reproduction outcome recorded as a first-class result | shipped-as-specified — the spec insisted this was a deliverable, not a postscript |

### ✅ The reproduction succeeded, and the root cause was upstream of where everyone was looking

The spec's whole premise was that no test ever forces the refresh. It does now — and the answer was
not in the leeway arithmetic at all:

**`CallbackEndpoint` never stored the refresh token.** `TokenRefreshCoordinator.refresh` therefore
returned at its very first guard — corroborated at `TokenRefreshCoordinator.java:127`,
`if (session.refreshToken() == null || !nearExpiry(session, now))` — **before any logging**. The fix
is visible at `CallbackEndpoint.java:239` (`.refreshToken(result.refreshToken())`) with the mechanism
documented in the comment at `:233`.

⛔ **This explains both halves of the original report.** The failure mode was *silence* because the
guard preceded every log statement, and an **IdP-revoked session kept answering 200** because the
refresh that would have discovered the revocation never ran. The user's report of "an exception at
refresh time" was real; the reason nobody could find it is that the code path exited before it could
speak.

⚠ **Note what this does to the cross-repo elimination.** TokenSheriff's verdict eliminated case 2a
within a stated bound and named **H4 — this repository's BFF wiring — as the remaining live
hypothesis**. H4 was right, and the fault was upstream of the refresh call rather than in it.
Residual 4 (discovery-resolved metadata), which this epic carried as the highest-priority lead, was
**not** where it lived.

## Metrics and Anomalies

45h55m wall against **2h14m worked**, ~3M tokens — by far the widest idle ratio in the epic.

## Routing and Merge Behavior

Merged via the merge queue as `b5369ca`; worktree removed; plan archived. `emit-landing` delivered
(the fourth success against three skips), and the plan then filed a **seventh message correcting its
own landing** once the post-merge failure surfaced.

⛔ **That self-correction is the inbox channel working exactly as designed** — a landing is emitted
before post-merge signals arrive, so the ability to supersede it is what keeps the ledger honest
rather than merely early.

## Reconciliation Actions

- [x] row `status` → `shipped`; `pr` `282`, `landing`, `plan_marshall_plan_id` stamped
- [x] Inbox drained: **7 scanned, 7 archived, 0 invalid** — 5 candidate-lessons deferred as a batch,
      1 landing reconciled, 1 finding absorbed
- [x] Three defects opened from the regression (red `main`; the cookie ITs' deliverability blindness;
      the `40..8192` ceiling above the browser guarantee)
- [x] **PLAN-16 staged and emitted** carrying this plan's own D1/D2/D3
- [x] Separately, the operator's SNAPSHOT-staleness observation opened as its own defect

## Follow-Ups

1. ⛔ **`main` is red until PLAN-16 deliverable 1 lands.** Config-plus-docs only: `refresh.enabled:
   false` and drop `max_cookie_size: 8192` from the cookie overlay.
2. ⛔ **The deliverability blindness is the durable finding, and it predates this plan.** The cookie
   stayed under 4096 only because the refresh token was not stored — the bug this plan fixed was what
   kept the suite accidentally honest. PLAN-16 D2 moves the signal onto the PR.
3. ⚠ **Five candidate lessons deferred as a batch.** Two of them —
   *"a green test suite is not coverage; assert the gate's own measurement"* and *"fixing a
   vacuous-green selector obliges sweeping every sibling lane that shares it"* — are the direct
   generalisation of this very regression and should cite it when promoted.
4. ⚠ **The fixture took a new instance rather than the in-realm client route.** TokenSheriff's prior
   art chose a client-level attribute inside the existing realm specifically to avoid widening the
   surface; this plan added a tenth instance and an overlay. Not wrong — it bought a cleaner
   separation — but it is a divergence from the folded guidance and is recorded as such.
