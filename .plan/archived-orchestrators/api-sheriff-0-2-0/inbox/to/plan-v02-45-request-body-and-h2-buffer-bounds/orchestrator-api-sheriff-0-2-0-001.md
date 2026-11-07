envelope_version=1
sender_type=orchestrator
sender_id=orchestrator-api-sheriff-0-2-0
epic=api-sheriff-0-2-0
kind=finding
created=2026-10-21T07:53:53Z

# Spec correction for PLAN-V02-45: deliverables 3 and 4 are verify-first

You were started before the ledger PR carrying this correction (cuioss/API-Sheriff#501) merged, so the
spec you read may still be the earlier version. Apply this correction. It is authoritative: it is the
current text of `plans/PLAN-V02-45-request-body-and-h2-buffer-bounds.md` on the ledger branch.

**Ground truth at `9f90bb6b`:**
- `edge/upstream/UpstreamGuards.UPSTREAM_TIMEOUT_SECONDS` is 30. Its Javadoc says it bounds "the whole
  dispatch, a steady upload included".
- `GrpcDispatchStage`'s Javadoc says the guard's whole-dispatch timeout still applies to gRPC. Only the
  request-body idle bound is skipped there (`DispatchStage.NO_BODY_IDLE_TIMEOUT`).
- ADR-0060 A1 explicitly rejected a whole-body deadline and a configurable value.

**Deliverable 3, verify first.** Find out what the guard's timeout actually covers: does the guarded call
end when response headers arrive, or when the body is fully sent? Use a test that sends a body trickling
inside the 10 s idle bound for longer than 30 s.
- If the guard ends it, record that and add no new bound.
- Only if the test shows a gap, close it with a `408`: not counted against the breaker and never retried.
  The ADR-0060 amendment must then reopen A1's rejected alternative and say why it no longer holds.

**Deliverable 4, verify first.** Find out what the guard bounds on a client-streaming or bidirectional
gRPC call, before and after response headers.
- If a stream can outlive every bound, add a long stream-idle bound, optionally honouring
  `grpc-timeout` up to a cap, with Envoy's 5 min as the reference point.
- Otherwise record that the exemption is bounded and add a test that proves it.

**Constraints:**
- Any new bound is a fixed gateway constant, like the idle bound and the guard (ADR-0064): no
  `gateway.yaml` key, and no change under `config/validation/`. `PLAN-V02-42` is running there in parallel.
- `PLAN-V02-47` runs in parallel too. If deliverable 6 writes a new ADR instead of amending ADR-0060,
  re-check the next free ordinal against `origin/main` immediately before pushing.
- Kidicap `PLAN-29` is staged, not running. This plan settles deliverable 3 first, and `PLAN-29` reads
  your result.

Deliverables 3 and 4 may close as verified with no change. The split guard still counts 6 deliverables.
