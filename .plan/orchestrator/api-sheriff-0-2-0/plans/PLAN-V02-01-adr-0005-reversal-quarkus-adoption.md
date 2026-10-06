# PLAN-V02-01: supersede ADR-0005 — adopt Quarkus/Jakarta mechanisms instead of hand-rolled equivalents

epic: api-sheriff-0-2-0
workstream: WS-01

> Staged plan spec — one shippable unit of work, ready for `/plan-marshall` hand-off.
> The orchestrator EMITS the command below; it never launches the plan inline.
> Source: an operator code review and the operator's explicit decision: **"Revert the ADR. If there
> is something available (quarkus) use that."**

## Objective

Four of the operator's review findings — a hand-rolled JSON writer, a hand-rolled environment-variable
resolver, a hand-rolled session store, and the near-absence of Jakarta annotations — are **not four
independent oversights. They are one architectural decision, working as designed.**

**ADR-0005 (`doc/adr/0005-module-structure.adoc`, status Accepted)** mandates a framework-agnostic
core: *"framework-agnostic packages carry no import of `io.quarkus..`, `io.vertx..`"*, enforced by an
ArchUnit gate. `EnvSecretResolver`'s own javadoc states the motive verbatim — *"keeping the engine
framework-agnostic"*. Under that ADR, using the Quarkus mechanism was **prohibited**, and the
hand-rolled equivalent was the compliant choice.

**The operator has decided to reverse that ADR.** This plan supersedes ADR-0005, retires the gate
that enforces it, and replaces hand-rolled infrastructure with platform mechanisms **wherever a real
one exists** — the qualifier is load-bearing, and deliverable 1 decides where it holds.

## Why this is one plan and not four

Each component individually looks like "replace X with the Quarkus equivalent". But every one of them
is currently *forbidden* from importing Quarkus by a gate that fails the build. Change any one of
them without settling the ADR and the arch-gate fails; settle the ADR four times and the rationale
forks. **The ADR verdict is the shared precondition, so it is deliverable 1 and everything else is
downstream of it.**

## Deliverables

**Seven deliverables — past the split guard, proceeding unsplit on operator instruction.** D7, the
session-management security audit, is the most important deliverable of this plan and must not be
trimmed to fit. If D3–D6 crowd each other in execution, **split the component conversions out and
keep D1+D2 intact**; the ADR and the gate retirement are the part that unblocks everything else and
they must land together. D5 and D7 stay together: the audit runs over the store D5 leaves.

1. **Supersede ADR-0005 with a new ADR.** **Write a new ADR that supersedes it — do not edit 0005 in
   place.** ADR-0005 records a genuine trade with consequences that were true when written; the audit
   value is in the supersession being visible. The new ADR must state: what changed since 0005 (the
   module set grew; no second consumer materialised; the agnostic seam cost more than it returned),
   what the new rule is, and **which components stay hand-rolled and why** — the reversal is not
   "delete everything custom", it is "prefer the platform where the platform actually offers it".
   Derive the ADR ordinal from `doc/adr/` on the branch at write time, checking `main` and open
   branches; `AdrOrdinalUniquenessContractTest` fails the build on a duplicate.

2. **Retire `FrameworkAgnosticArchTest` and the ADR-0005 gate.**
   The test was hardened with a vacuity guard (`everyAgnosticPackageResolvesToClasses`: a protected
   package that resolves to no classes fails), a dropped `allowEmptyShould`, and three added framework
   packages. That work is superseded, not wasted, and the deletion should say so. **Check whether any
   part of it generalises** — the vacuity guard is a reusable idea independent of ADR-0005 and may be
   worth keeping against a different rule rather than deleted with it.

   The non-gating OpenRewrite dirty-tree report job in `.github/workflows/maven.yml` is unrelated to
   this gate. Do not turn it into a hard gate as a side effect of this retirement.

3. **`bff/runtime/JsonWriter.java` → the platform JSON mechanism.**
   **OBSERVED**: a `public final class`, a pattern-matching switch writing into a `StringBuilder`,
   with call sites in `BffRuntime` and in `GatewayEdgeRoute.problemBody` (the problem+json body).
   The payloads are maps, collections, strings, numbers, booleans and null, which the Jackson already
   on the classpath (`quarkus-resteasy-jackson`) serialises; note that `JsonWriter` maps NaN and
   infinity to `null` and unknown types through `String.valueOf`. Replace with the Quarkus-provided
   serializer.
   **⚠ DEPENDENCY APPROVAL REQUIRED**: the operator suggested **dsl-json** for fixed-DTO shapes.
   `CLAUDE.md` § Dependency Management says *"Never add dependencies without explicit user
   approval"* — so **if the answer is a dependency Quarkus does not already bring, STOP and ask.**
   Prefer what the Quarkus BOM already supplies. Establish first **what these payloads actually are**:
   if they map to fixed DTOs, a record + the platform serializer is the answer; if they are dynamic
   maps, that is a different answer and the plan should say so.

4. **`config/load/EnvSecretResolver.java` → smallrye-config, if it genuinely duplicates it.**
   **OBSERVED**: wraps `System::getenv` behind an injectable lookup, implements `${VAR}` placeholder
   substitution, and raises `MissingVariableException` / `MalformedPlaceholderException`.
   SmallRye Config provides expression expansion with defaults natively — **but re-grounding found the
   semantics do not match**: the resolver has its own `:-` default syntax with no escape, refuses a
   malformed placeholder, reports every missing name at once, calls back on defaulted names, reads the
   environment only, and runs on the pre-boot `--validate-config` path before Quarkus (and so SmallRye)
   exists. The expected outcome is therefore *keep it, with that reason recorded*; overturn it only on
   evidence the pre-boot path can be served.
   **Analyze the whole `config/load` package as the operator asked, not just this class** — and be
   honest about the residue: this resolver runs against a **YAML document the gateway loads itself**,
   which is not the same lifecycle as MicroProfile Config property resolution. `ConfigLoader` also
   carries an environment-variable coercion arm (ADR-0052); any SmallRye mapping must cover it.
   **If the semantics do not actually match, say so and keep it** — a forced adoption that changes
   when-and-how secrets resolve is a security-relevant regression, not a simplification.

   The package now also serves the offline `--validate-config` path (ADR-0061, #387), which runs the
   boot's own pipeline through `config/boot/ConfigBootPipeline`; a replacement must keep that path
   giving the same verdict as the boot. Three public overloads lost their last production caller
   with #387 and are removed here under the pre-1.0 rules unless the analysis finds a use:
   `ConfigLoader.load()`, the three-argument `TopologyResolver.resolve(...)`
   (`config/topology/TopologyResolver.java`) and `EnvSecretResolver.resolve(String)`.

5. **`bff/session/**` — keep the hand-rolled store, record why, and close its three gaps.**

   **Decided with the operator (2026-10-06).** The server-mode session store stays custom. The two
   platform alternatives were examined and are rejected; the ADR from D1 records both, with these
   reasons:
   - **`@SessionScoped` CDI beans.** In Quarkus this scope is backed by a Servlet `HttpSession` and
     exists only with `quarkus-undertow`. The gateway's traffic is handled by one Vert.x catch-all
     route (`GatewayEdgeRoute.registerRoutes(@Observes Router)`), which never passes a servlet, so
     the session context would never be active. Making it active means moving the edge onto
     servlets — the streaming, HTTP/2, gRPC-trailer, WebSocket and passthrough design this
     gateway is built on.
   - **Vert.x Web `SessionStore` (`LocalSessionStore`, `ClusteredSessionStore`).** It would supply
     an idle timeout and a periodic reaper, but it has no size cap, no absolute lifetime and no
     lookup by `sid` or `sub`, and its reaper deletes without a callback, so an index beside it goes
     stale. A clustered store needs a cluster manager or a Redis/Infinispan store module — new
     dependencies and infrastructure. The adaptation would keep most of the custom code anyway.
     **Verify at outline:** whether a `put` after a `delete` re-creates a session in the Vert.x
     store. It is the one unverified point in this rejection; record the answer in the ADR.
   - Speed of destroying sessions by `sid` or `sub` is **not** a criterion. The requirement is that a
     back-channel logout ends every matching session promptly.

   **Close the three gaps the current store has** (each a known session-management weakness):
   - **A new session ID on every privilege change.** Step-up and scope widening
     (`bff/reserved/CallbackEndpoint`) rewrite the record under the same `sessionId` with a new
     `acr` and new tokens, and set no new cookie. Mint a new ID, store the record under it, delete
     the old one, and set the new cookie, in one step that keeps the write-back guarantee.
   - **An idle timeout** beside the existing absolute lifetime (`ttl_seconds`). Track the last
     access, add a configuration key with a secure default, and document it.
   - **Expired sessions dropped promptly.** Today an expired record — with its access, refresh and
     ID tokens — stays in memory until it is looked up or the store reaches `max_sessions`. Add a
     periodic sweep so tokens are not held longer than the session lives.

   **Keep what is right today**, and inherit its tests rather than re-deriving them: 256-bit
   `SecureRandom` IDs; the `__Host-` cookie with `Secure; HttpOnly; SameSite=Lax; Path=/`; a fresh ID
   at login; the single-monitor concurrency model and `replaceIfPresent`, which stop a logged-out
   session being written back; the fail-closed `max_sessions` cap.
   - `bff/logout/BackchannelLogoutReceiver` is the live caller of `SessionBinding#destroyBySid` and
     `#destroyBySub`; logout by `sid` and `sub` must still end every matching session.
   - `bff/runtime/SessionIdentity` is a session-derived portal DTO outside `bff/session/**`; it is
     part of the surface the changes have to serve.

   **Multi-replica deployments.** Server mode keeps its sessions in one process. Several replicas are
   supported through **sticky sessions in the orchestration layer** (load balancer or ingress
   affinity on the session cookie); state this in the operator documentation together with its
   consequence — a replica restart or failover ends the sessions it held. Cookie mode is the
   stateless alternative, once every replica shares the sealing key (`PLAN-V02-20`).

6. **CDI/Jakarta annotation adoption across the converted surface, and `tls/` reviewed.**
   **OBSERVED**: about 16 files carry `@ApplicationScoped` and roughly 59 CDI annotations exist under
   `api-sheriff/src/main/java` — the thin edge layer ADR-0005 prescribed. Re-count at outline. With
   the ADR superseded, bring the converted components into CDI properly rather than constructing them
   by hand in producers.
   **Also review the `tls/` package as the operator asked**: `ClientHelloSniParser` hand-parses a TLS
   ClientHello for SNI. **Set expectations honestly — this one is the least likely to have a drop-in
   platform replacement**: the parser exists to peek SNI *before* termination for L4 passthrough
   (ADR-0017), which is precisely the case a TLS-terminating framework API does not cover. Vert.x
   exposes SNI on a terminated connection; that is a different thing. **Review it, report the finding,
   and do not force a replacement that changes the passthrough semantics.** If the class still carries
   open Sonar findings, check whether other work already owns them before fixing them here.

   **Optional adoption, decided at outline.** `RouteRuntimeAssembler` allocates a per-tuple
   `HttpClient` and a resilience `Guard` for `WEBSOCKET` routes that no longer read them (epic Open
   Defect 12). It is a boot-time assembly cleanup adjacent to this deliverable but not the same
   subject. Adopt it only if D6 touches that assembler anyway; otherwise report that it stays unowned.

7. **A very thorough security audit of session management — the most important deliverable of
   this plan.** Run it after D5, over the code as it then stands, and over **both** session modes
   (server and cookie) and every BFF path that creates, reads, changes or ends a session: login,
   callback, step-up and widening, refresh, the info endpoint, RP-initiated logout, back-channel
   logout, CSRF, and the proxying of session-protected routes.

   **Test against every known successful attack class, not a sample.** Build the catalogue from
   OWASP ASVS V3 (Session Management) and the OWASP Session Management Cheat Sheet, RFC 6265bis, the
   OAuth 2.0 Security Best Current Practice (RFC 9700), OpenID Connect Back-Channel Logout 1.0 and
   RP-Initiated Logout 1.0, and the IETF draft on OAuth for browser-based apps (BFF pattern); add
   published CVEs against comparable gateways and BFF libraries. At minimum it covers:
   - **ID strength and handling** — predictability, enumeration, brute force, timing on lookup, IDs
     in URLs, logs, `Referer` or error bodies.
   - **Fixation and rotation** — at login, on step-up and widening, on refresh, and across the two
     modes.
   - **Theft and replay** — cookie theft and reuse, replay after logout and after expiry, and the
     cookie-mode case where a stolen sealed cookie cannot be revoked server-side (state the residual
     and its bound).
   - **Cookie-level attacks** — cookie tossing and shadowing, `__Host-` prefix bypass, cookie-jar
     overflow evicting the session cookie, cookie bombs, `SameSite=Lax` gaps on top-level GETs,
     downgrade to plain HTTP, `Set-Cookie` on cacheable responses and missing `Cache-Control`.
   - **Cross-site attacks on the session** — CSRF (including login CSRF), session riding through the
     proxied routes, clickjacking of step-up, CORS interplay.
   - **Flow attacks that end in a session** — authorization-code injection, mix-up (RFC 9207 `iss`),
     PKCE and `state`/`nonce` misuse, PAR and DPoP binding gaps (ADR-0058), open redirects in return
     and post-logout URLs.
   - **Token lifecycle inside the session** — refresh-token theft and rotation-reuse handling,
     concurrent refresh races, a refused token response discarding instead of revoking, tokens kept
     after the session ends.
   - **Logout correctness** — back-channel logout token validation (`iss`, `aud`, `iat`, `jti`
     replay, `events`, no `nonce`), forged or replayed logout tokens, logout that misses a session,
     and races between logout and refresh or widening.
   - **Lifetime and capacity** — idle and absolute expiry, clock skew, store exhaustion and login
     flooding (coordinate with `kidicap-gateway-requirements` PLAN-21, which owns pending-login
     flooding), memory held by expired sessions.
   - **Cookie-mode cryptography** — AES-GCM nonce uniqueness, key generation and rotation,
     tampering, truncation, cross-session and cross-gateway reuse of a sealed payload.
   - **Multi-replica** — sticky-routing failure modes and forged affinity cookies.

   For each class record: the attack, the code path that defends against it, the test that proves
   the defence (add one where none exists), and a verdict — defended, partially defended, or
   vulnerable. **Fix every confirmed vulnerability in this plan** where the fix fits its surface;
   otherwise stop and put it to the operator. **Disclosure:** following project policy, a finding
   that is not yet fixed is reported to the operator only and is never described in public, tracked
   text — PR descriptions, commit messages, issues, ADRs or the epic ledger — until it is fixed.

## Claim Labels

- **OBSERVED** (first-party): ADR-0005 exists, is status **Accepted**, and mandates the
  agnostic seam with the quoted no-import rule; `FrameworkAgnosticArchTest` enforces it via
  `noClasses().should().dependOnClassesThat().resideInAnyPackage(FRAMEWORK_PACKAGES)` and excludes
  `routing` by design; `EnvSecretResolver`'s javadoc says "keeping the engine framework-agnostic";
  `JsonWriter` is a `public final class` with two call sites; `InMemorySessionStore` is a `final class` with three
  `HashMap`s, synchronized methods and no CDI annotation; about 16 files carry `@ApplicationScoped`
  with roughly 59 CDI annotations corpus-wide; `ClientHelloSniParser` hand-parses ClientHello.
  - verdict: contradicted | checked_at: 1a20edade64aee1cb92fbddec7352a920fb5b46d | by: api-sheriff-0-2-0/cleanup | rescoped: yes | evidence: JsonWriter is now a public final class with a second call site in GatewayEdgeRoute.problemBody; the rest holds (ADR-0005 Accepted, FrameworkAgnosticArchTest, InMemorySessionStore three HashMaps, 16 @ApplicationScoped files); spec re-scoped
- **HYPOTHESIS (verify-at-outline)**: that a Quarkus-supplied JSON serializer covers `JsonWriter`'s
  payload shapes. **Confirm/refute artifact**: `JsonWriter`'s call sites and the actual payloads.
  - verdict: corroborated | checked_at: 1a20edade64aee1cb92fbddec7352a920fb5b46d | by: api-sheriff-0-2-0/cleanup | rescoped: n/a | evidence: call sites in BffRuntime and GatewayEdgeRoute.problemBody serialise only Map/Collection/String/Number/Boolean/null and quarkus-resteasy-jackson is on the classpath; JsonWriter maps NaN/Infinity to null
- **HYPOTHESIS (verify-at-outline)**: that SmallRye expression expansion matches `EnvSecretResolver`'s
  semantics. **Confirm/refute artifact**: the resolver's tests plus the YAML-load call path.
  **Explicitly refutable — and a refutation is a valid, expected outcome.** Re-grounding refuted it
  (D4 now records why); the outline confirms and records the keep decision.
  - verdict: contradicted | checked_at: 1a20edade64aee1cb92fbddec7352a920fb5b46d | by: api-sheriff-0-2-0/cleanup | rescoped: yes | evidence: EnvSecretResolver has its own :- default syntax, malformed-placeholder refusal, all-missing-names report and defaulted-name callback, is env-only and runs on the pre-boot ConfigValidationCommand path before SmallRye exists; D4 re-scoped to keep with reason
- **HYPOTHESIS (verify-at-outline)**: that a Quarkus session mechanism satisfies O(1) destroy-by-`sub`
  and destroy-by-`sid`. Superseded as a criterion on 2026-10-06 (see D5): speed is not the
  requirement, prompt logout of every matching session is. **Confirm/refute artifact**: `SessionStore`'s interface and the back-channel
  logout call path. **Likely to be refuted; that is fine and must be reported, not worked around.**
  Re-grounding refuted it from the API (Quarkus/Vert.x session stores index by session id only); D5's
  expected outcome is *keep, with the indexing requirement as the recorded reason*. Confirm against the
  resolved Quarkus artifact at outline, which the re-grounding did not read.
  - verdict: contradicted | checked_at: 1a20edade64aee1cb92fbddec7352a920fb5b46d | by: api-sheriff-0-2-0/cleanup | rescoped: yes | evidence: BackchannelLogoutReceiver needs destroyBySid/destroyBySub, served O(1) by InMemorySessionStore secondary indexes; Quarkus/Vert.x session stores index by id only (judged from the API, not a jar); D5 re-scoped to keep with reason
- **OBSERVED (absence)**: this spec does **not** establish which serializer the Quarkus BOM
  supplies here, what `ServerSessionBinding` or `SessionCookieCodec` do beyond existing, what the
  `tls/` package holds beyond `ClientHelloSniParser`'s role, or whether retiring the arch-gate
  breaks any other test.
  - verdict: corroborated | checked_at: 1a20edade64aee1cb92fbddec7352a920fb5b46d | by: api-sheriff-0-2-0/cleanup | rescoped: n/a | evidence: the spec states exactly these absences; tls/ holds about nine CDI classes
- OBSERVED: a step-up or widening keeps the session ID — read at `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/bff/reserved/CallbackEndpoint.java` § the widening merge (`.sessionId(live.sessionId())`, persisted through `SessionBinding#persist`, which sets no new cookie), on `origin/main` at `1a20edad`
- OBSERVED: the session has an absolute lifetime only — read at `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/config/model/OidcConfig.java` § `Session` (`ttlSeconds`, default 3600; no idle key)
- OBSERVED: expired sessions are removed only on lookup or when the store reaches its bound — read at `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/bff/session/InMemorySessionStore.java` § `resolve` and `create` (`sweepExpired` has no other caller)
- HYPOTHESIS: in Vert.x Web 4.5.33 a `put` after a `delete` re-creates the session — confirm/refute at `io.vertx.ext.web.sstore.impl.LocalSessionStoreImpl` § `put` in the resolved jar (verify-at-outline); decides one reason of the D5 rejection

## Expected Surface

- OBSERVED: `doc/adr/` — one NEW superseding ADR; `0005-module-structure.adoc` marked superseded
- OBSERVED: `api-sheriff/src/test/java/de/cuioss/sheriff/gateway/arch/FrameworkAgnosticArchTest.java` — D2
- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/bff/runtime/JsonWriter.java` — D3
- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/config/load/**` — D4, whole package
- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/bff/session/**` — D5, all six types
- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/bff/logout/BackchannelLogoutReceiver.java` — D5: the live caller of `SessionBinding#destroyBySid` / `#destroyBySub`, which relies on the O(1) guarantee D5 must preserve
- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/bff/runtime/SessionIdentity.java` — D5: a session-derived portal DTO outside the `bff/session/**` glob
- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/tls/**` — D6, review only
- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/bff/reserved/CallbackEndpoint.java` — D5: ID rotation on step-up and widening
- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/config/model/OidcConfig.java`, `api-sheriff/src/main/resources/schema/gateway.schema.json`, `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/quarkus/BffRuntimeProducer.java` — D5: idle-timeout key and the periodic sweep
- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/bff/**`, `api-sheriff/src/test/**`, `integration-tests/**` — D7: the audit's subject and its proving tests; fixes land where the audit finds them
- OBSERVED: `doc/user/bff-session.adoc`, `doc/user/bff-cookie.adoc`, `doc/security-threat-model.adoc` — D5 (sticky sessions, idle timeout), D7 (fixed findings only)
- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/config/topology/TopologyResolver.java` — D4: the unused three-argument `resolve(...)` overload
- HYPOTHESIS: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/ConfigValidationCommand.java` — only if D1's verdict adopts Quarkus command mode and the `--validate-config` flag moves onto it (ADR-0061) (verify-at-outline)
- HYPOTHESIS: `api-sheriff/pom.xml` — **only if** a dependency change is approved; otherwise untouched
- OBSERVED: `doc/architecture.adoc` and the three-layer docs for every converted component
- OBSERVED (absence, deliberate): **no gateway behaviour change.** This is an infrastructure
  substitution. A changed status code, a changed header, or a changed session lifetime is a finding.

## Dependencies and Sequencing

- Depends on: none.
- **RUNS ALONE.** Retiring an arch-gate mid-flight changes the gate set every other concurrent plan
  is verified against. The one carve-out: a plan that ships no Java is unaffected by the arch gate
  and may run beside it.
- `PLAN-V02-14` landed first (#387) and chose a flag on the gateway binary without this plan's
  verdict. ADR-0061 asks for a revisit if this plan adopts Quarkus command mode: decide it here.

## Standing Epic Clauses

- **THREE-LAYER DOCS** in the same PR.
- **SONAR ZERO-FINDINGS** — red is a HARD STOP.
- **NAMED LINE ITEMS** — seven named deliverables; an outline that collapses D6's `tls/` review into
  "no change needed" without reporting is a finding.
- **NEVER ADD DEPENDENCIES WITHOUT EXPLICIT USER APPROVAL** — binds D3 (dsl-json) directly. **ASK.**
- **TEST THE DELIVERED ARTIFACT** and **A GREEN SUITE IS NOT EVIDENCE** — a substitution
  that compiles and passes construction tests proves nothing about behaviour parity.
- **THE BUILD FAILS ON ANY COMPILER WARNING**, and this plan is the most exposed to it: D3–D6 swap
  hand-rolled infrastructure for platform APIs, and one that is deprecated at the pinned version
  fails the build. Budget for migrating off it, not for suppressing it.
- **SECURITY FINDINGS STAY PRIVATE UNTIL FIXED** — binds D7: an unfixed finding goes to the operator only.
- **RE-GROUND AT OUTLINE** — counts and line positions in this spec are leads; re-read the code.

## Finalize Boundary — the plan STOPS at the merge

The plan owns everything through the merge, then REPORTS AND STOPS. The post-merge aftermath is the
**orchestrator's**.

## Hand-Off Command

```text
/plan-marshall task="implement .plan/orchestrator/api-sheriff-0-2-0/plans/PLAN-V02-01-adr-0005-reversal-quarkus-adoption.md" plan_id=plan-v02-01-adr-0005-reversal-quarkus-adoption
```

**The explicit `plan_id` is load-bearing — do not drop it.**

## Write-Boundary

The plan implementing this spec touches only its own repository source and tests. It creates and
edits NO file under `.plan/orchestrator/` other than its own `inbox/{sender}-{seq}` message, and
reports its outcome through its PR and that message.
