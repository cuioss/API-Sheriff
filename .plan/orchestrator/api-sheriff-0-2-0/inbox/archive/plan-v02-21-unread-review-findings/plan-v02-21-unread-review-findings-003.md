envelope_version=1
sender_type=plan
sender_id=plan-v02-21-unread-review-findings
epic=api-sheriff-0-2-0
kind=candidate-lesson
created=2026-10-13T14:14:50Z

# Candidate lesson: "services whose routes reach X" is ambiguous when gateways share a config directory

**Source signal:** Q-Gate finding `dc8c33` (phase 3-outline, severity warning, resolution fixed in-run). File: `integration-tests/docker-compose.yml`

## What happened

Deliverable 3's success criterion was "every api-sheriff* service whose routes reach passthrough-backend or toxiproxy lists that service under depends_on". The criterion left the set for the implementer to work out, and pointed at each overlay's `tls.passthrough_sni` block as the signal. But all twelve api-sheriff* services mount the shared sheriff-config directory, so all twelve load `endpoints/untrusted.yaml` (to passthrough-backend:8443) and `endpoints/upstream-fault.yaml` (to toxiproxy:8667), whatever their own gateway.yaml overlay says. Only two also carry `passthrough_sni`. Read by routes the set is twelve; read by `passthrough_sni` it is two. The result depended on which reading the implementer picked.

## Fix applied in-run

D3 now names the resolved set: all 12 api-sheriff* services get passthrough-backend and toxiproxy as `service_healthy` dependencies, where "reach" means a declared route (from the shared untrusted.yaml / upstream-fault.yaml). The dependencies are delivered through an `x-gateway-backend-deps` anchor merged inside each `depends_on` map. The hardening anchor cannot carry them because YAML merge is shallow. The fallback is repeated entries, and the egress pair's explicit duplicates were dropped.

## Candidate rule

In the integration-test compose stack, the per-service overlay is not the whole route set: the shared sheriff-config endpoints apply to every gateway. An outline criterion that selects services "by route" must state the resolved service list, and say whether "reach" means a declared route or an exercised one.
