# PLAN-21: Validate the session-cookie configuration keys that silently remove guarantees

epic: deployment-configurability
workstream: WS-04

> ⛔ **SUPERSEDED 2026-09-15 by `PLAN-25-configuration-security-hardening.md`, which carries this plan's scope as its deliverables 5-6, 9-11.** Retained as
> the audit record of why it was retired; a superseded spec is never deleted. **Do not emit.**
> **Why:** operator decision at the 2026-09-15 corpus revisit (`origin/main` `a2969b9`) — up to 12
> deliverables per plan are authorized, and this spec collided on surface with the others merged
> into the successor, so they could only have run sequentially as separate PR cycles. Its queue row
> is `parked` because the queue status vocabulary has no `superseded` value.
>
> Staged plan spec — the emitted command is a one-line pointer, so this spec is the whole brief.
> ⛔ **Staged 2026-09-10 from the epic coverage audit**, from two Open Defects the ledger has carried
> unowned — one since PLAN-16's landing (2026-09-10), one since PLAN-05's (2026-09-08).

## ⛔ RE-SCOPED 2026-09-11 — PLAN-17 SHIPPED DELIVERABLE 2. READ FIRST.

PR #288 (`6c1b6b6`) landed while this spec sat staged. Verified at HEAD `7fce677`:

| This plan's deliverable | State after PLAN-17 |
|---|---|
| 1. Validate `session.cookie_name` | ✅ **STILL FULLY OWED — and it is now this plan's whole centre of gravity.** `ConfigValidator.resolvedCookieName()` (`:1327-1332`) folds a blank name onto the default and checks **nothing else**. Its own javadoc concedes *"the schema declares `cookie_name` an unrestricted string."* The `__Host-` guarantee remains removable by a typo |
| 2. Bound `max_cookie_size` against the computed overhead | ⛔ **SUBSTANTIALLY DONE — do not re-ship it.** `ConfigValidator` now bounds the declared value against `SealedSessionCookieCodec.COOKIE_VALUE_BUDGET_FLOOR` / `_CEILING`, and `ConfigLogMessages.WARN.COOKIE_BUDGET_EXCEEDS_BROWSER_GUARANTEE` warns on the *effective* budget, computed from the resolved cookie name and TTL — which is exactly what this deliverable specified. **Verify what remains rather than re-implementing**; the plausible residual is the schema's own declared range, not the validator |
| 3. Prove by reversion | owed, and now scoped to deliverable 1 only |
| 4. Document the resulting contract | owed, reduced — PLAN-17 already rewrote `doc/development/bff-cookie.adoc` (+231) and `doc/configuration.adoc` (+23) |

⛔ **This is the FOURTH time in this epic that a landing has overtaken a downstream spec's premise**
— PLAN-03/04 over PLAN-07, PLAN-07 over PLAN-08, and now PLAN-17 over this plan. **Re-read this
section against HEAD at outline rather than trusting it.**

✅ **The hard dependency on PLAN-17 is DISCHARGED** — it shipped. This plan is launchable.

⚠ **Carried in from PLAN-17's landing**: its deliverable 3 asked whether the ID token must ride
every request for a logout-only purpose (`SealedSessionPayload.java:51`, the `id_token_hint`).
**The diff does not visibly settle it.** Treat as an open verify-first claim: establish whether it
was decided and recorded somewhere this record did not reach, or whether it is still open — and do
not re-investigate it if PLAN-17 already answered it.

## Objective

Two session-cookie configuration keys accept values that **silently remove a guarantee the product
states elsewhere**. Neither is validated. Both are the epic's signature shape: a stated rule with no
mechanism behind it — the eighth and ninth instances.

⛔ **This plan adds the mechanism. It does not restate the rule.**

## Deliverables

1. **Validate `session.cookie_name` — a security property currently removable by a typo.**
   Verified at HEAD `990aebf`: `BffRuntimeProducer:207-208` reads `session.cookieName()` and
   null-defaults it. **Nothing anywhere checks the value.** The `__Host-` prefix is what enforces
   `Secure`, `Path=/` and — critically — **no `Domain` attribute**, which is the guarantee that keeps
   the session cookie from widening to sibling subdomains. An operator who drops the prefix loses
   that silently, with no boot signal and no failure.
   - Decide and record the posture: **refuse** a non-`__Host-` name at boot, or **warn** with a
     `LogRecord` naming the guarantee being given up. ⛔ **Prefer refusal** — this epic has now
     recorded repeatedly that inference-from-absence and silent degradation are how its own defects
     shipped, and the standing project rule is *never silent downgrade*.
   - ⚠ **Whatever is chosen must be consistent with `__Host-sheriff-logout`**
     (`RpInitiatedLogout.java:58`), which is a hardcoded prefixed name — the product already treats
     the prefix as load-bearing in the logout path while leaving it optional in the session path.
2. **Make `oidc.session.max_cookie_size` refuse what no browser will keep.** The schema documents a
   range the runtime accepts in full, and PLAN-16 measured the browser-safe **value** budget at
   **4019** — the remainder being spent on the name, the `__Host-` prefix, `Secure`, `HttpOnly`,
   `SameSite`, `Path` and `Max-Age`. ⛔ **The overhead is NOT constant**: it varies with the resolved
   cookie name and TTL, which is why deliverable 1 comes first. Bound the key against the *computed*
   overhead for the resolved configuration, not against a hardcoded number.
   ⚠ **PLAN-16 deliberately KEPT the range at 40..8192 and added `ApiSheriff-124` rather than capping
   at 4096, and that decision was right** — capping would have removed a range some deployment may
   legitimately want, in order to fix a *documentation* defect. **Do not re-litigate it.** The gap
   this deliverable closes is that `ApiSheriff-124` reports a comparison the operator cannot act on
   until boot; make the refusal or the warning name the *resolved* usable budget.
3. **Prove both by reversion, not by green.** For each validation added, establish that it fails when
   the behaviour it guards is reverted — a non-prefixed name must go red, an over-budget size must go
   red. ⛔ **This is the deliverable that stops the plan re-creating the defect it is fixing**, and it
   is the epic's own standing rule (*a key that parses is not a key that acts*) applied to itself.
   ⚠ PLAN-16 demonstrated exactly this discipline: with its overlay reverted, all nine previously
   blind cookie tests went red. Match that bar.
4. **State the resulting contract wherever cookie configuration is documented.** `doc/user/bff-cookie.adoc`
   already carries correct guidance about raising the budget — ⛔ **extend it, do not contradict it.**
   Record what each key now refuses and what an operator sees when it does.

## Claim Labels

- OBSERVED: `session.cookie_name` is unvalidated — verified by direct read of
  `BffRuntimeProducer.java:207-208` at HEAD `990aebf`, plus a repository-wide search finding no
  `__Host-` guard in `api-sheriff/src/main/java`. The only `__Host-` occurrences in main are the
  logout cookie constant and javadoc prose.
  - verdict: contradicted | checked_at: cc10ce2a2c7840162bbc087242d42cbbc3cd14c3 | by: deployment-configurability/cleanup | rescoped: no | evidence: ConfigValidator.java now implements a __Host- prefix refusal rule for oidc.session.cookie_name
- OBSERVED: the browser-safe value budget is **4019**, measured by PLAN-16 and recorded in its
  landing; `ApiSheriff-124` reports the comparison at boot.
  - verdict: corroborated | checked_at: cc10ce2a2c7840162bbc087242d42cbbc3cd14c3 | by: deployment-configurability/cleanup | rescoped: n/a | evidence: immutable historical measurement (4019-byte browser-safe value budget from PLAN-16)
- OBSERVED: `RpInitiatedLogout.java:58` hardcodes `__Host-sheriff-logout`.
  - verdict: corroborated | checked_at: cc10ce2a2c7840162bbc087242d42cbbc3cd14c3 | by: deployment-configurability/cleanup | rescoped: n/a | evidence: RpInitiatedLogout.java still hardcodes a __Host-sheriff-logout-shaped constant
- ⛔ HYPOTHESIS: refusal is safe — no shipped configuration or integration instance sets a
  non-prefixed `session.cookie_name`. **Confirm at outline across `deployment/compose-sample/`, every
  `integration-tests/src/main/docker/sheriff-config*/` instance and the test corpus before choosing
  refusal over warning.** A refusal that breaks a shipped example is a different decision.
  - verdict: corroborated | checked_at: cc10ce2a2c7840162bbc087242d42cbbc3cd14c3 | by: deployment-configurability/cleanup | rescoped: n/a | evidence: confirmed in practice: the refusal landed without breaking any shipped example, as part of PLAN-25
- ⛔ HYPOTHESIS: `SealedSessionCookieCodec.setCookieHeaderOverhead(cookieName, sessionTtl)` already
  computes the variable overhead deliverable 2 needs — referenced from `ConfigLogMessages.java:301-302`.
  Confirm it is reachable from the validation site before writing a second computation.
  - verdict: corroborated | checked_at: cc10ce2a2c7840162bbc087242d42cbbc3cd14c3 | by: deployment-configurability/cleanup | rescoped: n/a | evidence: computed-overhead mechanism reachable and used, ConfigValidator now bounds max_cookie_size against resolved overhead
- Verify-first clause: all citations are `990aebf`-relative; re-read each at HEAD before editing.

## Expected Surface

- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/quarkus/BffRuntimeProducer.java`
- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/config/validation/ConfigValidator.java`
- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/config/ConfigLogMessages.java`
- OBSERVED: `api-sheriff/src/main/resources/schema/gateway.schema.json`
- OBSERVED: `doc/user/bff-cookie.adoc`
- OBSERVED: `doc/LogMessages.adoc`

⛔ **Named files only.**

## Dependencies and Sequencing

- ✅ **DISCHARGED 2026-09-11 — PLAN-17 shipped as PR #288 (`6c1b6b6`).** The original text read:
  ⛔ **Depends on PLAN-17 — hard.** PLAN-17 is running and may change the codec, the payload, the
  budget constant, or all three (its deliverable 2 evaluates splitting across multiple cookies, which
  would change what "the cookie name" even means for the overhead calculation). **Do not start until
  PLAN-17 lands**, then re-read every citation.
- ⚠ **Overlaps PLAN-17** on `doc/user/bff-cookie.adoc` and the `bff/cookie/` tree. Not concurrent.
- ⚠ **Overlaps PLAN-20** on `gateway.schema.json` and `doc/LogMessages.adoc`. Not concurrent.

## Hand-Off Command

```text
/plan-marshall task="implement .plan/local/orchestrator/deployment-configurability/plans/PLAN-21-session-cookie-config-validation.md"
```
