# PLAN-SEC2-03: BFF logout and source keys

epic: security-hardening-tier-2
workstream: WS-03

> Staged plan spec — one shippable unit of work, ready for `/plan-marshall` hand-off. Lives at
> `plans/PLAN-SEC2-03-bff-logout-and-source-keys.md` and is queued as one row file,
> `queue/PLAN-SEC2-03.json`. The orchestrator EMITS the command below; it never launches the plan
> inline. This spec is SELF-SUFFICIENT except for the security detail it deliberately keeps out of
> tracked text (see Security Findings).

## Objective

Require a same-origin signal before the BFF logout endpoint ends a session, make the shared
`unknown` source key safe under the actions and quotas that consume it, and keep the detection
ledger tracking sources that already carry weight when it is full.

Disclosure is mechanism-level. Background: `.plan/local/security_notes_verified.adoc` entries B1,
D2(c), D4. **Read them first.** Local only; never quote them into tracked text.

## Deliverables

1. **Same-origin gate on logout.** Reserved paths are handled before the CSRF gate and the logout
   handler sees only the cookie header, so a top-level cross-site navigation reaches it. Pass
   `Sec-Fetch-Site` and `Origin` into `BffRuntime.ReservedHttpRequest`; `LogoutEndpoint.logout`
   acts only on `Sec-Fetch-Site: same-origin|none` or a trusted `Origin` (reuse the `CsrfDefence`
   rules, do not duplicate them); otherwise answer a `no-store` confirmation page whose form POSTs back
   through `CsrfDefence`. Document the behaviour (BFF docs, threat model BFF-09).
   - Done when: a `cross-site` logout request leaves the session intact and makes no IdP call, a
     `same-origin` one logs out (matched control), and the confirmation POST logs out — the
     cross-site case failing at `6ee137df` (`order: test-first`). An IT covers the confirmation
     round-trip if the unit level cannot.
   - Skills: pm-dev-java:java-core, pm-dev-java:java-security, pm-dev-java:junit-core, pm-documents:ref-asciidoc
   - Persona: security-expert
2. **The shared `unknown` source key.** A client behind a trusted proxy that passes RFC 7239
   `Forwarded` through can make its source resolve to `unknown`, which (a) the opt-in detection
   `block`/`delay` actions treat as one source and (b) the default-on BFF pending-authorization quota
   treats as one bucket. Never `block` or `delay` `unknown` (count and alert only); give the pending
   quota a safe treatment for `unknown` (verify-first: e.g. key on the `X-Forwarded-For` result alone
   when a trusted proxy sets it, or a separate bound that cannot evict resolvable clients — pick one
   and state why); boot WARN when a detection action other than `observe` is configured together with
   trusted proxies. Docs: state that an unresolvable or contested forwarded chain maps to `unknown`
   and how each consumer treats it.
   - Verify first: confirm cui-http 3.2 `ForwardedHeaderResolver.reconcileSources` behaviour on a
     disagreeing `Forwarded` (empty result) by test, not only by reading.
   - Done when: `SourceKeysTest` / `PendingQuotaKeys` tests with a disagreeing `Forwarded` show the
     new treatment, failing at `6ee137df`; a resolvable client is unaffected (matched control);
     the WARN fires only for the stated combination (`order: test-first`).
   - Skills: pm-dev-java:java-core, pm-dev-java:java-security, pm-dev-java:junit-core, pm-dev-java-cui:cui-logging, pm-dev-java-cui:cui-http
   - Persona: security-expert
3. **Detection ledger under pressure.** When the ledger is full, a new key is not tracked, so it can
   be neither held nor refused. Above a high-water mark, aggregate new IPv6 keys to /48 and keep a
   reserved slice for keys that already carry weight, so a source that has crossed the threshold
   stays tracked.
   - Done when: a small-bound ledger test filled with live keys still tracks (or refuses) a new key
     under `block`, failing at `6ee137df`; below the mark keys stay /64 (matched control)
     (`order: test-first`).
   - Skills: pm-dev-java:java-core, pm-dev-java:junit-core
   - Persona: implementer
4. **Documentation.** BFF logout (`doc/user/bff-session.adoc`, `doc/user/bff-cookie.adoc` as
   applicable), detection and source keying (`doc/configuration/security-and-edge-hardening.adoc`),
   threat model BFF-09 and the detection passages, `doc/LogMessages.adoc` for new records, ADR
   amendment where a decision changes (ADR-0076 for detection).
   - Done when: each behaviour change is described where the old behaviour was (`order: none`).
   - Skills: pm-documents:ref-asciidoc, pm-documents:ref-documentation
   - Persona: documenter

## Standing Constraints

- Pre-1.0 rules: no deprecation, no compatibility shims.
- **No new dependency.** Logging via `CuiLogger`, `%s`, exception first; new records are `LogRecord`s
  in `doc/LogMessages.adoc` with the next free identifier.
- Tests: JUnit 5, AAA, no Mockito/PowerMock/Hamcrest. Every code fix starts with a test that FAILS
  against the unfixed code; a test that cannot be made to fail refutes the premise — record and drop.
- Fixed constants, no new `gateway.yaml` key unless unavoidable (then a test that goes red when the
  key is deleted).
- Do not touch `edge/upstream/**`, `edge/terminal/**`, `auth/RetryingJwksLoader*`, ADR-0063.
- Documentation states the mechanism only.
- One PR, under CodeRabbit's 100-file cap. Revert unrelated formatter churn.
- Sonar: fix, or dismiss only on the operator's selection; `undecidable` blocks.

## Security Findings

Possible weaknesses this plan finds and does not fix on its branch go ONLY into a dated entry
appended to the git-ignored `.plan/local/security_notes.adoc` (plan id, location, what, why not
fixed) — never into the PR, commits, docs or the inbox message.

## Claim Labels

- OBSERVED: reserved paths are handled before the CSRF gate — read at `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/edge/GatewayEdgeRoute.java:723,766` (main `6ee137df`)
  - verdict: corroborated | checked_at: 31595bca01e9d0e01780a87b73ff2b8dac3dec40 | by: security-hardening-tier-2/analyze | rescoped: n/a | evidence: GatewayEdgeRoute handles reserved paths at :723 and the CSRF enforce for the session branch comes later at :766, at 31595bca
- OBSERVED: the logout case passes only the cookie header — read at `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/bff/runtime/BffRuntime.java:350` and `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/edge/reserved/ReservedPathHandler.java:157-168` (main `6ee137df`)
  - verdict: corroborated | checked_at: 31595bca01e9d0e01780a87b73ff2b8dac3dec40 | by: security-hardening-tier-2/analyze | rescoped: n/a | evidence: BffRuntime.dispatchToHandler (:350) passes only req.cookieHeader() to logout; ReservedPathHandler.dispatchReserved builds the request without Sec-Fetch-Site or Origin, at 31595bca
- OBSERVED: an empty forwarded resolution becomes `unknown` for both the source key and the pending quota — read at `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/edge/admission/SourceKeys.java:125-127` and `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/bff/runtime/PendingQuotaKeys.java:101-114` (main `6ee137df`)
  - verdict: corroborated | checked_at: 31595bca01e9d0e01780a87b73ff2b8dac3dec40 | by: security-hardening-tier-2/analyze | rescoped: n/a | evidence: SourceKeys (:125-127) falls to keyResolver.resolve(null, null, host) on an empty forwarded resolution; PendingQuotaKeys:113 orElse(SourceKey.UNKNOWN_SOURCE), at 31595bca
- OBSERVED: the pending store bounds 256 per source with own-oldest eviction — read at `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/bff/pending/InMemoryPendingAuthorizationStore.java:85,133-136` (main `6ee137df`)
  - verdict: corroborated | checked_at: 31595bca01e9d0e01780a87b73ff2b8dac3dec40 | by: security-hardening-tier-2/analyze | rescoped: n/a | evidence: InMemoryPendingAuthorizationStore MAX_PENDING_PER_SOURCE = 256 (:85) with the key's own oldest record evicted, at 31595bca
- HYPOTHESIS: cui-http 3.2 returns an empty client address when `X-Forwarded-For` and `Forwarded` disagree — confirm at cui-http 3.2 `ForwardedHeaderResolver.reconcileSources` by test (verify-at-outline, D2)
  - verdict: unverifiable | checked_at: fa8dd54f3dd66bd8f7c6bd20919b0cb9d7418fb2 | by: security-hardening-tier-2/analyze | rescoped: n/a | evidence: ForwardedClientResolutionTest:53 asserts a disagreeing Forwarded/X-Forwarded-For pair resolves empty and the PR merged green, but the cui-http 3.2 reconcileSources source was not read and no test was run by the analysis
- OBSERVED: the ledger bound is 65,536 and a new key at the bound is `UNTRACKED` and not counted — read at `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/detection/SourceLedger.java:86,203-206,279-291` (main `6ee137df`)
  - verdict: corroborated | checked_at: 31595bca01e9d0e01780a87b73ff2b8dac3dec40 | by: security-hardening-tier-2/analyze | rescoped: n/a | evidence: SourceLedger MAX_TRACKED_SOURCES = 65_536 (:86); a new key at the bound returns UNTRACKED and only an overflow counter increments (:203-206), at 31595bca
- OBSERVED: detection actions default to `OBSERVE` — read at `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/config/model/EnumerationDetectionConfig.java:77` (main `6ee137df`)
  - verdict: corroborated | checked_at: 31595bca01e9d0e01780a87b73ff2b8dac3dec40 | by: security-hardening-tier-2/analyze | rescoped: n/a | evidence: EnumerationDetectionConfig DEFAULT_ACTION = Action.OBSERVE (:77), at 31595bca

## Expected Surface

- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/bff/runtime/BffRuntime.java`
- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/bff/reserved/LogoutEndpoint.java`
- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/bff/csrf/CsrfDefence.java`
- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/edge/reserved/ReservedPathHandler.java`
- OBSERVED: `api-sheriff/src/test/java/de/cuioss/sheriff/gateway/bff/reserved/LogoutEndpointTest.java`
- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/bff/runtime/PendingQuotaKeys.java`
- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/edge/admission/SourceKeys.java`
- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/edge/admission/SourceGate.java`
- OBSERVED: `api-sheriff/src/test/java/de/cuioss/sheriff/gateway/edge/admission/SourceKeysTest.java`
- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/detection/SourceLedger.java`
- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/detection/SourceKeyResolver.java`
- OBSERVED: `api-sheriff/src/test/java/de/cuioss/sheriff/gateway/detection/SourceLedgerTest.java`
- HYPOTHESIS: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/config/` — the detection/trusted-proxy boot WARN and its `LogRecord` (verify-at-outline)
- HYPOTHESIS: `integration-tests/src/test/java/de/cuioss/sheriff/gateway/integration/` — a logout confirmation IT, only if needed
- OBSERVED: `doc/user/bff-session.adoc`
- OBSERVED: `doc/user/bff-cookie.adoc`
- OBSERVED: `doc/configuration/security-and-edge-hardening.adoc`
- OBSERVED: `doc/security-threat-model.adoc` — BFF-09 and detection passages only
- OBSERVED: `doc/adr/0076-One_per-source_detection_substrate_counts_pre-trust_rejections_and_answers_a_tripped_source.adoc`
- OBSERVED: `doc/LogMessages.adoc`

## Dependencies and Sequencing

- Depends on: PLAN-SEC2-01 (shipped) and the orchestrator's post-landing re-check of the private notes.
- Overlaps with: PLAN-SEC2-02 on `doc/security-threat-model.adoc` and `doc/LogMessages.adoc` only; PLAN-SEC2-01 on `doc/user/bff-session.adoc` and ADR-0076 (lands first).
- Adjacent to: `bff/logout/RpInitiatedLogout.java` (comment corrected by PLAN-SEC2-01; logic unchanged unless D1 needs it).

## Hand-Off Command

```text
/plan-marshall task="implement .plan/orchestrator/security-hardening-tier-2/plans/PLAN-SEC2-03-bff-logout-and-source-keys.md"
```

## Write-Boundary

The plan implementing this spec touches only its own repository source and tests, and appends to the
git-ignored `.plan/local/security_notes.adoc` under Security Findings. It creates and edits NO file
under `.plan/orchestrator/` other than its own `inbox/{sender}-{seq}` landing message — the
orchestrator owns every other ledger write — and reports its outcome through its PR and that inbox
message. See `persona-plan-orchestrator/standards/orchestration-model.md` § Ledger Write-Boundary.
