# PLAN-V02-26: Split `ConfigValidator` by Concern

epic: api-sheriff-0-2-0
workstream: WS-01

> Staged plan spec — one shippable unit of work, ready for `/plan-marshall` hand-off.
> The orchestrator EMITS the command below; it never launches the plan inline.
> Staged 2026-10-14 from inbox `plan-v02-23-cookie-forwarding-policy-001` (operator decision to rework
> before PLAN-V02-23). Third of three refactor plans, after `PLAN-V02-24`, parallel with `PLAN-V02-25`.

## Objective

`config/validation/ConfigValidator.java` is 3,273 lines: every boot rule lands in one class, so every
feature plan edits it and two plans touching validation always collide. A seam already exists —
`config/validation/rule/ValidationRule` with one implementation, `PortalRules` — and is not used for
anything else. Split the validator into rule classes by concern on that seam.

Pure refactor: the same configurations are accepted and refused with the same messages. The existing
validator tests passing unchanged is the evidence.

## Deliverables

1. **Group the rules.** Inventory the checks in `ConfigValidator` by concern (for example: topology and
   endpoints, anchors and route matching, auth and token validation, `oidc` / BFF, TLS and egress,
   limits and body caps, protocols) and settle the grouping at outline.
2. **One rule class per concern** on the `ValidationRule` seam (extend the seam if it cannot express a
   cross-cutting check, and say why). `ConfigValidator` keeps only orchestration: running the rules and
   collecting violations in a stable order.
3. **Rules derived from `PLAN-V02-24`'s declaration stay derived.** Checks that `PLAN-V02-24` made
   generic (scope legality of a policy block, the relaxing-override WARN) are not re-written per block
   here.
4. **Tests follow the rules.** Split `ConfigValidatorTest` and its siblings along the same lines; keep
   message assertions identical. Offline validation (`PLAN-V02-14`'s `--validate` path) stays green.
5. **Developer documentation**: where a new boot rule goes, in `doc/development/`.

Split guard: 5 deliverables. Keep the PR under 100 files and 150,000 diff characters.

## Claim Labels

- OBSERVED: `ConfigValidator.java` is 3,273 lines at `5490144f` — `git show | wc -l`
- OBSERVED: `config/validation/rule/` holds `ValidationRule` and one implementation, `PortalRules` — `git ls-tree` at `5490144f`

## Expected Surface

- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/config/validation/` and `rule/`
- OBSERVED: `api-sheriff/src/test/java/de/cuioss/sheriff/gateway/config/validation/`
- OBSERVED: `doc/development/`

## Dependencies and Sequencing

- Depends on: `PLAN-V02-24` and `PLAN-V02-12`'s second PR (adds `oidc` checks to the validator) having
  landed.
- Parallel with `PLAN-V02-25` (disjoint surfaces).
- Before: the re-grounded `PLAN-V02-23`; holds every staged plan adding boot rules (`PLAN-V02-06` D9,
  `-20`; kidicap `PLAN-21`, `-29`).

## Hand-Off Command

```text
/plan-marshall task="implement .plan/orchestrator/api-sheriff-0-2-0/plans/PLAN-V02-26-config-validator-split.md" plan_id=plan-v02-26-config-validator-split
```

## Write-Boundary

The plan implementing this spec touches only its own repository source and tests. It creates and
edits NO file under `.plan/orchestrator/` other than its own `inbox/{sender}-{seq}` message, and
reports its outcome through its PR and that message.
