# PLAN-V02-23: Cookie Forwarding Policy — configurable upstream cookies, gateway cookies never forwarded

epic: api-sheriff-0-2-0
workstream: WS-03

> Staged plan spec — one shippable unit of work, ready for `/plan-marshall` hand-off.
> Lives at `plans/PLAN-V02-23-cookie-forwarding-policy.md` and is queued as one row file,
> `queue/PLAN-V02-23.json`, in the epic ledger. The orchestrator EMITS the command below; it never
> launches the plan inline. This spec is SELF-SUFFICIENT: the emitted command is a one-line pointer
> and carries no brief, so every per-plan carry is authored here and nowhere else.


> ⛔ **PARKED 2026-10-14 at phase 4-plan (operator).** The outline came to 8 deliverables, ~67 files and
> 170,000–230,000 diff characters, about half of it the cost the structure charges for any new
> per-route policy. Those plans have all landed: `PLAN-V02-24` (#465), `PLAN-V02-25` (#487), `PLAN-V02-26` (#463), `PLAN-V02-28` (#467),
> `PLAN-V02-40` … `PLAN-V02-43` (#485, #489, #502, #505) and `PLAN-V02-44` (#476). Re-grounded against `00293178`:
> `cookies` becomes one `PolicyBlock` descriptor, its policy reaches the stages through `routing/Route`, and
> its boot rules live in a `config/validation/rule/` class registered in `ConfigValidator.DEFAULT_RULES` (ADR-0071).
> The local plan `plan-v02-23-cookie-forwarding-policy` (phase 4-plan) has an outline that predates those
> seams and is not resumed: this spec restarts as `plan-v02-23-cookie-forwarding-policy-r2`. The local plan `plan-v02-23-cookie-forwarding-policy`
> (outline written, no tasks, no branch) is resumed or restarted from this spec then.
>
> **Operator rulings taken during refine/outline — binding for the re-grounded plan:**
> - default `none` also relays NO upstream `Set-Cookie` (D4 wins over D1); breaking, must be stated;
> - same-origin baseline without a BFF = the received scheme + host, plus the exact `additional_origins`;
> - the WebSocket upgrade relays no upstream header today → pin that by test, add no relay;
> - gRPC trailers pass the shared response-header filter;
> - execution profile `full`.
>
> **Documentation, re-scoped:** D6/D7 first create ONE canonical section for the never-forward set
> (the browser `Cookie` never reaches an upstream, and its successor rule) and turn the copies in the
> ADRs, user pages and README into links to it, instead of correcting each copy.
>
> The critical-bug ruling of 2026-10-13 (the blanket `Cookie` strip) stays open until this plan ships.

## Objective

The gateway removes the browser's `Cookie` header from every proxied request without exception
(`ConnectionHeaders.REQUEST_STRIP`, made absolute by ADR-0037), while it relays every upstream
`Set-Cookie` that does not name a gateway cookie. So any upstream that keeps its own state in
cookies — an identity provider fronted under `/auth`, a session-based web application, an upstream
load balancer's sticky cookie — can set cookies it never gets back. **The operator ruled this a
critical bug (2026-10-13):** "We need to take care of cookies, eventually filter / allow some, but
removing all cookies is a bug." This plan replaces the blanket strip with an operator-configured
cookie policy — `none`, `all`, or a list of names — resolved gateway → anchor → endpoint → route,
applies the same policy to the cookies an upstream may set, defends cookie-forwarding routes against
cross-site requests at the gateway, and keeps every API Sheriff cookie away from every upstream
under every policy.

The design was settled with the operator on 2026-10-13 after a survey of other gateways and an
adversarial review; the decisions below are rulings, not proposals. Re-open one only on a finding
the outline makes against the source, and report it rather than deciding it silently.

## Deliverables

**Eight deliverables — above the presumptive split threshold, proceeding unsplit (operator
direction, 2026-10-13).** The request side, the response side, the CSRF guard and the record are one
contract: shipping the request side alone opens cookie tossing between upstreams (D4) and unguarded
ambient-credential routes (D5), so no half of it is safe to merge on its own. Size the PR at outline:
the review bots refuse more than 100 files or 150,000 diff characters, so split into two sequential
PRs if needed (D1–D5 code first, D6–D8 record, docs and IT second), never into two plans.

1. **The `cookies` policy block and its configuration surface.**
   - A new policy block (working name `cookies`; settle the final key names at outline and record
     them in D6) with a forwarding mode whose value is **YAML-typed**: the scalar `none`, the scalar
     `all`, or a YAML **list of cookie names** (a positive list; every other cookie is blocked). Not
     a comma-separated string: `all` and `none` are legal cookie names, and a scalar-versus-list
     type is what keeps the modes and the names apart.
   - **Scopes and resolution: gateway (global) → anchor → endpoint → route**, each step **replacing
     the whole block, never merging** — the rule ADR-0067 decides and `doc/user/anchors.adoc` § Policy-block resolution states for every policy block ("The winning block replaces every broader declaration wholesale"). A route's list replaces the
     inherited list; it does not add to it. `forward` is declarable at endpoint and route scope only (`PolicyBlock.FORWARD`, ADR-0036, ADR-0067), so this is a block of its own at all four scopes — a new `PolicyBlock` descriptor in `PolicyBlock.ALL` — not a key inside `forward`.
   - **Default: `none`** when no scope declares the block (the descriptor's nothing-declared fallback). On the request side this is today's behaviour. On the response side it is breaking: under `none` no upstream `Set-Cookie` is relayed (operator ruling), and D6 and the release notes must say so. A declared empty list is not a declaration (`PolicyBlock#isDeclared`); settle at outline whether `[]` is refused at boot.
   - **A global `all` is refused at boot.** `all` hands one upstream every other upstream's cookies
     on the shared gateway host; at the global scope it would do that for every API route silently.
     `all` is allowed at anchor, endpoint and route scope.
   - **An anchor block replaced at endpoint or route scope logs the existing override WARN**: set the descriptor's `overrideWarn`, so `RouteTableBuilder#warnOnAnchorOverride` emits `ApiSheriff-101`. Extend that record's block list in `doc/LogMessages.adoc` and the Override WARN column of the `anchors.adoc` scope table. It fires on every such replacement, not only a relaxing one (e.g. a route `all` under an anchor `none`). A relaxation-only WARN would be a new record.
   - **Name validation at boot:** every listed name is a non-empty RFC 6265 token; matching is
     exact and case-sensitive, as a user agent stores names. **A list naming a gateway cookie (D2)
     is a boot error**, never a silent drop.
   - **`forward.headers_allow` naming `Cookie` becomes a boot error** whose message points at the new
     block. Today it is accepted and silently has no effect
     (`ForwardPolicyStageTest.cookieNeverCrossesEvenWhenAllowListed` pins the silent ignore; that
     test changes with this deliverable).
   - **`forward.set_headers` naming `Cookie` is a boot error.** It is merged after the request strip
     today, so it is the one way a fixed `Cookie` could reach an upstream outside any policy.
   - JSON schema (`gateway.schema.json`, `endpoint.schema.json`), the `PolicyBlock` descriptor, the row in the `doc/user/anchors.adoc` scope table `[[_policy_block_scopes]]` (all three bound by `PolicyBlockSchemaContractTest`), the config model records, and `ConfigModelReflection` — a record unregistered there boots on the JVM and fails only in the native image.

2. **One definition of the gateway's own cookies, used by both directions.**
   - Today the set is `BffRuntimeProducer.gatewayCookieNames(SessionBinding)`: the session
     binding's cookie names (the configured session cookie, `__Host-sheriff-session` by default, and
     `-activity` in cookie mode), `__Host-sheriff-binding` and `__Host-sheriff-logout`. It is
     **empty when no BFF is configured**.
   - Make it one definition that both the request side (D3) and the response side (D4) consume,
     and that **always** covers the `__Host-sheriff-` prefix as well — with or without a BFF — so a
     gateway cookie can never cross because a mode happens to be off. Cover any gateway cookie a
     later plan adds by construction, not by a second list.
   - Match the way a user agent reads a name, as `UpstreamSetCookieFilter` already does: the text
     before the first `=` of the pair; `__Host-` / `__Secure-` compared case-insensitively; a
     nameless crumb whose value begins with `<gateway-name>=` counts as that cookie.

3. **Request side: the `Cookie` header is filtered by the route's policy, never stripped blindly.**
   - `cookie` leaves the absolute membership of `ConnectionHeaders.REQUEST_STRIP` and becomes
     policy-governed (`ForwardPolicyStage`, stage 5). Gateway cookies (D2) are removed under **every**
     mode, `all` included; then `none` sends no `Cookie`, a list keeps only the listed names, `all`
     keeps the rest. Rebuild the header from the parsed pairs; keep duplicate names and their order.
   - Applies to every dispatch that takes stage 5's header set: HTTP proxy, gRPC
     (`GrpcDispatchStage`) and the WebSocket upgrade (`WebSocketRelayStage`).
   - **HTTP/2 split cookies:** Vert.x 4.5.34 already joins multiple HTTP/2 `cookie` fields with
     `"; "` before the gateway sees them (`Http2HeadersAdaptor` constructor, used by
     `Http2ServerRequest`), so the first-value copy in `ForwardPolicyStage` loses nothing. Pin that
     with a regression test — an HTTP/2 request whose cookies arrive as separate fields forwards
     every allowed cookie — so a Vert.x upgrade cannot change it silently. HTTP/1.1 clients must not
     send more than one `Cookie` header (RFC 6265 §5.4); keep the first-value rule there.
   - Forwarded cookies count against the raised cookie-size cap a cookie-mode BFF gateway applies in
     `BasicChecksStage`; state in the docs that an upstream may refuse what the gateway accepted.

4. **Response side: an upstream may only set the cookies its route may receive.**
   - `UpstreamSetCookieFilter` (applied in `ResponseStage`, one gateway-wide instance built at `GatewayEdgeRoute.java:401`) becomes driven by the route's policy, passed into `relay` / `relayWithTrailers` from `TerminalDispatch`:
     a **list** relays only `Set-Cookie` lines naming a listed cookie; **`none`** relays none; **`all`**
     relays any line except a gateway cookie. Gateway cookie names (D2) are dropped under every mode.
     Without this, any upstream on the shared host can set a cookie named like another route's
     listed cookie, which that route then forwards — session fixation against, for example, a
     fronted identity provider.
   - **Drop the `Domain` attribute from every relayed `Set-Cookie`.** A cookie without `Domain` is
     host-only on the gateway's host, which is the strictest binding there is; an upstream's
     `Domain=<its internal host>` is otherwise refused by the browser, and a broader `Domain`
     would widen the cookie to sibling hosts. `Path` is left as sent: the gateway forwards paths
     unchanged, so an upstream's `Path` already matches what the browser sees.
   - Cover every response path that relays upstream headers: `ResponseStage#relay` (HTTP) and `#relayWithTrailers` (gRPC headers, and the gRPC trailers per the operator ruling). Both header paths already call the one private predicate `ResponseStage#relaysUpstreamHeader`; make that predicate policy-aware and shared rather than adding a second filter. The WebSocket upgrade relays no upstream header (operator ruling: pin by test, add no relay), and a `source: upstream` asset already strips every `Set-Cookie` (`AssetResponseEnvelope`); pin both by test. Write one test per path. `PLAN-V02-05` D1 later adds its identity-header strip to the same predicate and reuses
     these tests, so build it as the shared seam, not a cookie-only special case.

5. **A gateway-side same-origin check on every route that forwards cookies.**
   - `origin` and `referer` stay in the never-forward set (ADR-0037), and `Sec-Fetch-*` crosses only
     under forward-all, so a cookie-authenticated upstream cannot run its own Origin check behind
     the gateway. Forwarding cookies would therefore create ambient-credential routes nobody
     defends. The gateway enforces it instead — the Envoy CSRF-filter model, chosen by the operator
     on 2026-10-13.
   - On a route whose effective policy forwards **any** cookie (a list or `all`): unsafe methods and
     WebSocket upgrades must pass a same-origin check, reusing `CsrfDefence` (`Origin`; when absent,
     `Sec-Fetch-Site: same-origin`; both absent is refused). Safe methods are not checked.
     `CsrfDefence` is session-free, but its only instance belongs to the BFF runtime
     (`BffRuntime#csrfDefence` throws when inert) and its only call site is `GatewayEdgeRoute.java:588–590`
     (session branch only): build a per-route instance at assembly from the D5 origin set. `enforce`
     passes safe methods, so a WebSocket upgrade (`GET`) needs its own check. A `protocol: websocket`
     route runs `OriginValidationStage` against `websocket.allowed_origins` (`TerminalDispatch.java:281`),
     skipped when that list is empty (`require: none`); settle at outline whether a cookie-forwarding
     WebSocket route must declare a non-empty `allowed_origins` or runs the D5 check.
   - A per-block list of additional allowed origins (exact values only — no prefix, suffix or regex
     matching, which Envoy's own documentation warns is ambiguous).
   - The check is on by default for cookie-forwarding routes; switching it off is allowed per block
     and logs a boot WARN naming the route.

6. **Rewrite ADR-0037 in place** (operator ruling: replace, do not supersede; the record is still
   `Proposed`, and ADR-0051 was rewritten in place the same way by `PLAN-V02-01`).
   - `cookie` moves out of "credentials the gateway terminates or mediates" as an absolute member;
     the gateway's own cookies stay absolute. Replace the sentence "Re-admitting `Cookie` is a
     security regression in every case the gateway supports" with the new decision and its reason:
     fronting cookie-dependent upstreams is now a supported case.
   - Record the policy (D1–D5) and the rejected alternatives with the survey behind them: forward
     everything by default (Kong, Traefik, NGINX, APISIX); an all-or-nothing per-endpoint opt-in
     (KrakenD `input_headers`); the absolute strip (Duende BFF, Spring Cloud Gateway
     `RemoveRequestHeader=Cookie`); a comma-separated value; a global `all`; forwarding `Origin` and
     leaving CSRF to each upstream. Name the Duende advisory the threat model already cites
     (GHSA-vvg7-p7jw-8qr3, session cookie forwarded to the proxied API) as the class D2 prevents.
   - Settle the record's status. Update ADR-0036's single cookie reference if it contradicts the
     rewrite.

7. **Documentation: user docs, reference and threat model.**
   - `doc/configuration.adoc`: the block, its four scopes, the three modes, the defaults, the boot
     errors and WARNs.
   - `doc/user/anchors.adoc`: the block in the resolution chain and in the list of policy blocks.
   - `doc/user/endpoint-routes.adoc`: how a route fronts a cookie-dependent upstream, with an example.
   - `doc/user/protocol-routes.adoc`: the WebSocket upgrade and gRPC behaviour.
   - `doc/user/bff-session.adoc`, `doc/user/bff-cookie.adoc`, `doc/variants/02-bff-session.adoc`:
     the gateway's cookies are never forwarded under any policy; correct every sentence that says the
     browser `Cookie` is withheld "on every route".
   - `doc/architecture.adoc`: the forward stage and the response relay.
   - `doc/security-threat-model.adoc`: rows for cross-upstream cookie exposure under `all`, cookie
     tossing between upstreams on one host (D4), and cross-site requests on cookie-forwarding routes
     (D5); correct the existing statements that the browser `Cookie` never reaches an upstream.
   - `doc/LogMessages.adoc`: the new WARN and ERROR records.

8. **End-to-end proof in the integration suite, against `go-httpbin`.**
   - `go-httpbin` (`ghcr.io/mccutchen/go-httpbin:2.23.1`, already in
     `integration-tests/docker-compose.yml`) echoes received cookies at `/cookies` and sets them at
     `/cookies/set`. Add a gateway endpoint configuration under
     `integration-tests/src/main/docker/sheriff-config/endpoints/` with routes for each mode, and
     prove: the default `none` sends nothing; a list sends exactly the listed names; `all` sends
     everything except gateway cookies; on a BFF session route the session cookie never reaches the
     upstream under `all`; an upstream cannot set a cookie outside its route's policy; `Domain` is
     dropped; a cross-site unsafe request on a cookie-forwarding route is refused.
   - Follow the test conventions #424 introduced: surefire `@Tag` fork groups
     (`SurefireForkGroupContractTest`), the failsafe concurrency group
     (`FailsafeConcurrencyContractTest`), and `UnreachablePort` for dead ports.
   - **Ask of every new key: if it were deleted, would a test go red?** (`CLAUDE.md`,
     `doc/development/declared-limit-assertion-coverage.adoc`.)

**Not in this plan:** the `/auth`-fronted Keycloak sample (`PLAN-V02-12` D3). It needs this plan's policy; `PLAN-V02-12` D1, the back-channel origin, has landed (#492, ADR-0069). The sample stays `PLAN-V02-12`'s held D3, which its landing schedules after this plan and `PLAN-V02-15`; this plan's `go-httpbin`
suite is its proof.

## Claim Labels

- OBSERVED: `cookie` is an absolute member of the request never-forward set — read at `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/http/ConnectionHeaders.java` § `REQUEST_STRIP` (lines 157–158 at `00293178`), tested through `ConnectionHeaders#isRequestStripped` (line 212) and applied in `ForwardPolicyStage#applyRequestStrip` (line 312), whose `isWithheldFromUpstream` (lines 318–328) re-admits only `authorization` and lets `te` cross only as `trailers`.
  - verdict: corroborated | checked_at: 00293178dd8772344f5a908878719b78d67b6524 | by: api-sheriff-0-2-0/cleanup | rescoped: n/a | evidence: ConnectionHeaders REQUEST_STRIP ~157-158; ForwardPolicyStage applyRequestStrip ~312; anchors refreshed
- OBSERVED: `headers_allow` naming `Cookie` is accepted at boot and has no effect — no rule class under `config/validation/rule/` covers it (the only never-forward rule is `ForwardingRules#validateTokenRelayForwardConflict`, line 152, for `Authorization`, registered in `ConfigValidator.DEFAULT_RULES` at line 242), and `ForwardPolicyStageTest.cookieNeverCrossesEvenWhenAllowListed` (line 810) asserts the ignore. Nothing refuses `forward.set_headers` naming `Cookie` either: `ForwardPolicyStage#process` merges `set_headers` (line 219) after the strip (line 217).
  - verdict: contradicted | checked_at: 00293178dd8772344f5a908878719b78d67b6524 | by: api-sheriff-0-2-0/cleanup | rescoped: yes | evidence: rule moved to ForwardingRules (#489); no cookie rule; ForwardPolicyStageTest ~810 asserts the ignore; bullet re-anchored
- OBSERVED: the response side relays every upstream `Set-Cookie` except a gateway cookie name — read at `edge/relay/UpstreamSetCookieFilter.java` (class Javadoc, lines 20–47; `relays`, line 69, relays every line when the name set is empty). The filter is built in the `ResponseStage` constructor (lines 96–97) from the names `GatewayEdgeRoute` passes once per gateway (`new ResponseStage(bffRuntime.gatewayCookieNames())`, line 401). It is applied through the one private predicate `ResponseStage#relaysUpstreamHeader` (lines 105–107) by both `relay` (line 141, HTTP) and `relayWithTrailers` (line 247, gRPC). `ResponseStage` is gateway-wide, not per route: a per-route policy has to reach those two methods through `routing/Route` from `edge/terminal/TerminalDispatch` (lines 234 and 393).
  - verdict: contradicted | checked_at: 00293178dd8772344f5a908878719b78d67b6524 | by: api-sheriff-0-2-0/cleanup | rescoped: yes | evidence: ResponseStage built once per gateway (GatewayEdgeRoute ~401), not per route; bullet corrected
- OBSERVED: the gateway cookie names are derived from the active BFF and are empty without one — read at `quarkus/BffRuntimeProducer.java` § `gatewayCookieNames(SessionBinding)` (lines 727–739, called at line 712), carried by `bff/runtime/BffRuntime#gatewayCookieNames` (line 222), and `Set.of()` in `BffRuntime.inert()` (lines 230–231). Constants: `SessionCookieCodec.DEFAULT_COOKIE_NAME = "__Host-sheriff-session"` (`bff/session/SessionCookieCodec.java:45`), `SessionActivityCookieCodec.COOKIE_NAME_SUFFIX = "-activity"` (`bff/cookie/SessionActivityCookieCodec.java:105`), `BindingCookieCodec.COOKIE_NAME = "__Host-sheriff-binding"` (`bff/pending/BindingCookieCodec.java:55`), `RpInitiatedLogout.LOGOUT_STATE_COOKIE_NAME = "__Host-sheriff-logout"` (`bff/logout/RpInitiatedLogout.java:78`).
  - verdict: corroborated | checked_at: 00293178dd8772344f5a908878719b78d67b6524 | by: api-sheriff-0-2-0/cleanup | rescoped: n/a | evidence: BffRuntimeProducer.gatewayCookieNames ~727-739; BffRuntime.inert Set.of(); anchors refreshed
- OBSERVED: ADR-0037 is `Proposed` and states "Re-admitting `Cookie` is a security regression in
  every case the gateway supports" — read at
  `doc/adr/0037-The_gateway-owned_header_sets_are_enumerated_per_direction_and_bounded_by_knowledge.adoc` (Status line 15; sentence lines 161–162 at `00293178`).
  - verdict: corroborated | checked_at: 00293178dd8772344f5a908878719b78d67b6524 | by: api-sheriff-0-2-0/cleanup | rescoped: n/a | evidence: ADR-0037 Status Proposed (~15), sentence ~161-162
- OBSERVED: policy blocks resolve gateway → anchor → endpoint → route, nearest declared scope wins with whole-block replacement, through one descriptor per block. The code is `config/PolicyBlock.java` (record line 89, `ALL` line 172, `isDeclared` line 259: `null` or an empty list is not a declaration). The rule is decided by ADR-0067 and documented in `doc/user/anchors.adoc` § Policy-block resolution and its scope table `[[_policy_block_scopes]]`, which `PolicyBlockSchemaContractTest` binds to the descriptors and to both schemas. The override WARN (`ApiSheriff-101`, `config/ConfigLogMessages.java:137`, emitted by `RouteTableBuilder#warnOnAnchorOverride`, line 574) is not keyed on relaxing. It fires whenever a block whose descriptor sets `overrideWarn` (today `allowed_methods` and `security_filter`) is declared by the anchor and replaced at endpoint or route scope.
  - verdict: contradicted | checked_at: 00293178dd8772344f5a908878719b78d67b6524 | by: api-sheriff-0-2-0/cleanup | rescoped: yes | evidence: override WARN ApiSheriff-101 fires on any anchor replacement for overrideWarn blocks, not on relaxing; PolicyBlock/ADR-0067 re-scoped
- OBSERVED: `forward` is declarable at endpoint and route scope — `PolicyBlock.FORWARD` (`config/PolicyBlock.java:159–163`, endpoint scope added by #465), read from `EndpointConfig#forward` (`config/model/EndpointConfig.java:102`) and `RouteConfig#forward` (`config/model/RouteConfig.java:68`), and resolved once in `RouteTableBuilder#resolveRoute` (line 256) into `routing/Route#effectiveForward`. `GatewayConfig` and `AnchorConfig` carry no `ForwardConfig`.
  - verdict: contradicted | checked_at: 00293178dd8772344f5a908878719b78d67b6524 | by: api-sheriff-0-2-0/cleanup | rescoped: yes | evidence: forward is endpoint+route scope since #465 (PolicyBlock.FORWARD); D1 re-scoped
- OBSERVED: Vert.x 4.5.34 (managed by `quarkus-bom` 3.40.1 via `cui-quarkus-parent` 1.7.7) joins HTTP/2 `cookie` fields into one value — read in `vertx-core-4.5.34-sources.jar` § `io.vertx.core.http.impl.headers.Http2HeadersAdaptor` (constructor, lines 33–40, citing RFC 7540 §8.1.2.5), used by `Http2ServerRequest`. The gateway copies headers through `edge/request/EdgeRequests#toListMap` (line 264), and stage 5 keeps the first value (`ForwardPolicyStage.java:285`).
  - verdict: corroborated | checked_at: 00293178dd8772344f5a908878719b78d67b6524 | by: api-sheriff-0-2-0/cleanup | rescoped: n/a | evidence: Vert.x 4.5.34 Http2HeadersAdaptor joins cookie fields (~33-40); EdgeRequests.toListMap ~264
- OBSERVED: HTTP proxy, gRPC and WebSocket dispatch all take stage 5's forwarded header set — `edge/upstream/DispatchStage#dispatch` (lines 326–327), `edge/upstream/GrpcDispatchStage#dispatch` (lines 106–107) and `edge/relay/WebSocketRelayStage#relay` (lines 212–213, applied at line 237) (`forwardHeaders` parameter). All three are reached from `edge/terminal/TerminalDispatch` (`dispatchAndRelay` line 196, `dispatchGrpc` line 352, `dispatchWebSocket` line 279), fed by the single `forwardPolicyStage.process` call at `edge/GatewayEdgeRoute.java:611`.
  - verdict: corroborated | checked_at: 00293178dd8772344f5a908878719b78d67b6524 | by: api-sheriff-0-2-0/cleanup | rescoped: n/a | evidence: DispatchStage, GrpcDispatchStage, WebSocketRelayStage take forwardHeaders; reached from edge/terminal/TerminalDispatch; anchors refreshed
- OBSERVED: `CsrfDefence#enforce` (`bff/csrf/CsrfDefence.java:101`) skips safe methods and accepts an `Origin` only when it is in the configured trusted-origin set (constructor line 84; `session.csrf.trusted_origins`, defaulting to the `redirect_uri` origin, built at `quarkus/BffRuntimeProducer.java:703`). It falls back to `Sec-Fetch-Site: same-origin` only when `Origin` is absent (lines 113, 119–124).
  - verdict: contradicted | checked_at: 00293178dd8772344f5a908878719b78d67b6524 | by: api-sheriff-0-2-0/cleanup | rescoped: yes | evidence: CsrfDefence accepts Origin only from the configured trusted-origin set, not same-origin; Sec-Fetch-Site fallback only when Origin absent
- OBSERVED: the gRPC response relays upstream headers through the same predicate as HTTP (`ResponseStage#relayWithTrailers`, lines 258–262 → `relaysUpstreamHeader`); its trailer copy (lines 276–281) is the third site the operator ruling "gRPC trailers pass the shared response-header filter" covers. The WebSocket upgrade relays no upstream header: Vert.x answers the client upgrade, and an upstream refusal is relayed as its status alone (`edge/relay/WebSocketRelayStage#onUpstreamFailure`, from line 284). A `source: upstream` asset strips every `Set-Cookie` (`asset/AssetResponseEnvelope#isGatewayOwned`, line 217). D4's relay sites are therefore `ResponseStage#relay`, `#relayWithTrailers` and the gRPC trailer copy.
  - verdict: contradicted | checked_at: 00293178dd8772344f5a908878719b78d67b6524 | by: api-sheriff-0-2-0/cleanup | rescoped: yes | evidence: WebSocket upgrade relays no upstream header; gRPC headers via relaysUpstreamHeader; D4 relay sites re-scoped
- OBSERVED: `CsrfDefence` needs no session. Its constructor takes only the trusted-origin set (`bff/csrf/CsrfDefence.java:84`), and `enforce(PipelineRequest)` (line 101) reads only the method and two headers. Its only instance is built by `BffRuntimeProducer` (line 703) and exposed by `BffRuntime#csrfDefence` (lines 274–279), which throws on the inert runtime. Its only call site is `edge/GatewayEdgeRoute.java:588–590` (`bffRuntime.isActive() && branch == AuthBranch.SESSION`), not `SessionAuthenticationStage`. A cookie-forwarding route without a BFF therefore needs its own instance, built per route at assembly. `enforce` passes every safe method, so it does not check a WebSocket upgrade (`GET`); the upgrade's own gate is `pipeline/OriginValidationStage#validate` (line 68, called at `edge/terminal/TerminalDispatch.java:281`), which applies `websocket.allowed_origins` and is skipped when that list is empty (only a `require: none` route may boot with it empty).
  - verdict: contradicted | checked_at: 00293178dd8772344f5a908878719b78d67b6524 | by: api-sheriff-0-2-0/cleanup | rescoped: yes | evidence: hypothesis resolves (CsrfDefence session-free) but only call site is GatewayEdgeRoute ~588-590, not SessionAuthenticationStage; D5 re-scoped
- Verify-first clause: `PLAN-V02-12` (#492), `PLAN-V02-21` (#439) and the whole edge/config refactor (#463, #465, #467, #476, #485, #487, #489, #502, #505) have landed at `00293178`. Re-read `ForwardPolicyStage`, `PolicyBlock`, `RouteTableBuilder#resolveRoute`, `routing/Route`, `edge/terminal/TerminalDispatch`, `edge/relay/ResponseStage` and `UpstreamSetCookieFilter` at the outline's HEAD. `PLAN-V02-46` edits `edge/upstream/`, `edge/admission/` and `doc/security-threat-model.adoc`.
  - verdict: contradicted | checked_at: 00293178dd8772344f5a908878719b78d67b6524 | by: api-sheriff-0-2-0/cleanup | rescoped: yes | evidence: all preconditions landed at 00293178; clause rewritten to the current seams

## Expected Surface

- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/http/ConnectionHeaders.java` — D3
- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/forward/ForwardPolicyStage.java` — D3
- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/edge/relay/UpstreamSetCookieFilter.java`, `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/edge/relay/ResponseStage.java` — D4
- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/edge/GatewayEdgeRoute.java`, `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/edge/relay/WebSocketRelayStage.java`, `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/edge/upstream/GrpcDispatchStage.java` — D3, D4, D5 wiring
- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/bff/csrf/CsrfDefence.java` — D5
- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/quarkus/BffRuntimeProducer.java` — D2
- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/config/model/GatewayConfig.java`, `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/config/model/AnchorConfig.java`, `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/config/model/EndpointConfig.java`, `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/config/model/RouteConfig.java`, `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/routing/Route.java` — D1
- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/config/RouteTableBuilder.java` — D1 resolution chain
- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/config/validation/rule/ForwardingRules.java` or a new `config/validation/rule/<Cookie>Rules.java`, registered in `ConfigValidator.DEFAULT_RULES` (ADR-0071) — D1 boot rules
- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/config/PolicyBlock.java` (new descriptor in `ALL`), `routing/Route.java` (effective cookie policy), `config/ConfigLogMessages.java` (`ApiSheriff-101`, new records) — D1, D3–D5
- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/edge/terminal/TerminalDispatch.java` — per-protocol dispatch and relay, D3–D5; `edge/GatewayEdgeRoute.java` now holds only the stage-5 call (~611), the CSRF gate (~588–590) and the `ResponseStage` construction (~401)
- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/bff/runtime/BffRuntime.java` — D2; `pipeline/OriginValidationStage.java` — D5 (read, possibly reused)
- OBSERVED: tests under `api-sheriff/src/test/java/de/cuioss/sheriff/gateway/`: `config/PolicyBlockSchemaContractTest.java`, `config/PolicyBlockTest.java`, `config/RouteTableBuilderTest.java`, `config/validation/rule/ForwardingRulesTest.java` (or the new rule's test), `routing/RouteTest.java`, `edge/GatewayEdgeGatewayCookieTest.java`, `edge/relay/WebSocketRelayStageTest.java`, `edge/upstream/GrpcDispatchStageTest.java`, `quarkus/ConfigModelReflectionTest.java`
- OBSERVED: `doc/adr/0067-…adoc` (summary counts "seven policy blocks"), `README.adoc` (~185, "`Cookie` never crosses under any mode"), `doc/adr/0055-…adoc` (~79) — copies D6/D7 must turn into links
- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/quarkus/ConfigModelReflection.java` — D1 native registration
- OBSERVED: `api-sheriff/src/main/resources/schema/gateway.schema.json`, `api-sheriff/src/main/resources/schema/endpoint.schema.json` — D1
- HYPOTHESIS: one new config record under `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/config/model/` and the log-message class that owns the new records — D1 (verify-at-outline)
- OBSERVED: `api-sheriff/src/test/java/de/cuioss/sheriff/gateway/forward/ForwardPolicyStageTest.java`, `api-sheriff/src/test/java/de/cuioss/sheriff/gateway/http/ConnectionHeadersTest.java`, `api-sheriff/src/test/java/de/cuioss/sheriff/gateway/edge/relay/UpstreamSetCookieFilterTest.java`, `api-sheriff/src/test/java/de/cuioss/sheriff/gateway/edge/relay/ResponseStageTest.java`, `api-sheriff/src/test/java/de/cuioss/sheriff/gateway/bff/csrf/CsrfDefenceTest.java`, `api-sheriff/src/test/java/de/cuioss/sheriff/gateway/config/validation/ConfigValidatorTest.java` — D1–D5
- OBSERVED: `integration-tests/src/main/docker/sheriff-config/endpoints/`, `integration-tests/src/test/java/de/cuioss/sheriff/gateway/integration/`, `integration-tests/pom.xml` — D8
- OBSERVED: `doc/adr/0037-The_gateway-owned_header_sets_are_enumerated_per_direction_and_bounded_by_knowledge.adoc`, `doc/adr/0036-Forward_filtering_is_a_three-mode_policy_inside_an_authorised_route.adoc` — D6
- OBSERVED: `doc/configuration.adoc`, `doc/user/anchors.adoc`, `doc/user/endpoint-routes.adoc`, `doc/user/protocol-routes.adoc`, `doc/user/bff-session.adoc`, `doc/user/bff-cookie.adoc`, `doc/variants/02-bff-session.adoc`, `doc/architecture.adoc`, `doc/security-threat-model.adoc`, `doc/LogMessages.adoc` — D7

## Dependencies and Sequencing

- Depends on: `PLAN-V02-12` (#492) and `PLAN-V02-21` (#439), and the edge/config refactor it was parked for (`PLAN-V02-24` #465, `-44` #476, `-25` #487, `-26` #463, `-28` #467, `-40` … `-43` #485/#489/#502/#505). All have landed. Operator direction (2026-10-13): this plan is the next one emitted, ahead of every other staged plan in the queue.
- Both concurrent writers named at the 2026-10-14 emission have merged (`PLAN-V02-12` #492; kidicap
  `PLAN-30` #448). In flight at `00293178`: `PLAN-V02-22` (demo client, docs); `PLAN-V02-46` (`edge/upstream/`,
  `edge/admission/`, `doc/security-threat-model.adoc`) is emitted alongside. Re-fetch `origin/main` before
  outline and again before push.
- Depended on by: `PLAN-V02-12`'s held D3 (the `/auth`-fronted Keycloak sample), which also needs
  `PLAN-V02-12` D1 and its upstream `token-sheriff-client` release.
- Overlaps with: every staged spec declaring `edge/**`, `forward/**`, `config/**` or the docs above —
  `PLAN-V02-05`, `-06`, `-07`, `-20` in particular. Treat any plan touching the edge relay as
  overlapping `edge/**` (epic sequencing note, 2026-10-04).
- Not concurrent with `PLAN-V02-04` (ADR corpus audit): D6 rewrites ADR-0037.
- Adjacent to: the BFF session cookie machinery (`bff/cookie/`, `bff/session/`, `bff/pending/`, `bff/logout/`) — read for D2's
  name set, not changed.

## Hand-Off Command

```text
/plan-marshall task="implement .plan/orchestrator/api-sheriff-0-2-0/plans/PLAN-V02-23-cookie-forwarding-policy.md" plan_id=plan-v02-23-cookie-forwarding-policy-r2
```

## Security Findings

Fix a security finding inside this plan whenever possible; that is the preferred outcome. A finding
that is not fixed right away (out of scope, unconfirmed, deferred) stays out of git: it is never
written into a tracked file, a commit message, the PR title, body or review replies, a code comment,
the docs, or this plan's inbox and landing messages. Record it instead in
`.plan/local/security_notes.adoc` (git-ignored; create the file if it is missing and append, never
rewrite). Each entry carries the date, this plan's id, the location (file and line or component),
what was observed, and why it was not fixed. Tracked text may say only that a security note was
recorded locally, never what it says.

## Write-Boundary

The plan implementing this spec touches only its own repository source and tests. It creates and
edits NO file under `.plan/orchestrator/` other than its own `inbox/{sender}-{seq}` message — the
orchestrator owns every other ledger write — and reports its outcome through its PR and its inbox
message. The inbox exception's qualifiers and the sole sanctioned write mechanism are stated in
`persona-plan-orchestrator/standards/orchestration-model.md` § Ledger Write-Boundary.
