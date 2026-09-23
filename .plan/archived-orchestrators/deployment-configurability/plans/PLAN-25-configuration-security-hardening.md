# PLAN-25: Configuration security hardening — pin the BFF client leg, guard the egress inventory, validate the session cookie name, settle trusted-proxy breadth

epic: deployment-configurability
workstream: WS-08

> Staged plan spec — the emitted command is a one-line pointer, so this spec is the whole brief.
> ⛔ **Staged 2026-09-15 by the orchestrator's corpus revisit at `origin/main` `a2969b9`, MERGING
> `PLAN-20-egress-leg-pinning-and-sweep.md` (deliverables 1-4), `PLAN-21-session-cookie-config-validation.md`
> (deliverables 5-6) and `PLAN-22-trusted-proxy-breadth-threshold.md` (deliverables 7-8).** All three
> are retained as superseded audit records. Operator decision: up to 12 deliverables per plan are
> authorized; the three specs collided on `ConfigValidator`, `ConfigLogMessages`, `BffRuntimeProducer`,
> `gateway.schema.json`, `doc/LogMessages.adoc`, `doc/configuration.adoc` and ADR ordinal `0044`, so
> they could only ever have run one after another — three PR cycles for one sequential stream.

## Objective

Three configuration surfaces let an operator — or an unreviewed environment variable — **silently give
up a security guarantee the product states elsewhere**, and one outbound connection relies on a library
default where every sibling pins its posture explicitly:

- the BFF client leg (discovery, code exchange carrying the `client_secret`, refresh) is built with
  neither `verifyHostname` nor `sslContext`, while the JWKS leg deliberately passes its posture every
  time so that "an upstream default change cannot silently move this gateway's posture";
- `session.cookie_name` is unvalidated, so dropping the `__Host-` prefix silently removes the
  no-`Domain` guarantee;
- the `trusted_proxies` breadth check is a warning whose threshold reasoning was never recorded, and
  PLAN-06 moved the value into an environment variable no code review sees.

Add the mechanism in each case, prove it acts, and record the decisions. ⛔ **This plan adds mechanisms;
it does not restate rules.** Standing project rule: *never silent downgrade*.

## Deliverables

### Outbound TLS posture (from PLAN-20)

1. **Pin the BFF client leg.** `BffRuntimeProducer.java:229` builds `ClientConfiguration` with neither
   `verifyHostname` nor `sslContext`; that configuration is dialled by `DiscoveryResolver`,
   `TokenEndpointClient` and the refresh flow. Bind it explicitly to the `egress_tls` vocabulary
   PLAN-03/PLAN-04 landed — ⛔ **no third vocabulary**: decide whether it joins
   `egress_tls.jwks_verify_hostname` / `upstream_tls_profile` semantics or earns its own named knob,
   and record why. ⚠ Any profile binding **replaces** the JVM trust store (`ApiSheriff-119`); state the
   consequence wherever documented. ⛔ **Security stop**: if the resolved token-sheriff/cui-http version
   does NOT default hostname verification to `true`, this is a live vulnerability — stop and escalate
   before continuing through the list.
2. **Re-derive the egress inventory mechanically, and ship a guard.** Enumerate every outbound dial from
   source, not from documentation — the inventory has been wrong twice in a row (PLAN-03 found a fourth
   leg, PLAN-04 a fifth). Leads at HEAD: `GatewayEdgeRoute.java:1362`/`:1372` (Vert.x upstream, bound via
   `egressTlsBound`), `TokenValidatorProducer` (JWKS), `BffRuntimeProducer.java:229` (BFF client),
   `UpstreamAssetSource.java:255` (`HttpClient.newBuilder()`). ⛔ The output is a **guard** — an ArchUnit
   rule or contract test that fails when an outbound client is constructed without an explicit TLS
   posture — not a list in a document. If no guard is feasible, record why.
3. **Settle the JWKS knob's deployment-activation gap.** `egress_tls.jwks_verify_hostname` is proven at
   unit level only (`TokenValidatorProducerTest.JwksVerifyHostname`). Either add the deployment-level
   proof following the `sheriff-config-*` instance pattern, or record why unit coverage suffices under
   the epic's rule *a key that parses is not a key that acts*. ⚠ A new instance must be added to
   `doc/development/declared-limit-assertion-coverage.adoc`'s inventory block —
   `DescriptorInventoryWiringTest` derives its expectation from that note and fails otherwise.
4. **Guard the `doc/configuration.adoc` array-key inventory (issue #269) with deliverable 2's mechanism.**
   `doc/configuration.adoc:2684` says the enumeration is *"checked against those files"*; nothing checks
   it. Reuse the same guard shape; if deliverable 2 concludes no guard is feasible, **delete the claim of
   checking** rather than leave it standing.

### Session cookie configuration (from PLAN-21)

5. **Validate `session.cookie_name`.** `ConfigValidator.resolvedCookieName()` (`:1327`) folds a blank
   name onto the default and checks nothing else; its own javadoc concedes the schema declares
   `cookie_name` an unrestricted string. Decide and record the posture — ⛔ **prefer refusal at boot**
   over a warning — and keep it consistent with the product's three hardcoded `__Host-` names
   (`SessionCookieCodec.DEFAULT_COOKIE_NAME`, `RpInitiatedLogout.LOGOUT_STATE_COOKIE_NAME`,
   `BindingCookieCodec.COOKIE_NAME`).
6. **Close out `oidc.session.max_cookie_size` — verify, do not re-ship.** PLAN-17 (#288) already bounds
   the declared value in `ConfigValidator` and warns on the effective budget
   (`COOKIE_BUDGET_EXCEEDS_BROWSER_GUARANTEE`, `ApiSheriff-124`). The schema deliberately declares no
   `minimum`/`maximum` because the validator is the sole enforcing authority. Confirm nothing is left;
   ship only a genuine residual, and record "nothing owed" if that is the finding.

### Trusted-proxy breadth (from PLAN-22)

7. **Settle warn versus reject for the environment-variable path, and state the threshold's reasoning.**
   ⛔ **PLAN-22's premise is partly stale**: #267 (PLAN-15, `558a38b`) already raised the thresholds to
   `BROAD_PREFIX_IPV4 = 16` / `BROAD_PREFIX_IPV6 = 48`, so `172.16.0.0/12` now warns. What is still owed:
   whether a range from an **unreviewed environment variable** should be refused above a second, wider
   threshold, or why a warning suffices; the declared un-warned residual (`/16`, `/48`) restated for
   whatever lands; and removal of the redundant `ConfigValidatorTest` trusted-proxy method(s) around
   `shouldAcceptTightlyScopedCidrs` (`:711`) now covered by the parameterised
   `shouldApplyBroadPrefixThreshold` (`:746`), carried unowned since PLAN-06.
8. **Write the ADR PLAN-15 correctly declined to write.** Start from the draft preserved in
   `.plan/local/archived-plans/2026-09-05-trusted-proxy-breadth-and-probe-doc/logs/decision.log`
   (adr-propose, `2026-09-04T23:43`). Next free ordinal at HEAD is `0044` — re-check at outline; if
   deliverable 1 or 2 also owes an ADR, allocate both ordinals inside this plan.

### Across all three

9. **Prove every new validation and guard by reversion, not by green.** A non-prefixed cookie name must
   go red; an outbound client without an explicit posture must go red; a pinned BFF leg with the pin
   removed must go red; a refused proxy range must go red with the refusal reverted.
10. **Reconcile the documentation to what landed.** The egress inventory wherever it is enumerated
    (`doc/configuration.adoc`, `doc/security-threat-model.adoc`, `doc/LogMessages.adoc`), the cookie
    contract (`doc/user/bff-cookie.adoc` — extend, do not contradict its budget guidance), and the
    trusted-proxy threshold. ⚠ Mark which enumeration sites were re-derived so the next reader can tell.
11. **Close issues #256 and #269 against the shipped behaviour** — each closing comment states what now
    happens and names the observable (boot record, refusal or test) that proves it.

## Claim Labels

- OBSERVED: `BffRuntimeProducer.java:229` builds `ClientConfiguration` with issuer, client id, secret,
  auth method, scopes and redirect URI only — no `verifyHostname`, no `sslContext`.
  - verdict: contradicted | checked_at: cc10ce2a2c7840162bbc087242d42cbbc3cd14c3 | by: deployment-configurability/cleanup | rescoped: no | evidence: BffRuntimeProducer.java now binds verifyHostname/sslContext via egressTls.oidcVerifyHostname - this plan's own deliverable 1 landed
- OBSERVED: `TokenValidatorProducer.java` lives under `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/auth/`
  (PLAN-20 declared a non-existent `quarkus/` path) and calls `.verifyHostname(jwksVerifyHostname)`
  unconditionally at `:266`, binding `sslContext` only when a trust profile is declared.
  - verdict: corroborated | checked_at: cc10ce2a2c7840162bbc087242d42cbbc3cd14c3 | by: deployment-configurability/cleanup | rescoped: n/a | evidence: TokenValidatorProducer.java still lives under auth/ and calls .verifyHostname(jwksVerifyHostname) unconditionally
- HYPOTHESIS: the BFF leg verifies hostnames today only through the library's builder default —
  confirm/refute at the resolved token-sheriff `ClientConfiguration` builder § its `verifyHostname`
  default in the version `pom.xml` `version.token-sheriff` resolves (verify-at-outline).
  - verdict: corroborated | checked_at: cc10ce2a2c7840162bbc087242d42cbbc3cd14c3 | by: deployment-configurability/cleanup | rescoped: n/a | evidence: historical fact about the pre-fix reliance on a library default
- HYPOTHESIS: the egress inventory has a sixth leg — `UpstreamAssetSource.java:255` `HttpClient.newBuilder()`
  is the first lead to classify; confirm/refute at `UpstreamAssetSource` § the method containing `:255`
  (verify-at-outline).
  - verdict: corroborated | checked_at: cc10ce2a2c7840162bbc087242d42cbbc3cd14c3 | by: deployment-configurability/cleanup | rescoped: n/a | evidence: UpstreamAssetSource.java now explicitly documents and binds sslContext(defaultSslContext()), covered by EgressTlsPostureArchTest
- OBSERVED: `ConfigValidator.resolvedCookieName()` at `:1327` only replaces a null/blank name with
  `SessionCookieCodec.DEFAULT_COOKIE_NAME`; no `__Host-` check exists in `api-sheriff/src/main/java`.
  - verdict: contradicted | checked_at: cc10ce2a2c7840162bbc087242d42cbbc3cd14c3 | by: deployment-configurability/cleanup | rescoped: no | evidence: ConfigValidator now implements the __Host- prefix check, this plan's own deliverable 5
- HYPOTHESIS: refusing a non-`__Host-` cookie name breaks no shipped configuration — confirm/refute
  across `deployment/compose-sample/`, every `integration-tests/src/main/docker/sheriff-config*/gateway.yaml`
  and the test corpus § `cookie_name` (verify-at-outline).
  - verdict: corroborated | checked_at: cc10ce2a2c7840162bbc087242d42cbbc3cd14c3 | by: deployment-configurability/cleanup | rescoped: n/a | evidence: the refusal shipped without breaking any test corpus or shipped example
- OBSERVED: `gateway.schema.json`'s `max_cookie_size` description states the 40..8192 range in prose and
  declares no `minimum`/`maximum` keyword because the validator is the sole enforcing authority.
  - verdict: corroborated | checked_at: cc10ce2a2c7840162bbc087242d42cbbc3cd14c3 | by: deployment-configurability/cleanup | rescoped: n/a | evidence: validator range remains 40..8192 with no schema minimum/maximum keyword
- HYPOTHESIS: the ID token riding every request for `id_token_hint` (`SealedSessionPayload.java:35`,
  `:59`) was decided in PLAN-17 — confirm/refute at `doc/development/bff-cookie.adoc` and ADR-0043
  § `id_token_hint` before re-investigating (verify-at-outline).
  - verdict: corroborated | checked_at: cc10ce2a2c7840162bbc087242d42cbbc3cd14c3 | by: deployment-configurability/cleanup | rescoped: n/a | evidence: doc/development/bff-cookie.adoc and ADR-0043 both exist and address the id_token_hint question
- OBSERVED: `ConfigValidator.java:131-132` sets `BROAD_PREFIX_IPV4 = 16` and `BROAD_PREFIX_IPV6 = 48`,
  raised by `558a38b` (#267, 2026-09-04).
  - verdict: corroborated | checked_at: cc10ce2a2c7840162bbc087242d42cbbc3cd14c3 | by: deployment-configurability/cleanup | rescoped: n/a | evidence: ConfigValidator.java still sets BROAD_PREFIX_IPV4=16/IPV6=48 exactly as raised
- OBSERVED: issues #256 and #269 are open, read through the CI abstraction on 2026-09-15.
  - verdict: contradicted | checked_at: cc10ce2a2c7840162bbc087242d42cbbc3cd14c3 | by: deployment-configurability/cleanup | rescoped: no | evidence: gh issue view confirms both #256 and #269 are now closed, not open
- OBSERVED: `doc/adr/` runs to `0043` at HEAD and contains no ADR on `trusted_proxies` breadth.
  - verdict: contradicted | checked_at: cc10ce2a2c7840162bbc087242d42cbbc3cd14c3 | by: deployment-configurability/cleanup | rescoped: no | evidence: doc/adr/0044 now exists (this plan's own deliverable 8), doc/adr/ runs to 0047
- OBSERVED: `doc/configuration.adoc:2684` claims the array-key enumerations *"are checked against those
  files"*.
  - verdict: contradicted | checked_at: cc10ce2a2c7840162bbc087242d42cbbc3cd14c3 | by: deployment-configurability/cleanup | rescoped: no | evidence: doc/configuration.adoc's array-key enumeration claim is now backed by EgressTlsPostureArchTest, deliverable 4 landed the guard
- Verify-first clause: every citation is `a2969b9`-relative — re-read each at HEAD before editing.
  Deliverable 1's security stop precedes every other deliverable.

## Expected Surface

- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/quarkus/BffRuntimeProducer.java`
- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/auth/TokenValidatorProducer.java`
- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/config/validation/ConfigValidator.java`
- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/config/ConfigLogMessages.java`
- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/config/model/EgressTlsConfig.java`
- OBSERVED: `api-sheriff/src/main/resources/schema/gateway.schema.json`
- HYPOTHESIS: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/asset/UpstreamAssetSource.java` — only if deliverable 2 classifies it as an unpinned leg (verify-at-outline)
- OBSERVED: `api-sheriff/src/test/java/de/cuioss/sheriff/gateway/quarkus/BffRuntimeProducerTest.java`
- OBSERVED: `api-sheriff/src/test/java/de/cuioss/sheriff/gateway/auth/TokenValidatorProducerTest.java`
- OBSERVED: `api-sheriff/src/test/java/de/cuioss/sheriff/gateway/config/validation/ConfigValidatorTest.java`
- OBSERVED: `api-sheriff/src/test/java/de/cuioss/sheriff/gateway/arch/EgressTlsPostureArchTest.java` — deliverable 2's guard (new file; name confirmed at outline)
- HYPOTHESIS: `integration-tests/docker-compose.yml` — only if deliverable 3 adds an instance (verify-at-outline)
- HYPOTHESIS: `integration-tests/src/main/docker/sheriff-config-jwks-relaxed/gateway.yaml` — same (new file; verify-at-outline)
- HYPOTHESIS: `doc/development/declared-limit-assertion-coverage.adoc` — same (verify-at-outline)
- HYPOTHESIS: `doc/development/integration-test-topology.adoc` — same (verify-at-outline)
- OBSERVED: `doc/configuration.adoc`
- OBSERVED: `doc/LogMessages.adoc`
- OBSERVED: `doc/security-threat-model.adoc`
- OBSERVED: `doc/user/bff-cookie.adoc`
- OBSERVED: `doc/adr/0044-trusted-proxies-breadth-threshold.adoc` — new file; ordinal confirmed at outline

⛔ **Named files only.** ⚠ If deliverable 3 adds an integration instance, the IT class it needs is a new
file under `integration-tests/src/test/java/de/cuioss/sheriff/gateway/integration/` — PLAN-18 edits ten
*named* files there and none of them may be touched here; declare the new file before creating it.

## Dependencies and Sequencing

- ✅ **No hard dependency.** PLAN-17 (the cookie codec) and PLAN-15 (the threshold raise) have shipped.
- ✅ **Surface-disjoint from PLAN-24 and PLAN-18 as declared** — the three form the parallel round.
- ⛔ **PLAN-23 waits for this plan** — it declares `api-sheriff/src/test/` wholesale, which contains three
  test files declared here.
- ⚠ **Soft coupling with PLAN-24**: a new BFF-leg knob belongs in PLAN-24's TLS scenario guide. Do not
  edit `doc/user/tls-scenarios.adoc` here; the orchestrator folds it at landing.
- ⛔ **Security-bearing.** Every relaxation or refusal owes a threat-model statement and a boot record.

## Hand-Off Command

```text
/plan-marshall task="implement .plan/local/orchestrator/deployment-configurability/plans/PLAN-25-configuration-security-hardening.md"
```

## Write-Boundary

Touches only repository source and tests. Creates and edits NO file under
`.plan/local/orchestrator/` other than its own `inbox/{sender}-{seq}` message; reports outcome
through its PR and that message. See `orchestration-model.md` § Ledger Write-Boundary.
