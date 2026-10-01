# PLAN-01: Serialize Userinfo Claims as Their Native JSON Type

epic: kidicap-gateway-requirements
workstream: WS-01

> Staged plan spec — one shippable unit of work, ready for `/plan-marshall` hand-off.
> Lives at `plans/PLAN-01-userinfo-json-claims.md` and is queued in the epic `status.json` `plans[]`
> field. The orchestrator EMITS the command below; it never launches the plan inline.
> This spec is SELF-SUFFICIENT: the emitted command is a one-line pointer and carries no
> brief, so every per-plan carry is authored here and nowhere else.

## Objective

The BFF userinfo endpoint discloses every ID-token claim as the Java `toString()` form of its parsed
value instead of its native JSON type (AS-7, **blocking** for the KIDICAP Gateway): a list claim such as
`roles` renders as the string `"[A, B]"` instead of the JSON array `["A", "B"]`, and object, number and
boolean claims are flattened to strings too. This plan makes every disclosed claim serialize as its real
JSON type (object, array, number, boolean, string), as carried by the ID token.

## Source

KIDICAP Gateway requirements AS-7 (`archive/api-sheriff-aenderungen.adoc` § AS-7), priority **blocking**.

- Baseline (measured against 0.2.1): structured claims arrive as Java `toString`, lists as `"[A, B]"`.
- Proposal: take claims over in their JSON form from the ID token (object, array, number, boolean).
- Acceptance: a consumer integration check that currently only prints a notice becomes a hard assertion —
  in this repository that translates to tests proving native JSON types round-trip.
- Consumer migration (context only, not API Sheriff work): the KIDICAP Gateway turns its notice-only
  check in its own integration tests into a hard check.

## Deliverables

1. Fix `BffRuntimeProducer.toClaimMap` (or replace its conversion strategy) so each claim value is
   projected to its native JSON shape instead of always calling `ClaimValue.getOriginalString()`. Settle
   first (verify-first clause) whether the token-validation library's `ClaimValue` / parsed claim set
   exposes a typed accessor; if it does not, re-scope before implementing (JSON re-parse of the original
   string vs. an engine-side accessor addition).
2. Tests proving array-, object-, number- and boolean-valued claims round-trip through
   `UserInfoEndpoint.handle` as native JSON types, not strings — including the `allowed_claims`
   allowlist path unchanged.
3. Document the claim-serialization contract in `UserInfoEndpoint` Javadoc and the user documentation
   that describes the userinfo endpoint (`doc/user/bff-session.adoc` / `doc/configuration.adoc`).

## Claim Labels

- OBSERVED: every claim disclosed by the userinfo endpoint passes through `BffRuntimeProducer.toClaimMap`, which unconditionally calls `value.getOriginalString()` regardless of the claim's JSON type — read at `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/quarkus/BffRuntimeProducer.java` § `toClaimMap` (≈ lines 472-483 at fb9e774)
- OBSERVED: the converted map flows unchanged into the JSON response via `UserInfoEndpoint.handle` → `UserInfoOutcome.identity` — read at `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/bff/reserved/UserInfoEndpoint.java` § `handle`
- HYPOTHESIS: the external `ClaimValue` type (`de.cuioss.sheriff.token.validation.domain.token`) exposes no typed-JSON accessor — confirm/refute at `ClaimValue` in the token-validation library sources/Javadoc at the version pinned by `api-sheriff/pom.xml` (verify-at-outline)
- HYPOTHESIS: `api-sheriff/src/test/java/de/cuioss/sheriff/gateway/quarkus/BffRuntimeProducerTest.java` is the natural home for conversion tests — confirm/refute at that file § existing `toClaimMap` coverage (verify-at-outline)
- Verify-first clause: before scoping the fix's shape, settle whether a structured claim form is reachable from the validated ID token. If not, loop back and choose between a guarded JSON re-parse (only valid if the original string is guaranteed JSON) and a dependency on an engine release.

## Expected Surface

- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/quarkus/BffRuntimeProducer.java` — `toClaimMap`
- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/bff/reserved/UserInfoEndpoint.java` — Javadoc
- HYPOTHESIS: `api-sheriff/src/test/java/de/cuioss/sheriff/gateway/quarkus/BffRuntimeProducerTest.java` — conversion tests (verify-at-outline)
- HYPOTHESIS: `api-sheriff/src/test/java/de/cuioss/sheriff/gateway/bff/reserved/` — userinfo endpoint tests (verify-at-outline)
- HYPOTHESIS: `doc/user/bff-session.adoc` — userinfo documentation (verify-at-outline)

## Dependencies and Sequencing

- Depends on: none
- Overlaps with: PLAN-05 and PLAN-09 edit `BffRuntimeProducer.java` in the scope-wiring region (≈ line 232), not `toClaimMap`
- Adjacent to: AS-1 second stage (`catalog.visible_when`) would read claims — out of scope for this epic

## Hand-Off Command

```text
/plan-marshall task="implement .plan/local/orchestrator/kidicap-gateway-requirements/plans/PLAN-01-userinfo-json-claims.md"
```

## Write-Boundary

The plan implementing this spec touches only its own repository source and tests. It creates
and edits NO file under `.plan/local/orchestrator/` other than its own
`inbox/{sender}-{seq}` message — the orchestrator owns every other ledger write — and reports
its outcome through its PR and its inbox message. The inbox exception's qualifiers and the
sole sanctioned write mechanism are stated in
`persona-plan-orchestrator/standards/orchestration-model.md` § Ledger Write-Boundary.
