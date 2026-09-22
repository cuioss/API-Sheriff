# PLAN-V02-13: the terminal-rejection contract — what a rejection is CALLED, and what it is RENDERED as

epic: api-sheriff-0-2-0
workstream: WS-03

> **Owns GitHub issues [#188](https://github.com/cuioss/API-Sheriff/issues/188) and
> [#189](https://github.com/cuioss/API-Sheriff/issues/189)**, filed 2026-08-07 from a real downstream
> diagnosis and routed here by the orchestrator on the same day.
>
> **The two issues state they are independent, and they are — but they are NOT separable into two
> plans.** They were found through the same report, they touch the same four `renderProblem` call
> sites, and they share a documentation surface (`doc/architecture.adoc`:713–719,
> `doc/plan/04-request-pipeline.adoc`:181). Splitting them across plans manufactures a merge
> collision on that surface for no benefit.

## Re-Grounded 2026-08-08 at `963e422` (== `origin/main`, clean tree)

Epic-wide re-grounding pass at `decompose`. **This section outranks any conflicting line below it.**

**EVERY ANCHOR IN THIS SPEC HOLDS, LINE NUMBERS INCLUDED.** `EventType.java`:62
(`NO_ROUTE_MATCHED`, 404), :69 (`PASSTHROUGH_HOST_SMUGGLED`, 404), :71 (`METHOD_NOT_ALLOWED`, 405),
all three against `EventCategory.INPUT_VALIDATION`. `acceptsHtml` exists only at
`SessionAuthenticationStage.java`:181 and is used only at :168. `doc/architecture.adoc`:713–719 is
exactly the consolidated status table, listing `INPUT_VALIDATION` against 404 and 405 as the spec
says. `doc/plan/04-request-pipeline.adoc`:181 still asserts the absolute this change breaks.

**⚠ ONE COUNT IS WRONG, AND IT IS THE ONE D5's TEST MATRIX IS SIZED ON.** The prose says
`renderProblem` is `Accept`-blind at **four** call sites; the spec's own Claim Labels then correctly
enumerate **five** — :566, :694, :750, :775 **and :1093** — plus the private definition at :1096.
There are **five call sites**. The header note about *"the same four `renderProblem` call sites"* is
likewise off by one. **D5's per-site test matrix covers five sites under `Accept: text/html` and
five under `Accept: */*`.** The enumeration was right; only the tally was wrong.

**DISCHARGED — the ADR number.** The Sequencing section says *"the next free number is 0038 and it is
NOT free until PLAN-50 lands 0035."* **PLAN-50 landed.** `0035` is in the corpus, which is now
contiguous `0001`–`0037`, so **`0038` is free.** Still re-check against open branches at write time,
and note that V02-01, V02-06, V02-07, V02-11 and V02-12 also author ADRs in this epic.

**`doc/plan/` HAS NOT BEEN DELETED YET.** The D5 note asks whether `PLAN-V02-03` D3 landed first —
it has not; `doc/plan/` still holds all 12 files. So `04-request-pipeline.adoc`:181 is a real edit
target today. Re-check at outline: if V02-03 has landed by then, the reference is discharged rather
than edited, and the directory must not be resurrected.

**SEQUENCING — THIS PLAN IS NOW A HARD PREDECESSOR OF TWO OTHERS, NOT ONE.**
- `PLAN-V02-07` (threat-classification) sequences after it, as this spec already states — its D1
  weights the taxonomy D1 here re-cuts.
- **`PLAN-V02-06` (enumeration-hardening) ALSO sequences after it** — a fact this spec did not know.
  V02-06's D1 builds a trust-boundary-aware uniform-404 branch at **the same five `renderProblem`
  call sites** this plan makes content-negotiating, and it emits a new trip event into the same
  taxonomy. Building that against a category set and a render path about to change means doing it
  twice.

Surface-disjointness otherwise unchanged: disjoint from V02-09, V02-02, V02-14, V02-15, V02-16.


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

**JAVA ANCHORS HELD EXACTLY** at `95dd566`: `EventType`:62 / :69 / :71 still carry the three misfiled
members against `INPUT_VALIDATION`; `acceptsHtml` is still only at `SessionAuthenticationStage`:181,
used only at :168; `renderProblem` still has **five** call sites.

**TWO DOC ANCHORS ARE NOW WRONG — `PLAN-V02-03` moved them.**

- **`doc/plan/04-request-pipeline.adoc`:181 NO LONGER EXISTS.** That file is among the nine deleted.
  **The D5 obligation against it is DISCHARGED — strike it, and do not resurrect the directory**
  (three files survive there deliberately and are not this plan's business).
- **`doc/configuration.adoc`:1397 has moved to ≈:974** — the *"redirecting a JSON call is useless to
  its caller"* sentence D2 reuses as precedent. Re-anchor by content. The `:251` immutable-extension-map
  citation in D3 has likewise moved; the immutability statements now sit near :10–11 and :33. **Both
  are re-anchor-by-content, not re-derive** — the reasoning they support is unchanged.
- `doc/architecture.adoc`'s status table drifted by ~1 line (now ≈:714). Same treatment.

**THE V02-06 BOUNDARY IS NOW EXPLICIT** (see V02-06's matching section): this plan owns **what a
rejection is CALLED (D1) and how it is RENDERED (D2)**; V02-06 owns **whether an untrusted caller
sees the honest code at all**, and its uniform-404 must work *through* the negotiation this plan
installs. **This plan goes first** and should be treated as the head of the WS-03 chain.

## Objective

Make a terminal rejection say what actually happened, to whoever is actually reading it. Today a
routing miss is titled *"Input Validation"* and is rendered as `problem+json` into a browser
viewport — so the only human-readable field in the response is wrong, and the only reader present
cannot use the format it arrives in.

## The reported cost, stated once because it justifies the whole plan

A browser was pointed at a gateway under a hostname that is not the host of `oidc.redirect_uri`. The
reserved-path registry did not match, the request fell through to routing, matched nothing, and the
viewport rendered:

```json
{"type":"urn:api-sheriff:problem:input-validation","title":"Input Validation","status":404}
```

Correct, and useless. It was a top-level navigation — no JavaScript, no client to branch on the
status. **The actual cause was a host binding, and the word "Input Validation" actively pointed away
from it.** That is a real diagnosis cost, not a style objection.

## Deliverables

1. **Re-categorise the three `EventType` members that do not fit `INPUT_VALIDATION`.**

   OBSERVED at `origin/main` (`b8dde22`) — `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/events/EventType.java`:

   | Member | Line | Status | What actually happened |
   |---|---|---|---|
   | `NO_ROUTE_MATCHED` | :62 | 404 | deny-by-default routing — the request addressed nothing |
   | `PASSTHROUGH_HOST_SMUGGLED` | :69 | 404 | a reserved-for-passthrough `Host` on a terminated connection |
   | `METHOD_NOT_ALLOWED` | :71 | 405 | the verb is outside the route's effective allowlist |

   `EventCategory.INPUT_VALIDATION` documents itself as *"Path / parameter / header pipeline,
   collection limits, or body-size violations"*. None of the three is any of those. The category's
   remaining members — `SECURITY_FILTER_VIOLATION`, `PATH_NOT_ALLOWED`,
   `PARAMETER_LIMIT_EXCEEDED` — fit it exactly, which is what makes the misfit worth fixing rather
   than papering over.

   **Take the issue's option 1 (a new `ROUTING` category) unless a stronger case emerges, and record
   the reasoning either way.** Its option 3 — retitling `INPUT_VALIDATION` to something vaguer that
   covers both — is explicitly not recommended by the reporter and should not be adopted silently:
   it makes the *correct* members vaguer to fix the incorrect ones.

   **`PASSTHROUGH_HOST_SMUGGLED` is the sharpest case**: it names an attempted attack and currently
   reports under the same title as a request with too many query parameters.

   **This is public API surface** — a new category costs a new `type` URN. Say so in the ADR.

   **The category is load-bearing beyond the response body**: it is the metric dimension and the log
   classifier. Routing misses are operationally ordinary (crawlers, stale links, misconfigured
   clients); filter violations are a security signal. **Folding them into one bucket means neither
   can be alerted on cleanly** — which is why this must land before PLAN-V02-07 weights the taxonomy.

2. **Content-negotiate the terminal rejections: render static HTML to a navigating browser, the same
   status, no redirect.**

   OBSERVED: `GatewayEdgeRoute.renderProblem` is `Accept`-blind at **four** call sites — :566
   (`RESERVED_BODY_TOO_LARGE`), :694 (`METHOD_NOT_ALLOWED`), :750 (generic), :775
   (`NO_ROUTE_MATCHED`), plus the private definition at :1096 and a further dispatch at :1093.

   **The gateway already draws this distinction and the mechanism already exists** —
   `SessionAuthenticationStage`:168/:181 (`acceptsHtml`) redirects an unauthenticated *navigation*
   into the auth-code flow while giving XHR a `401 problem+json`. `doc/configuration.adoc`:1397
   states the reasoning: *"redirecting a JSON call is useless to its caller."* **The converse is
   equally true and is not acted on: rendering a JSON document is useless to a navigating browser.**
   Reuse `acceptsHtml`'s semantics rather than inventing a second negotiation rule.

   **Precedent, already accepted once:** gRPC rejections are already not `problem+json` — they are
   trailers-only, because a gRPC client cannot consume a `problem+json` body
   (`doc/architecture.adoc`, `_grpc_error_contract`). This is the second instance of a rule the
   codebase has already taken.

3. **Honour three constraints that the implementation must not quietly trade away.**

   - **DO NOT REDIRECT.** The issue rejects it for three reasons in descending weight and all three
     hold: (a) a `302` on a `404` **destroys the status code** — monitoring and uptime checks would
     see success-shaped traffic where a rejection occurred; (b) the gateway has **no landing page to
     redirect to** — `oidc.logout.final_redirect` exists only inside the `oidc` block, so a pure-proxy
     Variant 1 deployment has no target and the behaviour would be undefined for the majority
     configuration; (c) an error carried as a URL parameter turns the landing page into a
     **reflection sink**, which is the exact lever the product refuses elsewhere
     (`doc/configuration.adoc`:251, the immutable extension map).
   - **The HTML body must be STATIC PER CATEGORY.** `doc/security-threat-model.adoc`:705/:716
     (control `gw-12`) asserts *"No error response or log line contains a resolved secret value or a
     stack trace/internal detail; problem+json bodies carry category only."* No request path, no
     query string, no header value, no hostname may be echoed. The current document is exemplary
     here — `type`, `title`, `status` and nothing else. **Static also makes it trivially
     injection-free: there is no interpolation site.** Do not make the page templated; at most allow
     an operator-supplied static file path per status.
   - **`Accept: */*` (curl's default) MUST keep receiving `problem+json`.** Only an explicit
     `text/html` offer switches, matching `acceptsHtml`'s existing behaviour.

4. **Leave `/auth/userinfo` and the reserved endpoints alone — this is a PROHIBITED-ASSERTION
   boundary, not a scoping preference.**

   `/auth/userinfo` is `Accept`-blind by design. `demo-client/doc/playwright-suite.adoc`:324 records
   a **PROHIBITED ASSERTION** against ever testing it otherwise, and the demo client encodes it as a
   runtime assertion with `redirect: 'error'`. #189 explicitly does not propose changing it. **Scope
   the change to the edge's own rejections.** Reachability is the right filter: `404` and `405` are
   reachable by navigation, a `413` on a reserved POST path is not.

5. **Tests and documentation, both named as their own line item.**

   - Tests: each of the four call sites under `Accept: text/html` and under `Accept: */*`; a case
     asserting the HTML body interpolates nothing; a case asserting `/auth/userinfo` is unchanged;
     category assertions for all three re-categorised members including the metric dimension.
   - Documentation: `doc/architecture.adoc`:713–719 (the consolidated status mapping, which lists
     `INPUT_VALIDATION` against `404` and `405`) and `doc/plan/04-request-pipeline.adoc`:181 both
     currently assert the absolute this change breaks. An **ADR** for the new category and for the
     content-negotiated rendering, since both are public contract changes.

   > **`doc/plan/` is scheduled for deletion by PLAN-V02-03 D3.** If that has landed first, this
   > reference is discharged rather than edited — check before editing, and do not resurrect the
   > directory.

## Claim Labels

- OBSERVED (2026-08-07, `b8dde22`): `EventType.java`:62/:69/:71 carry the three members against
  `EventCategory.INPUT_VALIDATION`.
- OBSERVED (2026-08-07, `b8dde22`): `renderProblem` call sites at `GatewayEdgeRoute.java`:566, :694,
  :750, :775, :1093, defined at :1096; `acceptsHtml` exists only at
  `SessionAuthenticationStage.java`:181 and is used only at :168.
- ASSERTED BY THE ISSUE, NOT RE-VERIFIED: the three-row behaviour table in #189 (auth failure → 302
  on `text/html`; `/auth/userinfo` → 401 both ways; no route → 404 `problem+json` both ways) was
  measured against a running gateway by the reporter. **Re-measure before building on it** — a
  measured table from outside is a lead, and row 1 is what the whole design reuses.

## Expected Surface

- `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/events/EventType.java`, `EventCategory.java` — D1
- `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/edge/GatewayEdgeRoute.java` — D2, D3
- `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/bff/runtime/SessionAuthenticationStage.java` — read-only reference for D2
- `doc/architecture.adoc`, `doc/adr/00NN-*.adoc` (new) — D5
- OBSERVED (absence, asserted): `/auth/userinfo` and the reserved-path registry are **NOT** edited.

## Dependencies and Sequencing

- **PLAN-V02-07 (threat-classification) SHOULD SEQUENCE AFTER THIS PLAN.** Its D1 assigns severity
  weights to the existing `EventType`/`EventCategory`; weighting a taxonomy that is about to change
  means doing it twice, and the second time silently. If they must overlap, V02-07 owns the weights
  and this plan owns the membership — but say so explicitly.
- **ADR number**: allocate at emit time against the live corpus. As of 2026-08-07 the roadmap epic
  allocated 0035 (PLAN-50, running) and shipped 0036/0037 (PLAN-52), so **the next free number is
  0038 and it is NOT free until PLAN-50 lands 0035.** Re-check rather than assume.
- Surface-disjoint from PLAN-V02-09 and PLAN-V02-02.

## Issue Closure

**CLOSE [#188](https://github.com/cuioss/API-Sheriff/issues/188) AND
[#189](https://github.com/cuioss/API-Sheriff/issues/189) WHEN THIS PLAN LANDS.** Comment on each
naming the PR and merge commit, state which deliverable discharged it, and close it. If any part is
deliberately not done, say so on the issue and leave that issue open rather than closing it with a
silent gap.

**A PR body that merely mentions an issue does NOT link or close it** — `closingIssuesReferences`
stays empty. Use a closing keyword in the PR body, or close explicitly after the merge. Issues
#182/#183 sat open after their implementing PR landed for exactly this reason.

## Hand-Off Command

```text
/plan-marshall task="implement .plan/local/orchestrator/api-sheriff-0-2-0/plans/PLAN-V02-13-terminal-rejection-contract.md" plan_id=plan-v02-13-terminal-rejection-contract
```

## Write-Boundary

The plan implementing this spec touches only its own repository source and tests. It creates and
edits NO file under `.plan/local/orchestrator/` other than its own `inbox/{sender}-{seq}` message.
