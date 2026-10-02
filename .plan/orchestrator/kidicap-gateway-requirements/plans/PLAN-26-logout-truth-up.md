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
   ⚠ Ordinals 0053, 0056 and 0057 are taken; confirm the next free one by listing `doc/adr/` rather than
   trusting `manage-adr scan`, whose success payload omits the duplicate population entirely (plan-marshall
   lesson `2026-10-02-13-00x`; this is how `main` carried two ADR-0053 files for a week).

Split guard: 5 deliverables — well within the operator-authorized 12.

## Claim Labels

- HYPOTHESIS: BFF-09 is still labelled COVERED at HEAD, and the revocation hook is still bound to a no-op.
  Both are from PLAN-23's own report, written at its merge commit e445e299 — the orchestrator did NOT
  re-read either at HEAD before staging. Confirm/refute at `doc/security-threat-model.adoc` § BFF-09 and
  at the logout path's revocation seam in
  `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/bff/` § the logout handler's revocation call
  (verify-at-outline). ⛔ If BFF-09 is already corrected, close deliverable 1 and say so — do not rewrite
  a row that someone has since fixed.
- HYPOTHESIS: the cookie-mode residual is documented in the user docs but carries no disposition of
  CodeRabbit's Medium rating — confirm/refute at `doc/user/bff-cookie.adoc` and
  `doc/security-threat-model.adoc` § the cookie-mode rows (verify-at-outline).
- HYPOTHESIS: neither the problem+json extension-member rule nor the reserved-path boot rule has an ADR at
  HEAD — confirm/refute by searching `doc/adr/` for both subjects (verify-at-outline).
- OBSERVED: ADR ordinals 0053, 0056 and 0057 are taken at HEAD (0053 once, after PR #367 resolved the
  collision; 0056 the header-matcher ADR; 0057 PLAN-23's session-scope ADR) — orchestrator-verified
  2026-10-02 by listing `doc/adr/`.

## Expected Surface

- OBSERVED: `doc/security-threat-model.adoc` — BFF-09, and the cookie-mode logout rows
- OBSERVED: `doc/adr/` — two new records, or one new record plus an ADR-0018 amendment
- OBSERVED: `doc/user/bff-session.adoc`, `doc/user/bff-cookie.adoc` — the logout facts the threat model
  now points at
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
