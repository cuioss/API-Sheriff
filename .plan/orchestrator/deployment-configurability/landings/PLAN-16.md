# Landing Analysis: PLAN-16 — Cookie Deliverability and the Ceiling Footgun

epic: deployment-configurability
workstream: WS-04
pr: [#284](https://github.com/cuioss/API-Sheriff/pull/284) — merged as `990aebf`

> ⛔ **`main` IS GREEN.** `Demo Client E2E` — the only lane that catches this class, which runs
> post-merge and could not gate the PR — came back **success**, and all four post-merge runs passed.
> The red PR #282 deliberately left is cleared.

## Deliverable Fidelity vs Spec

Five deliverables, all shipped.

| Deliverable | Verdict |
|---|---|
| 1. Analysis of why nine tests passed on a session no browser could hold | shipped |
| 2. Browser-safe posture restored — the red-`main` fix | ✅ **proven, not asserted** |
| 3. `assertCookiesFitBrowserBudget` on the shared login path | shipped — verified at `BffKeycloakLoginFlow.java:317`, measuring the **emitted header** as the spec required |
| 4. `ApiSheriff-114` corrected, `ApiSheriff-124` added | shipped — `doc/LogMessages.adoc:80` |
| 5. 56-file pro-forma audit folded into the test-corpus note, shape (e) added | shipped |

### ✅ The reversion control is the result

With the overlay reverted, **all nine previously-blind cookie tests go red** on the new assertion —
`__Host-sheriff-session` at **5200 bytes**. That is the epic's own standing rule (*a key that parses
is not a key that acts*) discharged against tests: the blindness is not merely fixed, it is
**demonstrated closed**. A green suite proves nothing here; a suite that goes red on reversion proves
exactly the thing.

### ⛔ Deliverable 4 took the branch I argued against — and was right to

The spec offered two routes and I recorded a preference for **capping at 4096**, because that would
make `ApiSheriff-114`'s existing text true as written and avoid a `doc/LogMessages.adoc` edit
colliding with then-running PLAN-07.

The plan **kept the range at 40..8192** and added `ApiSheriff-124` instead. ✅ **Better call:** PLAN-07
shipped before this reached finalize, so the collision I was fencing against evaporated — and capping
would have removed a range some deployment may legitimately want, to fix a *documentation* defect.
Correcting the message and adding a boot-time warning fixes the actual problem without narrowing the
product.

## Metrics and Anomalies

**24 defects caught before merge, across four independent layers** — 18 by self-review over 4 rounds,
9 by CodeRabbit over 3, 3 by Sonar.

### ⛔ Two of them are the epic's own theme, self-inflicted

1. **`ApiSheriff-124` would have shipped comparing a value budget against RFC 6265's *header*
   guarantee** — a stated guarantee with no mechanism behind it, **introduced by the deliverable
   written to close exactly that class.** Caught by self-review, then hardened twice more by
   CodeRabbit, which showed the fixed `4019` threshold still failed for non-default cookie names and
   TTLs. The derivation now lives once on the codec.
2. **`toSetCookieHeader` bounded `Max-Age` below but not above**, so a clock before the login instant
   emitted a longer-lived cookie than configured — **while its own javadoc already claimed the cap.**
   Same class, in this plan's own new code.

⚠ **That is the seventh and eighth instance this epic has recorded**, and the first two authored *by
the fix for the sixth*. The lesson is not "be careful" — it is that this class is not eliminated by
attention, only by a mechanism, which is precisely what the reversion control provides and what
these two lacked at the moment they were written.

## Routing and Merge Behavior

- Merged as `990aebf`; worktree removed; plan archived. Inbox drained clean — 0 queued.
- ⛔ **The stall has a named signature now.** PLAN-07 landing as #283 made this PR **conflicted**, and
  **GitHub silently refuses to run `pull_request` workflows on a conflicted PR** — so CI failed
  closed waiting for a covering run that could never appear. **Rebasing fixed it instantly.**
  ✅ This is a genuinely reusable diagnosis: *CI stuck with no run at all + PR conflicted = rebase,
  not investigate.*

## Reconciliation Actions

- [x] row `status` → `shipped`; `pr` `284`, `landing`, `plan_marshall_plan_id` stamped
- [x] **Red-`main` defect RETIRED** — `Demo Client E2E` green, all four post-merge runs passed
- [x] **Cookie-IT deliverability-blindness defect RETIRED** — closed *and* demonstrated by reversion
- [x] **`ApiSheriff-114` mismatch defect RETIRED** — corrected, with `ApiSheriff-124` added beside it
- [x] Four defects opened (below)
- [x] **PLAN-17, PLAN-18 and PLAN-19 all unblocked** — each hard-depended on this landing

## Follow-Ups

1. ⚠ **The codec default (4096) sits above the browser-safe *value* budget (4019).** Documented, not
   hidden. Needs `gateway.schema.json`, which was out of scope here.
2. ⛔ **`session.cookie_name` is entirely unvalidated** (finding `45e0ba`) — **dropping the `__Host-`
   prefix silently loses the no-`Domain` guarantee.** That is a security property removable by a
   typo, with nothing to catch it. Also needs `gateway.schema.json`.
3. ⚠ **Cookie-mode refresh responses are not covered by the budget assertion** (CodeRabbit's sixth
   finding) — a genuine gap needing its own integration instance. ✅ **Replied on the thread rather
   than closed**, which is the right disposition for a real gap that is out of scope.
   ⛔ **This one belongs to PLAN-17**, whose entire subject is making cookie mode work *with* refresh.
4. ⚠ **`TlsEdgeProducerTest` flaked three times locally, never in CI** — lesson `2026-09-10-09-001`
   with the diagnosis and the fix (`listen(0, "127.0.0.1")`). ✅ **`LoopbackEphemeralBindArchTest`,
   which landed with #283, already enforces that rule for fixtures** — the production fixture
   predates it. Fifth sighting of this class, and the first with a guard already in place that simply
   does not reach the offending site.
