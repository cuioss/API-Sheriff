envelope_version=1
sender_type=plan
sender_id=kidicap-gateway-downstream
epic=kidicap-gateway-requirements
kind=finding
created=2026-09-16T10:51:04Z

## Downstream status: what `kidicap-gateway` now consumes from 0.2.1, and which AS items still bind it

Context for the epic, filed by the downstream deployment `kidicap-gateway` (GIP, GitLab
`gip/projects/kidicap-apigateway/kidicap-gateway`). No action is requested in this message; it records
what a real consumer of 0.2.1 verified, so the epic can weigh its items against observed usage.

### Confirmed working in 0.2.1, measured in a native distroless image

* **`egress_tls` knobs behave exactly as documented**, including the scope statements — see the sibling
  `finding` in this inbox for the measurement table and the untrusted-chain control.
* **`${VAR}` placeholders coerce to booleans at schema-typed pointers.** The deployment writes
  `upstream_verify_hostname: ${UPSTREAM_VERIFY_HOSTNAME:-true}` and the same for the JWKS key; the
  `:-default` form keeps the secure value when the variable is unset. This is the mechanism the
  scenario guide describes for `passthrough_sni` values, applied to booleans.
* **The JDK-triple trust route is the only one that works** on the distroless native image, as
  `configuration.adoc` § "Supplying a default trust store REPLACES the platform bundle" states. The
  integration stack builds a PKCS12 from the test CA in an init container and passes the three `-D`
  arguments on the runner's command line; `JAVA_TOOL_OPTIONS` is indeed ignored.
* **Boot records are usable as operator signals.** `ApiSheriff-17`, `ApiSheriff-118` and
  `ApiSheriff-120` were each observed and are now the documented check after a trust or hostname change.

### Items that still bind the gateway (no new information, only current weight)

* **AS-7 (structured userinfo claims)** — still blocking. `kidicap_permissions` arrives as a Java
  `toString` string, so the frontend cannot consume the userinfo view. The integration test carries it
  as a `HINWEIS` line rather than a failure, so the day it changes shape the test says so.
* **AS-13 (query validation after decoding)** — still shapes the configuration. The `api` anchor keeps
  `strict`, so a free-text search with spaces or umlauts answers `400`; the `app` anchor runs `minimal`
  purely to keep SPA deep links working. The integration test pins both behaviours, including the `400`,
  so the change will be visible as a failing expectation.

### What changed downstream since the last message

The repository now has a GitLab pipeline (`GIP/shared-pipelines`), a Helm chart and an integration test
that runs as a Helm test in-cluster — the same chart locally and in CI. The test covers the gateway
contract end to end against Keycloak: context path, login round trip through the Keycloak form,
userinfo, logout, per-application route targets, bearer validation, session mediation and CSRF. It is
therefore available as an external regression signal for API Sheriff releases: a parent-version bump is
run through it before the gateway adopts the release.

Version scheme, for cross-referencing in future messages: the gateway's release tags are
`<API-Sheriff-version>-<n>` (e.g. `0.2.1-1`), and a CI job refuses a tag whose prefix does not match the
parent version in `pom.xml`.
