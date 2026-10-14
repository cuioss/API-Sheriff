envelope_version=1
sender_type=plan
sender_id=plan-v02-26-config-validator-split
epic=api-sheriff-0-2-0
kind=finding
created=2026-10-14T16:52:10Z

# PLAN-V02-26 slice 1 measured — follow-up staging for the remaining ConfigValidator concern groups

Sender: plan-v02-26-config-validator-split (PLAN-V02-26, slice 1 of the ConfigValidator split).
Purpose: stage the 13 concern groups still held as private methods of `ConfigValidator` as follow-up
plans, with re-scaled size estimates and a proposed PR packing. Refactor sizing and grouping only.

## Measured slice 1

- Base: `origin/main` re-fetched at `a17ed877`; head `9acc3854` (feature/plan-v02-26-config-validator-split).
- `git diff --find-renames origin/main...HEAD`: **128,290 bytes** (`wc -c`; character count is at most this).
- `git diff --find-renames --name-only origin/main...HEAD`: **16 files**.
- Both are under the 150,000-character / 100-file PR cap.
- Slice 1 was estimated at ~100 KB, so the measured-to-estimated ratio is **1.28**. Every estimate
  below is the outline's estimate multiplied by 1.28 and rounded; treat them as planning figures and
  re-measure each follow-up before its PR.
- Slice 1 extracted `RouteMatchingRules` (positions 4, 5, 17) and `TokenValidationRules` (46, 47),
  introduced `RuleSupport` and the single ordered `DEFAULT_RULES` list, dropped `PortalRules.RULES`,
  moved the four route-matching baseline cases out of `ConfigValidatorTest`, and added
  `doc/development/boot-validation-rules.adoc` (the concern map is the source of the list below).

## The 13 groups still in ConfigValidator

Positions are 1-based positions in `DEFAULT_RULES`. Test sources name where the cases live today.

| Group | Rules (position) | Test sources today | Est. diff (outline) | Est. diff (×1.28) |
|---|---|---|---|---|
| TopologyRules | validateVersion (1), validateEndpointIdUniqueness (2), validateRouteIdUniqueness (3), validateBaseUrlResolvable (6) | `ConfigValidatorTest`: ConditionalBaseUrl, RuleViolations version/ids/alias, ValidConfiguration #1 | ~22 KB | ~28 KB |
| AnchorRules | validateAnchorPrefixDisjointness (7), validateAnchorReferencesExist (8), validateAnchorNamespaceMembership (9), validateAnchorAuthFloor (11), validateAccessAuthMatrix (14) | `ConfigValidatorTest`: AnchorRules nested minus rules 6/7, GrpcNamespaceExemption, AccessAuthMatrix | ~106 KB | ~136 KB |
| AuthRules | validateRouteAuthResolvable (10), validateEffectiveAuth (12), validateSessionFallback (13), validateEndpointScopesNeedAuthentication (44) | `ConfigValidatorTest`: AnchorRules rules 6/7, RuleViolations bearer/session, EndpointScopesNeedAuthentication; `ConfigValidatorSessionFallbackTest` (git mv) | ~43 KB | ~55 KB |
| LimitRules | validateSecurityProfileMinimal (15), validateEdgeHardening (38), validateAuthorizationHeaderValueLength (39) | `ConfigValidatorTest`: RuleViolations edge-hardening + authorization cap, SecurityProfileMinimalRefusal, GetBodyOptInDefault | ~77 KB | ~99 KB |
| TerminalActionRules | validateTerminalAction (16), validateAssetIndexAndFallback (37) | `ConfigValidatorTest`: TerminalActionConsistency, AssetIndexAndFallback; `ConfigValidatorRedirectTest` (git mv) | ~65 KB | ~83 KB |
| EdgeRules | validateForwardedTrust (18), validatePassthroughHostCollision (33), validatePassthroughAliasResolvable (34) | `ConfigValidatorTest`: RuleViolations trusted_proxies + passthrough, ValidConfiguration passthrough | ~45 KB | ~58 KB |
| HeaderRules | validateCors (19), validateContentSecurityPolicy (20), validateHeaderModes (21) | `ConfigValidatorTest`: ContentSecurityPolicyInjection, HeaderModesOrphanRefusal, RuleViolations CORS | ~41 KB | ~53 KB |
| OidcSessionRules | validateSessionMode (22), validateSessionMaxSessions (23), validateSessionIdleTimeout (24), validateSessionMaxSessionsPerSubject (25), validateSessionMaxCookieSize (26), validateSessionCookieName (27) | `ConfigValidatorTest`: SessionMaxSessionsPerSubject, RuleViolations session mode, BffOidcFoldRules session part, SessionCookieNameRefusal, SessionIdleTimeout | ~130 KB | ~167 KB |
| OidcClientRules | validateClientAuthentication (28), validateDefaultReturnUrl (45) | `ConfigValidatorTest`: DefaultReturnUrl, ClientAuthenticationMode | ~38 KB | ~49 KB |
| ReservedPathRules | validateClientJwksPath (29), validateUserInfo (30), validateLoginPath (31), validateStepUpPath (32) | `ConfigValidatorTest`: BffOidcFoldRules user_info/login part, ClientJwksPathCollision, ClientJwksPathCanonicalForm, StepUpPath, ReservedPathCanonicalForm | ~133 KB | ~171 KB |
| ProtocolRules | validateWebSocketConfig (35), validateRewriteLocationProtocol (36) | `ConfigValidatorTest`: WebSocketAllowlist, RewriteLocationProtocol, WebSocketOriginAllowlist | ~47 KB | ~60 KB |
| AssetDefaultsRules | validateAssetContentTypesAddOnly (40), validateAssetContentTypeValues (41) | `ConfigValidatorTest`: AssetContentTypesAddOnlyRefusal, AssetContentTypeValueRefusal | ~49 KB | ~63 KB |
| ForwardingRules | validateForwardModeExclusivity (42), validateTokenRelayForwardConflict (43) | `ConfigValidatorTest`: ForwardModeExclusivity, TokenRelayForwardConflict | ~35 KB | ~45 KB |

Sum of the re-scaled group estimates: ~1,067 KB.

## Proposed PR packing (re-scaled)

The outline's 7-PR packing does not survive the 1.28 re-scale: three single groups exceed or crowd
the 150 KB cap, and every multi-group PR lands at 146–151 KB with no headroom. Re-packed at a target
of about 120 KB per PR (headroom under the 150 KB cap), the follow-ups become **11 PRs** (12 in total
with slice 1):

1. **AnchorRules** — ~136 KB alone; over the 120 KB target, under the cap. Measure first; if the
   measurement exceeds ~140 KB, split `validateAccessAuthMatrix` (14) into its own PR.
2. **OidcSessionRules, part 1** — session mode, max_sessions, idle timeout, max_sessions_per_subject (22–25).
3. **OidcSessionRules, part 2** — max_cookie_size and cookie_name (26, 27). The group as a whole
   (~167 KB) exceeds the cap; the outline already named this split.
4. **ReservedPathRules, part 1** — user_info, login, step_up paths (30–32).
5. **ReservedPathRules, part 2** — `validateClientJwksPath` (29). The group as a whole (~171 KB)
   exceeds the cap; the outline already named this split.
6. **LimitRules** — ~99 KB.
7. **TerminalActionRules** — ~83 KB.
8. **EdgeRules + ProtocolRules** — ~118 KB.
9. **AssetDefaultsRules + ForwardingRules** — ~108 KB.
10. **AuthRules + OidcClientRules** — ~104 KB.
11. **HeaderRules + TopologyRules** — ~81 KB. As the last PR it also drops `ConfigValidator`'s
    then-unused `LOGGER` and leaves `ConfigValidatorTest` with the orchestration cases only
    (ValidConfiguration, StructuralVerbRejection, Aggregation).

A split group still lands its concern class once: part 1 creates the class with its rules, part 2
adds the remaining rules to it. The order between PRs is free except that the PR closing the last
private rule carries the `LOGGER` / `ConfigValidatorTest` clean-up.

## Cross-cutting notes for the follow-ups

- Three rules log a WARN (EdgeRules `BROAD_TRUSTED_PROXY`, TerminalActionRules
  `REDIRECT_EXTERNAL_TARGET_ALLOWED`, OidcSessionRules `COOKIE_BUDGET_EXCEEDS_BROWSER_GUARANTEE`).
  The rule class owns its `CuiLogger`; `LogRecord` ids and text stay, the logger category changes.
  `ConfigValidatorRedirectTest`'s `assertNoLogMessagePresent(WARN, ConfigValidator.class)` must then
  target the rule class or it turns vacuous.
- Shared helpers move into `RuleSupport` with the first slice that needs them:
  `effectiveAuth` / `effectiveRequire` / `effectiveProtocol` (Auth, Anchor, Limit, TerminalAction,
  Protocol, Forwarding), `lacksConfiguredIssuer` (Auth + Anchor), `MEDIA_TYPE_TOKEN`
  (AssetDefaults + OidcSession cookie-name shape), `prefixContains` / `strictlyContains` (Anchor).
- Member-level name references to update with their group: `ConfigLogMessages` and
  `PassthroughHostGuardStage` Javadoc, `doc/development/tls-edge.adoc`, `doc/user/tls-scenarios.adoc`,
  `TlsEdgeActivationWiringTest` and `integration-tests/src/main/docker/sheriff-config/topology.properties`
  (EdgeRules); the `gateway.yaml` comments naming `ConfigValidator.prefixContains` in three
  `integration-tests/src/main/docker/sheriff-config*` trees (AnchorRules); `endpoints/assets.yaml`
  naming `validateAssetSource` (TerminalActionRules); the `gateway.schema.json` description naming
  `validateAssetContentTypeValues` (AssetDefaultsRules); `doc/security-threat-model.adoc` test-class
  names per moved sibling test.
- Each follow-up updates the "State" column of the concern map in
  `doc/development/boot-validation-rules.adoc` from "private in `ConfigValidator`" to "extracted".
- PLAN-V02-12's new `oidc` checks, if they land first, stay private at their list position and move
  with OidcClient / ReservedPath / OidcSession.
- The point-in-time reports under `doc/quality-report/` stay untouched by every follow-up (dated
  snapshots that cite historical member names by design).
