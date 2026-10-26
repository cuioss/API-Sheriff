# PLAN-21: A Login Flood Must Not Evict Other Browsers' In-Flight Logins

epic: kidicap-gateway-requirements
workstream: WS-04

> Staged plan spec — one shippable unit of work, ready for `/plan-marshall` hand-off.
> Lives at `plans/PLAN-21-login-flood-pending-records.md` and is queued in the epic `status.json`
> `plans[]` field. The orchestrator EMITS the command below; it never launches the plan inline.
> This spec is SELF-SUFFICIENT: the emitted command is a one-line pointer and carries no brief.
> Staged 2026-09-17 from inbox message `kidicap-gateway-downstream-007.md`.

## Objective

`oidc.login.path` is unauthenticated by design and every call creates a pending-authorization record. The
in-memory store bounds itself at 10,000 records and evicts the OLDEST beyond that bound — a sound guard
against unbounded memory growth, but one that hands an attacker a different effect: a few thousand
`/auth/login` calls per record lifetime evict the records of real browsers currently sitting at the IdP
login form, whose callback then fails in a way that looks like an IdP or cookie problem. This plan makes a
flood cost the flooder rather than other users.

> ⚠ **Re-grounded 2026-10-14 (cleanup at `5490144f`):** #450 (`eff0724c`) moved the reserved BFF paths
> from `/auth/*` to `/bff/*`. Read every `/auth/login` and `/auth/callback` below as the default
> `/bff/login` and `/bff/callback` (`BffPendingLoginBoundIT` already uses them). `rate_limit` is declared in
> `endpoint.schema.json` (line 287 at `5490144f`), not in `gateway.schema.json`.

## Source

Inbox `kidicap-gateway-downstream-007.md` (finding, 2026-09-17), filed by the downstream deployment
`kidicap-gateway`; code read at `main`, not measured. Downstream reference: `AU-9`.

- Server mode uses `PendingAuthorizationStore.InMemory` with `DEFAULT_MAX_PENDING = 10_000`
  (`BffRuntimeProducer`); beyond the bound `evictOldestBeyondCapacity` removes the oldest record.
- `rate_limit` is reserved in the schema and has no effect, so there is no in-gateway counter-measure.
- Wanted (any of): bind pending records to the browser-binding cookie so one client cannot occupy more
  than a small number of slots and evict within the same client first; a per-client-address or
  per-binding rate limit on the login-initiation and callback paths; or at minimum a log record and a
  metric when a LIVE (unexpired) pending record is evicted, so the effect is diagnosable.
- Acceptance: a flood of `/auth/login` from one client does not make a concurrent login of another
  browser fail at the callback.

## Deliverables

> ✅ **DECIDED by the operator 2026-10-26: WAIT FOR `api-sheriff-0-2-0` `PLAN-V02-06`, THEN DO ALL OF IT AS
> ONE PLAN.** This plan is HELD until V02-06 has shipped; it is not split into an observability-now part and a
> cap-later part. Consequences: no part of it is emitted before V02-06 lands, no second hand-rolled limiter is
> built (so the ADR-0062 "why not reuse" branch below does not arise), and the plan is re-grounded (`cleanup`)
> when V02-06 lands, because the per-source substrate's keying, the trusted-proxy-resolved source address,
> the `OidcConfig`/schema keys and the BFF WARN ids and ADR ordinal it claims all become inputs the outline must
> read rather than guess. Until then the flood stays undiagnosable in production (no WARN or metric on a live
> eviction) and the accepted residual at `doc/security-threat-model.adoc` § login initiation (bff-02) stands.
> Deliverable 1's choice among options (a), (b) and (c) is made at that outline, against V02-06's actual result.

1. Decide the mechanism and record it as an ADR, **re-scoped 2026-09-21 by the cleanup re-grounding at
   3abc370**: a per-binding slot cap keyed on an existing cookie is NOT buildable as filed — the binding
   cookie is minted FROM the new record's own id inside `LoginFlow.initiate`, so at record-creation time
   there is no inbound client identity to key a cap on, and every `/auth/login` from one browser yields an
   independent cookie value. The real choice is therefore between: (a) establishing a stable pre-login
   client identity independent of any one pending record (a long-lived cookie set before the first
   initiation) and capping per that identity, or (b) an address-based rate limit on the initiation and
   callback paths, or (c) a combination. ⛔ **Options (b) and (c) consume `api-sheriff-0-2-0`
   `PLAN-V02-06`'s per-source substrate (its D3/D7 contract: a per-source leaky bucket that admits
   weighted events) — they do not build a second per-address limiter.** If this plan has to run before
   V02-06 lands, the ADR records why the substrate is not reused (ADR-0062 requires a reason for every
   hand-rolled component), and V02-06 then absorbs this limiter rather than standing beside it. The security argument belongs in the record — the store must stay
   bounded, and an unauthenticated path must not become a memory lever; option (a) itself adds a cookie an
   unauthenticated caller can discard, so its cap must degrade to (b) rather than fail open.
2. Implement the chosen mechanism in the pending-authorization store and its producer wiring, keeping the
   existing overall bound as the backstop.
3. Observability regardless of the mechanism: a `WARN` log record and a metric when a live (unexpired)
   pending record is evicted, so the condition is diagnosable in production; new records documented in
   `doc/LogMessages.adoc`.
4. Tests: a unit test that one client cannot evict another client's live record; a test that the overall
   bound still holds under a flood; an integration test for the acceptance criterion (concurrent login
   survives a flood from another client).
5. Documentation: `doc/user/bff-session.adoc` and `doc/configuration.adoc` for any new key;
   `doc/security-threat-model.adoc` for the DoS surface of the unauthenticated login path. (`rate_limit`
   is already documented as not implemented — `doc/configuration.adoc` § `rate_limit` and
   `doc/user/endpoint-routes.adoc` — so there is nothing to add for it.)

> **Folded 2026-10-12 from PLAN-25's landing.** A flood test now EXISTS:
> `integration-tests/src/test/java/de/cuioss/sheriff/gateway/integration/BffPendingLoginBoundIT.java`,
> added by #427 after PLAN-25's k6 version failed the post-merge benchmark. It pins the property as it is
> TODAY — the store is bounded at 10 000 and the oldest record is evicted — which is the behaviour this
> plan changes. So deliverable 4's integration test is an AMENDMENT of that suite, not a second flood
> test, and the plan must expect it to go red the moment eviction stops being global. The flood must be
> driven from the host as `localhost:10443`: the gateway answers its reserved login path only when the
> request host equals the host of `oidc.redirect_uri`, which is what broke the k6 version. No new file
> surface — the IT directory is already declared.

> ⚠ **Re-grounded 2026-10-26 (cleanup at `54f82f03`).** The premise is intact: the store, `LoginFlow`,
> `LoginInitiationEndpoint` and `BindingCookieCodec` are untouched since #377, eviction is still global and
> oldest-first, and no per-source limiter exists in `api-sheriff/src/main`. What changed is the framing.
> - **A second creation surface.** Any unauthenticated HTML navigation to a `require: session` route also calls
>   `loginFlow.initiate` (`SessionAuthenticationStage` :429 → `LoginInitiation`), beside `oidc.login.path`
>   (`LoginInitiationEndpoint` :130). A cap placed only in the endpoint leaves that path open: put it in or
>   before `LoginFlow.initiate`, or at both entry points. Neither login seam carries a source argument today
>   (`LoginFlow.initiate(requestedReturnUrl, scopes, now)`), so the signature changes.
> - **Source address.** Only the raw TCP peer (`PipelineRequest.peerAddress()`) is plumbed. Behind a load
>   balancer it keys every user to the proxy; a per-source limit needs the trusted-proxy-resolved client
>   address (ADR-0003 / ADR-0044, `forward/TcpPeerGate`). V02-06's keying decides this.
> - **IT constraint.** `BffPendingLoginBoundIT` floods and completes the other login from one host, so the
>   flooder and the "other browser" share a source address; under an address-keyed limit (b/c) the existing
>   flood would itself be throttled. The outline must settle how the victim login gets a distinct source
>   (trusted-proxy forwarded headers) or exercise option (a) with separate cookie jars. The IT runs
>   sequentially, never in the concurrent failsafe execution.
> - **Log amplification.** Deliverable 3's WARN must not become one line per eviction on an unauthenticated
>   path: latch it, one WARN per reason per runtime and repeats at DEBUG, as ApiSheriff-132 does (ADR-0051,
>   threat model :3360). The metric carries the count. The store has no clock, logger or metric dependency
>   today and eviction ignores expiry (an expired record frees its slot only on `consume`), so "live record
>   evicted" needs a reference instant (`createdAt()` + TTL) and a log/metric seam injected from
>   `BffRuntimeProducer`.
> - **Widenings share the bound.** `SessionWidening` creates the other live pending records (via
>   `StepUpEndpoint`, the session stage and callback re-drive); `StepUpCoordinator` also calls `create`/`store`
>   but nothing at the edge drives it. A flood can evict an authenticated user's widening record, and a
>   per-source cap must not throttle widenings.
> - **Existing residual.** `doc/security-threat-model.adoc` :3368–3374 already records an accepted residual
>   for this path ("no dedicated bound on login initiation …", row `bff-02` ~:3846). Deliverable 5 updates or
>   retires that residual instead of adding a row. `doc/user/bff-session.adoc` does not state the 10 000
>   pending bound.
> - **Log catalogue.** BFF records live in `bff/BffLogMessages.java`, not `ApiSheriffLogMessages.java`; the next
>   free WARN id looked like 140 at this HEAD and V02-06 D9 and V02-20 will claim ids concurrently: derive at
>   write time. `SheriffMetrics` has no pending-store meter (the nearest hook is `recordSessionEvent`,
>   `sheriff_session_events_total`): the eviction metric needs a new meter and a seam into the store.
> - **Sequencing.** `PLAN-V02-06` is running (no PR, no landing): options (b)/(c), and option (a)'s required
>   degrade-to-(b), need its D3/D7 substrate. Only an observability-only subset (the live-eviction WARN and
>   metric, no admission cap) is buildable now. Line references: `rate_limit` is `endpoint.schema.json:335`;
>   `LoginFlow`: authorize :131, same-origin :132–134, create :137, store :139, binding cookie :141;
>   `BffRuntimeProducer`: `DEFAULT_MAX_PENDING` :280, store :527. ADR ordinal: 0072 is free (0071 is the
>   highest; gaps are retired). New ADRs follow `doc/development/adr-writing.adoc` (500-line ceiling).

## Claim Labels

- HYPOTHESIS: `PendingAuthorizationStore.InMemory` bounds at `DEFAULT_MAX_PENDING = 10_000` and evicts the oldest beyond capacity — confirm/refute at `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/bff/login/` § pending-authorization store and `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/quarkus/BffRuntimeProducer.java` § store construction (verify-at-outline)
  - verdict: corroborated | checked_at: 54f82f03 | by: kidicap-gateway-requirements/cleanup | rescoped: n/a | evidence: BffRuntimeProducer.java:280 DEFAULT_MAX_PENDING = 10_000; :527 new PendingAuthorizationStore.InMemory(DEFAULT_MAX_PENDING); eviction PendingAuthorizationStore.java:115-121; store untouched since #377
- HYPOTHESIS: eviction is global rather than per-client, so a flood from one client evicts another client's live record — confirm/refute at the same store's eviction method (verify-at-outline)
  - verdict: corroborated | checked_at: 54f82f03 | by: kidicap-gateway-requirements/cleanup | rescoped: n/a | evidence: PendingAuthorizationStore.java:75 one LinkedHashMap, :91-95 store puts then evictOldestBeyondCapacity; no client partition; eviction ignores expiry and the store has no clock, logger or metric dependency
- HYPOTHESIS: `rate_limit` is reserved in the schema with no reader — confirm/refute at `api-sheriff/src/main/resources/schema/gateway.schema.json` § `rate_limit` and a grep for its config accessor (verify-at-outline)
  - verdict: corroborated | checked_at: 54f82f03 | by: kidicap-gateway-requirements/cleanup | rescoped: n/a | evidence: rate_limit is in endpoint.schema.json:335, read by no validation rule, stage or limiter (only RateLimitConfig, RouteConfig, ConfigModelReflection, tests and docs); doc/configuration.adoc:4249 says not implemented; V02-06 D9 (running) will add a boot WARN
- OBSERVED: `LoginFlow.initiate` creates the pending record and validates the return URL's origin — read at `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/bff/login/LoginFlow.java` § `initiate`
  - verdict: corroborated | checked_at: 54f82f03 | by: kidicap-gateway-requirements/cleanup | rescoped: n/a | evidence: LoginFlow.java:128 initiate; :131 authorize (PAR push before the store); :132-134 sameOrigin else defaultReturnUrl; :137 create; :139 pendingStore.store; unchanged since #377
- OBSERVED (cleanup re-grounding, 3abc370): the binding cookie is minted from the newly created record's own id — `PendingAuthorizationRecord.create(...)` → `pendingStore.store(pending)` → `bindingCookieCodec.toSetCookieHeader(pending.id())` — so no inbound binding identity exists at record-creation time — read at `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/bff/login/LoginFlow.java` § `initiate`
  - verdict: corroborated | checked_at: 54f82f03 | by: kidicap-gateway-requirements/cleanup | rescoped: n/a | evidence: LoginFlow.java:141 toSetCookieHeader(pending.id()); cookie __Host-sheriff-binding=<recordId> (BindingCookieCodec:55,:75-79); the login seams take no source or client identity; only PipelineRequest.peerAddress() exists and is not passed to them
- OBSERVED: `PendingAuthorizationStore.InMemory` keys one shared insertion-ordered map on `pending.id()` with no per-client partition, and `evictOldestBeyondCapacity` walks that single map — read at `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/bff/pending/PendingAuthorizationStore.java` § `InMemory`, `evictOldestBeyondCapacity`
  - verdict: corroborated | checked_at: 54f82f03 | by: kidicap-gateway-requirements/cleanup | rescoped: n/a | evidence: PendingAuthorizationStore.java:72-121 one insertion-ordered map keyed on pending.id(); eviction walks that map
- Verify-first clause (SETTLED and absorbed 2026-09-21): the clause's own "if not" branch fired — a per-binding cap keyed on an existing cookie is not viable, and deliverable 1 now carries the three re-scoped options. What remains open for outline is only WHICH option, and the cost of option (a)'s pre-login cookie on the unauthenticated path.
  - verdict: corroborated | checked_at: 54f82f03 | by: kidicap-gateway-requirements/cleanup | rescoped: n/a | evidence: per-binding cap keyed on an existing cookie stays non-viable (claim 4); no per-source limiter in api-sheriff/src/main; PLAN-V02-06 is running with no PR or landing, so options b/c are unbuilt

## Expected Surface

- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/bff/pending/PendingAuthorizationStore.java` — the store and its eviction (corrected 2026-09-21: it lives in `bff/pending/`, not `bff/login/`)
- OBSERVED (added 2026-10-05, re-grounding at `35f2bb37`): ⛔ **`rate_limit` is declared in the WRONG
  schema by this spec.** It is a PER-ROUTE key in
  `api-sheriff/src/main/resources/schema/endpoint.schema.json` (`:335` at `54f82f03`); `gateway.schema.json`
  carries no `rate_limit` at all. Any work on the reserved key edits the endpoint schema
- OBSERVED (added 2026-10-05): `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/bff/login/SessionWidening.java`
  and `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/bff/refresh/StepUpCoordinator.java` — PLAN-23's
  OTHER creators of pending records, sharing the one store. ⛔ An admission cap must not throttle a widening
  or a step-up while bounding logins
- OBSERVED (corrected 2026-10-26: BFF records live in `BffLogMessages`):
  `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/bff/BffLogMessages.java`
  (the WARN record for evicting a live record) and
  `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/quarkus/SheriffMetrics.java` (the eviction metric; a
  new meter, none exists for the pending store)
- OBSERVED (added 2026-10-26, re-grounding at `54f82f03`): the second creation surface —
  `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/bff/runtime/SessionAuthenticationStage.java`
  (`LoginInitiation`, :429) and
  `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/bff/reserved/LoginInitiationEndpoint.java` (:130)
- HYPOTHESIS (added 2026-10-26): option (a) adds a pre-login gateway-own cookie, which
  `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/http/GatewayCookies.java` already covers by
  construction (its reserved prefix) (verify-at-outline)
- HYPOTHESIS (added 2026-10-26): a new cookie or key would touch
  `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/config/validation/rule/CookieRules.java`,
  `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/config/validation/rule/OidcSessionRules.java` and
  `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/config/model/OidcConfig.java` (the model and schema are shared with running V02-06 and V02-20) (verify-at-outline)
- ⚠ OBSERVED (added 2026-10-05): since PAR (`e8db85bf`), `LoginFlow.initiate` pushes the authorization
  request to the IdP **before** storing the pending record (`LoginFlow:131` ahead of `:139` at `54f82f03`), so a login
  flood now also hits the IdP's PAR endpoint. **Any admission cap must sit before `authorize`**, not merely
  before the store — see `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/bff/login/PushedAuthorizationRequests.java`
- OBSERVED (added 2026-10-02, re-grounding at `e8db85bf`):
  `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/bff/pending/PendingAuthorizationRecord.java` — grew
  ~110 lines for PLAN-23's session widening (a `Widening` record kind with sub/attempt). It is a SIBLING
  record kind sharing the same pending store, so this plan's capacity and eviction work bounds it too —
  size the cap against both kinds, not just logins
- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/bff/pending/BindingCookieCodec.java` — the binding cookie minted per record
- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/bff/login/LoginFlow.java` — `initiate`
- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/quarkus/BffRuntimeProducer.java` — store construction
- HYPOTHESIS: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/bff/reserved/` — login-initiation and callback endpoints (verify-at-outline)
- HYPOTHESIS: `api-sheriff/src/main/resources/schema/gateway.schema.json` — new key and/or `rate_limit` documentation (verify-at-outline)
- HYPOTHESIS: `api-sheriff/src/test/java/de/cuioss/sheriff/gateway/bff/login/` (verify-at-outline)
- HYPOTHESIS: `integration-tests/src/test/java/` — flood IT (verify-at-outline)
- HYPOTHESIS: `doc/user/bff-session.adoc`, `doc/configuration.adoc`, `doc/security-threat-model.adoc`, `doc/LogMessages.adoc`, `doc/adr/` (verify-at-outline)

## Dependencies and Sequencing

- Depends on: none functionally; shares the BFF login surface with PLAN-14 and PLAN-20, so it is sequenced against whichever is in flight
- Overlaps with: PLAN-14 (`LoginFlow`, `BffRuntimeProducer`), PLAN-20 (`BffRuntimeProducer`, reserved endpoints), PLAN-19 (session branch)
- Consumes (options b/c): `api-sheriff-0-2-0` `PLAN-V02-06`'s per-source substrate — sequence after it when either option is chosen. Re-grounded 2026-10-26: V02-06 is still running, so options (b)/(c) are not buildable yet
- Re-grounded 2026-10-26: the cross-epic hold on the refactor chain is lifted (that chain has shipped). The running siblings V02-05, V02-06 and V02-20 fill all three slots and share `doc/configuration.adoc`, `doc/LogMessages.adoc`, `doc/security-threat-model.adoc`, `OidcConfig.java`, the BFF WARN ids and the next ADR ordinal with this plan. Never two WS-04 plans at once: PLAN-20 and PLAN-21 exclude each other
- Adjacent to: PLAN-20's browser step-up also creates authorization requests — the chosen cap must not throttle a legitimate step-up

## Hand-Off Command

> Path corrected 2026-10-05: the ledger moved from the retired `.plan/local/orchestrator/` address to the
> git-tracked `.plan/orchestrator/`. The old path is what `phase-1-init` would have persisted as this plan's
> `source_id`, and the current tooling classifies it `unrecognised_id` — which is exactly what made PLAN-23
> finish with `emit-landing: not orchestrated` and file no landing message. Emitting this spec from the old
> path would repeat that.

```text
/plan-marshall task="implement .plan/orchestrator/kidicap-gateway-requirements/plans/PLAN-21-login-flood-pending-records.md"
```

## Write-Boundary

The plan implementing this spec touches only its own repository source and tests. It creates
and edits NO file under `.plan/orchestrator/` other than its own
`inbox/{sender}-{seq}` message — the orchestrator owns every other ledger write — and reports
its outcome through its PR and its inbox message. The inbox exception's qualifiers and the
sole sanctioned write mechanism are stated in
`persona-plan-orchestrator/standards/orchestration-model.md` § Ledger Write-Boundary.
