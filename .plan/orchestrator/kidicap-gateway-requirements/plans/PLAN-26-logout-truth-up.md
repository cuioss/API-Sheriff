# PLAN-26: Make the Logout Record True — Correct BFF-09, Dispose of the Cookie-Mode Residual, Write the Two Missing ADRs

epic: kidicap-gateway-requirements
workstream: WS-04

> Staged plan spec — one shippable unit of work, ready for `/plan-marshall` hand-off.
> Lives at `plans/PLAN-26-logout-truth-up.md` and is queued in the epic `status.json`
> `plans[]` field. The orchestrator EMITS the command below; it never launches the plan inline.
> This spec is SELF-SUFFICIENT: the emitted command is a one-line pointer and carries no brief.
> Staged 2026-10-02 from PLAN-23's landing report (`landings/PLAN-23.md` § Follow-Ups), by operator
> decision.

## Objective

The threat model says a control is **COVERED** that the shipped runtime does not implement: BFF-09 claims
logout revokes tokens at the identity provider, while the revocation hook is bound to a **no-op**. PLAN-23
corrected the user documentation and left the threat model alone, so the repository now states both the
truth and the claim.

A security document that over-claims is worse than one with a gap: a gap invites a question, a false
COVERED closes it. This plan makes the record match the runtime — and, since the same landing left two
shipped decisions unrecorded and one Medium-rated residual undisposed, it closes those in the same pass.
It changes **no runtime behaviour**: everything here is record-keeping about behaviour that already
shipped.

## Source

`landings/PLAN-23.md` § Follow-Ups, items 1–3, from PLAN-23's finalize report (#369 / e445e299,
2026-10-02). The plan itself recorded the pattern as lesson `2026-10-02-12-007`
(`api-sheriff`, anti-pattern): *documentation asserted that logout revokes tokens at the identity provider
while production binds the revocation seam to a no-op*. That lesson stays in this repository's store
because the component is this project's own.

## Deliverables

> ⚠ **RE-GROUNDED 2026-10-05 at `35f2bb37`. This plan is SMALLER than filed — PLAN-23 itself did most of
> deliverables 1 and 3.** The filing rested on PLAN-23's report rather than a re-read of the threat model,
> and the re-read moves three rows:
>
> | # | State at HEAD | What remains |
> |---|---|---|
> | **1** | **mostly done** | `doc/security-threat-model.adoc` already carries a *"Not delivered: token revocation at logout"* paragraph (1904-1910), added by PLAN-23's own merge `e445e299`. The hook is still a no-op (`BffRuntimeProducer.buildLogoutEndpoint:1092-1096` passes `sessionRecord -> {}`). What still OVER-CLAIMS is narrower than filed: the **Control line (1871)** says the gateway revokes the refresh-token family, and the **summary row (2955)** reads `COVERED (server)` with no revocation caveat. Correct those two lines — do not rewrite the section |
> | **3** | ⛔ **already done** | The accepted cookie-mode residual is already documented with its bound and mitigations (1912-1928) and the summary row already reads `PARTIAL-accepted (cookie: a response in flight can outlast the logout)`. ADR-0018 and ADR-0057 record it too. CodeRabbit's Medium rating is therefore already disposed of in the record. **Drop this deliverable** unless the re-read finds the bound understated |
> | **5** | ordinal moved | the next free ADR ordinal is **0060** — 0053, 0056, 0057, **0058 (PAR/DPoP)** and **0059 (routing)** are taken. The filing's "do not take 0053" warning is stale |
> | 2, 4 | unchanged | the revocation-seam decision and the problem+json ADR still stand; no ADR states either rule at HEAD (ADR-0059 mentions an extension member only inside a rejected alias) |
>
> Net: **3 deliverables live** (1 narrowed to two lines, 2, 4, 5), one dropped (3). ⛔ The security point is
> undiminished: a Control line that claims refresh-token revocation the runtime does not perform is exactly
> the over-claim this plan exists to remove — it is now a two-line fix rather than a section rewrite.


1. **Correct BFF-09 in `doc/security-threat-model.adoc`.** State what the runtime does: the gateway ends
   its own session (and the IdP's, via the logout redirect / back-channel path where configured) and does
   **not** revoke the access or refresh token at the token endpoint. Re-label the entry honestly — the
   residual risk is a token that stays valid at the resource server until it expires — and say what an
   operator can do about it (short access-token lifetimes; IdP-side revocation on logout where the IdP
   supports it). ⛔ Do not relabel by deleting the row: the control's absence is the fact to record.
2. **Decide and record the revocation seam's fate.** It exists and is bound to a no-op. Either implement
   RFC 7009 revocation on logout, or state in code and docs that the seam is deliberately unbound and why.
   ⚠ **This is the one deliverable that may touch runtime code.** If the decision is to implement, STOP and
   say so — that is its own plan with its own tests, not a sub-step of a documentation pass. The default
   outcome here is the recorded decision, not the implementation.
3. **Dispose of the cookie-mode logout residual explicitly.** Cookie mode cannot observe a logout, so a
   late refresh or widening response can set a fresh cookie. PLAN-23 documented it as residual; CodeRabbit
   rates it **Medium** and nothing disposes of that rating. Record it as an accepted risk with its bound
   (what a fresh cookie can and cannot do after a logout, and for how long), or name the mechanism that
   would close it — and reference it from BFF-09's neighbourhood so the two logout facts sit together.
4. **ADR — the problem+json extension-member rule.** PLAN-23 shipped `403 problem+json` carrying
   `missing_scopes` and `step_up_url`; the rule governing extension members is undocumented. Write it.
5. **ADR — the reserved-path boot rule.** PLAN-23 shipped stricter boot validation on
   `oidc.step_up.path`, `oidc.login.path` and `oidc.user_info.path` (refusing query, fragment, backslash,
   control characters, whitespace). The ADR check suggests an **ADR-0018 amendment** rather than a new
   record; evaluate both and take the one that keeps the reserved-path model in one place.
   ⚠ The next free ordinal is **0060** (0053, 0056, 0057, 0058, 0059 taken — re-checked 2026-10-05); confirm by listing `doc/adr/` rather than
   trusting `manage-adr scan`, whose success payload omits the duplicate population entirely (plan-marshall
   lesson `2026-10-02-13-00x`; this is how `main` carried two ADR-0053 files for a week).

Split guard: 4 live deliverables (5 filed, deliverable 3 dropped as already done) — well within the operator-authorized 12.

## Claim Labels

- HYPOTHESIS: BFF-09 is still labelled COVERED at HEAD, and the revocation hook is still bound to a no-op.
  Both are from PLAN-23's own report, written at its merge commit e445e299 — the orchestrator did NOT
  re-read either at HEAD before staging. Confirm/refute at `doc/security-threat-model.adoc` § BFF-09 and
  at the logout path's revocation seam in
  `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/bff/` § the logout handler's revocation call
  (verify-at-outline). ⛔ If BFF-09 is already corrected, close deliverable 1 and say so — do not rewrite
  a row that someone has since fixed.
  - verdict: corroborated | checked_at: 35f2bb37 | by: kidicap-gateway-requirements/cleanup | rescoped: n/a | evidence: BFF-09 summary row (threat-model:2955) still reads COVERED (server) and the hook is still a no-op at BffRuntimeProducer:1092-1096
- HYPOTHESIS: the cookie-mode residual is documented in the user docs but carries no disposition of
  CodeRabbit's Medium rating — confirm/refute at `doc/user/bff-cookie.adoc` and
  `doc/security-threat-model.adoc` § the cookie-mode rows (verify-at-outline).
  - verdict: contradicted | checked_at: 35f2bb37 | by: kidicap-gateway-requirements/cleanup | rescoped: yes | evidence: the cookie-mode residual is ALREADY documented with its bound and mitigations (threat-model:1912-1928) and the summary row already reads PARTIAL-accepted - landed in e445e299
- HYPOTHESIS: neither the problem+json extension-member rule nor the reserved-path boot rule has an ADR at
  HEAD — confirm/refute by searching `doc/adr/` for both subjects (verify-at-outline).
  - verdict: corroborated | checked_at: 35f2bb37 | by: kidicap-gateway-requirements/cleanup | rescoped: n/a | evidence: no ADR states the problem+json extension-member rule or the reserved-path boot rule; ADR-0059 mentions an extension member only inside a rejected alias
- OBSERVED: ADR ordinals 0053, 0056 and 0057 are taken at HEAD (0053 once, after PR #367 resolved the
  collision; 0056 the header-matcher ADR; 0057 PLAN-23's session-scope ADR) — orchestrator-verified
  2026-10-02 by listing `doc/adr/`.
  - verdict: corroborated | checked_at: 35f2bb37 | by: kidicap-gateway-requirements/cleanup | rescoped: n/a | evidence: doc/adr carries one 0053 plus 0056/0057; ALSO 0058 (PAR/DPoP) and 0059 (routing) are now taken so the next free ordinal is 0060

## Expected Surface

- OBSERVED: `doc/security-threat-model.adoc` — BFF-09, and the cookie-mode logout rows
- OBSERVED: `doc/adr/` — two new records, or one new record plus an ADR-0018 amendment
- OBSERVED: `doc/user/bff-session.adoc`, `doc/user/bff-cookie.adoc` — the logout facts the threat model
  now points at
- OBSERVED (added 2026-10-05):
  `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/quarkus/BffRuntimeProducer.java` — the REAL home of
  the no-op revocation binding (`buildLogoutEndpoint:1077-1099`, with the explaining comment at 603-606).
  The declared surface named only `bff/`, where `RpInitiatedLogout` holds the seam's javadoc
- OBSERVED (added 2026-10-05): `doc/variants/03-bff-cookie.adoc` and `doc/development/bff-cookie.adoc` —
  the logout sections the threat model cites for the cookie-mode mitigations
- HYPOTHESIS: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/bff/` — ONLY if deliverable 2 resolves
  to "state it in code" (a comment or a named no-op binding). An implementation decision leaves this plan
  (verify-at-outline)
- HYPOTHESIS: `doc/configuration.adoc` — if the operator-facing mitigation of deliverable 1 names a
  configuration key (verify-at-outline)

## Dependencies and Sequencing

- Depends on: PLAN-23 — landed (#369).
- Overlaps with: PLAN-20 (`doc/adr/`, `doc/configuration.adoc`, `doc/security-threat-model.adoc`,
  `doc/user/bff-session.adoc`) and PLAN-21 (the same docs). Documentation-only overlap, but on the SAME
  threat-model and ADR files, so it is sequenced against both rather than paired.
- **Pairs cleanly with PLAN-24 or PLAN-25**, which touch neither the threat model nor `doc/adr/`.
- Small and mostly prose, so it is a good filler for a slot that would otherwise go unused — but it is
  **not** cosmetic: deliverable 1 removes a false security claim.

## Hand-Off Command

```text
/plan-marshall task="implement .plan/orchestrator/kidicap-gateway-requirements/plans/PLAN-26-logout-truth-up.md"
```

## Write-Boundary

The plan implementing this spec touches only its own repository source and tests. It creates
and edits NO file under `.plan/orchestrator/` other than its own
`inbox/{sender}-{seq}` message — the orchestrator owns every other ledger write — and reports
its outcome through its PR and its inbox message. The inbox exception's qualifiers and the
sole sanctioned write mechanism are stated in
`persona-plan-orchestrator/standards/orchestration-model.md` § Ledger Write-Boundary.
