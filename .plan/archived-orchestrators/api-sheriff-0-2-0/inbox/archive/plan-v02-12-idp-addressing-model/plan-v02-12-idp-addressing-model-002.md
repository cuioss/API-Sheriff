envelope_version=1
sender_type=plan
sender_id=plan-v02-12-idp-addressing-model
epic=api-sheriff-0-2-0
kind=finding
created=2026-10-13T10:54:33Z

# PLAN-V02-12 deliverables 5-7 wait on a token-sheriff-client release whose design must also cover revocation

Operator rulings (2026-10-13) during PLAN-V02-12 refine and outline:

1. **Upstream first.** The back-channel/discovery override (spec D1) goes into `token-sheriff-client`,
   not into the gateway. Today the library accepts only `issuer`. The newest remote tag is `0.9.6`,
   which is the pinned version, and `ClientConfiguration` on TokenSheriff `main` still has only
   `issuer`. PLAN-V02-12 consumes only a released artifact: no SNAPSHOT, no local build, no gateway-side
   stand-in.
2. **Stop deliverables 5-7 and redesign upstream.** On the pinned Keycloak 26.5.7 with
   `KC_HOSTNAME=https://localhost:1443` and `KC_HOSTNAME_BACKCHANNEL_DYNAMIC=true`, back-channel
   discovery moves token, JWKS, PAR, introspection and userinfo to `keycloak:8443`, but
   `revocation_endpoint` (and device authorization) stay on the external identity
   `https://localhost:1443`. The gateway's best-effort RFC 7009 revocation would then dial the external
   address from inside the container. The operator does not accept that as a residual: the upstream
   option must be designed to cover revocation as well, without rewriting the discovery metadata
   (RFC 8414 §3.3).

## What PLAN-V02-12 still does now

Deliverables 1-4: the reserved BFF paths move to `/bff/*`, userinfo becomes `/bff/session`, and the
client-auth JWKS default becomes `/bff/jwks`. Both sample anchors (`bff`, `bff-session`) are renamed.
The canonical-form check covers login, user_info and step_up paths. These ship as the first of two PRs
from the plan. Deliverables 5-7 (version bump, `oidc.discovery_url`, identity repoint plus removal of
`--host-resolver-rules`, ADR) are on hold until the upstream release exists.

## Downstream

PLAN-V02-09 and PLAN-V02-15 sequence behind this plan's D1, so they also wait on the upstream release.
