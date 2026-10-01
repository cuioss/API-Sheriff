# PLAN-13: Defect Fixes — Userinfo JSON Claims, Query Validation, JWKS Readiness

epic: kidicap-gateway-requirements
workstream: WS-01

> Staged plan spec — one shippable unit of work, ready for `/plan-marshall` hand-off.
> Lives at `plans/PLAN-13-defect-fixes.md` and is queued in the epic `status.json` `plans[]` field.
> The orchestrator EMITS the command below; it never launches the plan inline.
> This spec is SELF-SUFFICIENT: the emitted command is a one-line pointer and carries no brief.
> Aggregates superseded specs PLAN-01, PLAN-02, PLAN-10 (see `plans/superseded/`).

## Objective

Fix three defects the KIDICAP Gateway measured in API Sheriff 0.2.1, two of them blocking: userinfo
claims are disclosed as Java `toString()` strings instead of native JSON types (AS-7); the `strict`
security filter rejects legitimate decoded query values because they are validated against the
encoded-form character set (AS-13); and readiness reports `UP` although the first JWKS fetch failed, with
no retry before the refresh interval (AS-10, API-Sheriff#194). All three are behaviour fixes without new
routing primitives; each may surface a dependency on an external library, which the plan settles first.

## Source

KIDICAP Gateway requirements (`archive/api-sheriff-aenderungen.adoc`), translated.

**AS-7 — userinfo returns structured claims as JSON (priority blocking).** Baseline (measured): structured
claims come as Java `toString`, lists as `"[A, B]"`. Proposal: take claims over in their JSON form from the
ID token (object, array, number, boolean). Acceptance: the consumer's notice-only integration check becomes
a hard assertion — here: tests proving native JSON types round-trip.

**AS-13 — check query values after decoding (priority blocking).**
- Baseline (measured): `strict` rejects query values with spaces (`%20` and `+`), umlauts (`%C3%BC`), `:`,
  `/`, `"`, `%`, `#`, `<`, `>` with `400` (`INVALID_CHARACTER` / `INVALID_ENCODING at PARAMETER_VALUE`).
  `lenient` additionally lets only umlauts through — but also control characters, CR/LF and double
  encoding. `minimal` lets everything through but is forbidden on authenticated routes.
- Root cause per source (code): API Sheriff passes the DECODED values (`GatewayEdgeRoute.java:1197`,
  Vert.x `raw.params()`) to `cui-http`'s parameter pipeline. `URLParameterValidationPipeline` checks
  length → characters → decoding → normalization → patterns, validating characters against
  `CharacterValidationConstants.RFC3986_QUERY_CHARS` — the ENCODED-form set (unreserved plus
  `?&=!$'()*+,;`), lacking even `:`, `@`, `/` (allowed by RFC 3986 in queries) and `%`.
- Need: browser applications carry free text and structured values in query parameters (search
  "Max Müller", timestamps, router JSON arrays); today free-text search on every backend fails.
- Proposal: (a) pass the query RAW (encoded) to the pipeline, or `cui-http` applies the encoded-form check
  only to encoded input; (b) after decoding, check meaning instead of character classes: reject nullbyte,
  control characters (except possibly tab), CR/LF, double encoding, invalid UTF-8; allow printable
  Unicode, space and RFC 3986 `pchar` (`:`, `@`, `/`); (c) extend `RFC3986_QUERY_CHARS` by `:`, `@`, `/`, `%`.
- Acceptance under `strict`: `q=Max%20M%C3%BCller`, `q=2026-09-15T10%3A00%3A00Z`, `q=a%2Fb`,
  `q=%5B%22a%22%5D` → accepted and forwarded still encoded; `%00`, `%01`, `%0D%0A`, `%252F` → rejected.

**AS-13 re-grounding (orchestrator, 2026-09-15, API Sheriff fb9e774, cui-http tag `3.0` = c56c079 and
`main` = d385aa3 / 3.1-SNAPSHOT).** The source's root cause is right in direction but misplaces most of the
fix:
- The cui-http parameter pipeline is designed for the ENCODED wire form: `CharacterValidationStage` checks
  the wire form only and `DecodingStage` owns every decoded-form rule. API Sheriff hands it values Vert.x
  has already decoded, so the pipeline decodes a SECOND time. That produces false rejects (space, `ü`,
  `"`, a literal `%` from `%25`) AND a validated-vs-forwarded mismatch: `q=a%252F` reaches the pipeline as
  `a%2F`, is validated as `a/`, but `renderQuery` re-encodes Vert.x's `a%2F` and the upstream receives
  `a%252F`. The primary fix is therefore in API Sheriff, not in cui-http.
- cui-http `main` already contains the library-side corrections, unreleased since 3.0: #217 widens
  `RFC3986_QUERY_CHARS` by raw `/`, `:`, `@` (browsers send them unencoded); #210 makes double-encoding
  detection decode-aware and unconditional (also under `lenient`) and rejects malformed UTF-8 instead of
  replacing it with U+FFFD. Consuming them needs a cui-http 3.1 release.
- The source's proposal to add `%` to `RFC3986_QUERY_CHARS` is rejected: `%` is validated as a
  percent-encoding triplet; a literal percent must arrive as `%25`, which passes once raw values are
  handed over. A bare `%` in the set would only admit malformed encodings.
- Conflict: cui-http ADMITTED decoded CR, LF and TAB in `PARAMETER_VALUE` under every preset (form-data
  textarea carve-out), while a raw CR is rejected under `strict` — a raw-versus-encoded asymmetry. The
  source's acceptance requires `%0D%0A` to be rejected. Operator decision (a) → cuioss/cui-http#236, closed
  2026-09-16 by PR #239 and shipped in **cui-http 3.1** (released 2026-09-16).

**cui-http 3.1 (2026-09-16) — what this plan now builds on.** API Sheriff already resolves 3.1: the root
`pom.xml` declares `cui-quarkus-parent` 1.7.5 (commit 799976a), whose `cui-java-bom` 1.7.5 sets
`version.cui.http` to 3.1, and no pom overrides it. No snapshot and no version bump are needed.
- `allowLineBreaksInParameterValues` (PR #239, `SecurityConfiguration` component + builder, default `true`):
  `false` rejects a decoded CR/LF in `PARAMETER_VALUE`. TAB stays admitted; `BODY` and the header/cookie
  types are unaffected.
- #217 raw `/ : @` in query values, #210 decode-aware double encoding + strict UTF-8 (both already relied on
  by deliverable 3).
- Also in 3.1, adopted with the parent bump rather than by this plan, and relevant as regression surface:
  #237 forwarded host/port/context-path hardening (API Sheriff uses `ForwardedHeaderResolver`), #231 cookie
  name/value validation, #227 content-type pipeline enforcement, #229 configuration-surface reconciliation,
  #222 exception-detail sanitisation, #240 HTTP-client hardening.
- Residual false positives to test: under `strict` (`failOnSuspiciousPatterns`) a parameter value that
  starts with `javascript:`, `vbscript:`, `data:` or `file:` is rejected (e.g. free-text search `data: x`),
  and path-traversal patterns apply to parameter values.

**AS-10 — readiness on failed JWKS fetch (priority medium, API-Sheriff#194).** Baseline: readiness reports
`UP` although the first JWKS fetch failed; a retry follows only after the refresh interval; the consumer's
IT works around it via startup ordering. Proposal: readiness `DOWN` while no key set is loaded for a
configured issuer; faster retry with backoff.

Consumer migration (context only, never API Sheriff work): the gateway turns its userinfo notice into a
hard check, drops its `minimal` filter override on the `app` anchor (precondition for `require: session`
UI routes), and keeps its startup ordering.

## Deliverables

1. AS-7 — settle whether the token-validation library exposes a structured claim value (verify-first);
   fix `BffRuntimeProducer.toClaimMap` to project each claim to its native JSON type (object, array,
   number, boolean, string), `allowed_claims` allowlist behaviour unchanged.
2. AS-7 — tests proving array/object/number/boolean claims round-trip through `UserInfoEndpoint.handle`
   as native JSON types.
3. AS-13 — raw handoff: build `PipelineRequest.queryParameters` from the raw query (`raw.query()` split on
   `&` and the first `=`, still percent-encoded) instead of `raw.params()`; validate names with the
   `PARAMETER_NAME` pipeline (`PipelineFactory.createPipeline(ValidationType.PARAMETER_NAME, …)`, which
   exists but is not part of `PipelineSet`) and values with the `PARAMETER_VALUE` pipeline; forward the
   validated raw pairs verbatim instead of re-encoding decoded values through `URLEncoder`. Consumers that
   need decoded values (e.g. BFF `claims` parameter) keep reading them from Vert.x. Record as ADR (the
   security-filter contract changes: validated form = forwarded form).
4. AS-13 — CR/LF in decoded query values: API Sheriff's `strict` profile resolves a
   `SecurityConfiguration` carrying `allowLineBreaksInParameterValues(false)`; `lenient` and `minimal` keep
   the cui-http default (`true`). Note for outline: `SecurityConfiguration.strict()` is a shared preset
   constant and 3.1 offers no `with…` derivation for this flag (only `withContentBlockLists`), so the
   derived configuration is built through `SecurityConfiguration.builder()` — keep it demonstrably in sync
   with the preset (a test asserting every other field equals `strict()`), or request a
   `withLineBreaksInParameterValues` from cui-http and record that as a follow-up.
5. AS-13 — no dependency work: cui-http 3.1 is already resolved through `cui-quarkus-parent` 1.7.5. Verify
   the resolved version (`dependency:list`/`dependency:tree` for `de.cuioss:cui-http`) and correct the
   doubly-stale comment in `api-sheriff/pom.xml`, which names version 3.0 and a root-pom
   `version.cui.http` override that does not exist.
   Tests: full `strict` acceptance matrix, raw browser spellings (`?t=2026-09-15T10:00:00Z`, `?r=/a/b`),
   the validated-equals-forwarded property (`%252F` rejected, never forwarded as `%252F`), literal `%25`,
   `+` as space, names with decoded delimiters, regression for `lenient` / `minimal` and path/header
   validation, the protocol-scheme prefix false positive, unit + IT against the native image.
6. AS-10 — per-issuer live JWKS load state (loaded / not loaded / failed) observable without triggering a
   fetch on the probe path — library API if available, else a thin in-repo wrapper.
7. AS-10 — `GatewayReadinessCheck` reports `DOWN` while any configured issuer lacks a key set, with
   issuer-level detail that discloses nothing beyond what health output already allows.
8. AS-10 — backoff-bounded fast retry after a failed fetch, independent of the refresh interval and
   bounded against request storms.
9. AS-10 — tests: unit, and IT with the IdP started late (`DOWN` then `UP` without waiting the refresh
   interval); PR references API-Sheriff#194.
10. Documentation: userinfo claim contract (`UserInfoEndpoint` Javadoc, `doc/user/bff-session.adoc`),
    filter profile semantics (`doc/configuration.adoc`, `doc/security-threat-model.adoc`), ADR-0027 gap
    closed, `doc/LogMessages.adoc` for new records.

Split guard: 10 deliverables — within the operator-authorized 12 per plan.

## Claim Labels

- OBSERVED: every userinfo claim passes through `BffRuntimeProducer.toClaimMap`, which unconditionally calls `value.getOriginalString()` — read at `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/quarkus/BffRuntimeProducer.java` § `toClaimMap`
- OBSERVED: the converted map flows unchanged into the response via `UserInfoEndpoint.handle` — read at `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/bff/reserved/UserInfoEndpoint.java` § `handle`
- HYPOTHESIS: the external `ClaimValue` type exposes no typed-JSON accessor — confirm/refute at `ClaimValue` in the token-validation library at the version pinned by `api-sheriff/pom.xml` (verify-at-outline)
- OBSERVED: the decoded query map reaches the pipeline via `raw.params()` — read at `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/edge/GatewayEdgeRoute.java` § `buildPipelineRequest` (line 1197 at fb9e774)
- OBSERVED: `cui-http` is an external dependency, not source here — read at `api-sheriff/pom.xml` § `cui-http` dependency
- OBSERVED: at cui-http 3.0 `RFC3986_QUERY_CHARS` = unreserved plus `?&=!$'()*+,;` (no `/ : @`); on `main` (#217) it adds `/ : @`; the stage order is Length → Character → Decoding → Normalization → Pattern — read at `/home/oliver/git/cui-http` `cui-http-core/src/main/java/de/cuioss/http/security/validation/CharacterValidationConstants.java` § `RFC3986_QUERY_CHARS` (tag 3.0 and d385aa3) and `.../pipeline/URLParameterValidationPipeline.java` § `createStages`
- OBSERVED: `CharacterValidationStage` validates the wire form only and treats `%XX` as a triplet; `DecodingStage` decodes (`+` → space for parameters), rejects `%25XX`, surviving encodings, malformed UTF-8 (on `main`), decoded NUL, combining marks, `Cf` and non-ASCII `Zs`, and non-whitespace controls — read at `.../validation/CharacterValidationStage.java` § `validateCharacters` and `.../validation/DecodingStage.java` § `validate`, `validateDecodedCharacters`
- OBSERVED: decoded CR, LF and TAB are admitted for `PARAMETER_VALUE` under every preset — read at `.../validation/DecodingStage.java` § `decodedControlCharacterForbidden`, `isFormDataWhitespace`
- OBSERVED: `PatternMatchingStage` rejects parameter values starting with `javascript:`, `vbscript:`, `data:`, `file:` when `failOnSuspiciousPatterns` (true in `strict`) — read at `.../validation/PatternMatchingStage.java` § `checkProtocolHandlerSchemes`
- OBSERVED: API Sheriff validates names and values with the same value pipeline and forwards `URLEncoder`-re-encoded decoded values — read at `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/pipeline/ThoroughChecksStage.java` § `validateParameters` and `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/edge/GatewayEdgeRoute.java` § `renderQuery`, `encode`
- OBSERVED: `strict` maps to `SecurityConfiguration.strict()` (no control chars, no extended ASCII, normalize, fail on suspicious patterns; identical at 3.0 and `main`) — read at `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/config/model/SecurityProfile.java` § preset mapping and cui-http `.../config/SecurityDefaults.java` § `STRICT_CONFIGURATION`
- OBSERVED: cui-http 3.1 carries `allowLineBreaksInParameterValues` (default `true`; `false` rejects a decoded CR/LF in `PARAMETER_VALUE` only, TAB unaffected) — read at `/home/oliver/git/cui-http` (tag `3.1`) `cui-http-core/src/main/java/de/cuioss/http/security/config/SecurityConfiguration.java` § record components, `.../config/SecurityConfigurationBuilder.java` § `allowLineBreaksInParameterValues`, `.../validation/DecodingStage.java` § `decodedControlCharacterForbidden`
- OBSERVED: API Sheriff already resolves cui-http 3.1 — read at `pom.xml` § `cui-quarkus-parent` 1.7.5 (commit 799976a) and `cui-java-bom` 1.7.5 § `version.cui.http` = 3.1, with no `version.cui.http` override in any pom
- OBSERVED: `SecurityConfiguration.strict()` returns a shared preset constant and 3.1 exposes only `withContentBlockLists` as a derivation seam — read at `/home/oliver/git/cui-http` (tag `3.1`) `cui-http-core/src/main/java/de/cuioss/http/security/config/SecurityConfiguration.java` § `strict`, `withContentBlockLists`
- HYPOTHESIS: Vert.x `HttpServerRequest.params()` returns percent-decoded values, so `q=a%252F` is validated as `a/` and forwarded as `a%252F` today — confirm/refute with an edge test against `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/edge/GatewayEdgeRoute.java` § `buildPipelineRequest` (verify-at-outline)
- OBSERVED: `GatewayReadinessCheck`'s Javadoc names itself the designated seam for a live JWKS status read; the post-construction `DOWN` branch is unreachable today — read at `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/quarkus/GatewayReadinessCheck.java` § `call`
- OBSERVED: ADR-0027 records the JWKS readiness gap as open — read at `doc/adr/0027-The_token-validation_extensions_unqualified_beans_are_excluded_not_accommodated.adoc` § consequences
- HYPOTHESIS: the token-validation library exposes no per-issuer load status or retry backoff — confirm/refute at the library's JWKS loader API as wired from `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/auth/` validator producer (verify-at-outline)
- Verify-first clause: the cui-http question is SETTLED — 3.1 is released and already resolved, and it carries the CR/LF option (see the re-grounding section). Two library questions remain for outline: the structured claim value (AS-7) and the per-issuer JWKS status (AS-10). A refutation re-scopes only the affected AS item; an item gated on an external release is recorded as a documented gap in the PR and an inbox `finding`, and the remaining items still ship — one external gate must not stall the other two fixes.

## Expected Surface

- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/quarkus/BffRuntimeProducer.java` — `toClaimMap`
- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/bff/reserved/UserInfoEndpoint.java` — Javadoc
- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/edge/GatewayEdgeRoute.java` — `buildPipelineRequest`, `renderQuery`, `encode`
- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/pipeline/ThoroughChecksStage.java` — `validateParameters`
- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/pipeline/PipelineRequest.java` — raw query pairs
- HYPOTHESIS: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/pipeline/` — pipeline set with a `PARAMETER_NAME` pipeline (verify-at-outline)
- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/quarkus/GatewayReadinessCheck.java`
- HYPOTHESIS: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/auth/` — JWKS status wiring (verify-at-outline)
- OBSERVED: `api-sheriff/pom.xml` — stale cui-http comment (version 3.0, non-existent root-pom override)
- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/config/model/SecurityProfile.java` — `strict` derives a configuration with `allowLineBreaksInParameterValues(false)`
- HYPOTHESIS: `api-sheriff/src/test/java/de/cuioss/sheriff/gateway/quarkus/` — claim conversion and readiness tests (verify-at-outline)
- HYPOTHESIS: `api-sheriff/src/test/java/de/cuioss/sheriff/gateway/bff/reserved/` — userinfo tests (verify-at-outline)
- HYPOTHESIS: `api-sheriff/src/test/java/de/cuioss/sheriff/gateway/edge/` — query validation tests (verify-at-outline)
- HYPOTHESIS: `integration-tests/src/test/java/` — query matrix and late-IdP readiness ITs (verify-at-outline)
- OBSERVED: `doc/adr/0027-The_token-validation_extensions_unqualified_beans_are_excluded_not_accommodated.adoc`
- HYPOTHESIS: `doc/user/bff-session.adoc` — userinfo docs (verify-at-outline)
- HYPOTHESIS: `doc/configuration.adoc` — filter profiles (verify-at-outline)
- HYPOTHESIS: `doc/security-threat-model.adoc` — filter profiles (verify-at-outline)
- HYPOTHESIS: `doc/LogMessages.adoc` — new records (verify-at-outline)

## Dependencies and Sequencing

- Depends on: none in this queue; possibly external releases (`cui-http`, token-validation library)
- Overlaps with: PLAN-14 (`BffRuntimeProducer.java`, `doc/user/bff-session.adoc`, `doc/configuration.adoc`); PLAN-15 and PLAN-16 (`GatewayEdgeRoute.java`, `doc/configuration.adoc`) — queue head, runs first
- Adjacent to: PLAN-14's query-preserving return URL reads the raw query — the representation settled here is its input

## Hand-Off Command

```text
/plan-marshall task="implement .plan/local/orchestrator/kidicap-gateway-requirements/plans/PLAN-13-defect-fixes.md"
```

## Write-Boundary

The plan implementing this spec touches only its own repository source and tests. It creates
and edits NO file under `.plan/local/orchestrator/` other than its own
`inbox/{sender}-{seq}` message — the orchestrator owns every other ledger write — and reports
its outcome through its PR and its inbox message. The inbox exception's qualifiers and the
sole sanctioned write mechanism are stated in
`persona-plan-orchestrator/standards/orchestration-model.md` § Ledger Write-Boundary.
