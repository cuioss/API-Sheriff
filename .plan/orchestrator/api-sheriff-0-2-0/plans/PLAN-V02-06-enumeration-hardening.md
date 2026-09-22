# PLAN-V02-06: Enumeration Hardening — existence-oracle + 404-scanner detection

epic: api-sheriff-0-2-0
workstream: WS-03
track: **POST-0.1.0**

> **MOVED TO `api-sheriff-next` 2026-07-27** by operator decision at the full plan revisit: this is
> post-0.1.0 work. The `api-sheriff-roadmap` epic now carries the release track only and closes at
> the cut. Re-ground this spec against HEAD at that epic's decompose — its claim labels were written
> 2026-07-25 and several were already refuted by PLAN-06 and PLAN-23 (see
> `../../api-sheriff-roadmap/archive.md` § 6).

> Staged plan spec — ready for `/plan-marshall` hand-off. NEW 2026-07-25 (operator requirements intake
> + prior-art research; AskUserQuestion: "uniform-404 for untrusted only").
> ~~Gated POST-first-landing.~~ **That gate EXPIRED** — PLAN-05 and PLAN-06 both shipped.
>
> **⚠ MERGE PENDING — decided 2026-07-27, executed at this epic's DECOMPOSE, not now.**
> **PLAN-19 merges INTO this plan.** Rationale: three plans each partially generalizing one shared
> per-client component is how the third ends up rewriting the first's design — PLAN-19's own spec
> already flags it as its "central risk" (*"if PLAN-18's substrate is bucket-only and not
> general-purpose, this plan must generalize it, not duplicate it"*). Merged, the substrate is **one
> design decision** (bucket + sliding window + strike/ban API + the ECS/OCSF emit shape) instead of a
> cross-plan hypothesis. PLAN-20 stays separate — deception is a distinct feature carrying its own
> legal/operational review.
> **The merged spec is deliberately NOT authored yet**: both specs' claim labels date from
> 2026-07-25, and authoring a merged spec against stale ground truth for work that runs post-release
> is precisely the failure the verify-first contract exists to prevent. Re-ground, then merge.

> **Renumbered 2026-08-04.** This spec was `PLAN-18-enumeration-hardening.md` in the retired `api-sheriff-next`
> backlog epic. In-body references to other `PLAN-NN` numbers were deliberately **not**
> rewritten: many point at `api-sheriff-roadmap` plans that keep their numbers. Resolve any
> such reference through the renumbering map in this epic's `epic.md`.

## Re-Grounded 2026-08-08 at `963e422` (== `origin/main`, clean tree)

Epic-wide re-grounding pass at `decompose`. **This section outranks any conflicting line below it.**

**⚠ THE MERGE IS OFF — OPERATOR DECISION 2026-08-08, SUPERSEDING THE 2026-07-27 RULING.**
The header above says *"PLAN-19 merges INTO this plan"*. **It does not.** `PLAN-V02-07`
(threat-classification) stays a separate plan. Two facts that were not in evidence on 2026-07-27
changed the balance:

1. **`PLAN-V02-13` (terminal-rejection-contract) now re-categorises the very
   `EventType`/`EventCategory` taxonomy V02-07 weights**, so V02-07 must sequence after it. Merging
   would drag that dependency onto the substrate work, which does not need it.
2. **Merged, the plan carries ~11 deliverables** — far past the scope-bloat split guard, and this
   spec's own split-guard note already flags D3 as the heaviest item at five.

**What replaces the merge — and it preserves the original intent exactly.** The 2026-07-27 rationale
was *"the substrate is ONE design decision, not a cross-plan hypothesis."* That intent is now met by
contract rather than by merger: **this plan owns a written, general-purpose substrate contract, and
V02-07 consumes it without redesigning it.** See the new deliverable 7. The failure the merge
existed to prevent — a third plan rewriting the first's design — is prevented by the contract being
explicit and testable, not by the two plans being one document.

**NEW DELIVERABLE 7 — the substrate contract, stated as its own named line item.**
Publish D3's per-client substrate as a **general-purpose API with a written contract**, not as a
recon-code-specific bucket. The contract must state, at minimum: the per-source (+ host) keying and
its cardinality bound; how an arbitrary weighted event is admitted (not only 4xx recon codes); the
sliding-window/bucket semantics and their configuration; the strike/ban state transitions and their
query API; and the structured emit shape a consumer formats from. **V02-07's D1 weights and D2
window are the named first consumer — design against that consumer explicitly**, and record which of
its needs the contract deliberately does not serve so V02-07 can plan around them rather than
discover them. A substrate that V02-07 must generalise on arrival is this deliverable failing.

**CONFIRMED, first-party — the oracle is real and unchanged.** `EventType.java`:
`PATH_NOT_ALLOWED`:58 (400), `NO_ROUTE_MATCHED`:62 (404), `TOKEN_MISSING`:101 (401) — the three
differentiated codes the plan collapses. `RouteSelectionStage` still deny-by-default (doc at :35,
throw at :70). `EventCategory` carries all five values.

**CONFIRMED ABSENCE — no per-client detection exists.** A search for leaky-bucket / sliding-window /
per-client-bucket constructs under `api-sheriff/src/main/java` returns nothing, with a control query
passing (10 files reference `EventCounter`). D3 is genuinely net-new.

**MOVED — D1's render point.** `renderProblem` has **five** call sites in `GatewayEdgeRoute.java`
(:566, :694, :750, :775, :1093) plus its private definition at :1096. The trust-boundary branch must
account for all five, and **`PLAN-V02-13` is editing exactly these sites** — see Sequencing.

**SEQUENCING, UPDATED.** **Sequence after `PLAN-V02-13`.** V02-13 re-categorises `NO_ROUTE_MATCHED`
and `METHOD_NOT_ALLOWED` out of `INPUT_VALIDATION` and makes `renderProblem` content-negotiating.
Both are surfaces D1 rewrites. Building the uniform-404 branch against a taxonomy and a render path
that are about to change means doing it twice. **Also overlaps `PLAN-V02-05`** on `ResponseStage` /
the edge — the two are close enough that the disjointness check must read V02-05's *current outline*,
not its staged spec.

**RENUMBERING.** "PLAN-19" is **PLAN-V02-07**; "PLAN-20" is `api-sheriff-0-3-0`'s **PLAN-V03-01**
(honeypot) — a different epic, and this substrate deliberately ships a release ahead of it.
"Sequence after PLAN-05 / PLAN-06 / PLAN-15" refers to shipped `api-sheriff-roadmap` work.
The reference to WS-05 as this plan's dependent workstream is stale — V02-07 is **WS-03**, alongside
this plan.


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

**ALL ANCHORS HELD** at `95dd566`: `PATH_NOT_ALLOWED`:58, `NO_ROUTE_MATCHED`:62, `TOKEN_MISSING`:101,
and `renderProblem` still has exactly **five** call sites.

**THE V02-13 BOUNDARY IS NOW STATED EXPLICITLY, BECAUSE BOTH PLANS EDIT THE SAME FIVE SITES.**
Ambiguity resolved here rather than left to whoever runs second:

| Concern | Owner |
|---|---|
| WHICH `EventCategory` a rejection carries (the taxonomy, incl. a new `ROUTING` category) | **V02-13 D1** |
| HOW a rejection is RENDERED to a browser vs. a JSON client (`Accept` negotiation) | **V02-13 D2** |
| WHETHER an untrusted caller sees the honest code or a uniform 404 (the trust-boundary branch) | **V02-06 D1** |
| Response-TIMING uniformity on the reject path | **V02-06 D2** |

**V02-06 changes the code a rejection resolves TO; V02-13 changes what it is CALLED and how it is
RENDERED.** They compose at the same dispatch and must not re-litigate each other: this plan does not
touch the category assignment, and does not alter the content negotiation V02-13 installs — its
uniform-404 must work through that negotiation, not around it. **Sequence V02-13 first** so the
branch is built once against the final shape.

## Objective

Close the endpoint-existence oracle and detect enumeration scanning. Today deny-by-default routing means
no path listing exists, but the status codes leak existence: a non-existent path returns 404, an
existing-but-auth-required path 401, an existing-but-off-allowlist path 400 — an anonymous attacker can
map real endpoints. This plan presents a **uniform 404** (body + code + timing) to **untrusted /
unauthenticated** sources while keeping honest 401/403 for authenticated callers past the trust
boundary, and introduces a **per-client 404-rate detection substrate** (a CrowdSec-`http-probing`-style
leaky bucket) that WS-05's later plans reuse.

## Deliverables

1. **Uniform-404 for untrusted sources.** When the caller is unauthenticated / untrusted, a would-be
   401 (`TOKEN_MISSING`) / 400 (`PATH_NOT_ALLOWED`) / 404 (`NO_ROUTE_MATCHED`) is rendered as an
   **identical 404** (same body, code, and — deliverable 2 — timing). Authenticated callers past the
   trust boundary keep honest `401/403`. **Stated as its own line item** (behaviour change; the
   trust-boundary condition is the crux — an outline must not collapse it to "return 404").
2. **Response-timing uniformity** for the reject path, so 404-vs-would-be-401 are not timing-
   distinguishable (route rejects through one code path / add jitter; watch early-exit shortcuts).
3. **Per-client 404-rate detection substrate** — a NEW shared component: a per-source leaky bucket over
   4xx recon codes (400/403/404), keyed by source (+ host), with static-resource exclusion, that trips
   on a configurable threshold. **This substrate is the WS-05 foundation** PLAN-19 and PLAN-20 consume.
   In-memory / single-node by decision; emits its trip event for external correlation.
4. **Response on trip**: soft throttle / tarpit-lite or temporary local block of a tripped source
   (config-driven), plus a structured security event (feeds the signal system).
5. **Tests** (oracle closed for unauth, honest codes for auth, bucket trips on a scan pattern, static
   resources excluded).
6. **Architecture documentation + ADR** — **stated as its own named line item**. Document the NEW
   per-client detection substrate (a new component in the edge/pipeline architecture) in
   `doc/architecture.adoc`, and record an **ADR** for the uniform-404-for-untrusted trust-boundary
   policy (the existence-oracle decision, its rationale, and the deliberate scoping to untrusted
   callers). Plus **three-layer documentation** (`configuration.adoc` threshold/predicate/action config,
   `doc/user/`, `doc/development/`), including the usability note (uniform-404 is scoped to untrusted
   callers precisely to preserve authenticated-client error handling).

7. **The general-purpose substrate contract** — added 2026-08-08, see § Re-Grounded. **Stated as its
   own named line item.** D3 ships the mechanism; D7 ships the *contract* that stops the next
   consumer having to generalise it. `PLAN-V02-07` is the named first consumer.

**Split-guard re-evaluation, 2026-08-08.** Seven deliverables — **at the guard, proceeding unsplit,
rationale recorded.** D7 is a documentation-and-API-shape obligation on D3 rather than independent
work, and D5 is this plan's own tests; the substantive count is unchanged. **If it must split, the
line is D1+D2 (the oracle and its timing) | D3+D4+D7 (the substrate and its contract)** — D5 and D6
follow whichever half they test and document. Do not split between D3 and D7: shipping the substrate
without its contract is the exact failure the retired merge existed to prevent.

## Claim Labels

Corroborated against HEAD 3f60d49, 2026-07-25.

- OBSERVED (the oracle): the differentiated codes are real — `events/EventType.java`: `NO_ROUTE_MATCHED`
  (404), `TOKEN_MISSING` (401), `PATH_NOT_ALLOWED` (400); rendered by `edge/GatewayEdgeRoute.java`
  `renderProblem` per the event's HTTP mapping.
  - verdict: corroborated | checked_at: af638952bc02aadda158c78668ccf0960fa379ba | by: api-sheriff-0-2-0/cleanup | rescoped: n/a | evidence: EventType.java:58 PATH_NOT_ALLOWED(400), :62 NO_ROUTE_MATCHED(404), :101 TOKEN_MISSING(401) all hold. renderProblem (GatewayEdgeRoute.java:1248-1261) still maps status per event's HTTP mapping, unchanged mechanism.
- OBSERVED: deny-by-default routing (no listing) — `pipeline/RouteSelectionStage.java`:34-35
  (`NO_ROUTE_MATCHED`, "the gateway never forwards an unmatched request").
  - verdict: corroborated | checked_at: af638952bc02aadda158c78668ccf0960fa379ba | by: api-sheriff-0-2-0/cleanup | rescoped: n/a | evidence: RouteSelectionStage.java:37-38 (drifted from :34-35) still: 'gateway never forwards an unmatched request'; process():64-73 throws GatewayException(NO_ROUTE_MATCHED) on exhausted loop, no listing endpoint.
- OBSERVED: no per-client recon detection exists — `events/GatewayEventCounter.java` counts events
  globally (Micrometer), not per-source; a grep for a per-client/leaky-bucket construct returns nothing.
  Confirm/refute at `events/` § its counter set (verify-at-outline).
  - verdict: corroborated | checked_at: af638952bc02aadda158c78668ccf0960fa379ba | by: api-sheriff-0-2-0/cleanup | rescoped: n/a | evidence: GatewayEventCounter.java: plain ConcurrentHashMap<EventType,AtomicLong>, no per-source keying, no bucket/window construct (the '(Micrometer)' detail is stale -- class javadoc explicitly avoids Micrometer per ADR-0005). Repo search for bucket/throttle/recon/sliding/ban: only RateLimitConfig.java, a reserved-and-ignored block -- D3 genuinely net-new.
- HYPOTHESIS: the "trusted / authenticated caller" signal needed for the trust-boundary branch is
  available at the render point (the request carries its auth outcome). Confirm/refute at
  `edge/GatewayEdgeRoute.java` § where `renderProblem` is called and what auth state is in scope
  (verify-at-outline) — if the reject path cannot see auth state, the branch needs the pipeline to
  thread it, which widens the deliverable.
  - verdict: corroborated | checked_at: af638952bc02aadda158c78668ccf0960fa379ba | by: api-sheriff-0-2-0/cleanup | rescoped: n/a | evidence: process():754-846 -- PATH_NOT_ALLOWED/NO_ROUTE_MATCHED fire before authenticationStage.process(); TOKEN_MISSING fires inside it. The eventType param renderProblem already receives is itself sufficient signal to branch pre/post-trust-boundary -- no new PipelineRequest field needed (grep found none).
- Verify-first clause: confirm that collapsing to 404 does not break the shipped BFF/XHR contracts
  (PLAN-06's info endpoint deliberately returns 401-not-redirect for XHR) — the uniform-404 must NOT
  apply to those authenticated-session flows; scope the "untrusted" predicate against the landed auth
  model, not this spec's prose.
  - verdict: corroborated | checked_at: af638952bc02aadda158c78668ccf0960fa379ba | by: api-sheriff-0-2-0/cleanup | rescoped: n/a | evidence: UserInfoEndpoint.java javadoc still: no-session yields 401 problem+json, never a redirect -- a genuine currently-untrusted-by-naive-predicate 401 the uniform-404 predicate must still carve out. Risk unresolved, unchanged.

## Expected Surface

- OBSERVED: `edge/GatewayEdgeRoute.java` `renderProblem` — the trust-boundary-aware uniform-404 branch
- OBSERVED: `pipeline/RouteSelectionStage.java` — the route-miss origin of `NO_ROUTE_MATCHED`
- OBSERVED absence → NEW: a per-client recon-detection component under `events/` or a new package
- OBSERVED: `events/EventType.java` / `GatewayEventCounter.java` — a new trip event + counter
- OBSERVED: `config/model/**` — config for the threshold / trusted-source predicate / response action
- OBSERVED: `doc/configuration.adoc`, `doc/user/`, `doc/development/`; `api-sheriff/src/test/**`

## Dependencies and Sequencing

- Depends on: nothing functionally, but **sequence after PLAN-05 / PLAN-06 / PLAN-15** (all hold the
  edge / event system / auth model this plan reads). **Foundation of WS-05** — PLAN-19 and PLAN-20
  depend on its per-client substrate.
- Overlaps with: PLAN-17 (both touch the edge, likely disjoint enough to pair — decide at emit).
- **Split-guard note:** 5 deliverables, under the presumption. The substrate (D3) is the heaviest;
  if outline finds the trust-boundary threading (D1) is itself large, split D1 off rather than bloat.

## Standing Conventions

Three-layer docs, Sonar zero-findings, named line items, integration tests in the same plan.

## Hand-Off Command

```text
/plan-marshall task="implement .plan/local/orchestrator/api-sheriff-0-2-0/plans/PLAN-V02-06-enumeration-hardening.md" plan_id=plan-v02-06-enumeration-hardening
```

## Write-Boundary

Touches only its own repository source and tests; creates/edits NO file under `.plan/local/orchestrator/`.

## Status Trail

- plan_marshall_plan_id: {set at launch}
- pr: {set when the PR opens}
- landing: {set when landings/PLAN-18.md is recorded}
