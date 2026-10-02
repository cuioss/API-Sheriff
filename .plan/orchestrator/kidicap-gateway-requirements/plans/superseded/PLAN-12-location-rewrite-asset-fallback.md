# PLAN-12: Upstream `Location` Rewrite and Asset Index/Fallback Files

epic: kidicap-gateway-requirements
workstream: WS-02

> Staged plan spec — one shippable unit of work, ready for `/plan-marshall` hand-off.
> Lives at `plans/PLAN-12-location-rewrite-asset-fallback.md` and is queued in the epic `status.json`
> `plans[]` field. The orchestrator EMITS the command below; it never launches the plan inline.
> This spec is SELF-SUFFICIENT: the emitted command is a one-line pointer and carries no brief.

## Objective

Two low-priority routing gaps (AS-11, AS-12): an origin's `Location` header is relayed unchanged, so a
frontend redirecting to its own absolute path sends the browser off the gateway; and directory asset
routes have neither a directory index nor an SPA fallback, serving extensionless paths as
`application/octet-stream`. This plan adds opt-in `upstream.rewrite_location` and `asset.index` /
`asset.fallback`.

## Source

KIDICAP Gateway requirements AS-11 and AS-12 (`archive/api-sheriff-aenderungen.adoc` § AS-11, § AS-12),
both priority low.

**AS-11 — rewrite origin `Location` on proxy routes.** Baseline: an origin `Location` is relayed
unchanged (measured); if a frontend redirects to its own absolute path, the browser leaves the gateway.
Proposal: optional per route `upstream.rewrite_location: true`: a `Location` starting with the upstream's
base path is mapped onto the route prefix. Low priority because the consumer's UI contract already
forbids frontends redirecting to their own absolute paths.

**AS-12 — index and fallback file for asset routes.** Baseline: asset routes know no directory index and
no SPA fallback; without a file extension `application/octet-stream` is served (measured). Proposal:
`asset.index: index.html` for directory addresses, `asset.fallback: index.html` for unknown paths without
file extension. Low priority: UIs run via proxy; only needed when a UI is served from the image or
without its own server.

## Deliverables

1. `upstream.rewrite_location` (schema + upstream config): in `ResponseStage`, rewrite a `Location`
   (relative-path or same-upstream-origin absolute) whose path starts with the upstream base path onto
   the route prefix; never rewrite foreign origins; tests incl. context path and query/fragment
   preservation.
2. `asset.index` and `asset.fallback` (schema + asset config) in `DirectoryAssetSource`: index for
   directory addresses, fallback only for unknown extensionless paths; path-traversal protections and
   symlink rules unchanged; content type of index/fallback resolved from the served file.
3. Tests (unit + integration), native-image coverage of new config records, docs in
   `doc/user/endpoint-routes.adoc` and `doc/configuration.adoc`.

## Claim Labels

- OBSERVED: `ResponseStage.relay` copies `Location` like any other non-hop-by-hop header, no rewrite — read at `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/edge/ResponseStage.java` § `relay`
- OBSERVED: the `upstream` schema block has no `rewrite_location` key and the asset schema has no `index` / `fallback` keys — read at `api-sheriff/src/main/resources/schema/endpoint.schema.json` § `routes[].upstream`, `routes[].asset`
- OBSERVED: `DirectoryAssetSource.serve` requires `Files.isRegularFile`, with no index or fallback branch — read at `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/asset/DirectoryAssetSource.java` § `serve`
- OBSERVED: extensionless files default to `application/octet-stream` — read at `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/asset/AssetResponseEnvelope.java` § `DEFAULT_CONTENT_TYPE`
- HYPOTHESIS: the route runtime exposes both upstream base path and route prefix at response time — confirm/refute at `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/routing/RouteRuntime.java` § upstream/prefix accessors (verify-at-outline)
- Verify-first clause: the rewrite's "upstream base path" depends on PLAN-11's `upstream.path` decision — re-read that decision (or HEAD semantics) before scoping deliverable 1.

## Expected Surface

- OBSERVED: `api-sheriff/src/main/resources/schema/endpoint.schema.json` — `upstream.rewrite_location`, `asset.index`, `asset.fallback`
- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/edge/ResponseStage.java` — `relay`
- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/asset/DirectoryAssetSource.java` — `serve`
- HYPOTHESIS: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/config/model/` — upstream and asset config records (verify-at-outline)
- HYPOTHESIS: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/config/RouteTableBuilder.java` — `resolveAsset`, upstream resolution (verify-at-outline)
- HYPOTHESIS: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/routing/RouteRuntime.java` — prefix/base-path access (verify-at-outline)
- HYPOTHESIS: `api-sheriff/src/test/java/de/cuioss/sheriff/gateway/asset/` — asset tests (verify-at-outline)
- HYPOTHESIS: `doc/user/endpoint-routes.adoc` — route docs (verify-at-outline)
- HYPOTHESIS: `doc/configuration.adoc` — keys (verify-at-outline)

## Dependencies and Sequencing

- Depends on: PLAN-04 (shared `endpoint.schema.json`, config model, `RouteTableBuilder.java` — sequenced); PLAN-11 (semantic input for the rewrite)
- Overlaps with: PLAN-04, PLAN-06, PLAN-11 (`RouteTableBuilder.java`); PLAN-06 (`ResponseStage.java`, header application); PLAN-08 (`DirectoryAssetSource.java` not-found response)
- Adjacent to: none

## Hand-Off Command

```text
/plan-marshall task="implement .plan/local/orchestrator/kidicap-gateway-requirements/plans/PLAN-12-location-rewrite-asset-fallback.md"
```

## Write-Boundary

The plan implementing this spec touches only its own repository source and tests. It creates
and edits NO file under `.plan/local/orchestrator/` other than its own
`inbox/{sender}-{seq}` message — the orchestrator owns every other ledger write — and reports
its outcome through its PR and its inbox message. The inbox exception's qualifiers and the
sole sanctioned write mechanism are stated in
`persona-plan-orchestrator/standards/orchestration-model.md` § Ledger Write-Boundary.
