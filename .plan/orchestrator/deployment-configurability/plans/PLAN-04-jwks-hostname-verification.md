# PLAN-04: Configurable Hostname Verification — JWKS / Discovery Back-Channel

epic: deployment-configurability
workstream: WS-03

> Staged plan spec — the emitted command is a one-line pointer, so this spec is the whole brief.

## Objective

The gateway's second outbound TLS surface is the token-validation back-channel: the JWKS and
discovery fetches `TokenValidatorProducer` sets up per configured issuer. Hostname verification there
is unconditional and, unlike the upstream dial, cannot be reached by any configuration lever at all —
the underlying client exposes no such method. Make it configurable, **defaulting to ON**, named
consistently with the knob PLAN-03 introduces. Because no lever exists, this plan's first act is a
**mechanism decision recorded as an ADR**; the operator's "research a standard way first" applies with
full force here. Keep the scope to hostname matching: the existing `jwks.tls_profile` trust-anchor
mechanism is a separate, working feature and is not folded in.

## ⛔ MECHANISM RE-SCOPED 2026-09-07 — cui-http 3.0 SHIPS THE KNOB. READ THIS FIRST.

**Claim 11 is REFUTED and the hand-rolled trust manager is no longer the mechanism.** PLAN-03 landed
`<version.cui.http>3.0</version.cui.http>` (PR #268 → `a8c9834`), and the 3.0 sources carry
`HttpHandlerBuilder#verifyHostname(boolean)`, **default `true`** — precisely the knob
`cuioss/cui-http#165` requested and whose absence this plan's option (b′) was built around.

Read at `~/.m2/repository/de/cuioss/cui-http/3.0/cui-http-3.0-sources.jar` §
`de/cuioss/http/client/handler/HttpHandler.java` "Hostname verification":

> *"Setting it to `false` is a deliberate opt-in that skips **only** the match between the peer
> certificate's identity (SAN / CN) and the connected host. Certificate-chain trust, validity period,
> and algorithm constraints remain fully enforced… A WARN (`HTTP-116`) is logged for every handler
> built this way."*

That is the exact semantic this plan specified, now supplied upstream and warned about by the library.

### What this deletes

- ⛔ **Option (b′) — the delegating `X509ExtendedTrustManager` — is RETIRED.** Do not hand-roll it.
- ⛔ **The TokenSheriff coordination is DISSOLVED.** It existed only because both repositories would
  otherwise write the same security-sensitive class independently. Neither needs to now. Do not
  re-open that coordination; do not wait on it.
- ⛔ **The JDK-version bound on the (a)-refutation is MOOT.** It bounded a mechanism no longer in use.

### ⛔ What this ADDS — a hard constraint discovered in the same read, and the new design fork

The same Javadoc states that the relaxation is confined to the context the library derives itself:

> *"combining `verifyHostname(false)` with a caller-supplied `HttpHandlerBuilder#sslContext(SSLContext)`
> is rejected at `HttpHandlerBuilder#build()` time with `IllegalArgumentException`."*

⚠ **This gateway supplies exactly such a context.** `JwksTrustProfileResolver.resolve(...)` (`:119`)
returns an `SSLContext` for any issuer declaring `jwks.tls_profile`. So the two features are
**mutually exclusive as the API stands**:

| Issuer shape | `verifyHostname(false)` |
|---|---|
| **no** `jwks.tls_profile` — library-derived context | ✅ works directly |
| **has** `jwks.tls_profile` — caller-supplied `SSLContext` | ⛔ **refused at `build()` with `IllegalArgumentException`** |

⛔ **This is the plan's central design question now, and it must be settled before implementation.**
The corporate-internal-CA deployment — the one most likely to *need* hostname relaxation — is exactly
the one that also supplies a trust profile, so the collision is not a corner case. Settle at outline
which of these the plan takes, and record the rejected alternatives:
(i) refuse the combination at *boot* with a clear config error, mirroring the library;
(ii) derive the relaxed context from the profile's trust material ourselves and pass it as
caller-supplied with verification already relaxed inside it — **verify this is even reachable**, since
the library reserves relaxation to its own derivation;
(iii) petition upstream for a combination the library supports.

⚠ This also disturbs the original scoping note that `jwks.tls_profile` is *"a separate, working
feature and is not folded in"*. It is still separate, but the two now **interact by refusal**, and the
plan can no longer treat it as untouched.

⚠ **Everything below this block predates the refutation.** Deliverable 1's ADR is still owed but now
records a *different* decision; deliverables 2 and 3 stand in intent. Re-scope at outline against this
block, not against the prose beneath it.

## Deliverables

1. **Record the SETTLED mechanism as an ADR** under `doc/adr/`. ⚠ **The mechanism question was
   settled by the orchestrator on 2026-08-27 — this deliverable writes the decision up, it does not
   re-open it.** The verdicts are stamped on the claims below; the ADR reproduces the evidence rather
   than re-deriving it.

   ⚠ **Evidence re-verified 2026-08-27 at the versions this product actually uses.** The original
   settlement was read against **JDK 24.0.2** — the JDK that happens to be on `PATH` — while this
   project compiles and runs on **Java 25** (CI matrix 25 + 26). Re-read at
   `25.0.1-tem`: the finding **holds**, with a one-line drift (`:138-139`, not `:137-138`). The
   `cui-http` half was re-read at the **resolved** artifact (2.1.0) *and* cross-checked against the
   `/Users/oliver/git/cui-http` `main` checkout (2.2-SNAPSHOT): `HttpHandler.java` is **byte-identical**
   between them and nothing has touched the `client/handler` package since the `2.1.0` tag, so the
   absence is true of **current upstream**, not merely of the pinned release.
   ⛔ **Bound: JDK 26 is not installed locally and is unverified.** CI runs it. Re-check there.

   - ⛔ **(a) an upstream `cui-http` knob setting `SSLParameters.setEndpointIdentificationAlgorithm`
     is REFUTED.** `jdk.internal.net.http.AbstractAsyncSSLConnection:125` copies the caller's
     parameters — and `common/Utils.copySSLParameters:611` *does* carry
     `endpointIdentificationAlgorithm` across — but `:138-139` then unconditionally overwrites it to
     `"HTTPS"`, so no per-connection `SSLParameters` setting can reach it. The guard
     `disableHostnameVerification` is a `private static final` read once at class-init (`:71-72`) from
     the JVM-wide property that `java.net.http/module-info.java:166-169` documents as *"provided for
     testing purposes only."* (JDK 25.0.1.)
   - ✅ **(b′) a delegating `X509ExtendedTrustManager` forwarding chain validation to the platform
     trust manager with a NULL `SSLEngine`** is the chosen mechanism — chain trust, expiry and
     algorithm constraints preserved, hostname matching skipped. It goes through the
     `builder.sslContext(...)` seam `JwksTrustProfileResolver` already uses. **No `cui-http` release
     is required, and this plan is not blocked on one.**
   - ⛔ **A trust-all `X509TrustManager` is explicitly rejected** — it gives up chain validation too.
     Note this is exactly the shape `JwksTrustProfileResolver` must NOT drift into: it currently
     refuses an unresolvable `tls_profile` rather than falling back to default trust, and a
     trust-all knob beside it would be a second, silent way to widen trust.
   - ⛔ **(c) the JVM-wide system property is rejected** — process-global, not per-issuer, and
     documented by the JDK as test-only.

   Upstream tracking: `cuioss/cui-http#165` requests a first-class knob so consumers do not each
   hand-roll this security-sensitive class. **Enhancement, not a dependency** — do not wait for it;
   migrate and delete the local trust manager as a follow-up if it lands first.
   ⚠ **Cross-repo: the shared change is no longer (a) — it is the trust manager itself.** With (a)
   refuted, the duplication moved rather than disappeared. TokenSheriff's epic
   `deployment-and-refresh-gaps` → `PLAN-03-outbound-hostname-verification-core` deliverable 1 stages
   the *identical* decision against the *same* `cui-http` limitation and names this plan as its
   counterpart, so **both projects now land on (b′) and both will hand-roll the same
   security-sensitive delegating `X509ExtendedTrustManager`** — which is precisely the duplication
   `cuioss/cui-http#165` exists to end. Coordinate on the implementation so the two are the same
   class, and so whichever lands first is the one upstream absorbs. The pointer was one-directional
   (TokenSheriff named API Sheriff, not the reverse); recorded here 2026-08-27 so it reads both ways.
   ⛔ `corpus cross-check` is structurally blind to this: it scans only THIS repository's store and
   reports `epics_scanned: 0`, so the duplication cannot be surfaced by the tool and must be carried
   in prose.
2. **Bind the knob PLAN-03 already named.** ⛔ This plan **re-decides no naming**: PLAN-03's
   deliverable 2 is the single vocabulary authority for both outbound surfaces and has already
   settled the key name, value shape, default and documentation siting for the JWKS half. Read that
   landed vocabulary and implement against it. If it turns out PLAN-03 did not specify the JWKS-side
   key, that is a PLAN-03 gap — raise it rather than inventing a name here, because a name agreed
   after the fact is a name agreed twice, which is precisely the two-knobs-for-one-feature defect the
   split was structured to avoid.
3. **Implement it through `TokenValidatorProducer` / `JwksTrustProfileResolver`**, preserving the
   existing contract that an unresolvable `tls_profile` is refused at boot rather than falling back to
   default trust. The new knob must not become a second way to silently widen trust.
4. **Test it** — a matched positive and negative control proving a SAN-mismatched JWKS host is
   rejected by default and accepted with the knob off, plus a boot-validation test that the knob is
   parsed and refused when malformed. Cover the interaction with `tls_profile`: the two must compose
   predictably.
5. **Fill in the documentation PLAN-03 already framed, and complete the threat-model entry.**
   PLAN-03 authored the shared section covering both surfaces with the JWKS half marked as landing
   here; complete that half in place rather than adding a second section. The reader must come away
   seeing ONE feature — hostname verification, default ON — applied to two outbound surfaces, never
   two features that happen to resemble each other.

## Claim Labels

- OBSERVED: `TokenValidatorProducer` resolves per-issuer trust by calling
  `builder.sslContext(trustProfileResolver.resolve(issuer, tlsProfile))` — read at
  `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/auth/TokenValidatorProducer.java:203`. That
  `sslContext(...)` seam is the only injection point the current API offers, which is what makes
  mechanism (b) reachable today.
  - verdict: corroborated | checked_at: cc10ce2a2c7840162bbc087242d42cbbc3cd14c3 | by: deployment-configurability/cleanup | rescoped: n/a | evidence: TokenValidatorProducer.toHttpJwksLoaderConfig still calls builder.sslContext(trustProfileResolver.resolve(issuer, tlsProfile)) as the sole injection seam
- OBSERVED: an issuer that omits `tls_profile` never reaches the resolver — the caller skips
  resolution and default trust applies; when a profile IS named and cannot be resolved,
  `JwksTrustProfileResolver` **refuses** rather than falling back — read at
  `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/auth/JwksTrustProfileResolver.java:87` and
  `:123-130`. Deliverable 3 must preserve that refusal.
  - verdict: corroborated | checked_at: cc10ce2a2c7840162bbc087242d42cbbc3cd14c3 | by: deployment-configurability/cleanup | rescoped: n/a | evidence: JwksTrustProfileResolver.java still throws GatewayException(CONFIG_INVALID) on an unresolvable tls_profile
- OBSERVED: `TokenValidatorProducer.onStartup` forces the validator into existence at boot and a
  failure there aborts startup — stated at `api-sheriff/src/main/resources/application.properties`
  § the token-validation comment block. So a malformed new knob fails fast, which is the desired shape.
  - verdict: corroborated | checked_at: cc10ce2a2c7840162bbc087242d42cbbc3cd14c3 | by: deployment-configurability/cleanup | rescoped: n/a | evidence: application.properties still documents TokenValidatorProducer.onStartup forcing validator at boot
- OBSERVED: the request path runs off gateway.yaml's `token_validation` block via the
  `@GatewayValidator`-qualified validator, and the token-sheriff-validation-quarkus extension's own
  `sheriff.token.issuers.*` namespace is deliberately unused here — its unqualified health and metrics
  beans are excluded via `quarkus.arc.exclude-types` — read at the same file § the exclusion block.
  ⛔ The knob therefore belongs in gateway.yaml, NOT in the extension's Quarkus namespace.
  - verdict: corroborated | checked_at: cc10ce2a2c7840162bbc087242d42cbbc3cd14c3 | by: deployment-configurability/cleanup | rescoped: n/a | evidence: quarkus.arc.exclude-types still excludes token.quarkus.health/metrics at application.properties:337
- OBSERVED: the shipped artifact configures no issuer on the extension surface and no named trust
  profile in any profile, and that is stated as *"the contract, not an omission"* — read at the same
  file. A test fixture must supply its own issuer rather than assuming one.
  - verdict: corroborated | checked_at: cc10ce2a2c7840162bbc087242d42cbbc3cd14c3 | by: deployment-configurability/cleanup | rescoped: n/a | evidence: "the contract, not an omission" text still present near the exclusion block
- HYPOTHESIS: the resolved `cui-http` `HttpHandlerBuilder` exposes no hostname-verification method —
  its surface being `uri`, `url`, `sslContext`, `tlsVersions`, `connectionTimeoutSeconds`,
  `readTimeoutSeconds`, `allowInsecureHttp` — and `HttpHandler` builds a `java.net.http.HttpClient`
  whose `SSLParameters` set only `setProtocols(...)` — confirm/refute at the resolved `cui-http`
  version's `de/cuioss/http/client/handler/HttpHandler.java` § its constructor and `HttpHandlerBuilder`
  (verify-at-outline). This claim was read from a `cui-http` sources jar, NOT from this project's
  resolved dependency tree; re-read it at the version this project actually resolves before scoping.
  - verdict: contradicted | checked_at: cc10ce2a2c7840162bbc087242d42cbbc3cd14c3 | by: deployment-configurability/cleanup | rescoped: no | evidence: cui-http 3.1's HttpHandlerBuilder exposes verifyHostname but remains unreachable; gateway's operative mechanism is token-sheriff's own builder, unchanged at HEAD
- HYPOTHESIS: `java.net.http.HttpClient` enforces hostname verification internally and ignores a
  caller-supplied `SSLParameters.setEndpointIdentificationAlgorithm(null)`, leaving the JVM-wide
  system property and a permissive trust manager as the only working levers — confirm/refute against
  the target JDK's behaviour, exercised by a test against a SAN-mismatched host (verify-at-outline).
  This is the load-bearing claim: it decides between mechanisms (a), (b) and (c).
  - verdict: corroborated | checked_at: cc10ce2a2c7840162bbc087242d42cbbc3cd14c3 | by: deployment-configurability/cleanup | rescoped: n/a | evidence: stable JDK-level fact (HttpClient hostname enforcement), pom.xml still targets Java 25
- HYPOTHESIS: mechanism (b) cannot relax hostname matching without also relaxing chain trust, because
  endpoint identification runs inside the trust manager — confirm/refute at
  `javax.net.ssl.X509ExtendedTrustManager` § `checkServerTrusted(X509Certificate[], String, SSLEngine)`
  for the target JDK (verify-at-outline). If confirmed, the ADR must record that (b) is a strictly
  larger relaxation than the operator asked for.
  - verdict: corroborated | checked_at: cc10ce2a2c7840162bbc087242d42cbbc3cd14c3 | by: deployment-configurability/cleanup | rescoped: n/a | evidence: stable JDK-level fact (X509ExtendedTrustManager.checkServerTrusted semantics)
- Verify-first clause: settle the mechanism against the implementing source — the JDK's own client
  behaviour and the resolved `cui-http` `HttpHandler` — never against this spec's prose, a standards
  doc, or the ADR being written. A refutation re-scopes deliverables 2-4.
  - verdict: corroborated | checked_at: c6e6f52 | by: deployment-configurability/cleanup | rescoped: n/a | evidence: verify-first clause still HONOURED at c6e6f52: the mechanism was settled against the JDK 25.0.1 sources and the resolved cui-http 2.1.0 artifact cross-checked against the /Users/oliver/git/cui-http main checkout, and neither moved in cea163c..HEAD. The JDK 26 bound stands unchanged - CI runs it and it remains unverified locally.

## Expected Surface

- OBSERVED: `doc/adr/` — the mechanism-decision ADR (next free ordinal)
- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/auth/TokenValidatorProducer.java`
- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/auth/JwksTrustProfileResolver.java`
- HYPOTHESIS: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/config/model/TokenValidationConfig.java` — the new per-issuer key (verify-at-outline)
- HYPOTHESIS: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/config/model/IssuerConfig.java` — the field's home if it is per-issuer (verify-at-outline)
- HYPOTHESIS: the gateway.yaml JSON schema under `api-sheriff/src/main/resources/schema/` — the key must be schema-valid (verify-at-outline)
- HYPOTHESIS: `api-sheriff/src/test/java/de/cuioss/sheriff/gateway/auth/` — control pair + boot validation (verify-at-outline)
- OBSERVED: `doc/configuration.adoc` and `doc/security-threat-model.adoc`

## Dependencies and Sequencing

- Depends on: PLAN-03 — **hard, on its deliverable 2**, which is the sole naming authority for both
  outbound surfaces. This plan is implement-only against that vocabulary and must not start before it
  has landed. The dependency is deliberate: the operator asked whether these two plans should be
  merged, and they stay split only because their MECHANISMS differ (Vert.x `setVerifyHost` exists;
  cui-http has no such lever and may need an upstream release). Nothing about the CONTRACT differs,
  so the contract is decided once — see the epic decision of 2026-08-27.
- Overlaps with: PLAN-03 on the gateway.yaml config model, the schema, and both documentation files.
- Adjacent to: the `jwks.tls_profile` trust-anchor mechanism. Untouched — it governs which CAs are
  trusted, not whether the host name must match, and conflating them would widen trust silently.
- Adjacent to: the excluded extension beans (`quarkus.arc.exclude-types`). Untouched — the knob must
  not resurrect the extension's unqualified validator surface.

## Hand-Off Command

```text
/plan-marshall task="implement .plan/local/orchestrator/deployment-configurability/plans/PLAN-04-jwks-hostname-verification.md"
```

## Write-Boundary

Touches only repository source and tests. Creates and edits NO file under
`.plan/local/orchestrator/` other than its own `inbox/{sender}-{seq}` message; reports outcome
through its PR and that message. See `orchestration-model.md` § Ledger Write-Boundary.
