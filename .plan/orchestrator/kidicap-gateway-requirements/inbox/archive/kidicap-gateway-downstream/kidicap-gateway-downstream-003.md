envelope_version=1
sender_type=plan
sender_id=kidicap-gateway-downstream
epic=kidicap-gateway-requirements
kind=finding
created=2026-09-16T15:21:24Z

## Requirement: default the JWKS egress allowance to the host of `jwks.url`

Filed by the downstream deployment `kidicap-gateway`. Sibling of the message on the OIDC back-channel
hostname knob; this one is about a value that is redundant with a value the operator already wrote.

### The observation

`token_validation.issuers[].jwks.allowed_egress_hosts` must carry the host of the JWKS URL a second
time, as a bare hostname with no port. Every internal deployment needs it — the IdP resolves to an
RFC1918 address — so in practice it is not an exception but a second, derived value that the operator
maintains by hand next to the URL.

Three properties make that a trap rather than an inconvenience:

* **The two values can disagree silently.** Nothing compares them at boot.
* **An empty entry boots.** Measured 2026-09-16 against the 0.2.1 native image: with
  `allowed_egress_hosts: [""]` (a `${VAR}` that resolved to empty) the gateway starts normally, reports
  no violation, and the JWKS fetch is refused later at runtime. The failure surfaces as a login problem
  far from its cause.
* **A port makes it inert.** Measured: `host:8443` in the list is never matched — the comparison is
  host-exact — so a value copied from the URL rather than typed carefully has no effect, again without a
  boot-time complaint.

### Proposal

When `allowed_egress_hosts` is absent or empty for an `http`-sourced issuer, default it to the host of
that issuer's configured `jwks.url`.

The SSRF boundary is preserved: the operator wrote that URL into the configuration, so admitting exactly
its host is not a widening of trust — it is the narrowest allowance that makes the configured URL usable.
An operator who wants a different or wider set keeps naming hosts explicitly, exactly as today.

Alternatives, if defaulting is unwanted: accept `host:port` and compare on the host part, or add an
explicit marker (`allowed_egress_hosts: [from-jwks-url]`). Either removes the silent-mismatch class; the
default is the only one that also removes the duplicate value.

A boot-time refusal of an empty list entry would be a smaller improvement worth having in any case: it
converts the worst failure mode (starts, fails later, looks like a token problem) into a loud one.

### Why it matters downstream

The deployment cannot derive the value inside the container: the published image is distroless with a
native executable, so there is no shell and no entrypoint hook — the very reason
`configuration.adoc` gives for the `${VAR}` contract on `client_secret`. The derivation therefore has to
happen in the deployment tooling (Helm chart at render time, Compose start script), and Compose needs a
`:?` guard so an empty value fails the start instead of reaching the gateway. That is three places
carrying one rule that belongs in the component owning the schema.
