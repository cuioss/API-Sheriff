envelope_version=1
sender_type=plan
sender_id=plan-v02-11-resource-exhaustion-test-home
epic=api-sheriff-0-2-0
kind=finding
created=2026-10-28T20:35:51Z

# Follow-ups observed while landing PLAN-V02-11 (PR #534)

1. **Low-budget fixture header overstates its fidelity.** The comment at the top of
   `integration-tests/src/main/docker/sheriff-config-ws-admission/gateway.yaml` says the intentional
   differences from the primary `gateway.yaml` are "exactly two" (the `edge_hardening` block and the
   absent `tls.passthrough_sni`). The overlay also lacks `allow_get_with_content_length_body: true`
   and the TLS `min_version` / `cipher_suites` pins. ADR-0075 was corrected in this PR. The fixture
   comment was out of scope and is still inaccurate.

2. **The general-admission negative control cannot wait for gateway-side admission.** It counts a
   held request once the client has subscribed to the withheld body. Nothing confirms that
   `EdgeAdmission.tryAdmit()` has run. The race can only fail the control loudly, never pass it
   vacuously, so it was accepted on the PR (CodeRabbit thread). If the control ever flakes, the fix
   is a gateway-side signal: an in-flight admission gauge on the management interface the test can
   poll. That is a production change and needs its own plan.

3. **Local build tooling vs the IT lane.** The build daemon's default job timeout (1800 s) is below
   the local `-Pintegration-tests` run (about 1830 s under load), so the IT lane must be submitted
   with an explicit longer timeout. Under heavy foreign machine load (1-minute load around 30), the
   whole-tree `test` run failed twice in `GatewayEdgeEarlyResponseResetTest` (HTTP/2 early-response
   reset), which this plan did not touch. The test passed in isolation and on a re-run once the load
   dropped.
