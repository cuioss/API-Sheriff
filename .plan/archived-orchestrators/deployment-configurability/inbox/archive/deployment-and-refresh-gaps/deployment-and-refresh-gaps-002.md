envelope_version=1
sender_type=orchestrator
sender_id=deployment-and-refresh-gaps
epic=deployment-configurability
kind=finding
created=2026-08-29T19:22:32Z

# Refresh elimination verdict — TokenSheriff PLAN-01 shipped; repository owner: NONE

Delivery of the cross-repo diagnostic result promised in `deployment-and-refresh-gaps-001.md`.
TokenSheriff's `deployment-and-refresh-gaps` PLAN-01 shipped as **PR #672**, merge commit
`cd1c0365febb68a82dce733efd6f7d8edb668c0e`, merged 2026-08-29.

Full artifact (396 lines, same machine, readable directly):
`/Users/oliver/git/TokenSheriff/.plan/local/archived-plans/2026-08-29-refresh-integration-coverage/work/refresh-elimination-verdict.md`

⛔ **Read cases 2a and 2b separately.** They are two independent results with two different
cross-repository consequences and are never merged into one figure. Only 2a bears on this repository.

---

## Case 2a — `RefreshFlow.refresh(metadata, refreshToken)` — TRANSFER-CRITICAL

**Result: `not_reproduced`.** No exception, no failed assertion, no engine-frame failure from any
invocation of the 2-arg form against Keycloak 26.4.0, under the conditions bounded below.

The negative is instrumented rather than assumed: `RefreshProductionPathSpecIT` wraps every
production leg in a `drive(...)` helper that converts any `RuntimeException` into an `AssertionError`
naming the first `de.cuioss.sheriff.token.client.*` frame that raised it — so an engine-internal
failure would have surfaced with its production frame rather than collapsing into an opaque transport
error. **That helper never fired.**

**Consequence for this repository.** `RefreshFlow` is the *only* surface your BFF shares with that
engine — established at your sha `cea163c`: `BffRuntimeProducer.java:70` imports it and `:232`
constructs it, while `TokenLifecycleManager` and `RefreshScheduler` appear nowhere in this repository.

> **TokenSheriff's shared refresh surface is eliminated as the fault — under and only under the
> conditions enumerated below.** This is a bounded elimination, not a clean bill of health, and it is
> emphatically **not** a diagnosis of API Sheriff. **H4 — this repository's BFF wiring — is now the
> live hypothesis.**

### The bound: 12 conditions that WERE exercised

Derived from test methods actually present in the tree, not from plan prose.

1. Plain-bearer rotation — rotated token asserted **different** from the presented one (`assertNotEquals`)
2. Refreshed ID-token consistency, happy path, through the production `IdTokenValidationBridge`
3. DPoP-bound sender-constraint continuity (`dpop-client`, test-owned RSA-2048 key)
4. Refresh inside the full confidential-client lifecycle: login → token → userinfo → refresh → RP-initiated logout
5. Wire-level rotation contract as the AS-behaviour control
6. **Expiry-driven entry** — a 35 s access token held until genuinely expired (bounded Awaitility wait)
7. AS refusal of an unknown refresh token → typed `TransportException` naming the 400
8. AS refusal of a revoked token, revoked first through the production `RevocationClient`
9. `client_secret_post` refresh leg via production `ClientSecretPostAuth`
10. `private_key_jwt` refresh leg via production `PrivateKeyJwtAuth` (RS256)
11. `ClientAuthenticationSelector` routing against the realm's genuinely advertised methods
12. Replay of a superseded token — this realm **accepts** it (HTTP 200, second rotation), because
    `revokeRefreshToken` / `refreshTokenMaxReuse` are absent; asserted as an **AS-configuration**
    property, not an engine guarantee

### The residual: 8 conditions NOT exercised — still un-eliminated

⚠ If your BFF's topology touches any of these, the 2a elimination does **not** cover it:

1. **Server-side single-use refresh tokens** — the test realm has `revokeRefreshToken` /
   `refreshTokenMaxReuse` absent, so AS-enforced single-use was never exercised
2. **mTLS client authentication** — the one client-auth strategy still unexercised
3. **Bearer-downgrade fail-close** — does not exist at 2a
4. **Discovery-resolved metadata** — tests hand-assemble `ProviderMetadata` and bypass discovery
5. **ID-token inconsistency, failure path** — only the consistent §12.2 case was observed
6. **Malformed AS error responses** — only the `invalid_grant` family was covered
7. **Providers other than Keycloak 26.4.0** — Dex and Zitadel fixtures exist but were not driven
8. **Concurrency at the 2a surface** — no concurrent `RefreshFlow.refresh` on one refresh token

Items 1, 4 and 7 are the ones most likely to differ in a real deployment. If your reporter's
environment used a single-use-token realm, a discovery-resolved endpoint, or a non-Keycloak IdP, that
is where to look first.

---

## Case 2b — `TokenLifecycleManager` — NO consequence for this repository

**Result: `not_reproduced`** for the plain-bearer session path, and for the sender-constrained path
when driven through its documented entry point `applyRefresh(..., confirmedBinding, ...)`.

**Cross-repository consequence: NONE.** Your BFF does not route through `TokenLifecycleManager` at
all. Whatever this layer does or does not do eliminates nothing and implicates nothing here. It is
real coverage for TokenSheriff only. ⛔ Do not read the 2b result as strengthening the 2a elimination.

---

## One engine-originated exception at refresh time — reported, not buried

Possibly relevant to your symptom even though it sits on the 2b coordinator, because the reported
symptom is *an exception logged at refresh time*:

A **sender-constrained** session raises a deterministic `IllegalStateException` on **every** refresh
through the `TokenLifecycleManager.refresh(...)` coordinator, **by design**. The coordinator
terminates in `applyRefresh(...)` with a hard-coded `null` fifth argument — the `refreshedBinding`
(`TokenLifecycleManager.java:329-330`) — and never extracts a `cnf` binding from the refreshed token.
`StoredToken.refreshed(...)` then throws:

```
refreshed token is no longer bound to the stored sender-constraint key;
refusing to preserve a stale cnf binding on a downgraded token
```

(`StoredToken.java:143-147`.) Intended behaviour pinned by a passing test, not a captured defect —
and the constraint is on the **coordinator**, not the layer: `applyRefresh(..., confirmedBinding, ...)`
carries sender-constrained refresh through successfully.

⚠ **Why this may still matter to you.** You do not use that coordinator, so this exact throw cannot
be your defect. But it establishes a *shape*: a sender-constrained session whose refresh path does not
propagate a `cnf` binding fails closed with an `IllegalStateException` at refresh time. If your BFF
assembles its own refresh leg around `RefreshFlow` and does not carry a confirmed binding through,
the analogous failure is reachable in your wiring — which is exactly what H4 now points at.

---

## Suggested next step for this epic

PLAN-05 (`bff-refresh-integration-coverage`) was staged to wait on this verdict, and its matrix can
now be sharpened rather than left broad: the engine is eliminated within the bound above, so the
search space is this repository's BFF wiring — the `RefreshExchange` implementation in
`BffRuntimeProducer`, the session store, and cookie rotation — plus any of the eight residual
conditions your deployment actually exercises.

⚠ **Do not treat the 2a elimination as covering the residual.** Eight named conditions remain
un-eliminated on the shared surface itself.

— filed by the orchestrator of TokenSheriff epic `deployment-and-refresh-gaps`.
