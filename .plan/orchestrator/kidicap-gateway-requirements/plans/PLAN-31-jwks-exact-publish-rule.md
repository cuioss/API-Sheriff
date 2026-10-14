# PLAN-31: Publish a Refreshed JWKS Key Set by Comparing Key Ids, Not by the Triggering Key Id

epic: kidicap-gateway-requirements
workstream: WS-01

> Staged plan spec — one shippable unit of work, ready for `/plan-marshall` hand-off.
> Lives at `plans/PLAN-31-jwks-exact-publish-rule.md` and is queued as one row file,
> `queue/PLAN-31.json`, in the epic ledger. The orchestrator EMITS the command below; it never launches
> the plan inline. This spec is SELF-SUFFICIENT: the emitted command is a one-line pointer and carries no
> brief.
> Staged 2026-10-14 from the Watch "The JWKS publish rule stays approximate until token-sheriff exposes a
> key set's key ids", whose trigger fired: token-sheriff 0.9.7 (released 2026-10-14) carries
> `JwksLoader.getKeyIds()` (cuioss/TokenSheriff#801, PR #803), and `main` is on 0.9.7 since #449
> (`5c2a8143`).

## Objective

PLAN-24 (#430, ADR-0063) refreshes an issuer's JWKS once per window when a token names an unknown key
id. It loads the fresh key set off to the side and publishes it **only if it holds the key id that
triggered the refresh**. That is an approximation, shipped by operator decision because token-sheriff
0.9.6 could not list a key set's key ids. Its cost: a caller presenting invented key ids can claim the
window, and the refresh it triggers is discarded even when the fetched set holds a genuinely rotated
key, so the rotation is picked up only at the next background refresh.

0.9.7 lists the key ids. This plan replaces the approximation with the exact rule — publish the fresh
set when its key ids differ from the current set's — and removes the accepted residual from ADR-0063
and the threat model.

## Source

- Watch in `epic.md` (source: `landings/PLAN-24.md`, lesson `2026-10-12-16-013`).
- Upstream: `JwksLoader.getKeyIds()` returns an immutable snapshot of the **active** key set's key ids,
  empty before the first load, never `null`, blank key ids omitted; retired grace-period key sets are
  not included (`doc/validation/architecture.adoc` in TokenSheriff 0.9.7).
- #449 implemented `RetryingJwksLoader.getKeyIds()` as a pure delegate to the current delegate's active
  set (`Set.copyOf(snapshot.get().delegate().getKeyIds())`), with tests. It changed no publish behaviour.

## Deliverables

1. **The exact publish rule in `RetryingJwksLoader.settleRefresh`.** Publish the fresh delegate when it
   loaded (`LoaderStatus.OK`) and its `getKeyIds()` differs from the current delegate's; discard it
   otherwise. Settle at outline, and state in the ADR, what "differs" means in each case:
   - the fresh set adds a key id → publish (a rotation; the triggering key id need not be in it);
   - the fresh set equals the current set → discard, however many unknown key ids triggered it (a flood
     of invented key ids must still change nothing);
   - the fresh set only drops key ids → decide; the retired delegate keeps answering for the grace period
     either way, so state which is safer and why;
   - either set empty (blank key ids are omitted upstream) → never publish an empty set over a loaded one.
   The single-flight claim, the per-window bound, the retired-delegate grace period and the
   "discarded refresh never starts the failed-load retry" property stay exactly as they are.
2. **Tests that pin the change.** In `RetryingJwksLoaderTest`: a refresh triggered by an invented key id
   whose fetched set holds a newly rotated key **is published** (red on `main`); an unchanged set is
   discarded under a flood; the drop-only and empty cases as decided in D1. Extend
   `JwksKeyRotationIT` if it can drive the "invented key id wins the window during a rotation" case end to
   end; if not, say why the unit level is sufficient.
3. **Remove the residual from the record.** Amend ADR-0063 (its Risks bullet "A caller presenting invented
   key ids can win the window" and the sentence that closing it needs a library accessor) and the
   threat model's GW-07 residual paragraph (`doc/security-threat-model.adoc`, the paragraph ending "which
   token-sheriff 0.9.6 does not") to state the exact rule. Keep the other residual (tokens signed with a
   new key are rejected within the first window after an abrupt change) unless D1 changes it. Update the
   class Javadoc of `RetryingJwksLoader` (lines ~84–89 describe the approximate rule) and any
   `doc/configuration.adoc` / `doc/user/` text that describes when a refreshed set is used.
   ⚠ Re-grounded 2026-10-14 (cleanup at `5490144f`): #448 added a sibling GW-07 subsection
   (`gw-07-upstream-ports`); the paragraph to change is in the `gw-07-jwks-refresh` subsection, plus its
   summary row ("a refresh published only when it holds the triggering key id"). Three more places state
   the approximate rule: `doc/LogMessages.adoc` (ApiSheriff-136, "a refresh replaces the key set only when
   it holds the key id that triggered it") and `doc/configuration.adoc` (the sentence "The refresh replaces
   the key set only if it holds the key id that triggered it").

Split guard: 3 deliverables.

## Claim Labels

- OBSERVED: `settleRefresh` discards the fresh delegate when `fresh.getKeyInfo(kid).isEmpty()` — read at `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/auth/RetryingJwksLoader.java` § `settleRefresh`, at `5c2a8143`
  - verdict: corroborated | checked_at: 5490144f | by: kidicap-gateway-requirements/cleanup | rescoped: n/a | evidence: RetryingJwksLoader.settleRefresh :553; :555 discards when status != OK || fresh.getKeyInfo(kid).isEmpty()
- OBSERVED: `RetryingJwksLoader.getKeyIds()` delegates to the current delegate's active set and was added by #449 — read in the diff of `5c2a8143`
  - verdict: corroborated | checked_at: 5490144f | by: kidicap-gateway-requirements/cleanup | rescoped: n/a | evidence: RetryingJwksLoader.getKeyIds() :683-684 returns Set.copyOf(delegate().getKeyIds()); added by 5c2a8143 (#449)
- OBSERVED: `main` pins `version.token-sheriff` 0.9.7 — read at `pom.xml`, at `5c2a8143`
  - verdict: corroborated | checked_at: 5490144f | by: kidicap-gateway-requirements/cleanup | rescoped: n/a | evidence: pom.xml:63 version.token-sheriff 0.9.7
- OBSERVED: ADR-0063 § Risks and the threat model's GW-07 residual both say closing the residual needs a key-id accessor that 0.9.6 lacks — read at `doc/adr/0063-…adoc` (~lines 219–227) and `doc/security-threat-model.adoc` (~lines 1276–1290), at `5c2a8143`
  - verdict: corroborated | checked_at: 5490144f | by: kidicap-gateway-requirements/cleanup | rescoped: n/a | evidence: ADR-0063 Risks :219-227 and threat-model gw-07-jwks-refresh residual :1282-1290 end "which token-sheriff 0.9.6 does not"; Javadoc RetryingJwksLoader:86-89
- HYPOTHESIS: `JwksKeyRotationIT` can drive a rotation behind a claimed window end to end — confirm/refute at `integration-tests/src/test/java/de/cuioss/sheriff/gateway/integration/JwksKeyRotationIT.java` (verify-at-outline)
  - verdict: unverifiable | checked_at: 5490144f | by: kidicap-gateway-requirements/cleanup | rescoped: n/a | evidence: JwksKeyRotationIT (:150) drives rotation and an invented-key-id flood separately; whether it can drive the combined case is an outline judgement

## Expected Surface

- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/auth/RetryingJwksLoader.java`
- OBSERVED: `api-sheriff/src/test/java/de/cuioss/sheriff/gateway/auth/RetryingJwksLoaderTest.java`
- OBSERVED: `doc/adr/0063-An_unknown_key_id_triggers_one_bounded_JWKS_refresh_per_issuer_and_the_refresh_cadence_is_configurable.adoc`
- OBSERVED: `doc/security-threat-model.adoc`
- OBSERVED (added 2026-10-14, cleanup at `5490144f`): `doc/LogMessages.adoc` — ApiSheriff-136 states the approximate rule
- OBSERVED (added 2026-10-14): `doc/configuration.adoc` — states the approximate rule
- HYPOTHESIS: `integration-tests/src/test/java/de/cuioss/sheriff/gateway/integration/JwksKeyRotationIT.java` (verify-at-outline)
- HYPOTHESIS: `doc/configuration.adoc`, `doc/user/` pages describing the `jwks` refresh keys (verify-at-outline)

## Dependencies and Sequencing

- Depends on: PLAN-24 (#430) and PLAN-28 (#437), landed; #449 (token-sheriff 0.9.7), landed.
- Overlaps with: PLAN-30 (running, PR #448) on `doc/security-threat-model.adoc` only — a different
  section; pairable. Not with PLAN-29, which also edits the threat model and ADRs heavily — sequence.
- Small; a good filler for a free slot.

## Hand-Off Command

```text
/plan-marshall task="implement .plan/orchestrator/kidicap-gateway-requirements/plans/PLAN-31-jwks-exact-publish-rule.md"
```

## Write-Boundary

The plan implementing this spec touches only its own repository source and tests. It creates
and edits NO file under `.plan/orchestrator/` other than its own
`inbox/{sender}-{seq}` message — the orchestrator owns every other ledger write — and reports
its outcome through its PR and its inbox message. The inbox exception's qualifiers and the
sole sanctioned write mechanism are stated in
`persona-plan-orchestrator/standards/orchestration-model.md` § Ledger Write-Boundary.
