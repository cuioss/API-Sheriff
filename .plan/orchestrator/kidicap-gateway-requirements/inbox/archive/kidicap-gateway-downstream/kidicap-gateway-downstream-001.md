envelope_version=1
sender_type=plan
sender_id=kidicap-gateway-downstream
epic=kidicap-gateway-requirements
kind=finding
created=2026-09-16T10:50:58Z

## Requirement: a hostname-verification knob for the BFF's OIDC back-channel

Filed by the downstream deployment `kidicap-gateway` (GIP, GitLab
`gip/projects/kidicap-apigateway/kidicap-gateway`), which runs API Sheriff 0.2.1 rebuilt under the
context path `/KIDICAP.Gateway`. This is a new item beyond AS-1..AS-14 — proposed name **AS-15**; the
orchestrator owns the actual numbering.

### What is missing

`egress_tls` carries `upstream_verify_hostname` and `jwks_verify_hostname`. The BFF's own back-channel
to the identity provider — discovery, the authorization-code exchange, refresh — is dialled by
`token-sheriff-client` and has no equivalent knob. An IdP reached through a name its certificate does
not carry is therefore reachable for token validation but not for login: the two legs of the same
relationship answer differently.

`doc/user/tls-edge.adoc` (branch `feature/release-docs-and-tls-scenario-guide`) already names the leg
and its missing trust seam (`token-sheriff-client#597`); this message asks for the hostname half of the
same seam.

### Evidence (measured 2026-09-15, API Sheriff 0.2.1, native distroless image)

Gateway plus Keycloak 26.5.7 in a k3s cluster, one generated CA trusted by the gateway on both tiers.
The dialled name `<service>.<namespace>.svc` resolves but is not in the certificate's SANs; the
certificate names `<service>` and `<service>.<namespace>.svc.cluster.local`.

| Configuration | Result |
|---|---|
| Upstream alias on the unnamed host, defaults | `502`; with `upstream_verify_hostname: false` → `200`, `WARN ApiSheriff-118` |
| JWKS URL on the unnamed host, defaults | JWKS refused (`No subject alternative DNS name matching …`), bearer route `401` |
| same with `jwks_verify_hostname: false` | `TokenSheriff-7: JWKS loaded successfully`, bearer route `200`, `WARN ApiSheriff-120` |
| Issuer on the unnamed host, BOTH knobs `false` | login `500`; `TokenSheriffClient-200: OIDC discovery failed … No subject alternative DNS name`, stack through `DiscoveryResolver.fetch` |
| same, plus `-Djdk.internal.httpclient.disableHostnameVerification=true` | full round trip: login `302` → callback `302` → userinfo `200` |
| same property, but the chain is untrusted (no truststore) | JWKS and discovery still refused, `PKIX path building failed` |

The last row is the control that matters for the security argument: the JDK property, like the two
documented knobs, relaxes the name comparison only — chain validation is untouched. The two documented
scope statements (ADR-0040 § scope, ADR-0041 § what disabling does not do) reproduce exactly in a
downstream deployment.

### Proposal

A third key in the same block, defaulting to the secure value:

```yaml
egress_tls:
  oidc_verify_hostname: true   # default true; false relaxes hostname matching on discovery,
                               # code exchange, refresh and revocation only
```

* Same semantics as `jwks_verify_hostname`: hostname matching only, chain trust untouched, global (no
  per-issuer override), one `WARN` at boot naming scope and remedy.
* Prerequisite is a TLS seam in `token-sheriff-client` (`token-sheriff-client#597`). If that seam
  arrives as an `SSLContext`, the same boot-time collision rule ADR-0041 states for
  `jwks_verify_hostname` plus `jwks.tls_profile` applies here and should be refused the same way.
* Acceptance, mirroring the existing matched-pair tests: a login round trip against an IdP whose chain
  is trusted and whose certificate does not name the dialled host — refused by default, completed with
  the knob `false`, and still refused when the chain is untrusted.

### Why it matters downstream

Two topologies in KIPS and in the GIP CI cluster reach the IdP through a name its certificate does not
carry: a cluster-internal service name, and split-horizon DNS where the gateway resolves the public
issuer host to an internal address. Both are the topologies ADR-0040/0041 already accept as legitimate
for the other two legs.

### Interim workaround in the downstream repository, and its exit

`kidicap-gateway` exports the two 0.2.1 knobs as environment variables (`UPSTREAM_VERIFY_HOSTNAME`,
`IDP_VERIFY_HOSTNAME`, both defaulting to `true`). Because a variable named after the IdP that relaxes
only half of the IdP surface is a trap, `IDP_VERIFY_HOSTNAME=false` additionally makes the deployment
pass `-Djdk.internal.httpclient.disableHostnameVerification=true` on the native runner's command line
(Helm chart and Compose start script). That property is JDK-internal, process-wide across JDK HTTP
client legs and silent — the visible record stays `WARN ApiSheriff-120` from the real knob, since the
same variable sets it.

The workaround is documented as such (`doc/open-issues.adoc#hostname-oidc`) and is removed in the same
change that adopts the knob: the variable then binds `egress_tls.oidc_verify_hostname`, and nothing in
the deployment surface changes.
