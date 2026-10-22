# PLAN-V02-46: Close an Abandoned Upstream Connection FIN-First, With an Abortive Fallback

epic: api-sheriff-0-2-0
workstream: WS-03

> Staged plan spec — one shippable unit of work, ready for `/plan-marshall` hand-off.
> The orchestrator EMITS the command below; it never launches the plan inline.
> Staged 2026-10-15 from inbox `plan-32-early-answer-it-timeouts-004`, a finding of kidicap PLAN-32 (#466)
> routed here by the kidicap session, with a comparison of seven gateways.

## Objective

After an early upstream answer or a client abort, the gateway resets the upstream HTTP/1.1 request and
closes the connection abortively with `SO_LINGER 0`. Kidicap PLAN-30 and ADR-0066 A1 chose this to avoid
local port exhaustion. The gateway frees its own socket and pool slot at once. In PLAN-32's investigation,
however, an upstream whose TCP stack did not process that RST, because body data was still in flight, kept
its side of the connection open until its own idle timeout. This behaviour predates PLAN-32 and is on
`main`.

**Whether to close is settled; only how to close is open.** Every gateway compared closes an upstream
connection whose request body was not fully sent and never reuses it: NGINX, Envoy, Go (Traefik, Caddy)
and Reactor Netty. The finding cites Envoy's CVE-2026-48743 as showing that reuse causes request desync; verify that before citing it in an ADR. Our "close, never reuse"
matches them. How they close differs:

- NGINX and Kong close upstreams gracefully; their `SO_LINGER 0` knob applies to the client side only.
- Go uses a plain `Close()`.
- HAProxy sets `SO_LINGER 0` only on sockets flagged as at risk of lingering, and its docs warn that
  middleboxes may drop the reset.

## Deliverables

1. **Evaluate FIN-first.** Shut down the write side, drain briefly (about 1 s, with a byte cap), and fall
   back to `SO_LINGER 0` only if the drain does not finish. Keep "never reuse". Re-run PLAN-30's
   port-exhaustion reset tests (`GatewayEdgeEarlyResponseResetTest`), `Http2EarlyResponseResetIT` and the
   upload benchmarks against both variants. Adopt FIN-first only if port exhaustion stays bounded, and
   record the measurement either way. The fixed 25 s request-body deadline from `PLAN-V02-45`
   (ADR-0060 Amendment A2, event `REQUEST_BODY_TIMEOUT`) resets the upstream request too, so it is one
   more trigger of the same close: cover it in the measurement alongside the early answer and the
   client abort. `edge/end/ConnectionRetirement` closes the client-side connection, not the upstream
   one, and is out of scope.
2. **Correct the reuse wording.** ADR-0060 A1 and `doc/architecture.adoc` say the reset "frees the
   connection" and leaves it "free for the next" request; check Amendment A2 (added by #506) for the
   same wording. That is true only when no body byte was sent;
   a partly sent HTTP/1.1 request closes its connection. State that precisely.
3. **Record it.** If deliverable 1 changes the close, add an amendment to ADR-0066 (A1 records
   `SO_LINGER 0`), update the `edge/upstream/EgressClients` Javadoc on the default branch's abortive close,
   and update the threat model's `gw-07-upstream-ports` section.
4. **Close the admit/drain window in `edge/admission/EdgeAdmission`.** `tryAdmit` checks `draining` and
   only then increments `inFlight`. A request that passes the check just before `drain()` sets the flag can
   still be admitted after `drain()` has read zero in flight and returned, so shutdown can cut it off.
   Increment first, re-check `draining`, and undo and refuse when it is set. Add a test that pins the
   ordering. This is a shutdown-lifecycle fix in the same edge lifecycle as deliverable 1. It predates
   `PLAN-V02-25`; CodeRabbit raised it on #487, which declined it as out of scope for a move.

Split guard: 4 deliverables. This is a behaviour change, so the normal limits apply.

## Claim Labels

- OBSERVED: the default upstream client sets `SO_LINGER 0` — `edge/upstream/EgressClients.java` ~171 (`setSoLinger(0)`) — `origin/main` at `99e9dfd3`
- OBSERVED: ADR-0060 A1 says the reset "frees the connection" and makes it "free for the next" (lines ~208, ~229); Amendment A2 starts at ~310 — `origin/main` at `99e9dfd3`
- OBSERVED: `EdgeAdmission.tryAdmit` (~93) checks `draining` before `inFlight.incrementAndGet()` (~97), and `drain()` (~157) sets the flag and then waits for `inFlight` to reach zero — `origin/main` at `99e9dfd3`
- OBSERVED: ADR-0066 Amendment A1 (~242) records `SO_LINGER 0`; the threat model's `gw-07-upstream-ports` entry (~1364, matrix row ~3598) states it COVERED — `origin/main` at `99e9dfd3`
- HYPOTHESIS: an upstream that does not process the RST keeps its side open until its own idle timeout — from inbox `-004`, observed in PLAN-32's investigation (verify at outline)

## Expected Surface

- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/edge/upstream/` (`EgressClients`, `DispatchStage`), `edge/admission/EdgeAdmission.java` (deliverable 4)
- OBSERVED: `api-sheriff/src/test/java/de/cuioss/sheriff/gateway/edge/GatewayEdgeEarlyResponseResetTest.java`
- OBSERVED: `doc/adr/0060-…adoc`, `doc/adr/0066-…adoc`, `doc/architecture.adoc`, `doc/security-threat-model.adoc`

## Dependencies and Sequencing

- After `PLAN-V02-25` (#487) and `PLAN-V02-45` (#506, `99e9dfd3`), which edited the same relay and
  dispatch code. Both have landed.
- Runs alongside `PLAN-V02-43` (#505, config validation) and `PLAN-V02-22` (demo client, release docs).
  The only shared file is `doc/security-threat-model.adoc`: V02-43 edits other sections. Rebase onto
  `origin/main` before push, and if deliverable 3 adds an ADR rather than an amendment, re-check the
  next free ordinal (0071 is taken by #505).
- Deliverable 2 is documentation only. If it should land sooner, it can ride with any doc-touching plan
  in `edge/`; say so in the PR.

## Hand-Off Command

```text
/plan-marshall task="implement .plan/orchestrator/api-sheriff-0-2-0/plans/PLAN-V02-46-upstream-close-after-early-answer.md" plan_id=plan-v02-46-upstream-close-after-early-answer
```

## Write-Boundary

The plan implementing this spec touches only its own repository source and tests. It creates and
edits NO file under `.plan/orchestrator/` other than its own `inbox/{sender}-{seq}` message, and
reports its outcome through its PR and that message.
