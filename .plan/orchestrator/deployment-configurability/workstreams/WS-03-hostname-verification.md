# WS-03: Configurable Hostname Verification (Outbound Connections)

epic: deployment-configurability

## Charter

TLS hostname verification is unconditional on every connection API Sheriff originates, with no
off-switch anywhere. Real gateway topologies — internal DNS, Docker service names, SAN-mismatched
internal CAs — need it disableable. This workstream makes it configurable, **defaulting to ON**,
on both outbound surfaces, researching a standard mechanism before implementing anything bespoke.
It closes when both surfaces carry a documented, tested off-switch that is secure unless explicitly
relaxed.

## "At least for the TLS termination mode" — read literally

ADR-0017 defines TLS termination as an actual named mode: a dedicated raw-TCP front listener owns
the public port, reassembles the ClientHello, and by SNI either opaquely **L4-relays** a passthrough
match to its backend or hands the still-encrypted connection to an internal **terminating** HTTPS
listener. Hostname verification is only meaningful in the terminating branch — a passthrough
connection is relayed at L4 and its certificate is never inspected by the gateway at all. The
operator's qualifier is therefore a precise scoping instruction, and PLAN-03 is cut exactly on it.

## The two surfaces are not one job

| | PLAN-03 — upstream dial | PLAN-04 — JWKS back-channel |
|---|---|---|
| Connection | Terminated request proxied to the upstream | Issuer JWKS / discovery fetch |
| Client | Vert.x `HttpClientOptions` / `RequestOptions` | cui-http `HttpHandler` over `java.net.http.HttpClient` |
| Knob available | `setVerifyHost(boolean)` is a first-class Vert.x method — never called | **None.** `HttpHandlerBuilder` exposes no hostname-verification method, and the JDK client enforces verification internally |
| Expected shape | Config model + binding + test | Mechanism decision (ADR), then implementation |

## Scope

- In scope: the upstream dial's TLS options on terminated connections; the JWKS trust path through
  `JwksTrustProfileResolver` / `TokenValidatorProducer`; the gateway.yaml policy vocabulary for both;
  and the threat-model and configuration documentation.
- Out of scope: certificate TRUST relaxation as a separate feature — `jwks.tls_profile` and the
  `client_ca` mTLS anchor stay as they are. Passthrough connections, which are never inspected.
  Changing any default: verification stays ON everywhere.

## Plans

| Plan | Status | Notes |
|------|--------|-------|
| PLAN-03-upstream-hostname-verification | staged | Terminated-mode upstream dial — gateway.yaml knob, default on |
| PLAN-04-jwks-hostname-verification | staged | JWKS back-channel — mechanism ADR, then the knob |

## Sequencing and Surface Notes

- PLAN-03 overlaps WS-02's PLAN-02 on `application.properties`; sequenced.
- PLAN-04 runs after PLAN-03 so the two knobs are named consistently in gateway.yaml's vocabulary.
- ADR-0025 boundary: gateway.yaml names TLS POLICY neutrally while ports and trust material stay
  deployment-supplied. Both knobs are policy and belong in gateway.yaml, not `application.properties`.
- Both plans ship a security-weakening switch. Each owes a docs deliverable stating the default,
  the blast radius, and the topologies where relaxation is legitimate.
