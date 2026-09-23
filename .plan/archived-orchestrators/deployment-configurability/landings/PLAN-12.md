# Landing: PLAN-12 — Instrument the kqueue readiness hypothesis

**Outcome**: ⛔ **HYPOTHESIS REFUTED.** Concluded 2026-09-03 at HEAD `5e8413b`.
**Vehicle**: executed outside the plan-marshall lifecycle by operator decision, from
`.plan/temp/kqueue-readiness-instrumentation.md`. No plan id, no PR, no lifecycle finalize —
by construction, per the tracking-spec header.
**Successor**: `loopback-stall-fix-and-instrumentation` (tracked from here as **PLAN-13**).

## What the plan set out to test

That the macOS-local live-Vert.x loopback stall (30 s CONNECT-tier hangs, ~26 % of local runs,
zero in CI) was a **missed kqueue readiness event** — the acceptor sitting in `KQueue.poll` because
a readiness notification was dropped.

## What it found instead

**It is not kqueue.** `.listen(0)` with no host binds the dual-stack wildcard `*:P`. Netty sets
`SO_REUSEADDR=true`, and macOS then permits that wildcard bind to take an ephemeral port another
process already holds as a `127.0.0.1`-specific listener. A client dialling `127.0.0.1:P` is routed
by BSD most-specific-match to that **other process**, which never answers. The acceptor is idle in
`KQueue.poll` because nothing was ever queued on its listener — the symptom the hypothesis mistook
for the cause.

## Evidence — measured, not argued

Held in `.plan/temp/kqueue-probe/` (`RESULTS.md`, `PRE-REGISTRATION.md`, and the probe sources
`CollisionPopulation.java`, `PortCollisionProof.java`, `BindArmComparison.java`, plus five run logs).

- 179 foreign `127.0.0.1` listeners inside 49152–65535 on this machine → **1.09 % per `listen(0)`**.
- Matched controls over 20 000 ephemeral binds: the wildcard arm took all 179 occupied ports
  (1.0931 %); the loopback-bound arm took **0**.
- `listen(P, "127.0.0.1")` over a squatted port fails with `BindException` — which is *why* the fix
  works, not merely that it correlates.

⚠ The decisive instrument is a **system-wide** `lsof -iTCP:<port>` at the live ceiling, which names
the foreign owner. A per-process `lsof` shows only a listener plus a client socket and no accepted
socket — suggestive, never conclusive. Four earlier fix attempts failed because the mechanism was
never measured; that is the reusable lesson, not the port number.

## What follows

- **The fix**: `.listen(0)` → `.listen(0, "127.0.0.1")` at **20 call sites** across **9 files** in
  `api-sheriff/src/test/java` — 7 under `edge/`, 2 under `tls/`. Verified present at `5e8413b`.
  Being landed by PLAN-13, not by this plan.
- ⛔ **Never widen a ceiling for this.** A timeout increase treats the symptom and re-hides the
  mechanism.

## Still open — carried, not closed

- Whether the 5 s TEARDOWN-tier occurrence (finding `f243e7`) is the same mechanism. **Not measured.**
- That CI is clean *because* its runners carry no such listeners. **Unproven** — testable by running
  `CollisionPopulation.java` in CI, which nothing yet does.
- The epic's 30s-hang defect is therefore **narrowed, not closed**.
