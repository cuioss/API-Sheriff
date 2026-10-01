# PLAN-02: Validate Query Values Against the Right Form (Encoded vs Decoded)

epic: kidicap-gateway-requirements
workstream: WS-01

> Staged plan spec — one shippable unit of work, ready for `/plan-marshall` hand-off.
> Lives at `plans/PLAN-02-query-parameter-validation.md` and is queued in the epic `status.json`
> `plans[]` field. The orchestrator EMITS the command below; it never launches the plan inline.
> This spec is SELF-SUFFICIENT: the emitted command is a one-line pointer and carries no brief.

## Objective

Under the `strict` security-filter profile, legitimate query values — spaces, umlauts, `:`, `/` — are
rejected with `400` (AS-13, **blocking** for the KIDICAP Gateway) because API Sheriff hands ALREADY-DECODED
query values to the `cui-http` parameter validation pipeline, which checks them against the character set
of the ENCODED form. This plan makes the validation check the form it was designed for and ensures
post-decode checks are meaning-based, so free text and structured values (names, timestamps, JSON arrays)
pass under `strict` while nullbytes, control characters, CR/LF, double encoding and invalid UTF-8 are
still rejected — without weakening any other profile.

## Source

KIDICAP Gateway requirements AS-13 (`archive/api-sheriff-aenderungen.adoc` § AS-13), priority **blocking**.

- Baseline (measured against 0.2.1): `strict` rejects query values with spaces (`%20` and `+`), umlauts
  (`%C3%BC`), `:`, `/`, `"`, `%`, `#`, `<`, `>` with `400` (`INVALID_CHARACTER` /
  `INVALID_ENCODING at PARAMETER_VALUE`). `lenient` additionally lets only umlauts through — but also lets
  control characters, CR/LF and double encoding through. `minimal` lets everything through but is
  forbidden on authenticated routes.
- Root cause per source (code): API Sheriff passes decoded values (`GatewayEdgeRoute.java:1197`, Vert.x
  `raw.params()`) to `cui-http`'s parameter pipeline; `cui-http`'s `URLParameterValidationPipeline`
  checks characters first (length → characters → decoding → normalization → patterns) against
  `CharacterValidationConstants.RFC3986_QUERY_CHARS` — the encoded-form set: unreserved plus
  `?&=!$'()*+,;`, which lacks even `:`, `@`, `/` (allowed by RFC 3986 in queries) and `%`.
- Need: browser applications carry free text and structured values in query parameters (search
  "Max Müller", timestamps, router JSON arrays). Today free-text search on every backend fails.
- Proposal: (a) API Sheriff passes the query RAW (encoded) to the pipeline, or `cui-http` applies the
  encoded-form character check only to encoded input; (b) after decoding, checks target meaning rather
  than character classes: reject nullbyte, control characters (except possibly tab), CR/LF, double
  encoding, invalid UTF-8; allow printable Unicode, space and RFC 3986 `pchar` (`:`, `@`, `/`);
  (c) extend `RFC3986_QUERY_CHARS` by `:`, `@`, `/` and `%` (for percent encoding).
- Acceptance under `strict`: `q=Max%20M%C3%BCller`, `q=2026-09-15T10%3A00%3A00Z`, `q=a%2Fb`,
  `q=%5B%22a%22%5D` → accepted and forwarded still encoded; `%00`, `%01`, `%0D%0A`, `%252F` → rejected.
- Consumer migration (context only, not API Sheriff work): the KIDICAP Gateway drops its `minimal`
  filter override on its public `app` anchor and flips an integration check; this is its precondition
  for giving UI routes `require: session`.

## Deliverables

1. Settle the fix location (verify-first clause): trace the pinned `cui-http` version's
   `URLParameterValidationPipeline` stage order and `RFC3986_QUERY_CHARS`, and decide whether an in-repo
   change (hand the raw encoded query to the pipeline) satisfies the acceptance matrix alone, or whether a
   `cui-http` change is required. Record the decision (ADR if the security-filter contract changes).
2. Implement the in-repo change in `GatewayEdgeRoute.buildPipelineRequest` (and the query forwarding
   path, so accepted values are forwarded encoded exactly as received). If a `cui-http` change is needed,
   the plan does NOT implement it here: it records the required library change as a follow-up
   (issue on the owning repository) and either consumes an available fixed release via `api-sheriff/pom.xml`
   (only with explicit user approval, per CLAUDE.md dependency rules) or stops at a documented gap.
3. Tests covering the full acceptance matrix under `strict` (accepted and rejected sets), plus
   regression tests that `lenient` / `minimal` behaviour and path/header validation are unchanged;
   security-focused tests for CR/LF, nullbyte and double encoding.
4. Update `doc/security-threat-model.adoc` (filter profile semantics) and the user documentation of
   security-filter profiles in `doc/configuration.adoc`.

## Claim Labels

- OBSERVED: API Sheriff passes the decoded query map to the pipeline via `raw.params()` — read at `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/edge/GatewayEdgeRoute.java` § `buildPipelineRequest` (line 1197 at fb9e774)
- OBSERVED: `cui-http` is an external Maven dependency, not source in this repository — read at `api-sheriff/pom.xml` § `cui-http` dependency (≈ line 49)
- HYPOTHESIS: `URLParameterValidationPipeline` checks characters before decoding against `RFC3986_QUERY_CHARS` = unreserved plus `?&=!$'()*+,;` — confirm/refute at `CharacterValidationConstants` § `RFC3986_QUERY_CHARS` in the `cui-http` sources at the pinned version (verify-at-outline)
- HYPOTHESIS: handing the raw encoded query to the existing pipeline, with no `cui-http` change, passes the acceptance matrix — confirm/refute at `URLParameterValidationPipeline` § stage order in the pinned `cui-http` sources (verify-at-outline)
- Verify-first clause: the plan's shape depends on whether the fix is in-repo only or requires a `cui-http` release. Settle against the pinned library source before scoping; on refutation of the in-repo-only hypothesis, loop back and re-scope deliverable 2 as gated on the library change.

## Expected Surface

- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/edge/GatewayEdgeRoute.java` — `buildPipelineRequest`, query forwarding (`renderQuery`, `encode`)
- HYPOTHESIS: `api-sheriff/pom.xml` — `cui-http` version, only if a fixed release is consumed with approval (verify-at-outline)
- HYPOTHESIS: `api-sheriff/src/test/java/de/cuioss/sheriff/gateway/edge/` — edge query-validation tests (verify-at-outline)
- HYPOTHESIS: `integration-tests/src/test/java/` — IT for the acceptance matrix against the native image (verify-at-outline)
- HYPOTHESIS: `doc/security-threat-model.adoc` — filter profile section (verify-at-outline)
- HYPOTHESIS: `doc/configuration.adoc` — security filter profiles (verify-at-outline)

## Dependencies and Sequencing

- Depends on: none in this queue; possibly an external `cui-http` release (see verify-first clause)
- Overlaps with: PLAN-04, PLAN-06, PLAN-07, PLAN-08 edit other regions of `GatewayEdgeRoute.java`
- Adjacent to: PLAN-03 (return URL with query) reads the query representation — coordinate encoding assumptions if both are in flight

## Hand-Off Command

```text
/plan-marshall task="implement .plan/local/orchestrator/kidicap-gateway-requirements/plans/PLAN-02-query-parameter-validation.md"
```

## Write-Boundary

The plan implementing this spec touches only its own repository source and tests. It creates
and edits NO file under `.plan/local/orchestrator/` other than its own
`inbox/{sender}-{seq}` message — the orchestrator owns every other ledger write — and reports
its outcome through its PR and its inbox message. The inbox exception's qualifiers and the
sole sanctioned write mechanism are stated in
`persona-plan-orchestrator/standards/orchestration-model.md` § Ledger Write-Boundary.
