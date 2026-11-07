# PLAN-V02-13: the terminal-rejection contract — what a rejection is CALLED, and what it is RENDERED as

epic: api-sheriff-0-2-0
workstream: WS-03

> Staged plan spec — one shippable unit of work, ready for `/plan-marshall` hand-off.
> The orchestrator EMITS the command below; it never launches the plan inline.
>
> **Owns GitHub issues [#188](https://github.com/cuioss/API-Sheriff/issues/188) (the category) and
> [#189](https://github.com/cuioss/API-Sheriff/issues/189) (the rendering)**, both from one real
> downstream diagnosis. They are independent questions on one dispatch and one documentation
> surface, so they stay in one plan.

## Objective

Make a terminal rejection say what actually happened. Today a routing miss, a smuggled passthrough
host and a disallowed method all report under `EventCategory.INPUT_VALIDATION`, beside genuine filter
violations. That category is the response title, the metric dimension and the log classifier, so an
ordinary routing miss and a security signal cannot be told apart or alerted on separately.

The rendering half of the original problem — a JSON document shown to a navigating browser — is
already solved on `main` for most rejections. What remains of it is one classification decision.
Around the taxonomy work the plan also closes two threat-model rows that depend on the same contract.

## The reported cost, stated once because it justifies the taxonomy work

A browser was pointed at a gateway under a hostname that is not the host of `oidc.redirect_uri`. The
reserved-path registry did not match, the request fell through to routing, matched nothing, and the
response read:

```json
{"type":"urn:api-sheriff:problem:input-validation","title":"Input Validation","status":404}
```

Correct, and useless. **The actual cause was a host binding, and the word "Input Validation" actively
pointed away from it.** That is a real diagnosis cost, not a style objection.

## Already in Place — do not rebuild

- **Content negotiation of terminal rejections.** `GatewayEdgeRoute.renderProblem` calls
  `answeredWithErrorPage()`, which renders the portal's HTML error page only when
  `portal.error_pages` is enabled, the event is `HTML_ELIGIBLE`, and `Accept` explicitly offers
  `text/html`. Wildcards never qualify. Otherwise the response is today's `problem+json`.
  `portal.ErrorPageClassifier.classify(EventType)` is an exhaustive, default-free switch sorting
  every `EventType` into `HTML_ELIGIBLE` or `KEEP_SHAPE`.
  - `NO_ROUTE_MATCHED` is already `HTML_ELIGIBLE`.
  - `METHOD_NOT_ALLOWED` and `PASSTHROUGH_HOST_SMUGGLED` are `KEEP_SHAPE`.
  - `RESERVED_BODY_TOO_LARGE` is `KEEP_SHAPE`, correctly: it occurs only on reserved, API-only paths.
- **The rendering constraints.** No redirect is involved, so the status code is preserved; the
  rendered titles are fixed, non-interpolated strings per status; `Accept: */*` keeps receiving
  `problem+json`.
- **Request-framing rejection.** `pipeline/FramingGate` rejects `Content-Length` together with
  `Transfer-Encoding`, multiple or comma-listed `Content-Length`, a body on a bodyless method (with
  the `allow_get_with_content_length_body` opt-in), and a `Connection`-header strip of a framing or
  trust header.
- **WebSocket Origin enforcement.** `pipeline/OriginValidationStage` fail-closed rejects an upgrade
  carrying a foreign or absent `Origin` against any route's configured `allowed_origins`,
  `require: session` routes included.

## Deliverables

1. **Re-categorise the three `EventType` members that do not fit `INPUT_VALIDATION`.**

   In `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/events/EventType.java`:

   | Member | Status | What actually happened |
   |---|---|---|
   | `NO_ROUTE_MATCHED` | 404 | deny-by-default routing — the request addressed nothing |
   | `PASSTHROUGH_HOST_SMUGGLED` | 404 | a reserved-for-passthrough `Host` on a terminated connection |
   | `METHOD_NOT_ALLOWED` | 405 | the verb is outside the route's effective allowlist |

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
   can be alerted on cleanly** — which is why this lands before `PLAN-V02-07` weights the taxonomy.

2. **Decide the HTML eligibility of the two re-categorised members that still keep their shape.**

   Once D1 lands the new category, decide whether `METHOD_NOT_ALLOWED` and
   `PASSTHROUGH_HOST_SMUGGLED` flip from `KEEP_SHAPE` to `HTML_ELIGIBLE` in
   `ErrorPageClassifier.classify()`. That is a one-line change per event plus a test, and a recorded
   reason either way. It is never a new negotiation mechanism — the mechanism exists.

3. **Confirm the three rendering constraints are pinned by a test, and add one only where a gap is
   found.**

   - **No redirect.** A `302` on a `404` destroys the status code for monitoring, has no landing page
     to point at in a pure-proxy deployment, and turns an error carried as a URL parameter into a
     reflection sink.
   - **The HTML body is static per status.** Threat-model control `gw-12` asserts that no error
     response contains internal detail: no request path, no query string, no header value, no
     hostname may be echoed. Static also means there is no interpolation site.
   - **`Accept: */*` (curl's default) keeps receiving `problem+json`.** Only an explicit `text/html`
     offer switches.

4. **Leave `/auth/userinfo` and the reserved endpoints alone — this is a PROHIBITED-ASSERTION
   boundary, not a scoping preference.**

   `/auth/userinfo` is `Accept`-blind by design. `demo-client/doc/playwright-suite.adoc` records
   a **PROHIBITED ASSERTION** against ever testing it otherwise, and the demo client encodes it as a
   runtime assertion with `redirect: 'error'`. #189 explicitly does not propose changing it. **Scope
   the change to the edge's own rejections.** Reachability is the right filter: `404` and `405` are
   reachable by navigation, a `413` on a reserved POST path is not.

5. **Tests and documentation, both named as their own line item.**

   - Tests: category assertions for all three re-categorised members, including the metric
     dimension; for every event D2 flips, its `renderProblem` call site under `Accept: text/html`
     and under `Accept: */*`; a case asserting `/auth/userinfo` is unchanged. `GatewayEdgeRouteTest`
     and `EventTypeTest` exist and are the natural homes.
   - Documentation: the consolidated status mapping in `doc/architecture.adoc`, which lists
     `INPUT_VALIDATION` against `404` and `405`. Find the table by content.
   - An **ADR** for the new category, since it changes a public contract. Derive its ordinal from
     `doc/adr/` on the branch at write time; a duplicate ordinal fails the build.

6. **Request-framing control `gw-02`: audit the existing gate, build only the residual.**

   `doc/security-threat-model.adoc` marks `gw-02` as `GAP`. That row is stale documentation for the
   most part: `FramingGate` already enforces the bulk of the control (see Already in Place). So:

   - (a) Confirm `FramingGate`'s coverage against the `gw-02` control, case by case. The control in
     full: reject a request bearing both `Content-Length` and `Transfer-Encoding`; a body on a
     bodyless method (HEAD/GET, unless `security_defaults.allow_get_with_content_length_body` is
     enabled — that opt-in is preserved unchanged); a bare-LF chunk terminator or other
     non-RFC-9112 chunk framing; a `Connection`-header cleanup that would strip
     `Content-Length`/`Transfer-Encoding`; re-derive or validate framing after any header mutation;
     and never pool an upstream connection whose framing was not fully validated.
   - (b) Build ONLY what (a) shows is absent. The two candidates, both unverified, are the bare-LF /
     non-RFC-9112 chunk terminator and the pooled-connection re-validation.
   - (c) Flip the `gw-02` row to `COVERED`, or to `PARTIAL` naming the residual.
   - (d) Re-categorise `FramingGate`'s rejection (today `SECURITY_FILTER_VIOLATION`) only if D1's
     taxonomy calls for it. It is a framing violation, not a routing miss.

   Test against a CL.TE / TE.CL / TE.TE / CL.0 smuggling corpus and assert zero desyncs, plus a case
   confirming `allow_get_with_content_length_body` behaves as before when unset.

7. **WebSocket control `gw-09`: require the Origin allowlist at boot for `require: session` routes.**

   The enforcement mechanism exists and is generic (see Already in Place). The gap is in
   `ConfigValidator.validateWebSocketRoute`: it requires a non-empty `allowed_origins` at boot
   **only** when the effective auth is bearer. A `require: session` WebSocket route with no
   allowlist boots successfully, and the stage's documented "an empty allowlist declares no
   enforcement" fallback is then silently in effect.

   **Extend the boot-time requirement to `Require.SESSION` routes** — one additional condition
   mirroring the existing bearer check — plus a test asserting a session WebSocket route with an
   empty allowlist fails config validation the same way a bearer one does. Also confirm, or add,
   `SameSite` on the session cookie so ambient cookies alone cannot authenticate the socket. Flip
   the `gw-09` row on landing.

   This is a config-validation rule, not a pipeline change. It does not depend on D1 or D2.

**Split-guard.** Seven deliverables — past the presumptive threshold, proceeding unsplit. The count
overstates the work: D2 and D3 are a decision and a confirmation rather than builds, and D6 and D7
are each bounded and touch no file the taxonomy work touches. **If it must split: D1+D2+D3+D4+D5
(the taxonomy and its render and documentation consequences) separates cleanly from D6 (framing) and
from D7 (config validation)** — the latter two share no code with the former or with each other.

## Claim Labels

- OBSERVED: `EventType.java` carries `NO_ROUTE_MATCHED`, `PASSTHROUGH_HOST_SMUGGLED` and
  `METHOD_NOT_ALLOWED` against `EventCategory.INPUT_VALIDATION`.
  - verdict: corroborated | checked_at: 05f6ee3ebb5ae32fb75082b660e6abdb7617edb6 | by: api-sheriff-0-2-0/cleanup | rescoped: n/a | evidence: EventType :62/:69/:71 NO_ROUTE_MATCHED/PASSTHROUGH_HOST_SMUGGLED/METHOD_NOT_ALLOWED still INPUT_VALIDATION
- OBSERVED: `renderProblem` has five call sites in `GatewayEdgeRoute.java` plus its private
  definition; `acceptsHtml` exists only in `SessionAuthenticationStage.java`, with one use.
  - verdict: corroborated | checked_at: 05f6ee3ebb5ae32fb75082b660e6abdb7617edb6 | by: api-sheriff-0-2-0/cleanup | rescoped: n/a | evidence: renderProblem sites :709/:837/:916/:941/:1489 def :1500; acceptsHtml SessionAuthenticationStage def :220 use :205
- ASSERTED BY THE ISSUE, NOT RE-VERIFIED: the three-row behaviour table in #189 (auth failure → 302
  on `text/html`; `/auth/userinfo` → 401 both ways; no route → 404 `problem+json` both ways) was
  measured against a running gateway by the reporter, before HTML error pages existed.
  **Re-measure before building on it** — a measured table from outside is a lead, and its third row
  no longer describes `main` when `portal.error_pages` is enabled.
  - verdict: unverifiable | checked_at: 05f6ee3ebb5ae32fb75082b660e6abdb7617edb6 | by: api-sheriff-0-2-0/cleanup | rescoped: n/a | evidence: runtime Accept-negotiation table needs a running gateway

## Expected Surface

- `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/events/EventType.java`, `EventCategory.java` — D1, D6
- `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/edge/GatewayEdgeRoute.java` — D2 only if a flipped event needs it (its `renderProblem` call sites are otherwise unchanged), D6 (framing-rejection site, to confirm at outline)
- `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/portal/ErrorPageClassifier.java` — D2: flip `METHOD_NOT_ALLOWED`/`PASSTHROUGH_HOST_SMUGGLED` from `KEEP_SHAPE` to `HTML_ELIGIBLE` if D1's re-categorisation warrants it
- `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/config/validation/ConfigValidator.java` — D7 (extend `validateWebSocketRoute`'s bearer-only allowlist requirement to `Require.SESSION`)
- `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/pipeline/OriginValidationStage.java` — read-only reference for D7; this plan does NOT edit it — the enforcement mechanism already exists
- `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/bff/runtime/SessionAuthenticationStage.java` — read-only reference for the D4 boundary only
- `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/pipeline/FramingGate.java` — D6: the existing gw-02 enforcement site. Edited only if D6(b) finds a residual to build.
- `doc/security-threat-model.adoc` — flip `gw-02` (D6) and `gw-09` (D7) from `GAP` on landing
- `doc/architecture.adoc`, `doc/adr/00NN-*.adoc` (new; derive the ordinal at write time) — D5, D6, D7
- OBSERVED (absence, asserted): `/auth/userinfo` and the reserved-path registry are **NOT** edited.

## Dependencies and Sequencing

- Depends on: none. This plan is the head of the WS-03 chain.
- Depended on by: `PLAN-V02-06` (its uniform-404 branch is built against the taxonomy D1 leaves)
  and `PLAN-V02-07` (its weights are assigned to that taxonomy). Both run after this plan. This plan
  owns category membership; `PLAN-V02-06` owns whether an untrusted caller sees the honest code at
  all; `PLAN-V02-07` owns the weights.
- Overlaps with: `PLAN-V02-08` on `SessionAuthenticationStage`, `ConfigValidator` and
  `GatewayEdgeRoute`; `PLAN-V02-14` on `ConfigValidator`. The disjointness gate decides at emit
  time. Not concurrent with `PLAN-V02-04` (ADR corpus audit), because D5 adds a record.

## Issue Closure

**CLOSE [#188](https://github.com/cuioss/API-Sheriff/issues/188) AND
[#189](https://github.com/cuioss/API-Sheriff/issues/189) WHEN THIS PLAN LANDS.** Comment on each
naming the PR and merge commit, state which deliverable discharged it, and close it. For #189, say
that the HTML rendering itself shipped earlier with the portal error pages and name what this plan
added. If any part is deliberately not done, say so on the issue and leave that issue open rather
than closing it with a silent gap.

**A PR body that merely mentions an issue does NOT link or close it** — `closingIssuesReferences`
stays empty. Use a closing keyword in the PR body, or close explicitly after the merge.

## Hand-Off Command

```text
/plan-marshall task="implement .plan/orchestrator/api-sheriff-0-2-0/plans/PLAN-V02-13-terminal-rejection-contract.md" plan_id=plan-v02-13-terminal-rejection-contract
```

## Write-Boundary

The plan implementing this spec touches only its own repository source and tests. It creates and
edits NO file under `.plan/orchestrator/` other than its own `inbox/{sender}-{seq}` message, and
reports its outcome through its PR and that message.
