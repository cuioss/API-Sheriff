# PLAN-V02-56: The 0.2.0 Final Cleanup — the `/auth`-Fronted Keycloak Sample and the Open Watches

epic: api-sheriff-0-2-0
workstream: WS-01

> Staged plan spec — one shippable unit of work, ready for `/plan-marshall` hand-off.
> The orchestrator EMITS the command below; it never launches the plan inline.
> Staged 2026-11-05 on operator instruction ("create a final cleanup plan with V02-12's held D3 and other watches / open aspects / defects").
> Every claim below was checked against `origin/main` at `954d7d88` unless it is labelled HYPOTHESIS.

## Objective

This is the last plan of the 0.2.0 epic. It ships the one deliverable that no plan holds, `PLAN-V02-12`'s D3 (the `/auth`-fronted
Keycloak sample). It also closes the epic's open Watches that are code, test or document work and need no external trigger (a flake
recurring, a release, a consumer report). Each deliverable names the Watch it retires. A Watch whose claim no longer holds at outline is
dropped and reported, not acted on.

## Deliverables

1. **The `/auth`-fronted Keycloak sample (`PLAN-V02-12` D3).** Read D3 in `plans/PLAN-V02-12-idp-addressing-model.md` in full; it is the
   binding text, and this paragraph only summarises it.
   - **Shape:** the gateway fronts the IdP under `/auth/*` on its own origin, as an overlay or variant of `deployment/compose-sample`, not a
     second standalone sample.
   - **Keycloak setup:** `--hostname https://{gw}/auth`, `proxy-headers=xforwarded`, and `http-relative-path=/auth` on the internal port as
     well. The origin mapping keeps the path, so the gateway's own back-channel calls reach `https://keycloak:8443/auth/realms/{realm}/…`.
     Settle this first.
   - **The loop:** the gateway is both the fronting proxy and the OIDC client. The variant consumes the shipped back-channel origin
     (`oidc` back-channel key, #492), so the gateway's own token and JWKS calls stay on the internal hop. A design in which it is not needed
     is a finding to report.
   - **Allowlist, never a catch-all:**
     - Exposed: `/realms/`, `/resources/`, `/.well-known/`, `/lb-check`.
     - Not exposed: `/admin/`, `/realms/master/`, `/metrics`, `/health` and the management port.
   - **Prove the canonicalisation:** a relative or non-normalised spelling must not reach `/admin/`. Add a test for this; don't just assert it.
   - **CI login proof:** extend `scripts/check-bff-login.sh` and its CI lane to the variant. Do not build a second login rig.
   - **Shipped images:** no IdP goes into any shipped image.
   - **Ports:** a new compose port follows the `x-gateway-*` anchors and the `port …` row format of `start-integration-container.sh`, if the
     variant touches the integration stack at all.
   - **Docs:** user docs for the variant, in the same PR.
2. **Config errors name the file that was loaded.** `RuleSupport.endpointFile(EndpointConfig)` (`RuleSupport.java:94`) derives
   `endpoints/{id}.yaml` from the endpoint id. The loader accepts any `.yaml` name, so an endpoint `id: redirects` loaded from
   `endpoints/legacy.yaml` is reported against the wrong file. The helper is used at 46 sites (one of them an import).
   - Choose one fix: either carry the source path through `EndpointConfig` from `ConfigLoader`, or make the loader refuse a file whose
     name differs from its id. Pre-1.0, so a breaking refusal is allowed.
   - Add a test that loads a mismatched name and asserts the reported file, shown red before the fix. Retires the Watch "CONFIG ERRORS
     NAME `endpoints/{id}.yaml`".
3. **The two unasserted `BFF-21` controls.**
   - `Cache-Control: no-store` on a gateway-rendered page that sets a cookie.
   - No upstream `Set-Cookie` relayed on a WebSocket upgrade.

   Add a test for each that fails when the control is removed; fix the code if a test is red against the current tree. While there, record
   a verdict on `PLAN-V02-08`'s two conservative status choices: `502` on a refused widening re-drive push, and `400` with ApiSheriff-134
   for an unbound token on a widening callback. Keep each as is, or change it, with the reason in the threat model or the ADR that owns
   it. Also check whether the refresh-revocation and nonce-retry items folded into `PLAN-V02-01` D7 were handled, and report what you find.
   Retires the `PLAN-V02-01` D7 residue Watch.
4. **The uniform-404 floor beyond eight paths.** `EnumerationOracleIT` measures eight pre-trust paths. Four more paths can be driven by a
   caller before trust is established:
   - the declared-length fast-reject;
   - a route-scoped filter violation;
   - the cookie-forwarding same-origin refusal;
   - the `protocol: grpc` route.

   Bring each into the measurement. If a path cannot be measured, state why. Then update the scope sentence in ADR-0073 A1 and in `gw-16`
   (`doc/security-threat-model.adoc`). A path that answers differently is a security finding (see Security Findings). Retires the
   "UNIFORM-404 FLOOR MEASURED ON EIGHT PATHS ONLY" Watch.
5. **Bind the hand-maintained inventories.** `DocumentedHeaderSetsContractTest` binds the identity set and both protocol tiers. Three lists
   are still maintained by hand:
   - the route-field inventory (`doc/configuration.adoc` against `endpoint.schema.json` and the config model);
   - the never-forward header set;
   - the response-strip header set.

   Bind each to its constant or schema with a contract test that is shown red against a deliberately diverged copy. Retires "HAND-MAINTAINED
   INVENTORIES NOT BOUND BY A TEST".
6. **Pin both operand orders of the anchor-overlap check.** `ConfigValidator`'s anchor-overlap check is covered in each order only by
   map iteration order. Add a test that asserts both orders explicitly. Retires that part of the "TEST-CYCLE REWORK RESIDUE" Watch.
7. **The `PLAN-V02-23` (#515) advisories.** Read the full list in
   `.plan/local/archived-plans/2026-10-24-plan-v02-23-cookie-forwarding-policy-r2/` before acting. Commit messages are history and stay
   as they are. The named code and doc items:
   - the reader list in `http/package-info.java`;
   - `RouteTableBuilder` emitting `ApiSheriff-101` on terminal routes;
   - the `@return` wording of `GatewayCookies.setCookieName`;
   - the BFF-2 heading in `doc/quality-report/`.

   Also fix the four `PLAN-V02-53` (#565) code-review advisories, folded 2026-11-05 (see `landings/PLAN-V02-53.md`):
   - the `PathSlashes` class Javadoc and `http/package-info.java` overstate its reach, because `UpstreamAssetSource.upstreamUri` still
     trims one slash itself. Migrate that site to `PathSlashes`, or correct both docs;
   - the `LocationPathReview` Javadoc says every reader uses `pathPortion`. `LocationRewriter`, the absolute branch of `sameOrigin` and
     `PortalRules` do not;
   - `ReturnTargetScopes` keeps a second, unreachable copy of the encoded-separator and dot-segment checks behind `sameOrigin`. Remove it
     (pre-1.0), keeping a test that proves `sameOrigin` still refuses those inputs.

   #565 already rewrote the reader list in `http/package-info.java`, so re-check that V02-23 item before acting on it.

   Fix each one that still holds, and drop the rest with a one-line reason in the PR. Retires that Watch.
8. **The `PLAN-V02-49` advisories and the `DispatchStage` question.**
   - `ConfigFixtures.anchoredEndpointSeed`: its Javadoc says anchor where endpoint is meant.
   - `baseRoute` and `backendUpstream`: one consumer each; inline them or keep them, with the reason.
   - `TopologyResolverTest.stillThrows…AlongsideSkippedAdditionalAlias`: misnamed, because its alias resolves.
   - `PortalConfig.DEFAULT_CACHE_SECONDS`: public, with no reader outside its class.
   - `TerminalDispatch.retireFailedRelayConnection`: its marked outcome looks unreachable from the dispatch path. Prove it reachable with a
     test, or remove it.
   - `doc/quality-report/test-quality.adoc`: the format validator reports header and list warnings.
   - `DispatchStage`: the operator decision on its inbound-body simplification (#385) cannot be taken as recorded, because the finding
     text was not kept. Re-review the inbound-body handling after `PLAN-V02-53`'s changes there. Apply a simplification only if it is
     behaviour-preserving and the edge tests stay green, and report what you found either way.

   Retires the `PLAN-V02-49` residue (4) and the "OPERATOR DECISION OWED: … `DispatchStage`" Watch.
9. **Document and comment residue.**
   - `integration-tests/src/main/docker/sheriff-config-ws-admission/gateway.yaml` line 18 says the overlay differs from the primary in
     "exactly two" things. It also lacks `allow_get_with_content_length_body: true` and the TLS `min_version` / `cipher_suites` pins.
     List the real differences.
   - `doc/quality-report/build-and-peripherals.adoc:166` says "All seven gateway instances". Count the instances and correct the number.
   - `demo-client/scripts/start-dev-environment.sh` lines 244–260 use the literal `120` four times for the Keycloak wait. ADR-0031 requires
     one named constant.

   - `api-sheriff/src/test/java/de/cuioss/sheriff/gateway/config/validation/rule/TerminalActionRulesRedirectTest.java` is not in
     the pre-commit formatter's stable layout: the gate rewrites a blank import-separator line on every run. It arrived unformatted
     with #561 (the re-hosted fork commits), and `PLAN-V02-53` reverted the rewrite six times. Commit the formatter's version, the one
     file only. Any OTHER file the gate rewrites stays reverted, as before.

   Retires "LOW-BUDGET FIXTURE HEADER" and "`PLAN-V02-21` DOCUMENTATION LEFTOVERS".

**Split guard.** Size the PR at outline. If it exceeds 100 files or 150,000 diff characters (the CodeRabbit and Sourcery caps), ship D1
as its own PR first and D2–D9 as a second, serially. D1 is the largest and the most independent. If D1 has to be cut entirely, say so
in the landing and leave its Watch open.

## Not in This Plan (stay as Watches, with the reason)

- **Recurrence-triggered flakes:**
  - `Http2StreamAbuseIT`, both methods;
  - the HTTP/1.1 drain 502 in the framing corpus;
  - `Http2EarlyResponseResetIT`;
  - `GatewayEdgeEarlyResponseResetTest`;
  - the general-admission control.

  Each has a standing rule: on a recurrence, reproduce under `ActiveProcessorCount=1`, then stage a fix.
- **Integration Tests push run `41340502392`** ("Broken pipe" in `generate-mtls-certificates`). The operator ruled 2026-11-05 that it
  stays a Watch.
- **Release-triggered:** the first real release after #509, and the release-guard pin move.
- **Measurement-triggered:**
  - `PLAN-V02-55` verification (c);
  - no benchmark figure for #558;
  - the JaCoCo re-measure;
  - the coverage `<append>` build change.
- **Owned elsewhere or ruled:**
  - per-route upstream timeouts (kidicap `PLAN-29`);
  - ADR-0066's abortive-close triggers (ruled: report, no test);
  - GW-07 (operator-named residual);
  - the inherited tooling constraints (owned upstream).
- **Consumer- or need-triggered:**
  - the ECS schema check;
  - the APISIX `bearer` lane;
  - the declined #387 refactor;
  - the untested refusal branches in the guard readers (they fail loud);
  - the FAPI conformance-suite run;
  - `gw-02` with an HTTP/2 upstream, which is a hardening item for 0.3.0.
- **Open Defect 4** (test-corpus backlog, low) and **Open Defect 10** (actionable at the 1.0 cut) carry into `api-sheriff-0-3-0`.
- **Open Defect 8** needs an operator live-gate check, not plan work.

## Standing Constraints

- **Pre-1.0 rules:** no deprecation and no compatibility shims.
- **Commit gate:** `verify -Ppre-commit` and `verify` before every build-touching commit. Then run `git status --porcelain` and revert
  rewrites of files the branch did not author.
- **Docs:** three-layer docs in the same PR.
- **Sonar:** zero new findings.
- **Tests that matter:** every new guard is shown failing against a deliberate cut before it is trusted.
- **`docker-compose*.yml` and `Dockerfile*`** are gate-requiring paths, so D1 runs the full process including `-Pintegration-tests`
  where it touches the integration stack.

## Expected Surface

- HYPOTHESIS: `deployment/compose-sample/**` — a variant overlay, for example a third compose file beside `docker-compose.plain-http.yml`,
  plus `sheriff-config` endpoint files for `/auth`, `docker/keycloak/sample-realm.json` and `scripts/*.sh` (D1, verify-at-outline)
- HYPOTHESIS: `.github/workflows/*` — the CI lane that runs `check-bff-login.sh`, extended to the variant (D1)
- HYPOTHESIS: `doc/user/**` — the variant's operator documentation (D1)
- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/config/validation/rule/RuleSupport.java`,
  `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/config/model/EndpointConfig.java`,
  `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/config/load/ConfigLoader.java` and its test (D2). The 45 call sites change only
  if the helper's signature changes.
- HYPOTHESIS: BFF page/cookie and WebSocket relay tests under `api-sheriff/src/test/java/de/cuioss/sheriff/gateway/` (D3), and
  `doc/security-threat-model.adoc`
- OBSERVED: `integration-tests/src/test/java/de/cuioss/sheriff/gateway/integration/EnumerationOracleIT.java`,
  `doc/adr/0073-Untrusted_callers_get_the_unrouted_404_for_every_rejection_raised_before_the_trust_boundary.adoc`,
  `doc/security-threat-model.adoc` (D4); HYPOTHESIS: an IT fixture route for the grpc and filter-violation paths
- OBSERVED: `api-sheriff/src/test/java/de/cuioss/sheriff/gateway/forward/DocumentedHeaderSetsContractTest.java` or a sibling,
  `doc/configuration.adoc`, `api-sheriff/src/main/resources/schema/endpoint.schema.json` (D5)
- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/config/validation/ConfigValidator.java` and its test (D6)
- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/http/package-info.java`,
  `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/http/GatewayCookies.java`,
  `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/config/RouteTableBuilder.java`, `doc/quality-report/*.adoc` (D7)
- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/http/PathSlashes.java`,
  `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/http/LocationPathReview.java`,
  `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/asset/UpstreamAssetSource.java`,
  `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/bff/login/ReturnTargetScopes.java` and its test (D7, folded from #565)
- OBSERVED: `api-sheriff/src/test/java/de/cuioss/sheriff/gateway/testsupport/ConfigFixtures.java`,
  `api-sheriff/src/test/java/de/cuioss/sheriff/gateway/config/topology/TopologyResolverTest.java`,
  `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/config/model/PortalConfig.java`,
  `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/edge/terminal/TerminalDispatch.java`,
  `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/edge/upstream/DispatchStage.java`, `doc/quality-report/test-quality.adoc` (D8)
- OBSERVED: `integration-tests/src/main/docker/sheriff-config-ws-admission/gateway.yaml`,
  `doc/quality-report/build-and-peripherals.adoc`, `demo-client/scripts/start-dev-environment.sh`,
  `api-sheriff/src/test/java/de/cuioss/sheriff/gateway/config/validation/rule/TerminalActionRulesRedirectTest.java` (D9)

## Dependencies and Sequencing

- **Depends on `PLAN-V02-53`: merged as #565 (`7a5d52ad`) on 2026-11-05, so this dependency is met.** That plan edits these files:
  - `ConfigValidator`, `RouteTableBuilder`, `DispatchStage`, `ForwardingRules` (a `RuleSupport.endpointFile` caller),
    `doc/configuration.adoc`, `doc/security-threat-model.adoc`, `endpoint.schema.json`;
  - the `integration-tests/` fixtures and ITs.

  This plan edits nine of them, so it starts from `main` at or after `7a5d52ad` and re-reads them.
- D1 depends on `PLAN-V02-12` D1 (back-channel origin, #492), `PLAN-V02-23` (cookie policy, #515) and `PLAN-V02-15` (BFF sample and
  login proof, #516). All three are merged.
- Next free ADR ordinal at staging: `0080`. Derive it at write time and again against `origin/main` before the push, if any ADR is written.

## Hand-Off Command

```text
/plan-marshall task="implement .plan/orchestrator/api-sheriff-0-2-0/plans/PLAN-V02-56-final-cleanup.md" plan_id=plan-v02-56-final-cleanup
```

Emit only after `PLAN-V02-53` has merged and its landing is analyzed.

## Security Findings

Fix a security finding inside this plan whenever possible; that is the preferred outcome.

A finding that is not fixed right away (out of scope, unconfirmed or deferred) stays out of git. It is never written into any of these:
- a tracked file, a code comment or the docs;
- a commit message, or the PR title, body or review replies;
- this plan's inbox and landing messages.

Record it instead in `.plan/local/security_notes.adoc`. That file is git-ignored; create it if it is missing, and append to it, never
rewrite it. Each entry carries:
- the date and this plan's id;
- the location (file and line, or component);
- what was observed;
- why it was not fixed.

Tracked text may say only that a security note was recorded locally, never what it says.

D1 (an IdP behind the gateway, the `/admin/` exposure) and D4 (a pre-trust path that answers differently) are the deliverables most
likely to turn one up.

## Write-Boundary

The plan implementing this spec touches only its own repository source, tests, docs and CI workflow.

It creates and edits NO file under `.plan/orchestrator/` other than its own `inbox/{sender}-{seq}` message. It reports its outcome through
its PR and that message. Striking the Watches it retires is the orchestrator's job at the landing.
