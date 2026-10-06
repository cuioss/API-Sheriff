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

**Six deliverables — at the split guard.** If D3–D6 crowd each other in execution, **split the
component conversions out and keep D1+D2 intact**; the ADR and the gate retirement are the part that
unblocks everything else and they must land together.

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

5. **`bff/session/**` → Quarkus session mechanisms, or a recorded justification for keeping it.**
   **OBSERVED**: `InMemorySessionStore` is a `final class` holding three plain `HashMap`s
   (`byId`, `bySid`, `bySub`), with lazy expiry on `resolve` plus `sweepExpired`, and **no CDI
   annotation of any kind**. Every mutating and reading method is `synchronized`, `create` reclaims
   expired entries when at capacity, and an expiry-versus-capacity regression test pins that model.
   Alongside it: `SessionStore`, `SessionRecord`, `SessionBinding`, `ServerSessionBinding`,
   `SessionCookieCodec`.
   Answer the operator's question directly — **is this a re-implementation of session management?**
   Partly yes and the design is deliberate; the plan must determine whether Quarkus/Vert.x session
   handling covers the **actual requirements**, which include back-channel logout by `sub` and by
   `sid` in O(1). **That indexing requirement is the thing to test any replacement against** — a
   generic session store that cannot destroy every session for a subject on a back-channel logout is
   not a substitute, and dropping that capability to adopt a framework API would be a security
   regression.
   - `bff/logout/BackchannelLogoutReceiver` is the live caller of `SessionBinding#destroyBySid` and
     `#destroyBySub`; any replacement must keep O(1) destroy for it.
   - `bff/runtime/SessionIdentity` is a session-derived portal DTO outside `bff/session/**`; it is
     part of the surface a replacement has to serve.
   - If the store is kept, inherit its existing concurrency model and regression test rather than
     re-deriving them. If it is replaced, state the replacement's concurrency model explicitly.

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
  and destroy-by-`sid`. **Confirm/refute artifact**: `SessionStore`'s interface and the back-channel
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

## Expected Surface

- OBSERVED: `doc/adr/` — one NEW superseding ADR; `0005-module-structure.adoc` marked superseded
- OBSERVED: `api-sheriff/src/test/java/de/cuioss/sheriff/gateway/arch/FrameworkAgnosticArchTest.java` — D2
- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/bff/runtime/JsonWriter.java` — D3
- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/config/load/**` — D4, whole package
- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/bff/session/**` — D5, all six types
- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/bff/logout/BackchannelLogoutReceiver.java` — D5: the live caller of `SessionBinding#destroyBySid` / `#destroyBySub`, which relies on the O(1) guarantee D5 must preserve
- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/bff/runtime/SessionIdentity.java` — D5: a session-derived portal DTO outside the `bff/session/**` glob
- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/tls/**` — D6, review only
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
- **NAMED LINE ITEMS** — six named deliverables; an outline that collapses D6's `tls/` review into
  "no change needed" without reporting is a finding.
- **NEVER ADD DEPENDENCIES WITHOUT EXPLICIT USER APPROVAL** — binds D3 (dsl-json) directly. **ASK.**
- **TEST THE DELIVERED ARTIFACT** and **A GREEN SUITE IS NOT EVIDENCE** — a substitution
  that compiles and passes construction tests proves nothing about behaviour parity.
- **THE BUILD FAILS ON ANY COMPILER WARNING**, and this plan is the most exposed to it: D3–D6 swap
  hand-rolled infrastructure for platform APIs, and one that is deprecated at the pinned version
  fails the build. Budget for migrating off it, not for suppressing it.
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
