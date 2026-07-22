# PLAN-02: Endpoint Anchors (roadmap 03)

epic: api-sheriff-roadmap
workstream: WS-02

> Staged plan spec. Source of truth: `doc/plan/03-endpoint-anchors.adoc` — read in full at
> launch; the design docs govern. Re-staged 2026-07-18 with the full work breakdown at
> operator request (the earlier summary-level spec risked under-scoping).

## Objective

Execute `doc/plan/03-endpoint-anchors.adoc` in full — ALL of D1–D5 plus the complete work
breakdown, in one plan. At the end the configuration subsystem is feature-complete for
anchors and substitution: parsed, validated, resolved into the `RouteTable`'s effective
per-route values, invisible at runtime. Must land before roadmap 04 (anchors change what the
route table materializes).

## Deliverables

Everything in the plan doc's work breakdown (12 items), grouped by design decision:

1. **D1 Schemas**: `gateway.schema.json` `anchors` object (name pattern, path_prefix, policy
   blocks, `additionalProperties: false`, shared `$defs` extraction); `endpoint.schema.json`
   optional `anchor` on endpoint + routes; endpoint `auth` required→optional (conditional
   mandatoriness moves to the validator).
2. **D2 Model + materialization**: `AnchorConfig` record, extended `GatewayConfig`/
   `EndpointConfig`/`RouteConfig`/`ResolvedRoute`; `RouteTableBuilder` chain resolution
   gateway→anchor→endpoint→route with wholesale replacement, effective `security_filter` +
   `security_headers` added to materialization; weakening WARNs; per-route effective-posture
   INFO boot log (new LogRecord, documented in `doc/LogMessages.adoc`).
3. **D3 Validator rules** (all-violations convention, file/pointer context): anchor-prefix
   disjointness, declared-anchor-exists, route-inside-prefix, no-undeclared-squatter,
   non-weakenable auth floor, endpoint-auth conditional mandatoriness, effective-auth
   completeness over the extended chain — each with valid + violating fixtures.
4. **D4 Unified `${VAR}` / `${VAR:-default}` substitution**: single pre-schema substitution
   pass over all three artifacts (pinned grammar, no escape syntax, loud failure on
   non-matching `${`); secrets classified on the pre-substitution value and the currently
   UNENFORCED secrets rule implemented with negative tests; `TOPOLOGY_<ALIAS>` and
   `ENDPOINT_<ID>_ENABLED` Java-level machinery deleted; docs caveat-removal + grammar
   documentation (ADR-0004 A1 already records the decision).
5. **D5 Hardening** (seven fixes, each with positive + negative tests): trusted_proxies real
   CIDR parsing + address-space-coverage trust-all guard (incl. complementary /1 pairs, both
   families; parsed set kept for Plan 04), upstream scheme allowlist http/https, prefix
   normalization before disjointness + same-prefix rule moved into ConfigValidator (ADR-0009
   single-reporter), `.yml` stray-file boot failure, binding-error redaction (no resolved
   value ever logged), YAML `StreamReadConstraints`/alias limits, header-matcher disjointness
   doc reconciliation (doc-first, code semantics stand).
6. **Configs, tests, ITs, docs**: sample configs (test sets + runnable
   `sheriff-config/` example with `api`+`bff` anchors, anchor-less fixtures kept); Quarkus
   fail-fast squatter test; property-based `@GeneratorsSource` containment/disjointness
   tests; ResolvedRoute effective-value assertions; existing routed-request IT stays green
   (behaviour-neutral proof); `verify-invalid-config-fails.sh` extended with an
   anchor-violation variant; configuration.adoc verified field-for-field; ADR-0007 flipped
   to Accepted in the same PR. Optional if time permits: seed an operator-facing config guide.

## Expected Surface

- `api-sheriff/` — config schemas, `config.model`, loader, `ConfigValidator`,
  `TopologyResolver`, `RouteTableBuilder`, `ResolvedRoute`, LogRecords + tests
- `doc/` — configuration.adoc, LogMessages.adoc, ADR-0007 status (ADR-0004 unchanged)
- `integration-tests/` — sheriff-config example, invalid-config script variant
- Explicitly NOT: runtime consumption of materialized filter/header values (Plan 04)

## Dependencies and Sequencing

- Depends on: PLAN-01 (shipped, PR #72); roadmap plans 01+02 delivered
- Overlaps with: PLAN-03-request-pipeline consumes its output — must land first
- Scope note: 6 deliverable groups (~12 doc work-breakdown items). Operator explicitly
  directed all of roadmap 03 in one plan (2026-07-18); split guard satisfied by recorded
  rationale — the plan doc itself defines this as one atomic, single-branch unit whose
  parts (schemas, model, validator, substitution) cannot ship independently.

## Hand-Off Command

```text
/plan-marshall Execute doc/plan/03-endpoint-anchors.adoc IN FULL — all of D1-D5 and the complete work breakdown in this one plan; read the plan doc and its Required Reading (ADR-0007, configuration.adoc Anchors + validation summary, architecture.adoc Configuration Subsystem) before touching code; the design docs govern. Deliver: (D1) anchors schema extensions in both schema files with shared $defs, optional anchor refs, endpoint auth to optional; (D2) AnchorConfig + extended model records, RouteTableBuilder gateway→anchor→endpoint→route wholesale-replacement resolution materializing effective auth/methods/security_filter/security_headers into ResolvedRoute, weakening WARNs, per-route effective-posture INFO LogRecord documented in doc/LogMessages.adoc; (D3) all seven validator rules (prefix disjointness, anchor exists, route-inside-prefix, no undeclared squatter, non-weakenable auth floor, endpoint-auth conditional mandatoriness, effective-auth completeness) in the all-violations pass with file/pointer context and a negative test each; (D4) the unified ${VAR}/${VAR:-default} pre-schema substitution pass over gateway.yaml, endpoints/*.yaml, and topology.properties with the pinned grammar and loud non-matching-${ failure, secrets classified pre-substitution and the currently-unenforced secrets rule implemented with negative tests, and the TOPOLOGY_<ALIAS> + ENDPOINT_<ID>_ENABLED Java machinery deleted; (D5) all seven hardening fixes — trusted_proxies CIDR parsing with address-space-coverage trust-all guard (complementary /1 pairs, both families, parsed set retained for Plan 04), http/https upstream scheme allowlist, prefix normalization + same-prefix rule moved to ConfigValidator per ADR-0009, .yml stray-file boot failure, binding-error redaction, YAML StreamReadConstraints, header-matcher disjointness doc fix. Plus: sample/test/integration configs with api+bff anchors (anchor-less fixtures kept), Quarkus fail-fast squatter test, property-based @GeneratorsSource containment tests, anchor-violation variant in verify-invalid-config-fails.sh, configuration.adoc verified field-for-field, ADR-0007 flipped Accepted in the same PR. Out of scope: runtime consumption of the materialized filter/header values (Plan 04). Branch feature/plan-03-endpoint-anchors. Acceptance criteria are the plan doc's section 6 verbatim.
```

## Status Trail

- plan_marshall_plan_id: (set at launch)
- pr: (set when the PR opens)
- landing: (set when recorded at landings/PLAN-02.md)
