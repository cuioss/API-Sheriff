# PLAN-V02-44: Merge `ResolvedRoute` and `RouteRuntime` into One Per-Route Type

epic: api-sheriff-0-2-0
workstream: WS-01

> Staged plan spec — one shippable unit of work, ready for `/plan-marshall` hand-off.
> The orchestrator EMITS the command below; it never launches the plan inline.
> Staged 2026-10-15. Operator decision: split `PLAN-V02-25` up front instead of mid-run (lesson
> `2026-10-14-20-004`). This is its first half (deliverable 1 and the V02-24 residue). The
> `GatewayEdgeRoute` decomposition stays in `PLAN-V02-25` and builds on this plan. Design rulings of
> 2026-10-14 apply: merge rather than wrap, `@Builder`, and the existing suite as proof.

## Objective

**Every per-route value is carried by two parallel types.** `config/model/ResolvedRoute` holds the resolved
configuration. `routing/RouteRuntime` (206 lines) holds the same effective auth, methods, forward block,
security headers, retry and not-modified toggles, allowed origins and WebSocket idle timeout, plus the
runtime objects: matcher, protocol processor, security configuration, HTTP client, resilience guard,
location rewriter and asset source. `edge/RouteRuntimeAssembler` (305 lines) copies field by field from
one to the other. A new per-route policy therefore needs a field in both types plus a copy line.

Merge the two into one immutable type, built once at boot. This is a refactor with no observable
behaviour change. The proof is the existing suite passing. A test changed to make the refactor pass must
have its reason stated in the PR.

## Deliverables

1. **Settle the shape at outline.** Decide which package owns the merged type (today `config/model` and
   `routing/` each own one half). Decide how boot-time construction splits between policy resolution
   (`config/PolicyBlock`, #465) and runtime assembly (clients, guards, matchers, processors). Decide
   whether `RouteRuntimeAssembler` survives as the assembly step or folds into the builder. Put the choice
   to the operator before moving code.
2. **Merge.** Build one immutable per-route type with Lombok `@Builder`. Remove the field-by-field copy.
   Migrate every reader in `pipeline/` (`PipelineRequest`, `RouteSelectionStage`, `ThoroughChecksStage`,
   `VerbGateStage`), `auth/AuthenticationStage`, `bff/`, `edge/` (the dispatch stages, `GatewayEdgeRoute`,
   `StreamAwareRetryGate`, `WebSocketRelayStage`), `routing/RouteMatcher` and `quarkus/`. Migrate the
   tests that construct either type, using the builder. Resolve Open Defect 12 (a dead allocation in
   `RouteRuntimeAssembler`) here, or show it is gone.
3. **Carry `PLAN-V02-24`'s residue** (small, behaviour-neutral):
   - (a) `ApiSheriff-101`, the override WARN, names only the route and the anchor. Since #465 an
     **endpoint** block can replace an anchor's `security_filter`. The record should therefore name the
     replacing scope (`endpoint` or `route`) and the endpoint id, taken from the resolver's result. This
     came from finding `a49cb1`, which the plan's operator deferred during V02-24's finalize. Reword the
     template, and update `doc/LogMessages.adoc` and the LogAsserts in `RouteTableBuilderTest`. The threat
     model's `_residual_policy_block_replacement` names this WARN as the control.
   - (b) Correct `doc/development/README.adoc` ~354–356. The offline `--validate-config` run never reaches
     the body-limit reader, so "every run fails" is wrong there. Also correct the `ConfigBootPipeline`
     Javadoc (~192), which overstates what a newly gained scope reaches without an edit.
4. **Documentation.** `doc/architecture.adoc` describes the single per-route type. No ADR here: the ADR
   covering the merge and the edge package boundaries is `PLAN-V02-25`'s.

Split guard: 4 deliverables. The usual limits apply: under 100 files and 150,000 diff characters. This is
not a move-only slice, so the relaxed cap does not apply. If the migration of readers and tests does not
fit, ship deliverable 1 and the type with its production readers first, and the test migration second.

## Claim Labels

- OBSERVED: `RouteRuntime.java` is 206 lines and `RouteRuntimeAssembler.java` 305 — `wc -l` at `5490144f`
- OBSERVED: the reader set above — `git grep -l` for `ResolvedRoute`, `RouteRuntime` and `RouteRuntimeAssembler` over `api-sheriff/src` at `f9b637e2`: about 20 main and about 30 test files
- OBSERVED: kidicap `PLAN-32`'s PR #466 touches `edge/DispatchStage`, `GatewayEdgeRoute`, `GrpcDispatchStage`, `DispatchStageTest` and `GatewayEdgeEarlyResponseResetTest`, all of which are readers of the per-route types — `gh pr view 466 --json files`, 2026-10-15

## Expected Surface

- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/config/model/ResolvedRoute.java`, `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/routing/`, `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/edge/RouteRuntimeAssembler.java`
- OBSERVED: the readers in `pipeline/`, `auth/`, `bff/`, `edge/`, `quarkus/` listed in deliverable 2
- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/config/RouteTableBuilder.java`, `config/ConfigLogMessages.java`, `config/boot/ConfigBootPipeline.java`
- OBSERVED: the matching tests under `api-sheriff/src/test/java/de/cuioss/sheriff/gateway/`
- OBSERVED: `doc/architecture.adoc`, `doc/LogMessages.adoc`, `doc/development/README.adoc`

## Dependencies and Sequencing

- `PLAN-V02-24` has landed (#465), so this can start now.
- **Concurrent with kidicap `PLAN-32` (#466) under the rebase rule.** The two overlap only where `edge/`
  reads the per-route type. PLAN-32 fixes a CI flake and has priority; if it merges first, this plan
  rebases. Re-fetch `origin/main` before outline and before push.
- Disjoint from the validator slices (`config/validation/`): may run alongside them.
- Before `PLAN-V02-25` (`GatewayEdgeRoute` decomposition), then the re-grounded `PLAN-V02-23`, then
  `PLAN-V02-27`. Holds every staged plan editing `edge/**` or `routing/**`: `PLAN-V02-05`, `-06` and `-07`.

## Hand-Off Command

```text
/plan-marshall task="implement .plan/orchestrator/api-sheriff-0-2-0/plans/PLAN-V02-44-route-type-merge.md" plan_id=plan-v02-44-route-type-merge
```

## Write-Boundary

The plan implementing this spec touches only its own repository source and tests. It creates and
edits NO file under `.plan/orchestrator/` other than its own `inbox/{sender}-{seq}` message, and
reports its outcome through its PR and that message.
