envelope_version=1
sender_type=plan
sender_id=plan-28-jwks-refresh-follow-ups
epic=kidicap-gateway-requirements
kind=landing
created=2026-10-13T14:03:51Z

## What landed

plan-28-jwks-refresh-follow-ups shipped as #437 (merged, squash via the merge queue, 82f2d7d8).

```landing-facts
schema=landing-facts/1
plan_id=plan-28-jwks-refresh-follow-ups
epic=kidicap-gateway-requirements
pr=#437
merge_state=merged
cleanup_owed=false
deliverables_total=8
deliverables_done=8
total_tokens=5983254
total_wall_seconds=25362.0
steps=finalize-step-sync-baseline:done,finalize-step-simplify:done,finalize-step-security-audit:done,pre-submission-self-review:done,architecture-refresh:done,pre-push-quality-gate:done,push:done,create-pr:done,ci-verify:done,automatic-review:done,sonar-roundtrip:done,adr-propose:done,branch-cleanup:done,lessons-capture:done,finalize-step-preference-emitter:done,record-metrics:done,finalize-step-print-phase-breakdown:done,emit-landing:done
```

## Residue

- **D8 (upload_small benchmark) is diagnosed, not fixed.** The failure is a gateway-edge defect introduced by #431 (ADR-0066): each early-answered HTTP/1.1 upload resets the upstream request, closing the connection and holding one local port for 60 s, which caps new upstream connections near 470/s; past that the circuit breaker opens and requests get a fast 503 (40% error rate in a local run on the merged branch). CI passes only because its runner is slower (26,442 iterations, 1,790 below the port limit). Filed as inbox finding `plan-28-jwks-refresh-follow-ups-001`; it needs its own plan. The post-merge Performance Benchmark run on the merge (run 38407570839) was still in progress when this landing was written; a red result there is plausible and would be this defect, not a regression in this plan's code.
- **D6 (GatewayEdgeFramingCorpusTest) is partly unexplained.** Neither failure reproduced locally (Java 25 only; Java 26 not run). `bodylessMethodNeverCarriesABodyUpstreamOnHttp2[2]` runs through the path #431 reworked; the CI-only 502 on `soleTransferEncodingNotExactlyChunked` has no explanation.
- **D7 fix confirmed by one clean full integration run, not proven by repetition.** The cause was a docker-assigned loopback management port; one-off gateways now use a fixed port (application port + 9000, ADR-0065). The stub IdP admin port in StubIdentityProviderRig is still docker-assigned (not seen failing).
- **Two finalize checks degraded honestly:** `pre-submission-self-review` did not run (no self-review surfacer in this project; recorded as done with work_performed=false, not as a clean review), and `pre-push-quality-gate` ran only its whole-tree `verify -Ppre-commit` arm (the per-bundle and test-compile arms are written for the marketplace repo and do not resolve here).
- **Branch carries a merge of origin/main (#431, #435)** made at the operator's request for the D8 benchmark run.
- **Open doc residue:** `doc/configuration.adoc` still has two stale `token_validation` mentions (around line 2213 and the YAML example comment near line 380); the `IssuerConfig.Jwks` Javadoc still repeats the three refresh ranges unchecked; CodeRabbit's merge-risk note says the documented file-source restriction could still go stale (the new contract test checks key ranges, not that restriction).
- **Lesson candidates:** six candidate-lesson inbox messages (002-007) were written by lessons-capture.
