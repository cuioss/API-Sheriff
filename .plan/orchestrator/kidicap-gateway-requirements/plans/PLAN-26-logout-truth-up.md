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
~~It changes no runtime behaviour: everything here is record-keeping about behaviour that already
shipped.~~ **Superseded 2026-10-26 (operator decision on deliverable 2):** this plan now also binds the
logout revocation seam, so it changes runtime behaviour. Deliverables 1, 4 and 5 remain record-keeping.

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
> | **5** | ordinal moved | the next free ADR ordinal is **0067** (re-checked 2026-10-13 at `48e8d5a6`; 0066 is #431's early-response ADR; 0062 is the ADR-0005 supersession, 0063 is PLAN-24's JWKS refresh ADR, 0064 and 0065 are PLAN-27's upstream-guard and test-port ADRs) — 0058 (PAR/DPoP), 0059 (routing), **0060 (dispatch framing)** and **0061 (offline config validation)** are taken. The filing's "do not take 0053" warning is stale, and so was this row's own 0060 |
> | 2, 4 | unchanged | the revocation-seam decision and the problem+json ADR still stand; no ADR states either rule at HEAD (ADR-0059 mentions an extension member only inside a rejected alias) |
>
> ⚠ **Line references re-measured 2026-10-06 at `6788bdae`** — #386 added ~59 lines above this section of the
> threat model, so the numbers in the table above have moved; the content has not. Current positions: the
> BFF-09 heading is at 1917, the over-claiming **Control line ("revoke the RT family") at 1930**, the
> *Not delivered* paragraph at 1963-1969, the accepted cookie-mode residual at 1971-1987, and the
> **summary row at 3014**. Locate each by its text, not by these numbers.
>
> Net: **3 deliverables live** (1 narrowed to two lines, 2, 4, 5), one dropped (3). ⛔ The security point is
> undiminished: a Control line that claims refresh-token revocation the runtime does not perform is exactly
> the over-claim this plan exists to remove — it is now a two-line fix rather than a section rewrite.
>
> ⚠ **RE-GROUNDED 2026-10-26 (cleanup at `54f82f03`) — still 3 live deliverables (1 narrowed, 2, 4, 5), 3 dropped.**
> Nothing in the sibling work (#476, #487, #492, #515, #521 and the validator extractions) closed any of
> them. The numbers above moved again (the threat model grew ~560 lines, so locate every item by its text):
> BFF-09 anchor :2473; Control line "revoke the RT family" :2487; Status :2537; *Not delivered* paragraph
> :2544–2550; accepted cookie-mode residual :2552–2568; summary row :3853. `buildLogoutEndpoint` is
> `BffRuntimeProducer` :1271 (no-op lambda :1284–1287; explaining comment :696–698). The reserved-path rule
> is now `config/validation/rule/ReservedPathRules.java` (`validateReservedPath` :340,
> `ABSOLUTE_GATEWAY_PATH_REQUIREMENT` :90, `canonicalReservedPathRefusal` :290); `ConfigValidator` holds only
> the ordered rule list (ADR-0071).
> - **ADR ordinals.** 0056 does NOT exist (merged into 0054 by the ADR corpus audit) and 0065 left the corpus.
>   The highest is 0071; the **next free ordinal is 0072** (gaps 0002, 0012, 0021, 0031, 0056, 0065 are retired:
>   do not reuse them). Running siblings may claim 0072 first: re-list `doc/adr/` at write time. New records
>   obey `doc/development/adr-writing.adoc` (500-line body ceiling in `AdrRecordLengthContractTest`, skeleton,
>   no PR numbers/SHAs/dates/lesson ids in the body). ADR-0018 has ~95 lines of room and already owns the
>   reserved-path model (:51–74), so the amendment option for deliverable 5 stays viable. The problem+json
>   members are named at ADR-0057 :134–139 (not :134–136); link ADR-0069 (revocation mapping) and ADR-0071.
> - **Deliverable 2's premise shifted.** The original blocker for RFC 7009 revocation on logout, the external
>   `revocation_endpoint` origin, is solved: `oidc.back_channel_origin` is wired through
>   `BffRuntimeProducer.backChannelConfiguration` (ADR-0069 names revocation in its mapped calls), token-sheriff
>   is 0.9.8, a `RevocationClient` on that configuration is built at :585, and a helper
>   `revokeRefreshToken(...)` (:1162–1170, DEBUG when no `revocation_endpoint`) already serves the refresh
>   coordinator. Binding the seam would be a one-line lambda. So "deliberately unbound" is now a choice AGAINST a
>   cheap implementation: the recorded reason must be something other than the endpoint-origin problem. Facts
>   the decision ADR must cover: the seam runs synchronously after `sessionBinding.destroy` and before the
>   end-session redirect (`LogoutEndpoint` :116–124; `RpInitiatedLogout` :150–157, broad catch to DEBUG, which is
>   also audit item SEC-7); the back-channel logout receiver never revokes; the existing helper revokes only the
>   refresh token; revoking the presented refresh token would NOT close the cookie-mode residual (a late refresh
>   whose rotation already ran), only shrink it. ⛔ The standing rule is unchanged: if the decision is to
>   implement, STOP and say so; that is its own plan. **This is an operator decision, not an outline detail.**
> - **Wider surface if implemented, or if the decision is stated in code:** `bff/logout/RpInitiatedLogout.java`
>   (+ its `package-info.java`), `bff/reserved/LogoutEndpoint.java`, `BffLogoutIT` (javadoc :31–38),
>   `doc/variants/02-bff-session.adoc`, `doc/user/tls-edge.adoc` :436–441 (states the no-op and says to revisit),
>   `doc/configuration.adoc`. Doc counterpart of deliverable 1: neither `doc/user/bff-session.adoc` § Logout
>   (:1097–1118) nor `doc/user/bff-cookie.adoc` :800–803 says that logout sends no revocation request.
>   `doc/quality-report/security-posture.adoc` (SEC-7 and a BFF-09 row) is a dated report: optional touch. No
>   test reads the threat model, so the doc edits carry no contract-test risk; a comment-only change to a
>   `.java` file still makes the commit subject to the full gate.


1. **Correct BFF-09 in `doc/security-threat-model.adoc`.** State what the runtime does: the gateway ends
   its own session (and the IdP's, via the logout redirect / back-channel path where configured) and does
   **not** revoke the access or refresh token at the token endpoint. Re-label the entry honestly — the
   residual risk is a token that stays valid at the resource server until it expires — and say what an
   operator can do about it (short access-token lifetimes; IdP-side revocation on logout where the IdP
   supports it). ⛔ Do not relabel by deleting the row: the control's absence is the fact to record.
2. **Implement RFC 7009 refresh-token revocation on logout — DECIDED by the operator 2026-10-26 (overriding
   this deliverable's former stop-and-split rule).** The seam exists and is bound to a no-op; this plan binds
   it. The operator chose to keep the implementation inside this plan rather than a follow-up, so **this plan
   is no longer documentation-only: it changes runtime code and the full Pre-Commit Process (quality gate and
   verify) and the native integration lane apply.** Scope for the outline:
   - Bind `buildLogoutEndpoint`'s revocation argument (`BffRuntimeProducer` :1284–1287) to the existing
     `revokeRefreshToken(revocationClient, metadata, refreshToken, clientAuthentication)` helper (:1162–1170),
     which already builds on the `oidc.back_channel_origin` configuration (ADR-0069) and handles a missing
     `revocation_endpoint` at DEBUG. Establish at outline whether the refresh token is available on every
     logout path in server and cookie mode (`SessionRecord` carries one) and say what happens when it is not.
   - Settle and record in the ADR: the call runs synchronously after `sessionBinding.destroy` and before the
     end-session redirect (`LogoutEndpoint` :116–124, `RpInitiatedLogout` :150–157); a failure is swallowed at
     DEBUG today (audit item SEC-7), so decide whether a failed revocation stays DEBUG, becomes a latched WARN
     (one per reason per runtime, as ApiSheriff-132 does) or is metered; the logout latency bound on a slow
     IdP; only the refresh token is revoked (the access token stays valid until it expires); the back-channel
     logout receiver, which the IdP initiated, does not revoke; and that revoking the presented refresh token
     SHRINKS the cookie-mode residual but does not close it (a late refresh whose rotation already ran).
   - Tests: `RpInitiatedLogoutTest`, `BffRuntimeProducerTest`, and `BffLogoutIT` driven so that the revocation
     request is observed at the IdP stub (the IT's Javadoc :31–38 currently says "bound as a best-effort
     no-op"). CLAUDE.md: if the binding were deleted, a test must go red.
   - Records that change with the code: the threat model BFF-09 Control line (:2487) becomes true for the
     refresh token, the *Not delivered* paragraph (:2544–2550) and its :2556–2557 sentence are rewritten, the
     summary row (:3853), `doc/variants/02-bff-session.adoc`, `doc/variants/03-bff-cookie.adoc`,
     `doc/user/tls-edge.adoc` :436–441, `doc/configuration.adoc`, the user docs' Logout sections, and
     `package-info.java` of `bff/logout`. Deliverable 1 is therefore written AFTER this deliverable's decision
     on failure semantics, and states exactly what is and is not revoked.
   - Coordination: `BffRuntimeProducer`, `BffLogMessages` and the next ADR ordinal are shared with the running
     sibling plans V02-06 and V02-20; derive the ordinal and any WARN id at write time. The deliverable count
     is unchanged (the split guard is not at issue); the footprint is now `*.java` plus docs.
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
   ⛔ **Not before `api-sheriff-0-2-0` `PLAN-V02-12`'s first PR has landed.** That plan is rewriting this
   same rule right now: one shape-plus-canonical-form check (`validateReservedPath`) for every reserved
   path, which moves to `/bff/*`. Write the ADR against that unified rule. This plan is the rule's only
   ADR owner; V02-12's held ADR (frontchannel/backchannel split) references it and does not restate it.
   ✅ **Gate met 2026-10-14:** V02-12's first PR landed as #450 (`eff0724c`). The rule to record is
   `validateReservedPath` (and `ABSOLUTE_GATEWAY_PATH_REQUIREMENT`) as #450 left it, now in
   `config/validation/rule/ReservedPathRules.java` (moved out of `ConfigValidator` by the ADR-0071 extraction): the
   reserved paths default under `/bff/*`, and the check also refuses dot segments, `//`, `;` and
   percent-encoding. Read it there; it is a reference, not a file this plan edits.
   ⚠ The next free ordinal is **0072** (re-checked 2026-10-26 at `54f82f03`; 0067–0071 are taken and 0056/0065 no longer exist); confirm by listing `doc/adr/` rather than
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
  - verdict: corroborated | checked_at: 54f82f03 | by: kidicap-gateway-requirements/cleanup | rescoped: n/a | evidence: BffRuntimeProducer.buildLogoutEndpoint :1271 passes sessionRecord -> no-op at :1284-1287; threat model BFF-09 :2473, Control line 'revoke the RT family' :2487, Status :2537, summary row :3853 has no revocation caveat; the Not-delivered paragraph :2544-2550 already exists, so two lines remain
- HYPOTHESIS: the cookie-mode residual is documented in the user docs but carries no disposition of
  CodeRabbit's Medium rating — confirm/refute at `doc/user/bff-cookie.adoc` and
  `doc/security-threat-model.adoc` § the cookie-mode rows (verify-at-outline).
  - verdict: contradicted | checked_at: 54f82f03 | by: kidicap-gateway-requirements/cleanup | rescoped: yes | evidence: cookie-mode residual already disposed: threat model :2552-2568 accepted with bound and mitigations, summary :3853 PARTIAL-accepted, variants/03-bff-cookie.adoc:310-321, user/bff-cookie.adoc:805-813, ADR-0018:359-363; deliverable 3 stays dropped
- HYPOTHESIS: neither the problem+json extension-member rule nor the reserved-path boot rule has an ADR at
  HEAD — confirm/refute by searching `doc/adr/` for both subjects (verify-at-outline).
  - verdict: corroborated | checked_at: 54f82f03 | by: kidicap-gateway-requirements/cleanup | rescoped: n/a | evidence: no ADR states either rule: ADR-0057:134-139 names missing_scopes and step_up_url only for its own 403; ADR-0018:51-74 covers reserved-path precedence only; the rule is now ReservedPathRules.java (validateReservedPath :340, ABSOLUTE_GATEWAY_PATH_REQUIREMENT :90)
- OBSERVED: ADR ordinals 0053, 0056 and 0057 are taken at HEAD (0053 once, after PR #367 resolved the
  collision; 0056 the header-matcher ADR; 0057 PLAN-23's session-scope ADR) — orchestrator-verified
  2026-10-02 by listing `doc/adr/`. **Re-scoped 2026-10-26: 0053 and 0057 are still taken; 0056 no longer
  exists (merged into 0054) and the next free ordinal is 0072.**
  - verdict: contradicted | checked_at: 54f82f03 | by: kidicap-gateway-requirements/cleanup | rescoped: yes | evidence: 0053 and 0057 taken; 0056 does not exist (merged into 0054 by #521) and 0065 left the corpus; highest is 0071, next free ordinal 0072; spec text corrected 2026-10-26

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
- HYPOTHESIS: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/bff/` — the logout package and its `package-info.java`, which name BFF-09 and revocation (verify-at-outline). Deliverable 2 is decided 2026-10-26: implement, inside this plan
- OBSERVED (scope decided 2026-10-26):
  `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/bff/logout/RpInitiatedLogout.java` — the
  `TokenRevocation` seam, :33–47 and :287–309; its Javadoc changes with the binding
- OBSERVED (scope decided 2026-10-26): `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/bff/reserved/LogoutEndpoint.java` — javadoc :35–48 describes the revocation call
- OBSERVED (added 2026-10-26): `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/bff/BffLogMessages.java` — only if the failure semantics decided at outline add a latched WARN (derive the WARN id at write time)
- HYPOTHESIS: `doc/configuration.adoc` — one revocation-seam statement exists; read it, and edit it if the
  operator-facing mitigation of deliverable 1 names a configuration key (verify-at-outline)
- OBSERVED (added 2026-10-26, re-grounding at `54f82f03`): `doc/variants/02-bff-session.adoc` (states the
  no-op seam; only the 03 variant was listed) and `doc/user/tls-edge.adoc` :436–441 (explicitly states the
  seam is bound to a no-op)
- OBSERVED (scope decided 2026-10-26): `integration-tests/src/test/java/de/cuioss/sheriff/gateway/integration/BffLogoutIT.java`
  (javadoc :31–38, "bound as a best-effort no-op") — must change: it asserts the revocation request reaches the IdP stub
- OBSERVED (added 2026-10-26): `api-sheriff/src/test/java/de/cuioss/sheriff/gateway/bff/logout/RpInitiatedLogoutTest.java` and `api-sheriff/src/test/java/de/cuioss/sheriff/gateway/quarkus/BffRuntimeProducerTest.java` — the unit homes for the binding and its failure semantics
- HYPOTHESIS (added 2026-10-26): `doc/quality-report/security-posture.adoc` (SEC-7 and a BFF-09 row) — a dated report, an optional consistency touch (verify-at-outline)
- OBSERVED (added 2026-10-26, read-only references for deliverables 4 and 5, not files this plan edits):
  `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/config/validation/rule/ReservedPathRules.java` and
  `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/bff/runtime/SessionAuthenticationStage.java`

## Dependencies and Sequencing

- Depends on: PLAN-23 — landed (#369). Deliverable 5 also waited on `api-sheriff-0-2-0` `PLAN-V02-12`'s
  first PR (the reserved-path rule it records was being rewritten there). Re-grounded 2026-10-26: that
  gate is met (#450) and the back-channel half landed as #492 (ADR-0069); the rule now sits in a stable
  place, `ReservedPathRules`. The cross-epic hold on this plan is lifted (the refactor chain shipped).
- If deliverable 2 decides to implement RFC 7009 revocation on logout, it hits the external
  `revocation_endpoint` problem: in the container deployments the advertised revocation endpoint is the
  IdP's external origin. token-sheriff-client 0.9.7 solves it (`backChannelOrigin`, TokenSheriff#800 →
  PR #805: endpoints on the issuer's origin, revocation included, are sent to the back-channel origin),
  and `api-sheriff-0-2-0` `PLAN-V02-12` exposes it as an `oidc` key. So an implementation here runs
  after V02-12's held half has landed that key; this plan still owns logout revocation itself.
  (Re-grounded 2026-10-26: that key has landed; see the re-grounding note under Deliverables.)
- Overlaps with: PLAN-20 (`doc/adr/`, `doc/configuration.adoc`, `doc/security-threat-model.adoc`,
  `doc/user/bff-session.adoc`, and now `BffRuntimeProducer`) and PLAN-21 (the same docs and `BffRuntimeProducer`),
  and, since deliverable 2 became an implementation (2026-10-26), the running siblings V02-06 and V02-20 on
  `BffRuntimeProducer` and `BffLogMessages`. Sequenced against all of them rather than paired.
- Re-scoped 2026-10-26 from "small and mostly prose" to prose plus one runtime binding: it needs the full
  quality gate, verify and the native integration lane, and it is no longer a no-risk filler for an unused slot.
  Deliverable 1 still removes a false security claim and is not cosmetic.

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
