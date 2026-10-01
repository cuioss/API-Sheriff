envelope_version=1
sender_type=plan
sender_id=kidicap-gateway-downstream
epic=kidicap-gateway-requirements
kind=finding
created=2026-09-17T15:00:00Z

## Defect: route header matchers are case-sensitive, and `present: false` is never evaluated

Filed by the downstream deployment `kidicap-gateway`. Measured 2026-09-17 against the 0.2.1 native image
(experiment build in the downstream Helm integration test); the involved code is unchanged at `main`.

### Context

The downstream separates bearer clients from BFF browsers on one path with a route pair distinguished by
`match.headers` on `Authorization` (see the sibling requirement `kidicap-gateway-downstream-005.md`). Two
matcher defects make that pair fail silently.

### Defect 1 — header names are compared case-sensitively

`PipelineRequest` lower-cases inbound header names, and `RouteSelectionStage` passes
`request.singleValueHeaders()` (lower-case keys) to `RouteMatcher.matches`. `RouteMatcher.headersMatch`
looks up `requestHeaders.get(header.name())` with the name exactly as configured. HTTP field names are
case-insensitive (RFC 9110 §5.1), so `name: Authorization` is a legitimate spelling — and it never matches.

Measured: with `name: Authorization, present: true` on a bearer route, a request carrying a valid bearer
token is routed to the sibling session route (`401`, and `200` with the session's token when a cookie is
present). No boot warning.

### Defect 2 — `present: false` does not mean "absent"

`headersMatch` only evaluates `present` when it is `TRUE`; `present: false` constrains nothing, so the route
matches every request. `ConfigValidator.validateRouteDisjointness` (`presenceDistinguishes`) nevertheless
treats `present: true` vs `present: false` as disjoint and accepts the pair. With equal prefixes the stable
sort in `RouteTableBuilder` keeps declaration order, so the outcome depends on file order.

Measured: declaring the `present: false` route before the `present: true` route makes the latter
unreachable — every request, with or without `Authorization`, is served by the first route.

### Expected

1. Header matcher names compared case-insensitively (normalise to lower case at binding or in
   `RouteMatcher.from`).
2. `present: false` matches only when the header is absent, so the disjointness check's assumption holds
   and declaration order no longer matters.
3. Tests: unit tests for both cases in `RouteMatcherTest` / `RouteSelectionStageTest`; an IT with a
   present/absent pair declared in both orders.

### Why it matters downstream

Both defects degrade to "the more permissive-looking but wrong route wins" without any signal; the
downstream can only catch them through integration checks per route pair. Once fixed, the pair is robust
and file order is irrelevant.
