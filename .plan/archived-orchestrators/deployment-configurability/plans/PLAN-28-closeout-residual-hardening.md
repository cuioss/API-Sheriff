# PLAN-28: Closeout Residual Hardening

epic: deployment-configurability
workstream: WS-09

> Staged plan spec — one shippable unit of work, ready for `/plan-marshall` hand-off.
> Bundles every Open Defect this epic recorded that (a) is still genuinely open at HEAD `3e3addc`,
> and (b) was never absorbed into a shipped plan. Each claim below was re-verified fresh against
> `3e3addc` on 2026-09-22 — not transcribed from the epic's older, dated Open Defects prose — and
> two candidates from that prose (a TLS key-material diagram, a stale `release.yml` comment) were
> DROPPED after re-verification found them already shipped.

## Objective

Close out the deployment-configurability epic's residual Open Defects: adopt cui-http's `paranoid()`
security preset as an operator-selectable option per the standing operator ruling, extend the
config-loader's environment-variable coercion to cover map-valued schema keys (unblocking
`tls.passthrough_sni`), make IdP-initiated back-channel logout provable in the integration realm,
consolidate root-path (`/`) normalisation onto one canonical helper per language boundary, and clear
three small pieces of doc/process residue (a stale version citation, a too-tight shared test-teardown
ceiling, and an undocumented project-config decision) so the epic can close with nothing silently
dropped.

## Deliverables

1. Add a `PARANOID` value to `SecurityProfile` (`api-sheriff/src/main/java/de/cuioss/sheriff/gateway/config/model/SecurityProfile.java`),
   mapped in `preset()` to `cui-http`'s `SecurityConfiguration.paranoid()`, alongside the existing
   `STRICT`/`LENIENT`/`MINIMAL` arms. `STRICT` stays `DEFAULT_PROFILE` — this is an additional option,
   not a default change. Cover with a unit test parallel to the existing `STRICT`/`LENIENT` cases, and
   document the new value in `doc/configuration.adoc` and its `LogMessages.adoc`/schema description
   wherever `security_defaults.profile` is enumerated.
2. Extend `ConfigLoader`'s environment-variable coercion (`api-sheriff/src/main/java/de/cuioss/sheriff/gateway/config/ConfigLoader.java`,
   `coerce()` at `:550` and its `ARRAY_TYPE` sibling `coerceList()` at `:565`) with an `object`-typed
   case, so a map-valued schema key can be supplied from a single environment variable the way
   `coerceList()` already does for array-valued keys. Prove it against `tls.passthrough_sni`
   (`api-sheriff/src/main/resources/schema/gateway.schema.json:184-188`, currently `type: object`,
   `additionalProperties: {type: string}`) with a `ConfigLoaderTest` case supplying an SNI-hostname →
   topology-alias map through one variable, and update `doc/configuration.adoc`'s coercion-rules table.
3. Fix server-mode back-channel logout unreachability in the integration realm
   (`integration-tests/src/main/docker/keycloak/integration-realm.json`): register
   `backchannel.logout.url` on the refresh-client/BFF client definitions and add the built-in `basic`
   client scope to `defaultClientScopes` so issued access tokens carry `sid`. Add an integration test
   (alongside the existing `integration-tests/src/test/java/de/cuioss/sheriff/gateway/integration/BffLogoutIT.java`)
   proving an IdP-initiated back-channel logout terminates the gateway-held session.
4. Consolidate root-path (`/`) normalisation onto one canonical helper per language boundary. Current
   independent call sites: `integration-tests/src/test/java/de/cuioss/sheriff/gateway/integration/BaseIntegrationTest.java`
   (Java), `integration-tests/src/test/java/de/cuioss/sheriff/gateway/integration/ManagementRootPathLabelIT.java`
   (Java), and the shell-script `rstrip`-equivalent trims in `integration-tests/scripts/start-integration-container.sh`,
   `demo-client/scripts/start-dev-environment.sh`, and `deployment/compose-sample/scripts/start-sample.sh`.
   Route the Java sites through one shared helper and the shell sites through one shared function/idiom;
   add a `/`-input test case per language boundary rather than only the already-covered non-root cases.
5. Fix the stale token-sheriff version citation at `doc/technical_aspects.adoc:307` (currently reads
   `0.9.2`) — this gateway's shipped behavior (ADR-0041 JWKS `verifyHostname`, ADR-0044/0045 egress
   pinning) depends on `token-sheriff-client` `0.9.5`+; update the citation to match the version this
   epic actually shipped against.
6. `WebSocketRelayStageTest`'s idle-release await (`api-sheriff/src/test/java/de/cuioss/sheriff/gateway/tls/WebSocketRelayStageTest.java:470`,
   `Awaits.until(..., Awaits.TEARDOWN_CEILING_SECONDS)`) shares the same 5-second ceiling
   (`Awaits.TEARDOWN_CEILING_SECONDS = 5` at `api-sheriff/src/test/java/de/cuioss/sheriff/gateway/testsupport/Awaits.java:136`)
   that two independent runs (this repo's merge-queue ejection, and a cross-repo sighting at
   5023ms/5000ms) overshot. Either give this call site a wider, purpose-specific ceiling or raise
   `TEARDOWN_CEILING_SECONDS` with a recorded rationale — do not silently retry past it.
7. Record an explicit rationale for `.plan/marshal.json:108`'s `re_review_on_loopback: false` — state
   in a comment or the adjacent doc why CodeRabbit's silent re-review and PR-Agent's no-auto-retrigger
   on loop-back are an accepted trade-off, or flip the setting if not. This is the one non-code,
   non-Java deliverable in this plan; it is `.plan/**` config, so it is documentation/config-only for
   the Pre-Commit Process gate.

## Claim Labels

- OBSERVED: `SecurityProfile` (`api-sheriff/src/main/java/de/cuioss/sheriff/gateway/config/model/SecurityProfile.java:58-149`)
  declares exactly `STRICT`, `LENIENT`, `MINIMAL` and no `PARANOID` arm; `preset()` (`:143-149`) has no
  case for it — read at HEAD `3e3addc`.
- OBSERVED: `ConfigLoader.coerce()` (`api-sheriff/src/main/java/de/cuioss/sheriff/gateway/config/ConfigLoader.java:550-564`)
  switches only on `boolean`, `integer`/`number`, and `ARRAY_TYPE`; no `object` case exists —
  read at HEAD `3e3addc`. `gateway.schema.json:184-188` declares `tls.passthrough_sni` as
  `type: object` with `additionalProperties: {type: string}`, so no environment variable can supply it
  today.
- OBSERVED: `integration-realm.json` (`integration-tests/src/main/docker/keycloak/integration-realm.json:55-77`)
  declares `defaultClientScopes` without `basic` and carries no `backchannel.logout.url` key anywhere in
  the file — read at HEAD `3e3addc` (only `backchannel.logout.revoke.offline.tokens` is set, a
  different, narrower attribute).
- OBSERVED: root-path normalisation is independently implemented at
  `integration-tests/src/test/java/de/cuioss/sheriff/gateway/integration/BaseIntegrationTest.java`,
  `integration-tests/src/test/java/de/cuioss/sheriff/gateway/integration/ManagementRootPathLabelIT.java`,
  and the three `rstrip("/")`-equivalent shell trims in `start-integration-container.sh`,
  `start-dev-environment.sh`, and `start-sample.sh` — read at HEAD `3e3addc`, matching the count first
  recorded in the epic's Open Defects (2026-09-11) and not consolidated since.
- OBSERVED: `doc/technical_aspects.adoc:307` reads `0.9.2` — read at HEAD `3e3addc`.
- OBSERVED: `Awaits.TEARDOWN_CEILING_SECONDS = 5` (`api-sheriff/src/test/java/de/cuioss/sheriff/gateway/testsupport/Awaits.java:136`)
  and `WebSocketRelayStageTest.java:470` awaits on it directly — read at HEAD `3e3addc`.
- OBSERVED: `.plan/marshal.json:108` sets `"re_review_on_loopback": false` with no adjacent comment —
  read at HEAD `3e3addc`.
- OBSERVED (absence, verified — not carried over from stale Open Defects prose): a TLS/key-material
  diagram already exists and is embedded — `doc/user/tls-scenarios.adoc:66` references
  `doc/resources/diagrams/tls-key-material.svg`, shipped by PLAN-24 (`fb65222`, PR #305). The epic's
  older Open Defects entry calling for this diagram is stale and is deliberately NOT a deliverable
  here.
- OBSERVED (absence, verified): `pom.xml`'s `UpgradeToJava21`/`UpgradeToJava25` recipe-exclusion
  comment block (`:360-431`) is current and accurate at HEAD `3e3addc` — the older Open Defects entry
  describing a stale comment at `pom.xml:201` no longer matches the file and is NOT a deliverable here.
- OBSERVED (absence, verified): `.github/workflows/release.yml` no longer contains a "Both signed
  digests" comment — the older Open Defects entry is stale and is NOT a deliverable here.

## Expected Surface

- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/config/model/SecurityProfile.java`
- OBSERVED: `api-sheriff/src/test/java/de/cuioss/sheriff/gateway/config/model/SecurityProfileTest.java`
- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/config/ConfigLoader.java`
- OBSERVED: `api-sheriff/src/test/java/de/cuioss/sheriff/gateway/config/ConfigLoaderTest.java`
- OBSERVED: `api-sheriff/src/main/resources/schema/gateway.schema.json`
- OBSERVED: `integration-tests/src/main/docker/keycloak/integration-realm.json`
- OBSERVED: `integration-tests/src/test/java/de/cuioss/sheriff/gateway/integration/BffLogoutIT.java`
- OBSERVED: `integration-tests/src/test/java/de/cuioss/sheriff/gateway/integration/BaseIntegrationTest.java`
- OBSERVED: `integration-tests/src/test/java/de/cuioss/sheriff/gateway/integration/ManagementRootPathLabelIT.java`
- OBSERVED: `integration-tests/scripts/start-integration-container.sh`
- OBSERVED: `demo-client/scripts/start-dev-environment.sh`
- OBSERVED: `deployment/compose-sample/scripts/start-sample.sh`
- OBSERVED: `doc/technical_aspects.adoc`
- OBSERVED: `doc/configuration.adoc`
- OBSERVED: `doc/LogMessages.adoc`
- OBSERVED: `api-sheriff/src/test/java/de/cuioss/sheriff/gateway/tls/WebSocketRelayStageTest.java`
- OBSERVED: `api-sheriff/src/test/java/de/cuioss/sheriff/gateway/testsupport/Awaits.java`
- OBSERVED: `.plan/marshal.json`

## Dependencies and Sequencing

- Depends on: none — every other plan in this epic is terminal (`shipped`/`landed`/`superseded`).
- Overlaps with: none live. WS-08's PLAN-25 (shipped, `7ba9734`) touched `ConfigLoader.java` and
  `gateway.schema.json` for unrelated keys; both are terminal, so no collision applies.
- Adjacent to: WS-08's outbound-TLS/cookie/trusted-proxy mechanism family — this plan does not touch
  `egress_tls.*`, cookie codec/size, or `trusted_proxies`.

## Hand-Off Command

```text
/plan-marshall task="implement .plan/orchestrator/deployment-configurability/plans/PLAN-28-closeout-residual-hardening.md"
```

## Write-Boundary

The plan implementing this spec touches only its own repository source and tests. It creates
and edits NO file under `.plan/orchestrator/` other than its own
`inbox/{sender}-{seq}` message — the orchestrator owns every other ledger write — and reports
its outcome through its PR and its inbox message. The inbox exception's qualifiers and the
sole sanctioned write mechanism are stated in
`persona-plan-orchestrator/standards/orchestration-model.md` § Ledger Write-Boundary.
