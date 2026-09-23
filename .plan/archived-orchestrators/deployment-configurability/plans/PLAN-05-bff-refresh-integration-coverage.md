# PLAN-05: BFF Refresh Integration Coverage and Failure Reproduction

epic: deployment-configurability
workstream: WS-04

> Staged plan spec — the emitted command is a one-line pointer, so this spec is the whole brief.

## Objective

A user working with the system normally reported that **an exception was logged at refresh time**. No
error text, stack trace, log identifier or reproduction steps are available. The BFF's transparent
near-expiry refresh — `TokenRefreshCoordinator`, the path that fires while a user is simply working —
has **no test that ever forces it to run**: the one integration test covering the mediated session
concedes in its own comment that the realm's 900s access-token lifespan keeps the token inside
validity for the whole test, so it asserts session continuity instead. Build the missing trigger and
use it to attempt the reproduction. The report describes an exception during ordinary use, which is
exactly this path — `TokenRefreshCoordinator.refresh` catches `TokenSheriffException` and logs
`WARN.SESSION_REFRESH_FAILED`. The reproduction's outcome, positive or negative, is a deliverable in
its own right.

## Deliverables

1. **A short-access-token-lifespan Keycloak fixture.** All three shipped realms use realm-level
   lifespans of 900s, 900s and 300s with no client-level override, so nothing can force a
   near-expiry refresh without a wall-clock wait. Add a dedicated client (or realm) with a lifespan
   on the order of seconds, chosen so it trips the BFF's configured `leeway_seconds: 30` window
   quickly. Decide client-attribute versus separate realm and record why — the client route is
   cheaper and avoids touching the gateway.yaml `token_validation` block.
2. **An integration test that actually forces the refresh** through the live BFF edge, driving
   `TokenRefreshCoordinator.refresh(...)` rather than asserting around it. Prove the refresh really
   ran — the mediated token observed upstream must change — rather than merely that the session
   stayed usable, which is what the current test proves.
3. **Cover all three outcome kinds.** `RefreshOutcome` has a three-value `Kind`, with factories
   `current(...)`, `refreshed(...)` and `failed()`. A test matrix covering only the happy path leaves
   the failure branch — the one the user hit — untested. Assert the `Set-Cookie` rotation on the
   refreshed branch and the 401 `application/problem+json` on the failure branch.
4. **Correct the unsupported claim in the existing test.** `BffSessionMediationIT`'s comment asserts a
   forced-refresh trigger *"is validated by the native+Docker IT run"*. No file in the repository sets
   a short access-token lifespan, so that claim is unsupported. Either make it true via deliverable 1
   or delete it — a comment asserting coverage that does not exist is worse than a silent gap.
5. **Report the reproduction outcome explicitly.** If an exception reproduces, capture its type,
   message and log identifier and file it in the plan's inbox message — that capture is the input to a
   follow-up fix plan, not something to fix in passing. If it does **not** reproduce, state exactly
   which refresh variants WERE exercised (near-expiry trigger, hard-expired token, IdP-rejected
   refresh token, rotated cookie, concurrent requests on one session) and which were not, so the
   negative result is bounded rather than an open-ended "could not reproduce".

## Claim Labels

- OBSERVED: `BffSessionMediationIT` states in its own comment that *"the integration realm's 900s
  access-token lifespan keeps the token well inside its validity for both requests, so this asserts
  the always-available continuity path rather than forcing a refresh (a forced-refresh trigger needs a
  short access-token-lifespan realm client and is validated by the native+Docker IT run, not asserted
  with a wall-clock wait here)"* — read at
  `integration-tests/src/test/java/de/cuioss/sheriff/gateway/integration/BffSessionMediationIT.java:97-106`.
  - verdict: contradicted | checked_at: cc10ce2a2c7840162bbc087242d42cbbc3cd14c3 | by: deployment-configurability/cleanup | rescoped: no | evidence: BffSessionMediationIT.java:97-106 comment rewritten to point to BffTokenRefreshIT and its 45s refresh-client
- OBSERVED: **no file in the repository sets a short access-token lifespan.** All three realm imports
  carry realm-level `accessTokenLifespan` only — `integration-realm.json` 900, `benchmark-realm.json`
  900, `deployment/compose-sample/docker/keycloak/sample-realm.json` 300 — and none defines a
  client-level override. The comment's "validated by the native+Docker IT run" claim is therefore
  **unsupported**. Asserted absence; re-derive at HEAD before scoping.
  - verdict: contradicted | checked_at: cc10ce2a2c7840162bbc087242d42cbbc3cd14c3 | by: deployment-configurability/cleanup | rescoped: no | evidence: integration-realm.json:82 now carries a client-level access.token.lifespan:45 override
- OBSERVED: `TokenRefreshCoordinator.refresh(SessionRecord, String, Instant)` is the entry point, and
  it catches `TokenSheriffException` and calls
  `LOGGER.warn(refreshFailure, BffLogMessages.WARN.SESSION_REFRESH_FAILED, REFRESH_FAILURE_REASON)` —
  read at
  `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/bff/refresh/TokenRefreshCoordinator.java:125`
  and `:172-177`. **This is a logged exception at refresh time**, which is precisely what the report
  describes; it is the first place to look.
  - verdict: corroborated | checked_at: cc10ce2a2c7840162bbc087242d42cbbc3cd14c3 | by: deployment-configurability/cleanup | rescoped: n/a | evidence: TokenRefreshCoordinator still logs WARN.SESSION_REFRESH_FAILED on a caught TokenSheriffException
- OBSERVED: `RefreshOutcome` carries a three-value `Kind` with factories `current(...)`,
  `refreshed(...)` and `failed()`, and its compact constructor throws
  `IllegalArgumentException("a " + kind + " outcome must carry a session")` — read at the same file
  `:272-335`. Three branches, one of which is the reported failure.
  - verdict: contradicted | checked_at: cc10ce2a2c7840162bbc087242d42cbbc3cd14c3 | by: deployment-configurability/cleanup | rescoped: no | evidence: RefreshOutcome.Kind now has FIVE values (CURRENT, REFRESHED, DEFERRED, UNAVAILABLE, FAILED), not three
- OBSERVED: the coordinator is documented as *"anything else gets 401 application/problem+json"* and
  as framework-agnostic — read at the same file `:67`.
  - verdict: contradicted | checked_at: cc10ce2a2c7840162bbc087242d42cbbc3cd14c3 | by: deployment-configurability/cleanup | rescoped: no | evidence: the exact "anything else gets 401" framing superseded by FAILED-vs-UNAVAILABLE distinction in class Javadoc
- OBSERVED: `revokeRefreshToken` and `refreshTokenMaxReuse` are absent from every realm import, so
  Keycloak's defaults apply and refresh tokens are not single-use in any shipped fixture — read at all
  three realm JSONs (asserted absence). Replay-driven refresh failure is therefore not reproducible
  against them as configured.
  - verdict: contradicted | checked_at: cc10ce2a2c7840162bbc087242d42cbbc3cd14c3 | by: deployment-configurability/cleanup | rescoped: no | evidence: integration-realm.json and sample-realm.json now both declare revokeRefreshToken:true and refreshTokenMaxReuse:0, landed later by PLAN-26
- OBSERVED: `BffRuntimeProducer` wires the refresh machinery, and the native build forces runtime
  initialization for `de.cuioss.sheriff.token.client.flow` because *"Deliverable 16 wired the engine
  into the live edge (BffRuntimeProducer -> AuthorizationCodeFlow -> FlowContext.create)"* — read at
  `api-sheriff/src/main/resources/application.properties` § the native-image block. The refresh path
  runs through the token-sheriff-client engine, so a failure may originate below the coordinator.
  - verdict: corroborated | checked_at: cc10ce2a2c7840162bbc087242d42cbbc3cd14c3 | by: deployment-configurability/cleanup | rescoped: n/a | evidence: application.properties still carries the native initialize-at-run-time build arg and the deliverable-16 wiring comment
- HYPOTHESIS: the reported exception originates on the IdP-side refresh exchange surfacing as
  `TokenSheriffException` — an expired or rotated refresh token, or a client-auth failure — rather
  than inside the coordinator's own logic — confirm/refute at
  `TokenRefreshCoordinator.java` § the `RefreshExchange` interface and its live implementation in
  `BffRuntimeProducer` (verify-at-outline). Leading candidate: it is the only branch that both throws
  and logs.
  - verdict: unverifiable | checked_at: cc10ce2a2c7840162bbc087242d42cbbc3cd14c3 | by: deployment-configurability/cleanup | rescoped: n/a | evidence: whether the reported exception originated on the IdP-side refresh exchange is a reproduction fact recorded in a plan landing/inbox message, not determinable from source alone
- HYPOTHESIS: a client-level `access.token.lifespan` attribute on a Keycloak client overrides the
  realm-level value and is honoured on realm import — confirm/refute at the Keycloak realm-import
  reference for the pinned image `quay.io/keycloak/keycloak:26.5.7` and by observing an issued token's
  `exp` (verify-at-outline). Deliverable 1's cheaper route depends entirely on this.
  - verdict: corroborated | checked_at: cc10ce2a2c7840162bbc087242d42cbbc3cd14c3 | by: deployment-configurability/cleanup | rescoped: n/a | evidence: confirmed: integration-realm.json client-level access.token.lifespan:45 override is live and consumed by BffTokenRefreshIT's dedicated 45s refresh-client
- Verify-first clause: settle the candidate mechanism against the implementing source —
  `TokenRefreshCoordinator` and its wired `RefreshExchange` at HEAD — and settle the Keycloak lifespan
  claim against an actually-issued token, not against documentation. ⛔ Do **not** narrow the test
  matrix to the leading candidate: the report carries no error text, so the reproduction is a search,
  and narrowing early is how a search returns a false negative.

## Expected Surface

- OBSERVED: `integration-tests/src/main/docker/keycloak/integration-realm.json` — the short-lifespan client, or a sibling realm file
- OBSERVED: `integration-tests/src/test/java/de/cuioss/sheriff/gateway/integration/BffSessionMediationIT.java` — the unsupported comment, and the continuity test
- HYPOTHESIS: `integration-tests/src/test/java/de/cuioss/sheriff/gateway/integration/BffRefreshSpecIT.java` — the new forced-refresh test (verify-at-outline: a new class is expected, but folding in may prove cleaner)
- HYPOTHESIS: `integration-tests/src/test/java/de/cuioss/sheriff/gateway/integration/BffKeycloakLoginFlow.java` — a fixture accessor for the short-lifespan client (verify-at-outline)
- HYPOTHESIS: the mounted gateway.yaml under `integration-tests/` — a `leeway_seconds` or issuer entry for the new client (verify-at-outline). This is the entry that decides whether this plan collides with PLAN-04.

## Prior Art — TokenSheriff's refresh-integration plan, analyzed 2026-09-07

Read directly from `/Users/oliver/git/TokenSheriff/.plan/local/archived-plans/2026-08-29-refresh-integration-coverage/work/refresh-elimination-verdict.md`
(396 lines). **Four things transfer; one warning comes with them.**

### 1. The fast-expiry fixture is already designed — copy the PATTERN, re-derive the NUMBER

TokenSheriff's §5 records the decision explicitly: it took the **client-level attribute route** —
`refresh-fast-client` carrying `access.token.lifespan: 35` **inside the existing realm** — rather
than a separate realm. Its stated rationale applies here verbatim: a separate realm is required only
when `revokeRefreshToken` or `refreshTokenMaxReuse` are needed, and those are realm-level settings
this coverage does not depend on. Staying in-realm avoided pulling `application.properties` into the
change set and **preserved surface-disjointness from sibling plans** — the same problem this spec has.

⛔ **The 35 s value does NOT transfer as a number.** It was derived against *TokenSheriff's*
scheduler: long enough that a freshly issued token is not already inside the **30 s refresh lead** at
store time (so the "not yet due" assertion means something), short enough to become due within ~5 s
under a bounded 15 s wait. **API Sheriff's BFF has its own lead**, so re-derive the value against
`TokenRefreshCoordinator` rather than copying 35.

### 2. The elimination bound is WIDER than this spec was written against

The second coverage pass closed four conditions that were residuals when this spec was staged.
**Expiry-driven entry into `RefreshFlow` is now INSIDE the bound** (§1.3 variant 6) — as are
`client_secret_post`, `private_key_jwt`, selector routing (variants 9–11) and the whole
`invalid_grant` family (variants 7–8). ⚠ A BFF that refreshes on an expiry trigger is therefore no
longer outside the elimination, which **weakens** the prior expectation that simply forcing expiry
would reproduce the fault.

### 3. Residual 4 remains the live lead, and it is still the right starting point

`§1.4 item 4 — discovery-resolved metadata`: TokenSheriff's tests hand-assemble `ProviderMetadata`
and bypass `DiscoveryResolver` entirely, because a discovery round trip would re-advertise the
container's unreachable internal endpoints. **This gateway resolves it for real** at
`BffRuntimeProducer.java:213`. That path is untouched by the elimination and is where this plan
should start.

### 4. Residuals — FILTERED against this gateway, not carried across wholesale

⛔ **Corrected 2026-09-07.** An earlier revision of this section listed four residuals and told the
reader to "check each against the BFF's actual configuration". That was the wrong way round: the
verdict's §1.4 enumerates **its own bound**, answering *"what did that fixture not exercise"* — not
*"what could break refresh here"*. Importing it unfiltered hands this plan a to-do list built for a
different repository. Filtered:

| Residual | Applies here? |
|---|---|
| `item 2` **mTLS client auth** | ⛔ **NO — inapplicable, drop it.** The BFF **hard-codes** `client_secret_basic`: `BffRuntimeProducer.java:210` sets `.authMethod(ClientAuthMethod.CLIENT_SECRET_BASIC)` and `:212` constructs `new ClientSecretBasicAuth(clientId, clientSecret)`. There is no selector and no configuration key — a grep of the config model and JSON schema for any client-auth key returns nothing. `MtlsClientAuth` is not imported and not reachable. **And even hypothetically it is the wrong shape**: a client-auth failure fails loudly at authentication (`invalid_client` / 401), not as an exception logged mid-refresh. |
| `item 1` AS-enforced single-use | ⚠ **Yes, for the REAL deployment.** `revokeRefreshToken` / `refreshTokenMaxReuse` are absent from this repo's realm fixtures too, so the local stack cannot exercise it either — but the reported exception came from a deployment whose AS configuration is unknown, and a server enforcing single-use is a live candidate. |
| `item 6` malformed responses | ⚠ Low. Keycloak does not produce them; belongs in unit tests against a mock endpoint, not here. |
| `item 8` concurrency at 2a | ⚠ **Yes.** The BFF can plausibly drive concurrent refreshes for one session, and neither repository has exercised that. |

✅ **The client-auth dimension is settled, not open.** `client_secret_basic` is precisely the ambient
condition TokenSheriff's variants 1–5 ran under, so this gateway's client-auth leg sits **inside** the
elimination bound rather than near its edge.

### ⚠ Do NOT re-perform the hand-off

The verdict's §6 declares the hand-off **OUTSTANDING, owed by the operator** — that is a **frozen
2026-08-27 statement**. This epic drained the verdict on **2026-08-31** and the ledger records it as
satisfied. The document cannot know that. Read §6 as history, not as a task.

⛔ **And carry §1 and §2 as TWO results, as §6 insists.** Case 2b (`TokenLifecycleManager`) has **no
cross-repository consequence** — this gateway does not use that layer at all. Collapsing 2a and 2b
into one "refresh did not reproduce" figure asserts an elimination nobody performed.

## Dependencies and Sequencing

- ✅ **UNBLOCKED 2026-08-31 — the cross-repo verdict has landed and is verified.** TokenSheriff
  `deployment-and-refresh-gaps` PLAN-01 shipped as PR #672, merge `cd1c0365`, 2026-08-29 (confirmed by
  `git -C /Users/oliver/git/TokenSheriff`); the 396-line verdict artifact is present at the path the
  message names. **Case 2a — `RefreshFlow.refresh(metadata, refreshToken)`, the 2-arg form and the
  only surface this BFF shares — is `not_reproduced`** across 12 enumerated conditions, instrumented
  so an engine-internal failure would have surfaced with its production frame. ⛔ It is a **bounded**
  elimination, not a clean bill of health.
  ⚠ **Case 2b (`TokenLifecycleManager`) has NO consequence here** and must not be read as
  strengthening 2a — this gateway does not route through that layer at all.
  ➡ **H4 — this repository's BFF wiring — is now the live hypothesis**, and this plan's matrix is
  sharpened accordingly rather than left broad.
- ⚠ **The residual matters more than the elimination. Eight conditions were NOT exercised; three were
  tested against THIS repository's topology and the answers differ:**
  - ⛔ **Residual 4 (discovery-resolved metadata) IS LIVE HERE — highest priority.** Their tests
    hand-assemble `ProviderMetadata` and bypass discovery. **This gateway resolves it for real**:
    `BffRuntimeProducer.java:213` builds `memoize(() -> new DiscoveryResolver(clientConfiguration).resolve())`.
    The elimination therefore does **not** cover our actual metadata path. Start here.
  - ⚠ **Residual 7 (provider version).** They tested Keycloak **26.4.0**; the shipped sample runs
    Keycloak **26.5.7**. Same provider, different minor — not a gap, but not an identity either.
  - ⚠ **Residual 1 (server-enforced single-use refresh tokens).** Absent from our realms too, exactly
    as in their test realm — so neither side has exercised it. That says nothing about the
    **reporter's** environment, which is the actual unknown; ask before assuming.
- ✅ **One suggested line is CLOSED by ground truth, so do not spend the matrix on it.** The message
  flags a *shape* — a sender-constrained session whose refresh leg does not propagate a `cnf` binding
  fails closed with `IllegalStateException`. **That shape is not reachable here: DPoP is not in use.**
  `BffRuntimeProducer.java:227` states it outright ("no sender constraint (DPoP is not in use)"), and
  the only other `DPoP` mention in main source is a javadoc line in `AuthenticationStage`.
  Deprioritise it unless a deployment turns DPoP on.
- ✅ **The pairing's load-bearing assumption is SETTLED HERE, and it HOLDS** (checked at `cea163c`,
  drained from `inbox/deployment-and-refresh-gaps-001.md`). The assumption was *"this BFF's refresh
  leg actually reaches `RefreshFlow`"*. It does, directly:
  `quarkus/BffRuntimeProducer.java:70` imports `de.cuioss.sheriff.token.client.flow.RefreshFlow`,
  `:232` constructs it, and `:257` binds the `RefreshExchange` seam as
  `refreshToken -> refreshFlow.refresh(metadata.get(), refreshToken)`;
  `TokenRefreshCoordinator.java:42-43` and `:99` document the same seam. The ordering is therefore
  not void, and this was settled without waiting for PLAN-01, as that message asked.
- ⚠ **But the elimination step only transfers if PLAN-01 exercises `RefreshFlow` DIRECTLY.**
  `TokenLifecycleManager` and `RefreshScheduler` appear **nowhere** in this repository — `git grep`
  over `api-sheriff`, `integration-tests` and `benchmarks` returns hits only for `RotationResult`
  (the return type, `TokenRefreshCoordinator.java:31/164/198/255`) and for `RefreshFlow` itself.
  TokenSheriff PLAN-01 deliverable 2 proposes driving
  `TokenLifecycleManager.refresh(sessionId, metadata, refreshFlow, revocationClient, …)`, which is a
  layer this gateway does not use. A non-reproduction obtained only through that layer eliminates
  nothing here. Send this back: PLAN-01 must carry the two-argument
  `RefreshFlow.refresh(metadata, refreshToken)` form as its own enumerated case.
- Open regardless of the verdict: a **native-image-specific** engine defect. This project runs the
  engine in a native image with `--initialize-at-run-time=de.cuioss.sheriff.token.client.flow`
  (`application.properties:137`), which the library's JVM tests do not exercise — a case only this
  repository can reproduce.
- Overlaps with: nothing, conditionally — disjoint from PLAN-01/02/03 outright, and from PLAN-04
  unless the fixture needs a gateway.yaml `token_validation` entry. Re-check at outline and report,
  because it changes this plan's disjointness verdict.
- Adjacent to: the `TokenRefreshCoordinator` unit suite in `api-sheriff/src/test/.../bff/refresh/`.
  Untouched — it covers the coordinator's logic; this plan adds the integration layer above it.
- Adjacent to: `StepUpCoordinator`, which shares the session-mediation surface. Untouched.
- Follow-up: if the reproduction captures an exception, a fix plan is staged by a later `analyze` from
  this plan's landing. Deliberately NOT pre-staged — a fix cannot be scoped before the defect is
  characterised.

## Hand-Off Command

```text
/plan-marshall task="implement .plan/local/orchestrator/deployment-configurability/plans/PLAN-05-bff-refresh-integration-coverage.md"
```

## Write-Boundary

Touches only repository source and tests. Creates and edits NO file under
`.plan/local/orchestrator/` other than its own `inbox/{sender}-{seq}` message; reports outcome
through its PR and that message. See `orchestration-model.md` § Ledger Write-Boundary.
