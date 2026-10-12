envelope_version=1
sender_type=plan
sender_id=jwks-unknown-kid-refresh
epic=kidicap-gateway-requirements
kind=candidate-lesson
created=2026-10-12T15:29:53Z

component=api-sheriff:gateway-edge
category=bug
created=2026-10-12

# GatewayEdgeFramingCorpusTest is flaky: two unrelated failures, each green on re-run

Source: observation from the orchestrating session of plan `jwks-unknown-kid-refresh` (PR #430). The plan's branch touches nothing under `gateway/edge`.

`GatewayEdgeFramingCorpusTest` (raw-socket edge tests) failed twice during this plan, in two different test methods, and passed on re-run both times.

| Where | Test method | Symptom | Evidence it is not the branch |
|-------|-------------|---------|-------------------------------|
| CI, Java 26 | `soleTransferEncodingNotExactlyChunked` | response status 502 | the sibling CI run of the same commit was green |
| Local | `bodylessMethodNeverCarriesABodyUpstreamOnHttp2[2]` | 30 s timeout | re-run green, no code change in between |

## Candidate lesson

Treat a red `GatewayEdgeFramingCorpusTest` on a branch that does not touch `gateway/edge` as a suspected flake, and establish that before changing code: compare against the sibling run of the same commit, then re-run. Do not start fixing the branch on this signal alone.

The underlying defect is open. A 502 on one method and a 30 s timeout on another both fit a race in the raw-socket test setup (the upstream or the gateway listener not ready, or a connection not released) rather than a framing bug, but that is a hypothesis; neither failure was diagnosed in this plan.

## Impact

Each occurrence costs a full CI or local re-run. Because the class guards request-framing behaviour, a habit of re-running it until green would also hide a real regression there. It needs a diagnosis, not a retry rule.
