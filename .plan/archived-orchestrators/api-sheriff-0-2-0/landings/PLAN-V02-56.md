# Landing Analysis: PLAN-V02-56 — Final Cleanup: the `/auth`-Fronted Keycloak Sample and the Epic's Open Watches

epic: api-sheriff-0-2-0
workstream: WS-01
pr: #566 (https://github.com/cuioss/API-Sheriff/pull/566), merged through the merge queue as `793612f6` on 2026-11-06

> Written 2026-11-06 by `analyze` (inbox drain, message `plan-v02-56-final-cleanup-005.md`, `landing-check` complete: true).
> The PR state, the file list, the `main` CI runs and the D2/D5/D9 code claims were re-read by the orchestrator at `793612f6`
> rather than copied from the message.

## Verdict: verified

All deliverables shipped in one PR. The landing reports 11 deliverables: the outline split the spec's nine (D1 into parts). The
operator waived the split guard because the PR stayed at 74 files, under CodeRabbit's 100-file cap. The epic's queue is now empty.

## Deliverable Fidelity vs Spec

| Deliverable (spec) | Verdict | Evidence |
|--------------------|---------|----------|
| D1 `/auth`-fronted Keycloak sample (V02-12's held D3) | shipped | `docker-compose.auth-fronted.yml` overlay with its own `sheriff-config-auth-fronted/` set; allowlist is the sample realm, `/auth/resources/` and the sample realm's RFC 8414 document only; `IdpFrontingCanonicalPathTest` (6 tests), `ComposeSampleAuthFrontedWiringTest`, extended `ConfigValidationParityTest`; `check-bff-login.sh`, `start-sample.sh` and both CI workflows cover the variant; ADR-0069 and both compose-sample docs updated |
| D2 config errors name the loaded file | shipped | `ConfigLoader` l. 389 refuses an endpoint whose id differs from its file name, so `RuleSupport.endpointFile` is always the loaded file. Breaking (pre-1.0) |
| D3 BFF-21 controls, V02-01 D7 residue, V02-08 verdicts | shipped | `GatewayEdgeGatewayCookieTest` (`no-store` on a cookie-setting page) and the WebSocket-upgrade `Set-Cookie` test; ADR-0058 decisions 11 (`502`) and 19 (`400` + ApiSheriff-134). The three V02-01 D7 items are reported still open and documented: replaced by a 0.3.0 carry Watch |
| D4 uniform-404 beyond eight paths | shipped | `EnumerationOracleIT` measures twelve pre-trust paths; ADR-0073 A1 updated |
| D5 bind the hand-maintained inventories | shipped | `RouteFieldInventoryContractTest` (5) and `DocumentedHeaderSetsContractTest` (8), each shown red against a diverged copy |
| D6 anchor-overlap operand orders | shipped | `AnchorRulesTest` asserts both orders |
| D7 V02-23 advisories + the four #565 advisories | shipped | `http/package-info.java`, `GatewayCookies`, `RouteTableBuilder` (red first), `PathSlashes` vs `UpstreamAssetSource` (now strips the whole trailing run: breaking), `LocationPathReview` Javadoc, the unreachable checks in `ReturnTargetScopes` removed |
| D8 V02-49 advisories and the `DispatchStage` re-review | shipped | `ConfigFixtures`, `TopologyResolverTest`, `PortalConfig.DEFAULT_CACHE_SECONDS`, `TerminalDispatch.retireFailedRelayConnection` marked branch removed as unreachable (three test assertions removed with operator acceptance); the re-review changed nothing and states why |
| D9 doc and comment residue | shipped | ws-admission overlay comment; `build-and-peripherals.adoc` l. 166 now says "twelve gateway instances"; `start-dev-environment.sh` uses `IDP_READY_ATTEMPTS=120`; the formatter's layout of `TerminalActionRulesRedirectTest.java` committed |

## Orchestrator verification

- #566 `MERGED` as `793612f6`: 74 files, +4,883 / -499.
- `main` push runs at `793612f6`: Maven Build `42232027584`, Integration Tests `42232027775`, Demo Client E2E `42232027035` and
  Config Validation `42232027191` all succeeded. The PR-attached benchmark `42232032139` succeeded.
- Zero unresolved review threads; the one unresolved row is CodeRabbit's summary `issue_comment` (no thread). Sonar new-code issue
  count 0.
- The landing's 7,815-test quality gate ran at `d8b84c67`; the final head `2a6d1a1c` is one Sonar S5778 test-only commit, covered by CI.

## Scope added beyond the spec

Under the operator's "Hold the line" ruling: a new server-mode key `oidc.session.max_cookie_header_value_length`, which raises only
the pre-route `Cookie` header-value cap (also applied to inbound `Set-Cookie`). Schema, `doc/configuration.adoc` and ADR-0019 cover
it; `ServerModeCookieHeaderCapTest` tests it. The `/auth`-fronted sample sets 4096 on the strict profile, sized from a measured
2,474-character worst case that 2048 refused. Recorded as a decision.

## Metrics and Anomalies

- Tokens: 40,547,156. Wall time: 67,942 s (about 18.9 h). Model `claude-opus-5-5`, effort `low`, plugin/executor 0.1.2036.
- Functional and code review closed on operator override at the final head, so their verdict and count facts were not recorded as
  typed facts.
- The re-fire waiver was again refused by the merge gate (`merge_candidate_not_gated`): the same class as `PLAN-V02-53`. Routed.
- Builds for a new command key were clipped at the 300 s default three times. Routed.
- `landing-check` could not compute a surface delta (`declared_not_supplied_and_realized_not_supplied`).
- `marshal.json` reported stale (0.1.1986 vs 0.1.2036): Watch, operator runs `/marshall-steward`.

## Observed alongside, not caused by #566

- `Dependabot Updates` security job for `minimatch` in `/demo-client` failed at `update_files` on 2026-11-05 and again on
  `793612f6` (`42232049339`). Watch added.
- #563 (`0113f51b`) moved the `cuioss-organization` pins to v0.44.0 with `release-process.adoc` in the same PR and passed the
  guard-docs check: the RELEASE-GUARD DOCS Watch is closed.

## Follow-up candidates

1. Two advisory nits left on `main` (the `IdpFrontingCanonicalPathTest` Javadoc "two" vs three cuts; a login-check probe comment
   "first and last" vs second-to-last). Watch, no plan.
2. The accepted BFF residuals (refresh-token discard, no RFC 7009 logout revocation, nonce-retry gaps): 0.3.0 candidates.

## Reconciliation Actions

- [x] row `status` → `shipped`; `pr` 566; `landing` `landings/PLAN-V02-56.md`
- [x] `epic.md`: decision added; eight `[OWNER PLAN-V02-56]` Watches struck; V02-49 residue item (4) done; V02-01 D7 Watch replaced
  by the 0.3.0 carry Watch; RELEASE-GUARD DOCS Watch struck; three Watches added (two nits, Dependabot `minimatch`, stale `marshal.json`)
- [x] candidate lessons 001-004 are all plan-marshall findings: routed to plan-marshall `lessons-routing`
- [x] all five inbox messages archived after persistence
