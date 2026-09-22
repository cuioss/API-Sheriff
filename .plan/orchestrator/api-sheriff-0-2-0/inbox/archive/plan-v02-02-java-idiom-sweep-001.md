envelope_version=1
sender_type=plan
sender_id=plan-v02-02-java-idiom-sweep
epic=api-sheriff-0-2-0
kind=landing
created=2026-08-09T04:17:14Z

## What landed

Plan `plan-v02-02-java-idiom-sweep` merged as **PR #198**, merge commit `e3434044472810410db4aab1390567743fe8d703`, base `main`.

- 9 deliverables, 12 tasks, 8 commits.
- Issue #178 is CLOSED, with a comment naming PR #198 and merge commit `e343404`.

## Spec corrections the epic must absorb

These are first-party measurements from the run, not re-readings of the spec.

1. **D3's occurrence count is 20, not 19.** The spec asserted 19, but the spec's *own* per-carrier breakdown sums to 20. The 19 was an inherited arithmetic error that propagated unchallenged spec -> clarified request -> outline. Corrected in all three during the run. **The epic should fix the spec** — the plan cannot, and the next reader of the spec will otherwise inherit the same 19.
2. **The D2 seed named ONE deprecated `host()` carrier; there are TWO** — `GatewayEdgeRoute:460` and `GatewayEdgeRoute:1146`. The outline's re-grounding section had already caught this before implementation.
3. **The measured deprecation set was 5 sites across 4 files**, not the 1-2 the spec implied. `integration-tests` and `benchmarks` were clean — which is exactly what made a reactor-wide `failOnWarning` safe to turn on.

## Premises refuted at outline

Two of the plan's own premises did not survive grounding:

- **The `require` enum is not a security improvement and carries no behavioural delta.** `gateway.schema.json` and `endpoint.schema.json` already enum-gate the value, and `ConfigLoader` schema-validates before bind, so the illegal-value path was already closed. The operator re-confirmed the conversion on a narrowed **type-safety-only** rationale.
- **A missing `ConfigModelReflection` registration is NOT invisible to JVM gates at this HEAD.** `ConfigModelReflectionTest` walks the record-component graph, so the omission fails on the JVM. The residual hazard is **package scope only** — a record outside the walked package set. This overtakes the long-standing "native reflection gap is invisible to JVM gates" belief; see the accompanying candidate-lesson.

## Defect caught by pre-submission self-review

The self-review caught a genuine **fail-open auth path**. A constant-only `switch` *statement* is a legacy switch, so `javac` does **not** enforce exhaustiveness. The plan had removed the trailing `throw` on the strength of a guarantee that does not exist. Fixed by adding a `case null -> throw` arm, which makes it an enhanced switch and restores the compiler check.

## Unused operator approval

The operator's dependency approval for the Vert.x `WebSocketClient` went **unused**: `vertx-core` 4.5.30 already ships `io.vertx.core.http.WebSocketClient`, so no new artifact was added. The approval cost an operator interaction that a classpath check would have avoided.

## Residue the epic should track

Deliberately deferred and reported rather than swept:

- **`RouteRuntimeAssembler` still allocates a per-tuple `HttpClient` (and a resilience `Guard`) for `WEBSOCKET` routes that no longer read it.** Collapsing this is a behavioural change to boot-time allocation plus a nullability-contract change reaching `RouteRuntime` and `DispatchStage` — a design task, not a sweep edit.
- **`TokenValidatorProducer.applyJwks` qualifies for a switch conversion** but sits outside D5's declared `affected_files`, so it was left alone.

## Automated review

CodeRabbit posted 4 inline findings plus a review body: 2 fixed on-branch (a skill-doc claim that overstated its test's coverage; a missing `AuthConfig` thread-safety Javadoc note), 2 declined with recorded refutations, and the review-body nitpick declined with a reason. All threads replied and resolved.
