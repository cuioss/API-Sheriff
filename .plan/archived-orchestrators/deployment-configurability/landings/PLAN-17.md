# Landing Analysis: PLAN-17 — Make Cookie Mode Viable With Refresh

epic: deployment-configurability
workstream: WS-04
pr: [#288](https://github.com/cuioss/API-Sheriff/pull/288) — merged as `6c1b6b6`, 2026-09-10 20:38 UTC

> ⛔ **THIS LANDING WAS NOT REPORTED.** It reached the ledger only because `analyze` fetched `main`
> while corroborating PLAN-08 and found two commits the operator's paste did not mention. `emit-landing`
> ran and **skipped** here too (archived `logs/work.log`, `2026-09-10T21:09:57Z`), and no paste
> covered it. **Both channels were silent on a shipped plan for a full day.**

## Deliverable Fidelity vs Spec

The spec set four **routes** (enlarge / split / reduce / package) in the operator's binding
preference order and required the outcome be reported as a combination, not a single pick.

| Deliverable | Verdict | Evidence checked at `6c1b6b6` |
|---|---|---|
| 1. Establish whether the budget can be enlarged | shipped — **negative verdict, which the spec licensed** | The commit subject settles it: *"at 4019"*. `COOKIE_VALUE_BUDGET_FLOOR` / `_CEILING` / `DEFAULT_COOKIE_VALUE_BUDGET` now exist in `SealedSessionCookieCodec.java` |
| 2. Evaluate SPLITTING across multiple cookies | **evaluated and declined** — no chunking code shipped | No split implementation in `bff/cookie/`; the recorded non-goal stands |
| 3. Reduce the payload — fewer claims, and the logout-only ID token | **partially** — payload re-encoded (`utf8(...)` fields), but the ID token still rides the session | `SealedSessionPayload.java` +161; the `id_token_hint` question is not visibly settled in the diff |
| 4. Packaging pipeline, ORDER verified, threat-modelled | ✅ **shipped in full, and it is the centre of the landing** | `Deflater`/`Inflater` imported at `SealedSessionCookieCodec.java:31-32`; compression at `:515` with `Deflater.BEST_COMPRESSION`, rationale in javadoc at `:509` |
| 5. State the resulting capability plainly | shipped | `doc/development/bff-cookie.adoc` +231, `doc/configuration.adoc` +23, `doc/LogMessages.adoc` |

### ✅ The ADR the spec demanded was written, and it is not a gesture

`doc/adr/0043-Compress-then-encrypt_in_the_sealed_session_cookie_and_the_CRIME_BREACH_discriminator.adoc`
(260 lines). ⛔ **It answers the discriminator the spec named, in the shape the spec demanded** —
its two analysis halves are titled *"can an attacker force additional seals?"* and *"can an attacker
vary the plaintext next to the secret?"*, and it carries a section headed **"The claim is scoped,
deliberately"** plus `Alternatives Considered`. The spec warned that *"a conclusion of 'does not
apply here' is a legitimate output — but it must be reached, not assumed"*. It was reached.

### ✅ The compression stage went in at the corrected ORDER

The operator's sketch was `serialize → base64 → compress → encrypt`; the spec corrected it to
`serialize → COMPRESS → ENCRYPT → base64url → split` with a reason per stage. **The landed code
compresses before sealing**, which is the correction holding under implementation.

### ✅ A browser-level control shipped that no deliverable asked for

`demo-client/tests/05-cookie-size-budget.spec.js` (+231) — a Playwright test measuring the budget in
a **real browser**. Every prior control in this lane measured the emitted header server-side. This
is the first that measures what the spec's deliverable 1 was actually arguing about.

## Metrics and Anomalies

- Diff: 22+ files, `SealedSessionCookieCodec` +194, `SealedSessionPayload` +161, ADR +260
- ⚠ **No metrics recorded** — no `metrics.json` in the archived plan directory
- ⚠ **No review/CI detail available to this record.** Nothing was pasted and nothing was filed;
  everything above is read from the diff and the archived logs

## Routing and Merge Behavior

- Merged `2026-09-10 20:38 UTC`, **1h45m before PLAN-08** — so the two ran concurrently as paired
- ⛔ **`emit-landing` skipped (occurrence 5).** Combined with PLAN-08, the channel record is now
  **3 delivered / 5 skipped-at-runtime / 1 absent-from-manifest / 1 correct abstention**
- ⛔ **PLAN-17 and PLAN-08 were emitted as surface-disjoint and they were.** No collision observed

## ⛔ This landing moved three staged specs' premises — the FOURTH such overtake in this epic

- **PLAN-21 deliverable 2 is now substantially DONE.** `ConfigValidator` (+48) bounds
  `max_cookie_size` against `COOKIE_VALUE_BUDGET_FLOOR`/`_CEILING` and warns via
  `COOKIE_BUDGET_EXCEEDS_BROWSER_GUARANTEE`, accounting for the resolved name and TTL — which is
  what PLAN-21 D2 specified.
- ✅ **PLAN-21 deliverable 1 is NOT done and is now the whole plan.** Verified by direct read:
  `ConfigValidator.resolvedCookieName()` (`:1327-1332`) folds blank onto the default and **checks
  nothing else**; its own javadoc concedes *"the schema declares `cookie_name` an unrestricted
  string."* The `__Host-` guarantee is still removable by a typo.
- ⛔ **PLAN-22's declared ADR ordinal `0043` IS NOW TAKEN by this landing.** Next free is `0044`.

## Reconciliation Actions

- [x] row `status` → `shipped`
- [x] row `pr` → `288`
- [x] row `landing` → `landings/PLAN-17.md`
- [x] row `plan_marshall_plan_id` → `cookie-mode-refresh-viability`
- [x] Open Defect *"codec default 4096 sits above 4019"* — **retired**
- [x] Open Defect *"cookie-mode REFRESH not covered by the budget assertion"* — **retired**
- [x] `emit-landing` Open Defect — recurrence recorded (occurrence 5)
- [x] PLAN-21 re-scoped in place; PLAN-22 ADR ordinal corrected `0043` → `0044`

## Follow-Ups

- **The ID token still rides every request for a logout-only purpose** — deliverable 3's sharpest
  finding is not visibly settled by the diff. Folded into PLAN-21 as a verify-first claim rather
  than staged separately.
- **`demo-client/` is now a test surface for this lane** and is declared by no staged spec.
