# PLAN-V02-01: supersede ADR-0005 — adopt Quarkus/Jakarta mechanisms instead of hand-rolled equivalents

epic: api-sheriff-0-2-0
workstream: WS-01
track: **POST-0.1.0** — gated behind the release cut (PLAN-08B in `api-sheriff-roadmap`).

> Staged plan spec — one shippable unit of work, ready for `/plan-marshall` hand-off.
> Source: operator code review 2026-08-01, plus the operator's explicit decision
> (`AskUserQuestion`, 2026-08-01): **"Revert the ADR. If there is something available (quarkus) use
> that."** The orchestrator EMITS the command below; it never launches the plan inline.

> **Renumbered 2026-08-04.** This spec was `PLAN-38-adr-0005-reversal-quarkus-adoption.md` in the retired `api-sheriff-next`
> backlog epic. In-body references to other `PLAN-NN` numbers were deliberately **not**
> rewritten: many point at `api-sheriff-roadmap` plans that keep their numbers. Resolve any
> such reference through the renumbering map in this epic's `epic.md`.

## Re-Grounded 2026-08-08 at `963e422` (== `origin/main`, clean tree)

Epic-wide re-grounding pass at `decompose`. **This section outranks any conflicting line below it.**

**CONFIRMED, first-party** — `doc/adr/0005-module-structure.adoc` exists (175 lines) and its Status
block still reads `Accepted`; `FrameworkAgnosticArchTest.java` present (167 lines);
`JsonWriter.java` still **exactly 126 lines**; `EnvSecretResolver.java` present (186 lines);
all six `bff/session/` types present; `tls/ClientHelloSniParser.java` present.

**REFUTED — D5's concurrency premise.** The spec says the three `HashMap`s are **unsynchronised**.
They are not: every mutating and reading method now carries `synchronized`
(`InMemorySessionStore.java`:76 `create`, :105 `resolve`, :120 `destroyById`, :126 `destroyBySid`,
:132 `destroyBySub`, :138 `sweepExpired`, :153 `size`). `api-sheriff-roadmap` PLAN-49 (#167,
`0559871`) supplied that model, plus opportunistic-at-capacity reclamation (`sweepExpired` called at
:85 inside `create`) and a `removeInternal` de-index at :95 before `byId.put` at :96.
**Consequence**: D5's instruction *"whatever replaces or keeps them must state the concurrency
model explicitly"* is already discharged for the *keep* branch — inherit PLAN-49's model and its
expiry-vs-capacity regression test rather than re-deriving them. The class is still a `final class`
with three plain `HashMap`s and still carries **no CDI annotation**, so the rest of the claim holds.

**STALE COUNTS — D6.** "only 9 files carry `@ApplicationScoped` and 33 CDI annotations exist
corpus-wide" is now **11 files / 45 annotation occurrences** across `api-sheriff/src/main/java`.
Re-count at outline; do not scope D6 on the spec's numbers.

**DISCHARGED.**
- The **HARD GATE has opened** — `api-sheriff-roadmap` is `phase: closed` and relocated to
  `.plan/local/archived-orchestrators/`. The PLAN-08B D2 re-scope note below is moot: 0.1.0 **and**
  0.1.1 have both shipped.
- **ADR numbering**: "PLAN-31B is taking 0025" is stale — `0025` shipped (server-TLS surface). The
  corpus is contiguous `0001`–`0037`, so **`0038` is the next free number and it is genuinely free**
  (roadmap PLAN-50 landed `0035`). Still re-check against open branches at write time.

**RENUMBERING.** "Never concurrent with PLAN-39" means **PLAN-V02-02** (java-idiom-sweep) — still
binding, both sweep `api-sheriff/src/main/java/**`. "Depends on PLAN-36" refers to a shipped
`api-sheriff-roadmap` plan: read the landed diff, do not wait.

**Write-Boundary correction**: the section at the foot names `api-sheriff-next`, an epic that no
longer exists. The binding path is `.plan/local/orchestrator/api-sheriff-0-2-0/`.


## Re-Grounded (2) 2026-08-09 at `95dd566` — after four landings

`PLAN-V02-02`, `-03`, `-16` and `-17` have shipped. **This section outranks the 2026-08-08
re-grounding above it wherever they conflict.**

**EPIC-WIDE, AND NO SPEC BELOW KNOWS IT: THE BUILD NOW FAILS ON ANY COMPILER WARNING.**
`PLAN-V02-02` turned on `<showDeprecation>true</showDeprecation>` **and**
`<failOnWarning>true</failOnWarning>` reactor-wide (`pom.xml`:163, :178), so javac runs with
`-Werror` across all six modules. A deprecated API or an unchecked cast is now a **build failure**,
not a log line. Two consequences bind every plan:

1. **Answer such a failure by migrating off the warned construct.** `CLAUDE.md` states it directly:
   a `@SuppressWarnings` added to get back to green *"hollows the gate out while leaving it reporting
   success"*, and it collides with the Pre-1.0 rule forbidding deprecated code at all.
2. **The failure reaches the executor as a `warnings[]` row plus a `-Werror` `errors[]` row.** Read
   both arrays — the line number lives on the warning row.

**THIS PLAN IS THE MOST EXPOSED TO THE NEW GATE.** D3–D6 swap hand-rolled infrastructure for platform
mechanisms; any Quarkus/Jakarta API that is deprecated at the pinned version now **fails the build**
rather than warning. Budget for migration, not suppression.

**D2's arch-gate retirement now sits beside a NEW gate.** `PLAN-V02-17` added a **non-gating**
OpenRewrite dirty-tree report job (`.github/workflows/maven.yml`:185). It is deliberately
non-blocking; do not "tidy" it into a hard gate as a side effect of retiring
`FrameworkAgnosticArchTest`, and do not assume the two are related — they are not.

**Open Defect (12) is a candidate for this plan and the call is yours to make at outline.**
`RouteRuntimeAssembler` allocates a per-tuple `HttpClient` **and** a resilience `Guard` for
`WEBSOCKET` routes that no longer read them. It is an allocation/design cleanup on the boot-time
assembly path — adjacent to D6's CDI adoption, but **not** the same subject. Adopt it only if D6
touches that assembler anyway; otherwise report that it remains unowned rather than absorbing it.

**Still runs alone.** Confirmed by the 2026-08-09 landings: it was held `launched`-not-started while
V02-16 and V02-17 ran, and that carve-out (a plan shipping no Java is unaffected by the arch gate)
is now proven rather than theorised.

## Re-Grounded (4) 2026-09-24 at `05f6ee3` — after 18 commits (#343–#354, release 0.2.3)

Every claim was re-checked. Premises hold with line drift (`BffRuntime` sole `JsonWriter` call site now `:279`). **Two new D5 facts, both added to the Expected Surface:** `bff/logout/BackchannelLogoutReceiver` (+46 lines, with new `LogoutRejection`/`LogoutRejectionLog` per ADR-0051) is now the live caller of `SessionBinding#destroyBySid`/`#destroyBySub`, so any session-store replacement must keep O(1) destroy for it. `bff/runtime/SessionIdentity` (#343) is a new session-derived portal DTO outside `bff/session/**`. CDI footprint re-measured: 16 files carry `@ApplicationScoped`, ~59 CDI annotation occurrences (was 11/45 at `af63895`). `ConfigLoader` grew +110 lines (#341, ADR-0052) — D4's SmallRye mapping must cover the new env-coercion arm. No deliverable discharged.

**ADR numbering, corrected across the corpus:** `doc/adr/` now holds 55 records. `0053` is DUPLICATED (#348 renamed the portal ADR `0050`→`0053` while #346 claimed `0053` concurrently), and the next free ordinal is `0055`. Every earlier "next free is 0038/0050" line in this spec is stale. Re-derive the ordinal at write time, and prefer landing after `PLAN-V02-19`, which fixes the duplicate and adds an ordinal-uniqueness test.

## Objective

Four of the operator's review findings — a hand-rolled JSON writer, a hand-rolled environment-variable
resolver, a hand-rolled session store, and the total absence of Jakarta annotations — are **not four
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
   Take the next free ADR number, **re-verified at outline against `main` AND every open branch**
   (this epic's ADR numbering has gone stale six times; PLAN-31B is taking 0025).

2. **Retire `FrameworkAgnosticArchTest` and the ADR-0005 gate.**
   **⚠ Note before deleting**: this test was *hardened* on 2026-07-30 (`+73` lines in the steward
   range `9b8ec4a..b903526`) — a dropped `allowEmptyShould`, a new
   `everyAgnosticPackageResolvesToClasses` guard, three added framework packages. That work is
   superseded, not wasted, and the deletion should say so. **Check whether any part of it generalises**
   — the "protected package resolved to NO classes" vacuity guard is a *reusable* idea independent of
   ADR-0005 and may be worth keeping against a different rule rather than deleted with it.

3. **`bff/runtime/JsonWriter.java` → the platform JSON mechanism.**
   **OBSERVED**: 126 lines, package-private, already using Java 21 pattern-matching switch, writing
   into a `StringBuilder`. Replace with the Quarkus-provided serializer.
   **⚠ DEPENDENCY APPROVAL REQUIRED**: the operator suggested **dsl-json** for fixed-DTO shapes.
   `CLAUDE.md` § Dependency Management says *"Never add dependencies without explicit user
   approval"* — so **if the answer is a dependency Quarkus does not already bring, STOP and ask.**
   Prefer what the Quarkus BOM already supplies. Establish first **what these payloads actually are**:
   if they map to fixed DTOs, a record + the platform serializer is the answer; if they are dynamic
   maps, that is a different answer and the plan should say so.

4. **`config/load/EnvSecretResolver.java` → smallrye-config, if it genuinely duplicates it.**
   **OBSERVED**: wraps `System::getenv` behind an injectable lookup, implements `${VAR}` placeholder
   substitution, and raises `MissingVariableException` / `MalformedPlaceholderException`.
   SmallRye Config provides expression expansion with defaults natively.
   **Analyze the whole `config/load` package as the operator asked, not just this class** — and be
   honest about the residue: this resolver runs against a **YAML document the gateway loads itself**,
   which is not the same lifecycle as MicroProfile Config property resolution. **If the semantics do
   not actually match, say so and keep it** — a forced adoption that changes when-and-how secrets
   resolve is a security-relevant regression, not a simplification.

5. **`bff/session/**` → Quarkus session mechanisms, or a recorded justification for keeping it.**
   **OBSERVED**: `InMemorySessionStore` is a `final class` holding three plain `HashMap`s
   (`byId`, `bySid`, `bySub`), with lazy expiry on `resolve` plus an operator-driven `sweepExpired`,
   and **no CDI annotation of any kind**. Alongside it: `SessionStore`, `SessionRecord`,
   `SessionBinding`, `ServerSessionBinding`, `SessionCookieCodec`.
   Answer the operator's question directly — **is this a re-implementation of session management?**
   Partly yes and the design is deliberate; the plan must determine whether Quarkus/Vert.x session
   handling covers the **actual requirements**, which include back-channel logout by `sub` and by
   `sid` in O(1). **That indexing requirement is the thing to test any replacement against** — a
   generic session store that cannot destroy every session for a subject on a back-channel logout is
   not a substitute, and dropping that capability to adopt a framework API would be a security
   regression. Note the three `HashMap`s are **unsynchronised**; whatever replaces or keeps them must
   state the concurrency model explicitly.

6. **CDI/Jakarta annotation adoption across the converted surface, and `tls/` reviewed.**
   **OBSERVED**: only 9 files carry `@ApplicationScoped` and 33 CDI annotations exist corpus-wide —
   the thin edge layer ADR-0005 prescribed. With the ADR superseded, bring the converted components
   into CDI properly rather than constructing them by hand in producers.
   **Also review the `tls/` package as the operator asked**: `ClientHelloSniParser` hand-parses a TLS
   ClientHello for SNI. **Set expectations honestly — this one is the least likely to have a drop-in
   platform replacement**: the parser exists to peek SNI *before* termination for L4 passthrough
   (ADR-0017), which is precisely the case a TLS-terminating framework API does not cover. Vert.x
   exposes SNI on a terminated connection; that is a different thing. **Review it, report the finding,
   and do not force a replacement that changes the passthrough semantics.** Note it also owns two open
   Sonar findings (epic Open Defect 10) already assigned to PLAN-34 D7 — **do not double-fix**.

## Claim Labels

- **OBSERVED** (`b903526`, first-party): ADR-0005 exists, is status **Accepted**, and mandates the
  agnostic seam with the quoted no-import rule; `FrameworkAgnosticArchTest` enforces it via
  `noClasses().should().dependOnClassesThat().resideInAnyPackage(FRAMEWORK_PACKAGES)` and excludes
  `routing` by design; `EnvSecretResolver`'s javadoc says "keeping the engine framework-agnostic";
  `JsonWriter` is 126 lines and package-private; `InMemorySessionStore` is a `final class` with three
  `HashMap`s and no CDI annotation; corpus-wide CDI annotation count is 33 across 9
  `@ApplicationScoped` files; `ClientHelloSniParser` hand-parses ClientHello.
  - verdict: corroborated | checked_at: 05f6ee3ebb5ae32fb75082b660e6abdb7617edb6 | by: api-sheriff-0-2-0/cleanup | rescoped: n/a | evidence: ADR-0005 Accepted; FrameworkAgnosticArchTest present; JsonWriter pkg-private; config/load/ 5 files; InMemorySessionStore 7 synchronized methods; CDI now 16 files / ~59 annotations (was 11/45)
- **HYPOTHESIS (verify-at-outline)**: that a Quarkus-supplied JSON serializer covers `JsonWriter`'s
  payload shapes. **Confirm/refute artifact**: `JsonWriter`'s call sites and the actual payloads.
  - verdict: unverifiable | checked_at: 05f6ee3ebb5ae32fb75082b660e6abdb7617edb6 | by: api-sheriff-0-2-0/cleanup | rescoped: n/a | evidence: BffRuntime.java:279 still sole JsonWriter.toJson call site; whether the Quarkus serializer covers the payload shape is a design question
- **HYPOTHESIS (verify-at-outline)**: that SmallRye expression expansion matches `EnvSecretResolver`'s
  semantics. **Confirm/refute artifact**: the resolver's tests plus the YAML-load call path.
  **Explicitly refutable — and a refutation is a valid, expected outcome.**
  - verdict: unverifiable | checked_at: 05f6ee3ebb5ae32fb75082b660e6abdb7617edb6 | by: api-sheriff-0-2-0/cleanup | rescoped: n/a | evidence: config/load/ unchanged structurally but ConfigLoader +110 lines (#341, ADR-0052 env coercion); SmallRye-vs-YAML semantics is a design question
- **HYPOTHESIS (verify-at-outline)**: that a Quarkus session mechanism satisfies O(1) destroy-by-`sub`
  and destroy-by-`sid`. **Confirm/refute artifact**: `SessionStore`'s interface and the back-channel
  logout call path. **Likely to be refuted; that is fine and must be reported, not worked around.**
  - verdict: unverifiable | checked_at: 05f6ee3ebb5ae32fb75082b660e6abdb7617edb6 | by: api-sheriff-0-2-0/cleanup | rescoped: n/a | evidence: SessionStore O(1) destroy-by-sub/sid still binding; NEW live consumer BackchannelLogoutReceiver:130/:132 (+LogoutRejection/Log, ADR-0051) added to surface
- **OBSERVED (absence)**: the orchestrator did **not** verify which serializer the Quarkus BOM
  supplies here, did **not** read `ServerSessionBinding` or `SessionCookieCodec` beyond their
  existence, did **not** read the `tls/` package beyond `ClientHelloSniParser`'s role, and did
  **not** establish whether retiring the arch-gate breaks any other test.
  - verdict: unverifiable | checked_at: 05f6ee3ebb5ae32fb75082b660e6abdb7617edb6 | by: api-sheriff-0-2-0/cleanup | rescoped: n/a | evidence: about a prior reviewer's investigation scope; FrameworkAgnosticArchTest still unretired, so retirement impact untested

## Expected Surface

- OBSERVED: `doc/adr/` — one NEW superseding ADR; `0005-module-structure.adoc` marked superseded
- OBSERVED: `api-sheriff/src/test/java/de/cuioss/sheriff/gateway/arch/FrameworkAgnosticArchTest.java` — D2
- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/bff/runtime/JsonWriter.java` — D3
- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/config/load/**` — D4, whole package
- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/bff/session/**` — D5, all six types
- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/bff/logout/BackchannelLogoutReceiver.java` — D5 (added 2026-09-24): the live caller of `SessionBinding#destroyBySid` / `#destroyBySub`, which relies on the O(1) guarantee D5 must preserve
- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/bff/runtime/SessionIdentity.java` — D5 (added 2026-09-24): a session-derived portal DTO (PR #343) outside the `bff/session/**` glob
- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/tls/**` — D6, review only
- HYPOTHESIS: `api-sheriff/pom.xml` — **only if** a dependency change is approved; otherwise untouched
- OBSERVED: `doc/architecture.adoc` and the three-layer docs for every converted component
- OBSERVED (absence, deliberate): **no gateway behaviour change.** This is an infrastructure
  substitution. A changed status code, a changed header, or a changed session lifetime is a finding.

## Dependencies and Sequencing

- **HARD GATE: `api-sheriff-roadmap` must be CLOSED** (PLAN-08B, the 0.1.0 cut). This epic does not
  emit before then.
- **⚠ CONSEQUENCE FOR PLAN-08B, WHICH IS STILL IN THE RELEASE EPIC**: its **D2 is the "ADR-0005
  module-extraction checkpoint"**. That deliverable is written against an ADR this plan supersedes.
  **PLAN-08B D2 must be re-scoped before the cut** — the checkpoint should record the state and note
  the pending supersession, not re-affirm a decision already reversed. **Recorded in the roadmap
  epic's ledger; this plan does not edit PLAN-08B.**
- **Depends on PLAN-36** (`api-sheriff-roadmap`) — 36 reshapes the config record surface D4 reads.
- **Never concurrent with PLAN-39** (idiom sweep) — both sweep `api-sheriff/src/main/java/**` broadly.
- **RUNS ALONE** by preference: retiring an arch-gate mid-flight changes the gate set every other
  concurrent plan is verified against.

## Standing Epic Clauses

- **THREE-LAYER DOCS** in the same PR.
- **SONAR ZERO-FINDINGS** — red is a HARD STOP.
- **NAMED LINE ITEMS** — six named deliverables; an outline that collapses D6's `tls/` review into
  "no change needed" without reporting is a finding.
- **NEVER ADD DEPENDENCIES WITHOUT EXPLICIT USER APPROVAL** — binds D3 (dsl-json) directly. **ASK.**
- **TEST THE DELIVERED ARTIFACT** and **A GREEN SUITE IS NOT EVIDENCE** (clause 12) — a substitution
  that compiles and passes construction tests proves nothing about behaviour parity.
- **STAMP THE HEAD** — premises carry HEAD `b903526` and **will be stale by the time this emits**;
  re-ground the whole spec at outline.

## Finalize Boundary — the plan STOPS at the merge

**Operator ruling, 2026-07-30.** The plan owns everything through the merge, then REPORTS AND STOPS.
The post-merge aftermath is the **orchestrator's**.

## Hand-Off Command

```text
/plan-marshall task="implement .plan/local/orchestrator/api-sheriff-0-2-0/plans/PLAN-V02-01-adr-0005-reversal-quarkus-adoption.md" plan_id=plan-v02-01-adr-0005-reversal-quarkus-adoption
```

**The explicit `plan_id` is load-bearing — do not drop it.**

## Write-Boundary

The executing plan MUST NOT create or edit any file under
`.plan/local/orchestrator/api-sheriff-0-2-0/`. Its two channels back to the epic are its PR and its
`inbox/` OUTBOX.
