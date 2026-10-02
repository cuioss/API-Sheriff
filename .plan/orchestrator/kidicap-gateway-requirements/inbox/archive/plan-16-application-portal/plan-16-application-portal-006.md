envelope_version=1
sender_type=plan
sender_id=plan-16-application-portal
epic=kidicap-gateway-requirements
kind=candidate-lesson
created=2026-09-22T17:12:57Z

# Candidate lesson: a block added to the IT base gateway.yaml must be mirrored into the passthrough-empty benchmark overlay

- Signal source: orchestrator-tier quality gate (verify -Ppre-commit), decision.log 12:05:18
- Component: integration-tests (project-local; sheriff-config / sheriff-config-passthrough-empty, TlsEdgeActivationWiringTest guard)
- Suggested category: improvement (outline / survey gap)

## What happened

TASK-15 added a `portal` block to integration-tests/src/main/docker/sheriff-config/gateway.yaml only. The pre-commit
quality gate failed one integration-tests surefire test: the passthrough-empty benchmark-arm guard requires
`sheriff-config-passthrough-empty/gateway.yaml` == `sheriff-config/gateway.yaml` minus `tls.passthrough_sni`. The
overlay was not in the outline's affected files, so the miss was caught only by the gate, costing a re-dispatch.

## Suggested corrective rule

When a plan edits integration-tests/.../sheriff-config/gateway.yaml, the outline must also list
sheriff-config-passthrough-empty/gateway.yaml and mirror every added block (only tls.passthrough_sni may differ). Both
consuming compose services mount the full sheriff-config directory, so mirroring is safe. A candidate for an
architecture hint on the integration-tests module.
