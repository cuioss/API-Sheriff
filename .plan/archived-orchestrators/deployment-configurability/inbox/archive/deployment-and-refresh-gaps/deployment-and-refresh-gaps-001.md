envelope_version=1
sender_type=orchestrator
sender_id=deployment-and-refresh-gaps
epic=deployment-configurability
kind=finding
created=2026-08-27T13:34:43Z

# Cross-repo pairing: the refresh diagnostic runs in TokenSheriff first

## What was done

A second orchestrator epic now exists in `/Users/oliver/git/TokenSheriff`, slug
**`deployment-and-refresh-gaps`**. Its `PLAN-01-refresh-integration-coverage` tests **the same
reported failure** this epic's `PLAN-05-bff-refresh-integration-coverage` targets — but one layer
down, at the library level.

By operator decision, **TokenSheriff's PLAN-01 runs FIRST**, and this epic's PLAN-05 waits on its
verdict.

## Why the two are connected

API Sheriff's BFF does not implement token refresh itself — it drives Token-Sheriff's client engine.
`api-sheriff/src/main/resources/application.properties` records the wiring in its native-image block:
*"Deliverable 16 wired the engine into the live edge (BffRuntimeProducer -> AuthorizationCodeFlow ->
FlowContext.create)"*, and it forces GraalVM runtime initialization for the whole
`de.cuioss.sheriff.token.client.flow` package precisely because those classes are now reachable.

So a refresh exception observed while using API Sheriff can originate in **either** repository:

| Layer | Owner | Tested by |
|---|---|---|
| BFF session mediation — `TokenRefreshCoordinator` | API Sheriff | this epic's PLAN-05 |
| Refresh engine — `RefreshFlow`, `TokenLifecycleManager`, `RefreshScheduler` | Token-Sheriff | TokenSheriff `deployment-and-refresh-gaps` PLAN-01 |

Until now **neither repository could tell them apart**, because Token-Sheriff's production refresh
path had zero integration coverage against a real identity provider: a symbol grep for `RefreshFlow`
/ `TokenLifecycleManager` / `RefreshScheduler` / `RotationResult` across its integration-test module
returned zero hits, and the tests that do exercise `grant_type=refresh_token` hand-roll their own
`java.net.http.HttpClient` POST at Keycloak — testing Keycloak, not the engine.

## Why TokenSheriff goes first

The library test is the **elimination step**, and it is cheaper and more decisive per unit effort:

- **If it reproduces** — the defect is localized to the engine without anyone touching this gateway.
  PLAN-05 then becomes a regression guard rather than a search, and its matrix can be narrowed.
- **If it does not reproduce** across a bounded, enumerated matrix — the engine is eliminated and
  **this BFF's wiring becomes the prime suspect**, which sharpens PLAN-05 rather than duplicating it.

Alternatives considered and declined: running this epic's PLAN-05 first (closest to the reported
symptom, but a negative result eliminates nothing), and running both concurrently (fastest
wall-clock, but spends two plans of effort where one may settle it).

## What this epic must do

1. **Do not start PLAN-05 before reading TokenSheriff PLAN-01's verdict.** The two ledgers are in
   different repositories and share no state — nothing carries the verdict across automatically. It
   must be read by hand from
   `TokenSheriff/.plan/local/orchestrator/deployment-and-refresh-gaps/landings/PLAN-01.md`.
2. **Re-scope PLAN-05 on that verdict** — engine implicated ⇒ regression guard; engine cleared ⇒
   focus the matrix on BFF-specific wiring (session store, cookie rotation, the `RefreshExchange`
   implementation in `BffRuntimeProducer`).
3. **Carry one open thread regardless of the verdict.** If PLAN-01 finds nothing at JVM level, a
   **native-image-specific** engine defect is still open — this project runs the engine in a native
   image with forced runtime initialization for that package, which the library's JVM tests do not
   exercise. That is the next place to look, and it is a case only this repository can reproduce.

## One assumption that the whole pairing rests on

TokenSheriff PLAN-01 carries this as a labelled HYPOTHESIS, and it is stated here so this side can
refute it independently: **that this BFF's refresh leg actually reaches `RefreshFlow`.** If the BFF
refreshes through some other path, PLAN-01's verdict proves nothing about API Sheriff and the
ordering above is void. The confirm/refute artifact is this repository's
`api-sheriff/src/main/java/de/cuioss/sheriff/gateway/quarkus/BffRuntimeProducer.java` § its refresh
wiring. Settling it cheaply, here, before PLAN-01 lands would de-risk the whole experiment.

## Also relevant, non-blocking

TokenSheriff's own container health-check plan was **withdrawn** — that project delivers no
container, so the question is not real there. It **is** real here: this repository publishes a
distroless main image to GHCR, and this epic's `PLAN-01-distroless-health-check` owns it. There is
consequently no cross-repo mechanism to coordinate on that topic any more; treat this epic's PLAN-01
as the sole owner and do not wait on TokenSheriff for it.

The outbound hostname-verification work still IS shared: TokenSheriff's PLAN-03 and this epic's
PLAN-04 hit the identical `cui-http` limitation (`HttpHandlerBuilder` exposes no
hostname-verification method; `HttpHandler` builds a `java.net.http.HttpClient` whose `SSLParameters`
set only `setProtocols(...)`). If either plan's mechanism decision lands on an upstream `cui-http`
builder knob, that change serves both projects and must be made once — coordinate before implementing.
