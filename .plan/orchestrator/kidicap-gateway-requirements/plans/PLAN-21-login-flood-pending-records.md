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

1. Decide the mechanism and record it as an ADR, **re-scoped 2026-09-21 by the cleanup re-grounding at
   3abc370**: a per-binding slot cap keyed on an existing cookie is NOT buildable as filed — the binding
   cookie is minted FROM the new record's own id inside `LoginFlow.initiate`, so at record-creation time
   there is no inbound client identity to key a cap on, and every `/auth/login` from one browser yields an
   independent cookie value. The real choice is therefore between: (a) establishing a stable pre-login
   client identity independent of any one pending record (a long-lived cookie set before the first
   initiation) and capping per that identity, or (b) an address-based rate limit on the initiation and
   callback paths, or (c) a combination. The security argument belongs in the record — the store must stay
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
   `doc/security-threat-model.adoc` for the DoS surface of the unauthenticated login path — and, if
   `rate_limit` stays reserved-but-inert, say so where it is documented rather than leaving it to read as
   available.

## Claim Labels

- HYPOTHESIS: `PendingAuthorizationStore.InMemory` bounds at `DEFAULT_MAX_PENDING = 10_000` and evicts the oldest beyond capacity — confirm/refute at `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/bff/login/` § pending-authorization store and `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/quarkus/BffRuntimeProducer.java` § store construction (verify-at-outline)
  - verdict: corroborated | checked_at: 35f2bb37 | by: kidicap-gateway-requirements/cleanup | rescoped: n/a | evidence: BffRuntimeProducer:252 DEFAULT_MAX_PENDING=10_000 and :443 InMemory(DEFAULT_MAX_PENDING)
- HYPOTHESIS: eviction is global rather than per-client, so a flood from one client evicts another client's live record — confirm/refute at the same store's eviction method (verify-at-outline)
  - verdict: corroborated | checked_at: 35f2bb37 | by: kidicap-gateway-requirements/cleanup | rescoped: n/a | evidence: InMemory.store evicts oldest beyond capacity on one global LinkedHashMap; no per-client partition
- HYPOTHESIS: `rate_limit` is reserved in the schema with no reader — confirm/refute at `api-sheriff/src/main/resources/schema/gateway.schema.json` § `rate_limit` and a grep for its config accessor (verify-at-outline)
  - verdict: corroborated | checked_at: 35f2bb37 | by: kidicap-gateway-requirements/cleanup | rescoped: n/a | evidence: RateLimitConfig accepted-and-ignored; referenced only by RouteConfig and ConfigModelReflection
- OBSERVED: `LoginFlow.initiate` creates the pending record and validates the return URL's origin — read at `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/bff/login/LoginFlow.java` § `initiate`
  - verdict: corroborated | checked_at: 35f2bb37 | by: kidicap-gateway-requirements/cleanup | rescoped: n/a | evidence: LoginFlow.initiate:129-144 same-origin-validates then creates and stores the record; the PAR push now runs first at :132
- OBSERVED (cleanup re-grounding, 3abc370): the binding cookie is minted from the newly created record's own id — `PendingAuthorizationRecord.create(...)` → `pendingStore.store(pending)` → `bindingCookieCodec.toSetCookieHeader(pending.id())` — so no inbound binding identity exists at record-creation time — read at `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/bff/login/LoginFlow.java` § `initiate`
  - verdict: corroborated | checked_at: 35f2bb37 | by: kidicap-gateway-requirements/cleanup | rescoped: n/a | evidence: LoginFlow:138-142 create then store then toSetCookieHeader(pending.id()); PAR added a push but no reorder of cookie minting
- OBSERVED: `PendingAuthorizationStore.InMemory` keys one shared insertion-ordered map on `pending.id()` with no per-client partition, and `evictOldestBeyondCapacity` walks that single map — read at `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/bff/pending/PendingAuthorizationStore.java` § `InMemory`, `evictOldestBeyondCapacity`
  - verdict: corroborated | checked_at: 35f2bb37 | by: kidicap-gateway-requirements/cleanup | rescoped: n/a | evidence: PendingAuthorizationStore:75-121 keys one LinkedHashMap on pending.id(); eviction walks that single keySet
- Verify-first clause (SETTLED and absorbed 2026-09-21): the clause's own "if not" branch fired — a per-binding cap keyed on an existing cookie is not viable, and deliverable 1 now carries the three re-scoped options. What remains open for outline is only WHICH option, and the cost of option (a)'s pre-login cookie on the unauthenticated path.
  - verdict: corroborated | checked_at: 35f2bb37 | by: kidicap-gateway-requirements/cleanup | rescoped: n/a | evidence: clause holds at HEAD: the cookie is minted from the new record (LoginFlow:142) so no inbound identity exists at creation time; the earlier contradicted verdict recorded the clause premise failing - the re-scope stands and the clause itself is now true

## Expected Surface

- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/bff/pending/PendingAuthorizationStore.java` — the store and its eviction (corrected 2026-09-21: it lives in `bff/pending/`, not `bff/login/`)
- OBSERVED (added 2026-10-05, re-grounding at `35f2bb37`): ⛔ **`rate_limit` is declared in the WRONG
  schema by this spec.** It is a PER-ROUTE key in
  `api-sheriff/src/main/resources/schema/endpoint.schema.json:295`; `gateway.schema.json` carries no
  `rate_limit` at all. Any work on the reserved key edits the endpoint schema
- OBSERVED (added 2026-10-05): `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/bff/login/SessionWidening.java`
  and `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/bff/refresh/StepUpCoordinator.java` — PLAN-23's
  OTHER creators of pending records, sharing the one store. ⛔ An admission cap must not throttle a widening
  or a step-up while bounding logins
- OBSERVED (added 2026-10-05): `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/ApiSheriffLogMessages.java`
  (the WARN record for evicting a live record) and
  `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/quarkus/SheriffMetrics.java` (the eviction metric,
  moved ~66 lines by `5ddf8081`)
- ⚠ OBSERVED (added 2026-10-05): since PAR (`e8db85bf`), `LoginFlow.initiate` pushes the authorization
  request to the IdP **before** storing the pending record (`LoginFlow:132` ahead of `:138`), so a login
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
