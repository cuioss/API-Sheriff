envelope_version=1
sender_type=plan
sender_id=kidicap-gateway-downstream
epic=kidicap-gateway-requirements
kind=finding
created=2026-09-17T14:46:00Z
revision=1
amended=2026-09-17T14:46:26Z

## Defect: RFC 9470 step-up is documented as active but is not wired into the edge

Filed by the downstream deployment `kidicap-gateway`. Code read at 0.2.1 and `main` (2026-09-17); not
measurable downstream, because the baked configuration does not enable `oidc.step_up` and the echo upstream
cannot emit an RFC 9470 challenge. Downstream reference: `doc/autorisierung/step-up.adoc#stand`, AU-8.

### Observation

* `BffRuntimeProducer` builds a `StepUpCoordinator` and stores it in `BffRuntime`
  (0.2.1: `BffRuntimeProducer.java` around line 312; `main`: around line 382).
* `BffRuntime.stepUpCoordinator()` has **no caller in production code**. `GatewayEdgeRoute` and the proxy
  dispatch path never read an upstream `WWW-Authenticate`; a `401 insufficient_user_authentication` reaches
  the client unchanged.
* The silent-satisfaction seam is bound to `(sessionRecord, challenge, now) -> Optional.empty()`, so even
  once wired every challenge would re-drive through the browser.
* The only callers are unit tests (`StepUpCoordinatorTest`, `BffRuntimeProducerTest`). The comment at the
  construction site says the edge integration is exercised by the Keycloak integration tests; no step-up
  test was found under `integration-tests/`.
* `doc/configuration.adoc`, row `step_up.*`: "honour an upstream `401` + `insufficient_user_authentication`
  challenge by silent refresh, then re-authorization with elevated `acr_values`/`max_age`" — describes the
  behaviour as present.

### Wanted

Either wire the coordinator into the edge (the requirement in `kidicap-gateway-downstream-009` builds on
exactly that), or state in the configuration reference that `step_up.*` is accepted but has no effect yet,
and refuse or warn at boot when it is enabled. An integration test that sends a challenge from an upstream
should pin whichever behaviour is chosen.
