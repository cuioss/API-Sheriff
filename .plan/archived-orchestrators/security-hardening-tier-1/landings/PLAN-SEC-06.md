# Landing Analysis: PLAN-SEC-06 — tier-2 quick wins

epic: security-hardening-tier-1
workstream: WS-04
pr: #607 — https://github.com/cuioss/API-Sheriff/pull/607

> Landing record for one shipped plan. Written by the `analyze` verb (inbox scan, message
> `plan-sec-06-tier-2-quick-wins-001.md`) after checking the inbox claims against git, the merged
> code and the PR state through the CI abstraction.

## Deliverable Fidelity vs Spec

`deliverables_total=7`, `deliverables_done=7`: the spec's six deliverables plus a D7 the lane added (native-image ITs). The landing message is `complete: false` on three keys: `effort=unknown`, `review_body_findings_total=unknown` and `review_body_findings_handled=unknown`. The two review-body keys are unknown because the producer's parser refuses one section of a CodeRabbit review body (see Metrics).

| Deliverable (spec) | Verdict | Evidence |
|--------------------|---------|----------|
| D1 — main-listener connection cap, idle-reap wording | shipped-modified | `quarkus.http.limits.max-connections=2048` with a derivation comment (`application.properties:143-162`). `ShippedApplicationPropertiesTest:156-171` goes red when the key is deleted. `MainListenerConnectionCapTest` shows the cap acts: CAP sockets are served, the next one is closed, and a freed slot is reused. The idle-reap overclaim is corrected in `EdgeHardeningOptions`, `edge-security.adoc` and the threat model. The spec's "derive from the container fd limit" could not be met because no fd limit is declared anywhere in the repo. The lane said so and pinned the cap to the default `admission_cap` (see Layer 1) |
| D2 — `/auth`-fronted sample narrowed to the login flow | shipped-as-specified | Four routes remain in `idp.yaml` (authorization, login-actions, logout, resources). The realm-wide and well-known routes are gone, and `account` / `account-console` are `enabled: false`. `start-sample.sh:192-218` now checks the effect (fail-closed on every parse path, python3 json, no jq). `IdpFrontingCanonicalPathTest` and `check-bff-login.sh` assert that account, token and discovery are unrouted, with the login path as the matched control. The optional `frame-ancestors` was not done. No compose bring-up was run (no Docker daemon), and the landing reports that |
| D3 — JWKS refresh publishes a rotated key | shipped-as-specified | `settleRefresh` → `carriesNewKey` (`RetryingJwksLoader.java:586-596`). Tests at `RetryingJwksLoaderTest:988` (rotated key after an invented kid), `:1017` (unchanged set discarded, the matched control), `:1034` and `:1062`. ADR-0063 amended |
| D4 — WebSocket `require: none` + `Authorization` must declare origins | shipped-modified (location) | Boot rule in `ForwardingRules.validateWebSocketAuthorizationOrigins` (`:268-300`), not `CookieRules` as the spec guessed. Header names are compared case-insensitively, the same way the runtime re-admits them. Tests at `ForwardingRulesTest:235-335` |
| D5 — delete `forwarded.trust_scheme_host` | shipped-as-specified | Removed from the record, schema, fixtures and docs. `ConfigLoaderTest:1498` refuses the key under `/forwarded`, with the matched control at `:1516` |
| D6 — documentation and Javadoc overclaims | shipped-partial | Most sites corrected. Still stale: the threat model's GW-08 Assertion (`security-threat-model.adoc:2111-2112`, "a reset the gateway sends never counts"), ADR-0076:197 ("counts only the resets a client sends"), the management "HTTPS by default" wording in `doc/architecture/observability-and-errors.adoc:259`, `doc/user/container-image.adoc:278` and two SVGs, and the ApiSheriff-115 remedy text (`ConfigLogMessages.java:177`, `LogMessages.adoc:79`) |
| D7 (added) — native-image ITs | shipped | `MainListenerConnectionCapIT` (direct listener only) and the fourth leg of `JwksKeyRotationIT` |

**Surface vs declaration** (`git diff 0ff33e95 898e0100`): 39 entries declared, 57 files realized, `expansion_detected`. Every added file is explained by a deliverable:
- D4 registration and docs: `ConfigValidator`, ADR-0015, `protocol-routes.adoc`, `validation-rules.adoc`, `boot-validation-rules.adoc`;
- D6 second sites: `NeutralTlsConfigSource`, ADR-0025, `FramingGate`, `environment-variable-overrides.adoc`, `doc/user/README.adoc`, `authentication-and-oidc.adoc`;
- D2: ADR-0069 and `doc/development/compose-sample.adoc`;
- D3: ADR-0063;
- D1 tests: `ShippedApplicationPropertiesTest`, `MainListenerConnectionCapTest`, `EdgeHardeningOptionsTest`;
- D5 tests and fixture: `ConfigModelContractTest`, `config/valid/gateway.yaml`;
- D7: four integration-test files and `integration-test-topology.adoc`.

Declared but untouched:
- `CookieRules` and the HYPOTHESIS `ProtocolRules` (the rule went to `ForwardingRules`);
- `docker-compose.auth-fronted.yml` (HYPOTHESIS);
- `doc/LogMessages.adoc` (HYPOTHESIS, no new record).

**Operator-accepted test removals** (three):
- `refreshWithoutTheTriggeringKeyIdChangesNothing` was deliberately inverted. Its "unchanged set discarded" protection survives at `RetryingJwksLoaderTest:1017` and `:1034`.
- `broadWellKnownPrefixIsReported` lost its subject when the well-known route was removed. No standing cut covers an `/auth/.well-known` widening any more.
- `JwksKeyRotationIT` was renamed and extended, not removed.

## Three-Layer Analysis

The landing was read on three layers by one read-only leaf, and the orchestrator re-read every point this report relies on before writing it here. **By reading, no build, no test run, no write.**

- **Dispatch:** one read-only leaf (`execution-context-level-3`, `orchestrator.analyze`). Reason: every full-ship landing gets its own leaf, and this one is 57 files across config, auth, admission Javadoc, the compose sample and 30 doc files.
- **Re-read by the orchestrator at 898e0100:**
  - `application.properties:143-162`;
  - `ForwardingRules.java:268-300`;
  - `RetryingJwksLoader.java:586-616`;
  - `security-threat-model.adoc:2110-2112` and ADR-0076:196-198;
  - `ConfigLogMessages.java:175-179`;
  - `gateway.schema.json:345`.
- **Verdict:**
  - layer 1: the D1 derivation premise is partly unmet (findings);
  - layer 2: D6 incomplete, plus minor test gaps;
  - layer 3: one major and several minor points, held outside tracked text.

### Layer 1 — Specified

Verdicts were persisted onto the spec with `corpus set-verdict` (producer `security-hardening-tier-1/analyze`). Claims 0–11 were checked at parent `0ff33e95`, claim 12 at `898e0100`.

| Claim | Verdict | Polarity | Evidence |
|-------|---------|----------|----------|
| 0–1: no connection cap; idle-reap wording | checked | confirms | parent `0ff33e95` |
| 2–3: realm-wide sample route; version-only check in `start-sample.sh` | checked | confirms | `idp.yaml:42`, `start-sample.sh:160-164` |
| 4: `settleRefresh` discards a set lacking the triggering kid | checked | confirms | `getKeyInfo(kid).isEmpty()` at parent |
| 5: no WebSocket Authorization-origin rule | checked | confirms | the rule landed in `ForwardingRules`, not `CookieRules` |
| 6: `trust_scheme_host` read by nothing | checked | confirms | `ForwardedConfig.java:42` |
| 7–10: ADR-0043, `tls-edge.adoc`, variant 02, ADR-0008 overclaims | checked | confirms | quoted lines at parent |
| 11 (HYPOTHESIS): "gateway-sent resets never count" statements present | checked | confirms | removed in four files; two more sites survive (D6 row) |
| 12 (HYPOTHESIS): `max-connections` acts on the main listener | corroborated by CI | — | the JVM test and the native IT are non-vacuous and ran green in #607 CI. The Quarkus recorder source was not read |

Also on layer 1:
- The D1 derivation rests on two premises the merged code does not fully bear out. The container fd limit does not exist in the repo, so the cap could not be derived from it. And holding the cap at the default `admission_cap` does not leave admission governing in-flight requests on every protocol.
- The fd-sizing advice also undercounts one deployment shape.
- Details are in the operator's local notes.

**Layer 1 verdict:** findings — the D1 derivation premise is partly unmet; every claim label is confirmed.

### Layer 2 — Implemented

- D1–D5 trace to code and to tests that can fail. D7 proves the cap only on a directly terminated listener: the redirect-listener sharing, the uncounted SNI front and the uncounted management listener are documented but not pinned.
- D6 is incomplete (sites listed in the fidelity table).
- Lane advisories, all confirmed by reading:
  - no test pins that the WebSocket rule leaves HTTP/gRPC `require: none` routes and non-proxy routes alone, so removing the guard at `ForwardingRules.java:274` would go undetected;
  - GW-09 does not name the new refusal;
  - the management-default wording survives in two docs;
  - the ApiSheriff-115 remedy is out of date;
  - `refreshHoldingAnExpiredRetiredKeyIdIsPublished` asserts the retired delegate's close without waiting for it (`RetryingJwksLoaderTest:1089`; the close runs after the snapshot swap at `RetryingJwksLoader.java:607-614`).
- New, unpinned claim: on HTTP/2, Netty collapses identical repeated `Content-Length` values (`FramingGate:36-41`). No test pins this.
- Cosmetic: `doc/configuration.adoc:386` lost one column of alignment.

**Layer 2 verdict:** findings — D6 incomplete, plus minor test gaps.

### Layer 3 — Correct

| Defect | File:line | Triggering input | Effect | Severity |
|--------|-----------|------------------|--------|----------|
| S6-A | `api-sheriff/src/main/resources/application.properties` (connection cap) | — | Bound shape of the new connection cap. Full detail held outside tracked text, reported to the operator and recorded in the operator's local notes | major |
| S6-B … S6-E | `application.properties`, `auth/RetryingJwksLoader.java`, `schema/gateway.schema.json` | — | Cap/admission interplay, descriptor sizing, a configuration bound that does not act as documented, and a key-set publication edge. Detail held outside tracked text | minor |
| S6-F, S6-G | `RetryingJwksLoader.java`, compose-sample `idp.yaml` | — | Not shown by reading; local notes | info |

No defect found by reading in:
- the D4 boot rule (case fold, positive-list copy, scope chain, message rendering);
- the `trust_scheme_host` refusal path;
- the `start-sample.sh` effect check, which is fail-closed on every parse path;
- the D3 concurrency: one snapshot read, a single in-flight flag, and a retired delegate closed on every replacement.

The operator's private assessment was re-checked against the merged code. The short-term part of the head-deadline entry and the doc parts of the back-channel, reset-counting and management entries are marked shipped. The management and reset-counting entries keep pointers to their remaining stale sites.

**Layer 3 verdict:** findings — one major and several minor points, held outside tracked text.

### Conformance to the Project's Own Rules

| Rule source | Rule | Verdict | Evidence |
|-------------|------|---------|----------|
| CLAUDE.md § Logging | CuiLogger, `%s`, LogRecord for INFO/WARN/ERROR | conforms | DEBUG only in the new paths; no new record |
| CLAUDE.md § Testing | JUnit 5 AAA, no Mockito/PowerMock/Hamcrest | conforms | real listeners, matched controls |
| CLAUDE.md § Testing | a key that parses must act | conforms | `max-connections` and `trust_scheme_host` are both pinned. One exception is in the layer-3 notes |
| CLAUDE.md § Pre-1.0 / dependencies | no `@Deprecated`, no shim, no new dependency | conforms | key removed outright; no pom change |
| CLAUDE.md § Pre-commit | no compiler `@SuppressWarnings` | conforms | only a Sonar `java:S2925` suppression, which carries both `@SuppressWarnings` and `NOSONAR` (redundant) |
| spec standing constraints | `edge/upstream/**`, `tls/**`, `edge/terminal/**` untouched | conforms | none in the diff |
| spec D6 | correct every overclaim named | deviates | six sites remain (fidelity table) |
| CLAUDE.md § Sonar | gate green, zero new issues | conforms | SonarCloud PR check pass; `sonar_open_new_issues=0` at the last head |

## Metrics and Anomalies

- **Tokens:** 48,983,061 total. The lane notes this is about 20k high, because one review-agent return was counted at an estimate.
- **Duration:** 101,210 s wall (about 28.1 h, which includes finalize waits).
- **Model / effort:** claude-sonnet-5-5, effort `unknown`; plugin and executor 0.1.2106 (no skew).
- **Steps:** all 15 `done` (parsed with a last-colon split; no namespaced ids).
- **Review and Sonar numbers re-read from the provider:**
  - `unresolved_review_threads`: landing 0. `ci pr comments --unresolved-only` shows 6 rows, all `review_body` / `issue_comment` kinds with an empty `thread_id`, so the re-read is 0. Agree.
  - `review_body_findings_total` / `_handled`: landing `unknown`. The re-read with `runbook_lane review-gate --pr-number 607` returns `undecidable_read`: the parser refuses CodeRabbit's "Files with no reviewable changes" section. This is the cause of the unknown, so the two agree. Routed to plan-marshall `lessons-routing` as `api-sheriff-security-hardening-tier-1-006`.
  - `sonar_open_new_issues`: landing 0. The re-read (`sonar read_open_issues --pr 607`) returns `count_status: undecidable` (head revision unavailable after the merge; last analysis `ecb5271d`). The SonarCloud PR check passed. Not re-verified, so not recorded as a difference.
- **Residue reported by the lane:**
  - The local `verify -Ppre-commit` formatter disagrees with `main`'s import-block style after #608: it re-adds a blank line in 451 files. The plan reverted that churn each time. Recorded as a Watch.

## Routing and Merge Behavior

- **Review:** CodeRabbit reviewed every head. Sourcery declined (PR too large; optional).
- **CI/merge:** all 25 PR checks were green or skipped (`ci checks status --pr-number 607`), including the post-merge "Run Integration Benchmarks". The plan merged through the merge queue as `898e0100`.
  - At branch-cleanup it was rebased once, with operator authorization, over #608 (formatter), #609 (test fix) and #606 (ledger). There was one import-block conflict, in `ShippedApplicationPropertiesTest`. `pr safe-merge` was refused because `main` requires the queue, so the plan landed through the queue.
  - The `main` push runs for `898e0100` all passed: Maven Build (incl. `deploy-snapshot`), Integration Tests, Config Validation, Demo Client E2E, Scorecard.
- **Parallelization:** no in-epic plan ran beside it. The rebase conflict came from the formatter commit #608, not from a sibling plan, so no declared surface was wrong on that account.
- **Expected Surface:** under-declared by 20 doc, test and config sites, all inside the deliverables' scope. It is not corrected, because no staged spec remains to pair against and the gate was not misled.

## Reconciliation Actions

- [x] row `status` → `shipped` — `orchestrator queue --transition PLAN-SEC-06 --status shipped`
- [x] row `pr` stamped — `607`
- [x] row `landing` stamped — `landings/PLAN-SEC-06.md`
- [x] row `plan_marshall_plan_id` stamped — `plan-sec-06-tier-2-quick-wins`
- [x] claim verdicts 0–12 persisted onto the spec — `corpus set-verdict` × 13
- [x] epic.md narrative reconciled (queue annotation, Open Defect for the incomplete landing, ground-truth Watch, formatter Watch)
- [x] resume anchor updated; `queue-view.md` regenerated and committed

## Follow-Ups

- The layer-3 points (one major, several minor) are with the operator. Staging a fix plan is the operator's call.
- D6 leftovers (six stale doc sites and the ApiSheriff-115 remedy) and the lane's test advisories (WebSocket rule negative controls, the racy close assertion) are a small doc/test follow-up, staged only on the operator's word.
- The formatter style mismatch needs its own change off `main` to settle which style is canonical.
- The live compose bring-up of the narrowed sample has never been run.
- With PLAN-SEC-06 shipped, every queued plan of this epic has shipped. The epic can close once the operator has decided on the open follow-ups (`/plan-marshall:plan-orchestrator close slug=security-hardening-tier-1`).
