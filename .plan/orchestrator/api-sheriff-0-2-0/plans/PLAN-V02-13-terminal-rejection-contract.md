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

## Re-Grounded (3) 2026-09-22 at `69b322b` — a same-day, unrelated landing discharged most of D2/D3

**PR #343 ("feat(portal): add application catalog, overview page and HTML error pages") landed
between this epic's cleanup pass and this plan's own D6/D7 fold, 148 files. It independently built
almost exactly what D2/D3 describe, by a different mechanism than either assumed. This section
outranks Re-Grounded (2) wherever they conflict, and the Objective/reported-cost narrative below is
now HISTORICAL — the bug it describes is fixed — but D1's taxonomy work stands on its own merits
independent of that narrative.**

**D2 — substantially DISCHARGED, not by content negotiation reuse but by a new
`portal.ErrorPageClassifier` + `GatewayEdgeRoute.answeredWithErrorPage()` pair.**
`ErrorPageClassifier.classify(EventType)` is an exhaustive, default-free switch sorting every
`EventType` into `HTML_ELIGIBLE` or `KEEP_SHAPE`; `renderProblem` (five call sites unchanged:
:664/:792/:871/:896/:1436, def :1447) calls `answeredWithErrorPage()`, which renders the portal's
HTML page only when `portal.error_pages` is enabled, the event is `HTML_ELIGIBLE`, **and** `Accept`
explicitly offers `text/html` (wildcards never qualify — `offersHtml` already implements D3's
constraint), else falling through to today's `problem+json` unchanged. **`NO_ROUTE_MATCHED` — this
plan's own flagship "Input Validation" misdiagnosis story — is already `HTML_ELIGIBLE` and
negotiated.** `METHOD_NOT_ALLOWED` and `PASSTHROUGH_HOST_SMUGGLED` (two of D1's three
re-categorisation targets) are currently `KEEP_SHAPE`; `RESERVED_BODY_TOO_LARGE` is deliberately
`KEEP_SHAPE` too, and correctly so — it lands only on reserved (API-only) paths, consistent with
D4's own reachability filter below. **D2 is narrowed to one decision, not a build**: once D1 lands
the new category, decide whether `METHOD_NOT_ALLOWED` and `PASSTHROUGH_HOST_SMUGGLED` should also
flip to `HTML_ELIGIBLE` in `ErrorPageClassifier.classify()` — a one-line-per-event change plus a
test, never a new negotiation mechanism. There is no "reuse `acceptsHtml`'s semantics" work left to
do; that mechanism was superseded before this plan reached it.

**D3 — DISCHARGED as a byproduct of D2's mechanism**, not built to this plan's original
specification but satisfying its constraints: status is preserved either way (no redirect involved
at all), `offersHtml` structurally excludes `Accept: */*`, and the rendered titles are fixed,
non-interpolated strings per status code. Nothing further to build; verify at outline that a test
already exercises these three constraints and add one only if a gap is found.

**D7 (this plan's own 2026-09-22 GW-09 fold) — WRONG AS WRITTEN, and the error is this plan's own,
not this PR's.** `D7` was authored from `doc/security-threat-model.adoc`'s prose alone, without
checking the pipeline code first — the mistake the epic's verify-first discipline exists to catch.
**`pipeline/OriginValidationStage.java` already exists and already implements GW-09 generically**,
predating even `af63895`: its own class Javadoc is headed *"The WebSocket-upgrade Origin gate
(GW-09, cross-site WebSocket hijacking)"*, it is NOT scoped to bearer routes, and it fail-closed
rejects an upgrade with a foreign/absent `Origin` against any route's configured `allowed_origins`
allowlist, `require: session` included. **The real, narrower gap**: `ConfigValidator.
validateWebSocketRoute` (:2192) only *requires* a non-empty `allowed_origins` at boot for
`effective auth 'bearer'` — a `require: session` WebSocket route with an empty (unconfigured)
allowlist boots successfully with **no enforcement**, because `OriginValidationStage`'s own contract
is explicit that an empty allowlist "declares no enforcement." D7 is corrected below to close that
boot-time gap rather than build a mechanism that already exists.

**D8 (this plan's sibling `PLAN-V02-06` GW-08 fold) — provisionally UNAFFECTED**, checked only
time-boxed: no HTTP/2 or gRPC pipeline file appears in this PR's diff. Re-verify at that plan's
outline rather than trusting this note.

**ADR corpus is now 50, not 49** — PR #343 landed `escape-bypass_constructs_are_refused_at_boot` as
part of the same change. Re-check against `main` and every open branch at write time, as always.

## Re-Grounded (4) 2026-09-24 at `05f6ee3` — after 18 commits (#343–#354, release 0.2.3)

**D6's build premise is REFUTED at HEAD, and D6 is re-scoped in place.** `pipeline/FramingGate` (predates the epic; relocated by #92) already rejects CL+TE, multiple or comma-listed `Content-Length`, a body on a bodyless method (with the exact `allow_get_with_content_length_body` opt-in), and a `Connection`-header strip of framing or trust headers. The gw-02 `GAP` row at `doc/security-threat-model.adoc:2127` is stale documentation, not a code gap. D6 is now an audit plus a residual build: the bare-LF chunk terminator and pooled-connection re-validation, both unverified. `FramingGate.java` is added to the Expected Surface. D2/D3 are re-confirmed partially discharged by `ErrorPageClassifier` (#343); `METHOD_NOT_ALLOWED`/`PASSTHROUGH_HOST_SMUGGLED` are still `KEEP_SHAPE` (`:132`). D1, D5 and D7 are open (`validateWebSocketRoute` `:2404` is still bearer-only). The `renderProblem` sites drifted to `:709/:837/:916/:941/:1489` (def `:1500`).

**ADR numbering, corrected across the corpus:** `doc/adr/` now holds 55 records. `0053` is DUPLICATED (#348 renamed the portal ADR `0050`→`0053` while #346 claimed `0053` concurrently), and the next free ordinal is `0055`. Every earlier "next free is 0038/0050" line in this spec is stale. Re-derive the ordinal at write time, and prefer landing after `PLAN-V02-19`, which fixes the duplicate and adds an ordinal-uniqueness test.

## Objective

Make a terminal rejection say what actually happened, to whoever is actually reading it. **This
narrative is now HISTORICAL as of Re-Grounded (3) above** — `PR #343` independently fixed the
render-format half of the bug it describes. D1's taxonomy work (below) is retained on its own
merits: a routing miss and a filter violation sharing one category is still a real observability
defect, regardless of what format either renders as. Originally: today a routing miss is titled
*"Input Validation"* and is rendered as `problem+json` into a browser viewport — so the only
human-readable field in the response is wrong, and the only reader present cannot use the format it
arrives in.

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

6. **Folded in 2026-09-22 from `doc/security-threat-model.adoc` GAP row `gw-02`. RE-SCOPED
   2026-09-24 per Re-Grounded (4) below: this is now an audit that closes a residual, not a
   build.** `pipeline/FramingGate` already rejects CL+TE, multiple or comma-listed
   `Content-Length`, a body on a bodyless method (with the existing
   `allow_get_with_content_length_body` opt-in), and a `Connection`-header strip of a framing or
   trust header. It predates this epic. So D6 is: (a) confirm `FramingGate`'s coverage against
   the gw-02 control, case by case; (b) build ONLY the residual that the confirmation shows is
   absent. The two candidates, both unverified, are a bare-LF / non-RFC-9112 chunk terminator and
   never pooling an upstream connection whose framing was not validated. (c) Flip the stale gw-02
   `GAP` row to `COVERED`, or to `PARTIAL` with the named residual. (d) Re-categorise
   `FramingGate`'s rejection (today `SECURITY_FILTER_VIOLATION`) only if D1's taxonomy calls for
   it. The original text follows. Read it as the gw-02 control's full statement, not as a list
   of things to build.

   Reject a request bearing both `Content-Length` and `Transfer-Encoding`; a body on a bodyless
   method (HEAD/GET, unless `security_defaults.allow_get_with_content_length_body` is explicitly
   enabled — that opt-in already exists and must be preserved unchanged); a bare-LF chunk
   terminator or other non-RFC-9112 chunk framing; or a `Connection`-header cleanup attempt that
   would strip `Content-Length`/`Transfer-Encoding` after the fact. Re-derive/validate framing
   after any header mutation, and never pool an upstream connection whose framing was not fully
   validated.

   Categorise the new rejection consistent with D1's taxonomy decision (the `ROUTING` category if
   adopted, or a similarly-scoped member — this is a framing/protocol violation, not the generic
   `INPUT_VALIDATION` bucket the three misfiled members are being moved OUT of). Render it per D2's
   content-negotiation contract like every other terminal rejection this plan touches.

   Test against a CL.TE/TE.CL/TE.TE/CL.0 smuggling corpus and assert zero desyncs, plus a case
   confirming `security_defaults.allow_get_with_content_length_body`'s existing behaviour is
   unchanged when unset. **Verify the exact enforcement call site at outline** — this deliverable
   was folded in from the threat-model catalogue, not independently re-grounded against the
   pipeline code, so its anchor is not yet pinned the way D1/D2's are.

7. **Folded in 2026-09-22 from `doc/security-threat-model.adoc` GAP row `gw-09`, CORRECTED
   2026-09-22 same day per Re-Grounded (3) above — require the Origin allowlist at boot for
   `require: session` WebSocket routes, not build the check.**

   The enforcement mechanism already exists and is already generic: `pipeline/OriginValidationStage`
   fail-closed rejects a WebSocket upgrade carrying a foreign or absent `Origin` against any route's
   configured `allowed_origins`, `require: session` included — this predates the epic and this
   plan. The actual gap is narrower and lives in `ConfigValidator.validateWebSocketRoute`: it
   requires a non-empty `allowed_origins` at boot **only** for `effective auth 'bearer'`
   (`Require.BEARER`); a `require: session` WebSocket route with no configured allowlist boots
   successfully with the stage's own documented "no enforcement" fallback silently in effect.
   **Extend that boot-time requirement to `Require.SESSION` routes too** — one additional condition
   in `validateWebSocketRoute`'s existing bearer check, mirroring its shape exactly, plus a test
   asserting a session WebSocket route with an empty allowlist now fails config validation the same
   way a bearer one already does. Also confirm (or add) `SameSite` on the session cookie so ambient
   cookies alone cannot authenticate the socket once the allowlist is enforced.

   This is a config-validation-rule change, not a pipeline change — it does not touch D1's
   taxonomy or D2's render contract, so it is not blocked by either.

**Split-guard evaluation, 2026-09-22.** Seven deliverables — at the presumptive ~6 threshold,
proceeding unsplit, rationale recorded. This is a count on paper only: D2 and D3 are now residual
decisions rather than builds (Re-Grounded (3) above), and D6/D7 are each bounded, independently
landable, and touch no file D1/D2/D4/D5 touch. **If it must split, D1+D2(residual)+D4+D5 (the
taxonomy and its render/doc consequences) separates cleanly from D6 (framing rejection) and D7
(config-validation extension)** — the latter two share no code with the former three or each
other.

## Claim Labels

- OBSERVED (2026-08-07, `b8dde22`): `EventType.java`:62/:69/:71 carry the three members against
  `EventCategory.INPUT_VALIDATION`.
  - verdict: corroborated | checked_at: 05f6ee3ebb5ae32fb75082b660e6abdb7617edb6 | by: api-sheriff-0-2-0/cleanup | rescoped: n/a | evidence: EventType :62/:69/:71 NO_ROUTE_MATCHED/PASSTHROUGH_HOST_SMUGGLED/METHOD_NOT_ALLOWED still INPUT_VALIDATION
- OBSERVED (2026-08-07, `b8dde22`): `renderProblem` call sites at `GatewayEdgeRoute.java`:566, :694,
  :750, :775, :1093, defined at :1096; `acceptsHtml` exists only at
  `SessionAuthenticationStage.java`:181 and is used only at :168.
  - verdict: corroborated | checked_at: 05f6ee3ebb5ae32fb75082b660e6abdb7617edb6 | by: api-sheriff-0-2-0/cleanup | rescoped: n/a | evidence: renderProblem sites :709/:837/:916/:941/:1489 def :1500; acceptsHtml SessionAuthenticationStage def :220 use :205
- ASSERTED BY THE ISSUE, NOT RE-VERIFIED: the three-row behaviour table in #189 (auth failure → 302
  on `text/html`; `/auth/userinfo` → 401 both ways; no route → 404 `problem+json` both ways) was
  measured against a running gateway by the reporter. **Re-measure before building on it** — a
  measured table from outside is a lead, and row 1 is what the whole design reuses.
  - verdict: unverifiable | checked_at: 05f6ee3ebb5ae32fb75082b660e6abdb7617edb6 | by: api-sheriff-0-2-0/cleanup | rescoped: n/a | evidence: runtime Accept-negotiation table needs a running gateway

## Expected Surface

- `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/events/EventType.java`, `EventCategory.java` — D1, D6
- `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/edge/GatewayEdgeRoute.java` — D2 residual only (its five `renderProblem` call sites are unchanged), D6 (framing-rejection site, to confirm at outline)
- `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/portal/ErrorPageClassifier.java` — D2 residual: flip `METHOD_NOT_ALLOWED`/`PASSTHROUGH_HOST_SMUGGLED` from `KEEP_SHAPE` to `HTML_ELIGIBLE` if D1's re-categorisation warrants it (OBSERVED 2026-09-22, landed by PR #343, not this plan)
- `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/config/validation/ConfigValidator.java` — D7 (extend `validateWebSocketRoute`'s bearer-only allowlist requirement to `Require.SESSION`)
- `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/pipeline/OriginValidationStage.java` — read-only reference for D7; OBSERVED, this plan does NOT edit it — the enforcement mechanism already exists
- `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/bff/runtime/SessionAuthenticationStage.java` — read-only reference for D1/D2 boundary reasoning only, per D4
- `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/pipeline/FramingGate.java` — D6: the existing gw-02 enforcement site (OBSERVED 2026-09-24). It is edited only if D6(b) finds a residual to build.
- `doc/security-threat-model.adoc` — flip `gw-02` (D6) and `gw-09` (D7) from `GAP` on landing. gw-02's enforcement largely already exists (see D6).
- `doc/architecture.adoc`, `doc/adr/00NN-*.adoc` (new; re-derive the next free ordinal at write time — `0055` at `05f6ee3`, where `0053` is duplicated until PLAN-V02-19 lands) — D5, D6, D7
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
