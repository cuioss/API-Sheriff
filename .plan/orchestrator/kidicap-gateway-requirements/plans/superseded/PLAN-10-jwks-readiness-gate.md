# PLAN-10: Readiness DOWN Until JWKS Is Loaded, Faster Retry

epic: kidicap-gateway-requirements
workstream: WS-01

> Staged plan spec — one shippable unit of work, ready for `/plan-marshall` hand-off.
> Lives at `plans/PLAN-10-jwks-readiness-gate.md` and is queued in the epic `status.json` `plans[]` field.
> The orchestrator EMITS the command below; it never launches the plan inline.
> This spec is SELF-SUFFICIENT: the emitted command is a one-line pointer and carries no brief.

## Objective

The readiness probe reports `UP` although the first JWKS fetch for a configured issuer failed, and a new
attempt only follows after the refresh interval (AS-10, API-Sheriff#194). This plan makes readiness
`DOWN` while any configured issuer has no loaded key set and adds a faster, backoff-bounded retry after a
failed fetch — closing the gap `GatewayReadinessCheck`'s own Javadoc and ADR-0027 record as open.

## Source

KIDICAP Gateway requirements AS-10 (`archive/api-sheriff-aenderungen.adoc` § AS-10), priority medium;
tracked upstream as API-Sheriff#194.

- Baseline: readiness reports `UP` although the first JWKS fetch failed; a retry only follows after the
  refresh interval. The consumer's integration test works around it via startup ordering.
- Proposal: readiness `DOWN` as long as no key set is loaded for a configured issuer; faster retry with
  backoff.
- Consumer migration (context only): startup ordering may stay; its readiness wait becomes meaningful.

## Deliverables

1. Live per-issuer JWKS load state (loaded / not loaded / failed) observable without triggering a fetch
   on the probe path — via the token-validation library's API if available, else a thin in-repo wrapper.
2. `GatewayReadinessCheck` reports `DOWN` (with issuer-level detail that discloses no secrets or full URLs
   beyond what health output already allows) while any configured issuer lacks a key set.
3. Backoff-bounded fast retry after a failed fetch, independent of the steady refresh interval; bounded
   so an unreachable IdP cannot cause a request storm.
4. Tests (unit + integration: IdP started late → `DOWN` then `UP` without waiting the refresh interval),
   ADR-0027 updated, `doc/LogMessages.adoc` for new WARN/INFO records, close/reference API-Sheriff#194 in
   the PR.

## Claim Labels

- OBSERVED: `GatewayReadinessCheck`'s Javadoc names itself the designated seam for a live JWKS-loader status read and states the post-construction `DOWN` branch is currently unreachable — read at `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/quarkus/GatewayReadinessCheck.java` § `call`
- OBSERVED: ADR-0027 records the JWKS readiness gap as separate open work — read at `doc/adr/0027-The_token-validation_extensions_unqualified_beans_are_excluded_not_accommodated.adoc` § consequences (≈ line 206)
- HYPOTHESIS: the token-validation library exposes no per-issuer load status or retry-backoff configuration — confirm/refute at the library's JWKS loader API (`TokenValidator` / issuer config JWKS loader) as used from `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/auth/` token validator producer (verify-at-outline)
- Verify-first clause: settle whether status and fast retry are available from the library (configuration only) or require a wrapper/library change; if a library change is required, loop back and record the plan as gated on that release.

## Expected Surface

- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/quarkus/GatewayReadinessCheck.java`
- HYPOTHESIS: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/auth/` — validator/JWKS wiring (verify-at-outline)
- HYPOTHESIS: `api-sheriff/src/test/java/de/cuioss/sheriff/gateway/quarkus/` — readiness tests (verify-at-outline)
- HYPOTHESIS: `integration-tests/src/test/java/` — late-IdP readiness IT (verify-at-outline)
- OBSERVED: `doc/adr/0027-The_token-validation_extensions_unqualified_beans_are_excluded_not_accommodated.adoc`
- HYPOTHESIS: `doc/LogMessages.adoc` — new records (verify-at-outline)

## Dependencies and Sequencing

- Depends on: none
- Overlaps with: none known — surface-disjoint from every other plan in this epic
- Adjacent to: the `run-integration-tests` skill documents JWKS readiness diagnosis; update it only if behaviour it describes changes (outside `.plan/`, `.claude/**` doc change)

## Hand-Off Command

```text
/plan-marshall task="implement .plan/local/orchestrator/kidicap-gateway-requirements/plans/PLAN-10-jwks-readiness-gate.md"
```

## Write-Boundary

The plan implementing this spec touches only its own repository source and tests. It creates
and edits NO file under `.plan/local/orchestrator/` other than its own
`inbox/{sender}-{seq}` message — the orchestrator owns every other ledger write — and reports
its outcome through its PR and its inbox message. The inbox exception's qualifiers and the
sole sanctioned write mechanism are stated in
`persona-plan-orchestrator/standards/orchestration-model.md` § Ledger Write-Boundary.
