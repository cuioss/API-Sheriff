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
   ⚠ Re-grounded 2026-10-26 (cleanup at `54f82f03`), superseding the locations above where they differ:
   - **Pin.** `main` is on token-sheriff **0.9.8** (#491, `pom.xml:63`), not 0.9.7. The `getKeyIds()` override
     still compiles. The 0.9.8 jar was not readable locally (`~/.m2` stops at 0.9.6), so the outline MUST read
     the resolved 0.9.8 jar (`unzip -l`, `javap`) before relying on the "empty before first load / blank ids
     omitted" semantics in D1.
   - **Threat model.** The GW-07 residual is now `doc/security-threat-model.adoc` § `gw-07-jwks-refresh`
     (about lines 1355–1369). #492 already dropped the "which token-sheriff 0.9.6 does not" clause; it now says
     the library exposes the key ids since 0.9.7 but the refresh does not use them. D3's edit is therefore
     "replace that paragraph with the exact rule". Two further places state the approximate rule: the control
     bullet "A flood cannot evict valid keys" (~1332–1336) and the Status paragraph (~1371–1379); the summary
     row is ~3834.
   - **ADR-0063** states the approximate rule in more places than Risks (:219–227): the metadata summary
     (line 12), the Decision statement (:67–68), section 4 (:133–143), the Alternatives paragraph (:251–260)
     and the References entry (:344–345). All change together. The record is 350 lines, so the 500-line body
     ceiling is not at risk; follow `doc/development/adr-writing.adoc` (no PR numbers, SHAs, dates or lesson
     ids in the body).
   - **Javadoc.** The approximate-rule paragraph is `RetryingJwksLoader` :77–89; the `settleRefresh` Javadoc
     (:546–552) also states it. `kid` becomes unused in `runRefresh`, `settleRefresh` and the scheduling in
     `onUnknownKeyId` once the rule is exact; remove it (Pre-1.0 rule) and fix the `RetryingJwksLoaderTest`
     call sites.
   - **Other text.** `doc/LogMessages.adoc` :99 (ApiSheriff-136 Trigger; ApiSheriff-137 at :100 stays valid);
     `doc/configuration.adoc` :2333–2335 AND the second paragraph :2343–2346 ("a key id the identity provider
     never published … discarded even when the key set it fetched holds a newly rotated key"). No
     `doc/user/` page states the publish rule; drop it from the surface.
   - **`JwksKeyRotationIT` is a must-edit, not a hypothesis.** Its Javadoc (:62–70) and an assertion message
     (:242–244) state the old rule. A combined leg (replace the key, send one invented-key-id token to claim
     the window, assert a new-key token is accepted inside the same window) looks constructible with the
     existing rig (`replaceSigningKey()`, `accessTokenNamingKeyId()`, `keySetRequests()`); it is red on `main`.
   - **Design points for the outline.** Compare against the CURRENT snapshot's delegate at settle time, not at
     claim time (the library's own background refresh can change it while the off-to-the-side load is in
     flight). The drop-only case is real: publishing a strict subset replaces the active set at once while the
     retired delegate answers only for the grace period, so an invented-key-id caller could trigger a shrink;
     discarding leaves the shrink to the background refresh.

Split guard: 3 deliverables.

## Claim Labels

- OBSERVED: `settleRefresh` discards the fresh delegate when `fresh.getKeyInfo(kid).isEmpty()` — read at `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/auth/RetryingJwksLoader.java` § `settleRefresh`, at `5c2a8143`
  - verdict: corroborated | checked_at: 54f82f03 | by: kidicap-gateway-requirements/cleanup | rescoped: n/a | evidence: RetryingJwksLoader.java:553-565, :555 discards when closed or status != OK or fresh.getKeyInfo(kid).isEmpty(); file untouched since 5c2a8143 (#449)
- OBSERVED: `RetryingJwksLoader.getKeyIds()` delegates to the current delegate's active set and was added by #449 — read in the diff of `5c2a8143`
  - verdict: corroborated | checked_at: 54f82f03 | by: kidicap-gateway-requirements/cleanup | rescoped: n/a | evidence: RetryingJwksLoader.java:674-685 getKeyIds() returns Set.copyOf(snapshot.get().delegate().getKeyIds()); added by #449, unchanged; the @Override still compiles at token-sheriff 0.9.8
- OBSERVED: `main` pins `version.token-sheriff` 0.9.7 — read at `pom.xml`, at `5c2a8143`
  - verdict: contradicted | checked_at: 54f82f03 | by: kidicap-gateway-requirements/cleanup | rescoped: yes | evidence: pom.xml:63 now pins version.token-sheriff 0.9.8 (#491, 199a0cda); premise survives; the 0.9.8 jar was not readable locally (~/.m2 stops at 0.9.6), so the outline must read it before relying on empty and blank-id semantics; spec re-scoped
- OBSERVED: ADR-0063 § Risks and the threat model's GW-07 residual both say closing the residual needs a key-id accessor that 0.9.6 lacks — read at `doc/adr/0063-…adoc` (~lines 219–227) and `doc/security-threat-model.adoc` (~lines 1276–1290), at `5c2a8143`
  - verdict: contradicted | checked_at: 54f82f03 | by: kidicap-gateway-requirements/cleanup | rescoped: yes | evidence: ADR half holds (ADR-0063 Risks :219-227, file name exact); threat-model half contradicted: the residual is now security-threat-model.adoc:1355-1369, #492 dropped the 0.9.6 clause and says the refresh does not use the key ids; spec D3 location and edit rewritten
- HYPOTHESIS: `JwksKeyRotationIT` can drive a rotation behind a claimed window end to end — confirm/refute at `integration-tests/src/test/java/de/cuioss/sheriff/gateway/integration/JwksKeyRotationIT.java` (verify-at-outline)
  - verdict: unverifiable | checked_at: 54f82f03 | by: kidicap-gateway-requirements/cleanup | rescoped: n/a | evidence: outline judgement; JwksKeyRotationIT drives rotation (:171-199) and an invented-key-id flood (:202-245) separately; a combined leg looks constructible with the existing rig; the IT prose :62-70 and message :242-244 state the old rule

## Expected Surface

- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/auth/RetryingJwksLoader.java`
- OBSERVED: `api-sheriff/src/test/java/de/cuioss/sheriff/gateway/auth/RetryingJwksLoaderTest.java`
- OBSERVED: `doc/adr/0063-An_unknown_key_id_triggers_one_bounded_JWKS_refresh_per_issuer_and_the_refresh_cadence_is_configurable.adoc`
- OBSERVED: `doc/security-threat-model.adoc`
- OBSERVED (added 2026-10-14, cleanup at `5490144f`): `doc/LogMessages.adoc` — ApiSheriff-136 states the approximate rule
- OBSERVED (added 2026-10-14; located 2026-10-26): `doc/configuration.adoc` — states the approximate rule in section _jwks_key_rotation (:2333–2335 and a second paragraph :2343–2346). No page under the user documentation directory states the publish rule, so it is not on the surface
- OBSERVED (re-grounded 2026-10-26 at `54f82f03`): `integration-tests/src/test/java/de/cuioss/sheriff/gateway/integration/JwksKeyRotationIT.java` — must change: its Javadoc (:62–70) and an assertion message (:242–244) state the approximate rule
- HYPOTHESIS: `integration-tests/src/test/java/de/cuioss/sheriff/gateway/integration/StubIdentityProviderRig.java` — only if the combined leg needs a helper the rig lacks (verify-at-outline)
- HYPOTHESIS: `doc/development/integration-test-topology.adoc`, `doc/development/test-corpus-integrity.adoc` — only if they cite a key-rotation leg or a test this plan renames or removes (verify-at-outline)

## Dependencies and Sequencing

- Depends on: PLAN-24 (#430) and PLAN-28 (#437), landed; #449 (token-sheriff 0.9.7) and #491 (0.9.8), landed.
- Overlaps with: PLAN-30 is landed (#448), so that overlap is gone. Not with PLAN-29, which also edits the
  threat model and ADRs heavily — sequence.
- Re-grounded 2026-10-26: the operator hold of 2026-10-15 (multi-file overlap with V02-12, V02-23, V02-44)
  named three sibling plans that have all shipped; no plan, live or staged, claims `RetryingJwksLoader`,
  `RetryingJwksLoaderTest`, ADR-0063 or `JwksKeyRotationIT`. The running siblings V02-05, V02-06 and V02-20
  all touch `doc/configuration.adoc`, and V02-06 and V02-20 also `doc/LogMessages.adoc` and the threat model;
  this plan's regions there are disjoint (V02-06's gw-08 row at ~3837 sits next to this plan's ~3834: a
  textual-merge risk only). The operator's multi-file overlap question will still fire per candidate.
- Sequence BEFORE sibling `PLAN-V02-48` (ADR corpus cleanup part 2), which lists ADR-0063 among its rewrites
  and is declared never to run beside an ADR-authoring plan; V02-48 should rewrite the already-amended record.
- Small; a good filler for a free slot. Touches `*.java`: the full quality gate and verify apply, and the IT
  needs the native-image lane.

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
