envelope_version=1
sender_type=plan
sender_id=plan-14-session-and-scopes
epic=kidicap-gateway-requirements
kind=candidate-lesson
created=2026-09-21T22:25:21Z

# Candidate lesson: build-server daemon build needs --project-dir and an inner --timeout for IT builds

**Source signal**: script-failure cluster during plan-14-session-and-scopes (build-server-client daemon build).

**Observation**: Submitting the build to the marshalld daemon required `--project-dir` (not `--plan-id`) to target the tree. The integration-test build (`verify -Pintegration-tests`, native image + docker stack) also needed an inner `--timeout 3600`; the default inner timeout is too short for it.

**Suggested corrective action**: the build-server-client submit documentation / canonical invocation should state that the target is selected by `--project-dir`, and the IT/native build path should resolve a long inner timeout (e.g. from the architecture-resolved `bash_timeout_seconds` for that command) instead of relying on the default.

**Classification**: deferred to orchestrator.
