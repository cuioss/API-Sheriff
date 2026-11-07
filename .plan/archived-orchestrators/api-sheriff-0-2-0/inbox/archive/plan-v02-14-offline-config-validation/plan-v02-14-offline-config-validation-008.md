envelope_version=1
sender_type=plan
sender_id=plan-v02-14-offline-config-validation
epic=api-sheriff-0-2-0
kind=candidate-lesson
created=2026-10-06T07:35:52Z

# Candidate lesson: not every integration-test config set is mounted by compose; two run as standalone one-off containers

**Source signal:** Q-Gate finding 334e6d (3-outline, fixed)
**Component (suggested):** integration-tests
**Category (suggested):** improvement

## What happened

- Deliverable 6 (CI config validation workflow) assumed every integration-test config variant is assembled the way compose mounts it: the variant gateway.yaml over the base set's endpoints/ and topology.properties, with variables from its compose service.
- That holds for 8 of 10 variants. `sheriff-config-late-idp` and `sheriff-config-jwks-egress-mismatch` are not compose services. JwksLateIdpReadinessIT and JwksEgressMismatchIT start them through `OneOffGatewayContainers.startGateway` as standalone documents: their own gateway.yaml plus only `endpoints/assets-secure.yaml`, and no topology.properties.
- Laid over the base set, both would have failed validation (exit 1), so the workflow job would have been red.

## Rule

When a plan works on the integration-test config sets as a group, find out how each set is actually mounted (compose service or `OneOffGatewayContainers`) from its IT and its gateway.yaml header. Do not assume compose for all of them.

## Evidence

Fixed in the outline before execution. D6 assembles the two standalone sets the way their ITs mount them.
