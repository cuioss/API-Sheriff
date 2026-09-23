# PLAN-17: Make cookie mode viable WITH refresh — enlarge, split, or reduce the sealed session

epic: deployment-configurability
workstream: WS-04

> Staged plan spec — the emitted command is a one-line pointer, so this spec is the whole brief.
> ⛔ **Staged 2026-09-09 at operator direction, and it exists because PLAN-16 does NOT solve this.**

## Objective

PLAN-16 deliverable 2 returns `main` to green by setting `refresh.enabled: false` on the cookie
overlay. ⛔ **That is a deliberate retreat, not a fix.** After PLAN-16 lands, the honest statement of
this gateway's capability is: **cookie mode and refresh do not work together.** Access + id + refresh
seal to **5123 bytes** against a browser guarantee of ~4096, and PLAN-16 deliverable 4 will make the
gateway *refuse* that seal rather than emit it — turning a silent browser-side drop into a loud
fail-closed, which is better and still not working.

This plan makes them work together. **The operator's preference ordering is binding**, and the four
routes are evaluated in it:

| # | Route | Standing |
|---|---|---|
| 1 | **Enlarge** the usable cookie budget | *highly preferred* — and the most likely to be refused, since the limit is the browser's |
| 2 | **Split** across multiple cookies | operator-raised as potentially *"the most elegant way"* — it **circumvents** the limit rather than fighting it. A recorded rejected alternative, deliberately reopened |
| 3 | **Reduce** — fewer claims, Keycloak-side | *preferred reduction*: the only route that shrinks the payload without adding a mechanism here |
| 4 | **Package** — compress the content | the fallback, reached for last |

⚠ **They are not mutually exclusive.** Splitting plus a smaller payload is a better outcome than
either alone, and a payload that fits in one cookie after reduction makes splitting unnecessary.
Report the combination, not just the first route that works.

⚠ **Scope boundary.** PLAN-16 owns *restoring green*, *gating deliverability*, and *closing the
ceiling*. This plan owns *making the capability real*. It must not re-litigate PLAN-16's decisions,
and it depends on PLAN-16's deliverability assertion existing — that assertion is how this plan
proves any solution actually works.

## Deliverables

1. **Establish whether the cookie budget can be reliably enlarged — the highly preferred outcome, and
   the one most likely to be refused.** Settle it with evidence, not with a recollection of RFC 6265:
   - What each target browser actually enforces, and **whether the limit is per-cookie value or the
     whole `Set-Cookie` line including name and attributes** — the `__Host-` prefix, `Secure`,
     `HttpOnly`, `SameSite=Lax`, `Path=/` and `Max-Age` all consume budget this gateway already
     spends. If the 4096 is a *line* limit, the usable value budget is materially under 4096 and the
     current default is itself optimistic.
   - The per-domain **aggregate** cookie budget and cookie-count limit, since the session is not the
     only cookie a deployment sets.
   - **Output**: a written verdict — enlargeable or not — with the measurement behind it. A negative
     verdict here is a real result and licenses the deliverables below.
2. **Evaluate SPLITTING the sealed value across multiple cookies — operator-raised 2026-09-09 as
   potentially "the most elegant way", and it circumvents the limit rather than fighting it.**
   ⛔ **This is a RECORDED REJECTED ALTERNATIVE and must be engaged with, not rediscovered.**
   `SealedSessionCookieCodec.java:70-72` states it: *"Cookie splitting across multiple `Set-Cookie`
   headers is a deliberate non-goal; an operator whose token set does not fit is expected to reduce it
   or run server mode."*

   ✅ **Read that non-goal precisely before weighing it: it is a SIMPLICITY argument, not a SAFETY
   one.** It says the operator is *expected* to reduce or switch modes — a product-philosophy choice
   about where complexity belongs. Nothing in it claims splitting is unsafe. So reopening it is a
   product decision, and the bar is "is the complexity worth it", not "is it sound".

   ✅ **And one argument the original decision does not appear to have weighed: the seal makes
   splitting fail-closed BY CONSTRUCTION.** The value is sealed — encrypted and authenticated over the
   whole plaintext — so a missing, reordered or stale chunk cannot yield a partially-valid session:
   reassembly produces a failed authentication tag and a clean rejection, not a half-session. That
   removes the usual objection to split cookies. **Verify this against the actual seal construction
   before relying on it**; if the codec authenticates per-chunk rather than over the whole, the
   argument does not hold.

   **What the evaluation must settle:**
   - **Chunk naming and the `__Host-` prefix.** The prefix requires `Secure`, `Path=/` and no
     `Domain` — confirm those survive per-chunk, and that prefixed names may carry an index suffix.
   - **Per-domain limits.** Splitting trades one large cookie for several; measure against the
     per-domain **count** and **aggregate size** limits deliverable 1 establishes. A split that fits
     each chunk but breaches the aggregate has moved the failure, not removed it.
   - **Every request carries all chunks.** The `Cookie` header grows accordingly and hits the
     gateway's pre-route header-value cap — which `SealedSessionCookieCodec.java:74-79` records as the
     *same declared number* driving both ends, and which previously broke cookie mode when two
     constants disagreed. ⛔ **That interaction is the trap here**; settle it explicitly.
   - **Failure and eviction behaviour.** What happens when a browser evicts one chunk under pressure,
     and does the resulting rejection surface as a clean re-authentication rather than an error.
   - **An upper bound.** State how many chunks the design admits and what happens beyond it, so this
     does not become an unbounded budget that hides the next payload growth.
3. **If neither enlarging nor splitting: reduce the payload by carrying fewer claims — the preferred
   reduction.**
   ⚠ **Start from what the payload is actually made of**, read at `SealedSessionPayload.java:65-68`:
   `accessToken`, nullable `refreshToken`, `idToken`, plus fields, base64-encoded and sealed.
   - **Keycloak-side claim reduction** — trim the access and ID tokens at the source. This is the
     operator's preferred route and it is the only one that shrinks the payload without adding a
     mechanism to this gateway.
   - ⛔ **The finding that makes this more than a diet: the ID token is carried on EVERY request for a
     LOGOUT-ONLY purpose.** `SealedSessionPayload.java:51` states it — *"the raw ID token retained for
     the logout `id_token_hint`"*. Settle whether the whole ID token must ride the session, or whether
     `id_token_hint` can be satisfied by something smaller, or whether logout can be reworked so it is
     not needed at all. This may be a larger win than trimming claims, and it is orthogonal to
     Keycloak configuration.
   - ⚠ **Whatever is trimmed must not break token validation or the upstream bearer contract.** The
     access token is injected as the upstream bearer; claims an upstream authorizes on cannot be
     removed. Name what each removed claim was for.
4. **Design the packaging PIPELINE, verify its ORDER, and threat-model it — the fallback route, and
   the one whose order is easy to get wrong.** ⚠ **There is no compression stage today** — a search of
   `bff/cookie/` finds `Base64` and no `Deflater`, `GZIP` or equivalent. Three JWTs are base64url text
   with high redundancy, so compression is plausibly effective; that is why this route exists.

   ⛔ **The operator sketched `cookie(s) → serialize → base64 → compress → encrypt` and asked for the
   order to be verified. Two of those steps are misplaced, and the corrected order is:**

   | # | Stage | Why HERE and not elsewhere |
   |---|---|---|
   | 1 | **serialize** | the payload record → bytes |
   | 2 | **compress** | ⛔ **must precede encryption.** Ciphertext is high-entropy and does not compress — compressing after encrypting buys ~0% |
   | 3 | **encrypt (seal)** | AEAD over the compressed bytes; the tag covers everything before it |
   | 4 | **base64url encode** | ⛔ **must FOLLOW encryption, not precede it.** It is a *transport* encoding making binary cookie-safe. Base64 before compression inflates ~33% and then compresses that inflation back out — measurably worse than compressing raw bytes |
   | 5 | **split into cookies** | last, over the final encoded string, so chunking is pure byte-slicing and reassembly is concatenation |

   ⚠ **Verify this against the current implementation before changing it** — the codec already seals
   and base64s, so establish what order it uses today and whether stage 2 can simply be inserted.

   ⛔ **Threat-model stage 2, because compress-then-encrypt is the one stage with a named attack
   class.** CRIME and BREACH exploit exactly this shape: when attacker-influenced content is
   compressed in the same context as a secret, the compressed LENGTH becomes an oracle for the
   secret. The model must answer, with reasoning rather than assertion:
   - **Can an attacker influence any part of the plaintext?** The payload is access + id + refresh
     tokens; claims may carry user-derived values. Name which, if any, an attacker can steer.
   - **Can an attacker observe the length?** The sealed cookie is visible to the browser and to
     anyone who can read the `Set-Cookie` or `Cookie` header.
   - ⛔ **Can an attacker obtain MANY samples with varied input?** This is the discriminator. CRIME
     and BREACH need repeated measurement across attacker-varied plaintexts. A session cookie is
     sealed **once at login** (and again on refresh), not per request — so the oracle is far weaker
     than the per-response case those attacks target. **State whether that reduces the risk to
     acceptable or merely to unlikely**, and say which.
   - **What the mitigations would be if it does apply** — padding to a bucket size, compressing only
     the fields an attacker cannot influence, or declining compression entirely.
   ⚠ **A conclusion of "does not apply here" is a legitimate output** — but it must be reached, not
   assumed, and recorded as an ADR alongside whatever this plan settles.

   - Measure the actual ratio on a real sealed payload rather than assuming one. ⛔ **A stage that
     buys 20% does not make 5123 fit 4096 with headroom for the next claim-set growth** — state the
     margin the design leaves, not just whether it fits today.

5. **State the resulting capability plainly, wherever cookie mode is documented.** Whatever this plan
   settles, the outcome is a capability statement an operator can act on: cookie mode supports refresh
   under *these* conditions, or it does not and server mode is the answer. ⛔ **`doc/user/bff-cookie.adoc`
   already carries correct guidance about raising the budget — extend it; do not contradict it.**

## Claim Labels

- OBSERVED: the sealed payload is three JWTs plus fields — read at `SealedSessionPayload.java:65-68`
  (`accessToken`, `@Nullable refreshToken`, `idToken`), encoded through `appendField` at `:109`.
  - verdict: corroborated | checked_at: cc10ce2a2c7840162bbc087242d42cbbc3cd14c3 | by: deployment-configurability/cleanup | rescoped: n/a | evidence: SealedSessionPayload.java still declares accessToken/refreshToken/idToken as the payload composition
- OBSERVED: the ID token is retained for one purpose — read at `SealedSessionPayload.java:51`, *"the
  raw ID token retained for the logout `id_token_hint`"*.
  - verdict: corroborated | checked_at: cc10ce2a2c7840162bbc087242d42cbbc3cd14c3 | by: deployment-configurability/cleanup | rescoped: n/a | evidence: SealedSessionPayload.java:59 still documents idToken as retained for the logout id_token_hint
- OBSERVED: **no compression stage exists** — a search of
  `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/bff/cookie/` for `Deflater` / `GZIP` /
  `compress` returns nothing; only `Base64` appears. Asserted **absence**, verified as a presence
  would be.
  - verdict: contradicted | checked_at: cc10ce2a2c7840162bbc087242d42cbbc3cd14c3 | by: deployment-configurability/cleanup | rescoped: no | evidence: SealedSessionCookieCodec.java now imports Deflater and compresses BEST_COMPRESSION before sealing, per ADR-0043
- OBSERVED: cookie splitting is a recorded rejected alternative — read at
  `SealedSessionCookieCodec.java:70-72`, which names it *"a deliberate non-goal"*.
  - verdict: corroborated | checked_at: cc10ce2a2c7840162bbc087242d42cbbc3cd14c3 | by: deployment-configurability/cleanup | rescoped: n/a | evidence: SealedSessionCookieCodec.java:90 still states splitting remains a deliberate non-goal
- OBSERVED: the observed overflow is **5123 bytes** against a ~4096 guarantee, measured by the
  `Demo Client E2E` failure PLAN-05's message `…-007.md` reports.
  - verdict: corroborated | checked_at: cc10ce2a2c7840162bbc087242d42cbbc3cd14c3 | by: deployment-configurability/cleanup | rescoped: n/a | evidence: immutable historical measurement (5123 bytes overflow)
- HYPOTHESIS: the browser limit applies to the whole `Set-Cookie` line rather than the value alone —
  confirm/refute against each target browser's documented behaviour (verify-at-outline). ⛔ If true,
  the usable budget is below 4096 and `DEFAULT_COOKIE_VALUE_BUDGET = 4096` is itself optimistic,
  which changes deliverable 1's verdict and PLAN-16 deliverable 4's cap.
  - verdict: corroborated | checked_at: cc10ce2a2c7840162bbc087242d42cbbc3cd14c3 | by: deployment-configurability/cleanup | rescoped: n/a | evidence: confirmed by PLAN-16's landing: the browser-safe value budget is 4019, not the round 4096
- OBSERVED: today's pipeline is **serialize → seal → (encode)**, with no compression stage — read at
  `SealedSessionPayload.java:103-105` (*"Serializes this payload into the compact wire form the codec
  seals"*, returning UTF-8 bytes) and `:40` (`encode()` produces *"a compact, explicit,
  dependency-free"* wire form). ⚠ So the insertion point for a compression stage is **between
  `encode()` and the seal** — which is exactly stage 2 of the corrected order, and means the change
  is an insertion rather than a re-ordering. Confirm at outline.
  - verdict: contradicted | checked_at: cc10ce2a2c7840162bbc087242d42cbbc3cd14c3 | by: deployment-configurability/cleanup | rescoped: no | evidence: the pipeline now includes a compress stage per claim 2, so no-compression-stage no longer describes the shipped pipeline
- HYPOTHESIS: the base64 in `SealedSessionPayload` is *inside* the sealed plaintext (field encoding)
  rather than the outer transport encoding — confirm/refute at `:109` `appendField` and at the codec's
  own encode path (verify-at-outline). ⛔ If a field-level base64 sits inside the plaintext, it
  inflates what stage 2 then has to compress, and the pipeline design must account for it rather than
  treating base64 as a single outer stage.
  - verdict: contradicted | checked_at: cc10ce2a2c7840162bbc087242d42cbbc3cd14c3 | by: deployment-configurability/cleanup | rescoped: no | evidence: SealedSessionPayload.java:45 now states no per-field base64 armouring is needed - removed as part of the reduction work
- Verify-first clause: re-measure the sealed size at HEAD before scoping. PLAN-16 changes the overlay
  and may change the validator's cap; both move the ground this plan stands on.

## Expected Surface

- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/bff/cookie/SealedSessionPayload.java` — the payload composition
- HYPOTHESIS: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/bff/cookie/SealedSessionCookieCodec.java` — only if deliverable 3 lands a packaging stage (verify-at-outline)
- HYPOTHESIS: `integration-tests/src/main/docker/keycloak/integration-realm.json` — claim-scope reduction for deliverable 2 (verify-at-outline)
- HYPOTHESIS: `doc/user/bff-cookie.adoc` — the capability statement (verify-at-outline)

⚠ **`doc/user/bff-cookie.adoc` is also declared by PLAN-16**, which is why this plan sequences behind
it rather than running alongside. Re-check at emit.

## ⛔ Carried in from PLAN-16's landing (2026-09-10)

- ⛔ **The budget assertion PLAN-16 added does NOT cover cookie-mode REFRESH responses** — CodeRabbit's
  sixth finding on PR #284, correctly replied-to rather than closed. ⚠ **That is exactly the case this
  plan must prove.** Whatever route this plan takes, the refresh leg needs its own integration
  instance and its own assertion; inheriting `assertCookiesFitBrowserBudget` from the login path
  proves the login cookie fits and says nothing about the re-sealed one. ⛔ **The re-sealed cookie is
  the LARGER of the two** — it is the one carrying a rotated refresh token.
- ✅ **Deliverable 1's hypothesis is CONFIRMED, and it moves the target.** This spec asked whether the
  browser limit applies to the whole `Set-Cookie` line rather than the value alone, warning that if
  so *"the usable budget is below 4096 and `DEFAULT_COOKIE_VALUE_BUDGET = 4096` is itself
  optimistic."* PLAN-16 measured it: **the browser-safe VALUE budget is 4019**, with the remainder
  spent on the name, the `__Host-` prefix, `Secure`, `HttpOnly`, `SameSite`, `Path` and `Max-Age`.
  ⛔ **So the real target is 4019, not 4096** — and any reduction or split must clear that, not the
  round number.
- ⚠ **`session.cookie_name` is unvalidated**, so the header overhead is not even fixed: a longer name
  shrinks the usable value budget further. Deliverable 2's split arithmetic and deliverable 3's
  reduction target must both treat the overhead as **variable**, not constant.

## Dependencies and Sequencing

- ⛔ **Depends on PLAN-16 — hard, on its deliverable 3.** That deliverable adds the assertion that the
  emitted `Set-Cookie` fits the browser budget. **This plan cannot prove any solution works without
  it**: without that assertion, a "fix" that still overflows looks identical to one that does not,
  which is precisely the blindness that produced this situation.
- ⚠ Also overlaps PLAN-16 on `doc/user/bff-cookie.adoc`; sequencing behind it resolves both.
- ✅ **Not urgent, and must not be treated as urgent.** `main` goes green via PLAN-16 deliverable 2
  regardless. This plan restores a *capability*; it does not fix a breakage.
- ⚠ **A negative verdict on deliverable 1 is a legitimate outcome and is not a failure of this plan.**
  If the budget genuinely cannot be enlarged and the payload genuinely cannot be reduced enough, the
  honest deliverable is a documented statement that cookie mode does not support refresh and server
  mode is the supported path for deployments that need it.

## Hand-Off Command

```text
/plan-marshall task="implement .plan/local/orchestrator/deployment-configurability/plans/PLAN-17-cookie-mode-refresh-viability.md"
```
