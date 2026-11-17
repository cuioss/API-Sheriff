# PLAN-SEC2-04: Leftover hardening

epic: security-hardening-tier-2
workstream: WS-04

> Staged plan spec — one shippable unit of work, ready for `/plan-marshall` hand-off. Lives at
> `plans/PLAN-SEC2-04-leftover-hardening.md` and is queued as one row file,
> `queue/PLAN-SEC2-04.json`. The orchestrator EMITS the command below; it never launches the plan
> inline. This spec is SELF-SUFFICIENT except for the security detail it deliberately keeps out of
> tracked text (see Security Findings).

## Objective

Collect and close what the epic's first three plans found and did not fix: the observations the
implementing plans recorded in the operator's private notes, and the observations the orchestrator's
three-layer reads of the PLAN-SEC2-01, PLAN-SEC2-02 and PLAN-SEC2-03 landings raised. Each item is verified first by a
test at the sha it names, then fixed, or recorded as refuted or accepted with the reason. One plan, one
PR, so no leftover stays only in a landing report or a note.

Disclosure is mechanism-level. Background: `.plan/local/security_notes.adoc` entries SEC2-01-A,
SEC2-01-B, SEC2-03-A, SEC2-03-B, SEC2-03-C, SEC2-03-D and SEC2-L-01 to SEC2-L-15 (the orchestrator's landing-read items, each naming its deliverable; the SEC2-03 entries carry verified results and fix
directions; the JUnit probes that produced them are under `.plan/local/security-probes/sec2-03/`), and
the Follow-Ups of `landings/PLAN-SEC2-01.md`, `landings/PLAN-SEC2-02.md` and `landings/PLAN-SEC2-03.md`. **Read them first.** Local
only; never quote them into tracked text. The probes are local scratch: turn them into repository tests,
never commit them as they are.

## Deliverables

1. **Forwarded-chain judgement and source-key steering (SEC2-03-A, SEC2-03-D).** The edge's
   header-size check and the forwarded resolver judge different values, and the `X-Forwarded-For`
   fallback lets a client choose its detection key behind a proxy that does not manage that header. Make
   the edge check and the resolver judge the same value, and take the fallback only when the family is
   shown proxy-managed (or strip the unmanaged family as ADR-0003 prescribes); key a trusted peer's
   present-but-unresolvable forwarding headers so they are not exempt from the configured action.
   Pick the direction from the verified fix directions and state why.
   - Verify first: reproduce each confirmed path from the notes as a repository test at `fa8dd54f`.
     If a path cannot be reproduced there, record it as refuted and drop that part.
   - Done when: the reproducing tests fail at `fa8dd54f` and pass after the change; a proxy that appends
     to `X-Forwarded-For` and a resolvable client are unaffected (matched controls); the unsupported
     topology is stated in `doc/adr/0003-forwarded-headers.adoc` and
     `doc/configuration/routing-and-forwarding.adoc` (`order: test-first`).
   - Skills: pm-dev-java:java-core, pm-dev-java:java-security, pm-dev-java:junit-core, pm-dev-java-cui:cui-http
   - Persona: security-expert
2. **Logout provenance (SEC2-03-B).** The same-origin proof reads only `Origin` and `Sec-Fetch-Site`, so
   any same-origin request ends the session, not only a navigation. Settle the fix direction recorded in
   the notes (require navigation metadata and refuse prefetch/prerender when Fetch Metadata is present,
   or end the session only on a trusted-`Origin` POST and answer every GET with the confirmation page),
   state why, and make the method name and documentation say what the code checks. Also settle two
   leaf observations by test: that the confirming POST completes when `session.csrf.trusted_origins`
   is declared without the gateway's own origin, and what BFF-09 says about a request the browser marks
   `Sec-Fetch-Site: none`.
   - Verify first: re-run the logout probe's cases against the real `LogoutEndpoint` at `fa8dd54f`; the
     two leaf observations are HYPOTHESIS in Claim Labels.
   - Done when: a same-origin subresource request leaves the session intact and a trusted navigation
     (matched control) and the confirming POST still log out, the subresource case failing at
     `fa8dd54f`; the trusted-origins observation is either fixed (boot refusal, WARN or an implicit
     gateway origin) or recorded as refuted by test; BFF-09 states the mechanism (`order: test-first`).
   - Skills: pm-dev-java:java-core, pm-dev-java:java-security, pm-dev-java:junit-core, pm-documents:ref-asciidoc
   - Persona: security-expert
3. **Recon-trip key on the threat ledger (SEC2-03-C).** A recon trip is scored on the key it was tripped
   under, which can be the `/48` aggregate, while the source's own events are scored on its `/64`, and
   nothing combines the two. Carry the request's own source key with the trip and score the trip on it,
   or credit an existing tracked `/64` under the aggregate; keep ADR-0078's keying statement true.
   - Done when: a test with a threat-tracked `/64` and a trip keyed on the `/48` scores both on one entry,
     failing at `fa8dd54f`; the default-tuning case alerts at the same request number as before (matched
     control) (`order: test-first`).
   - Skills: pm-dev-java:java-core, pm-dev-java:java-security, pm-dev-java:junit-core
   - Persona: security-expert
4. **Full-ledger behaviour and its accuracy.** Bound or document the fail-closed refusal of a new source
   when the ledger is full (the notes' fix directions for SEC2-03-D: a reserved slice, or evicting the
   lowest-level live entry instead of refusing; at least state the consequence in the docs). Settle by
   test three leaf observations: that the blocked-source query runs the ledger's once-per-second sweep on
   the event loop, that a source keyed `unknown` is logged as blocked although the gate never answers it,
   and that `SourceLedgerPressureTest.accruingSourceStaysTrackedInAFullLedger` goes red when the change
   is reverted (the leaf read it as green against the base, while the documentation cites it as proof).
   - Verify first: the three observations are HYPOTHESIS in Claim Labels.
   - Done when: each observation is fixed with a test failing at `fa8dd54f`, or recorded as refuted; the
     cited proof test is made to fail against the base or the citation is corrected; the bound or the
     stated consequence of the full-ledger refusal is in `doc/user/edge-security.adoc` and ADR-0076
     (`order: test-first`).
   - Skills: pm-dev-java:java-core, pm-dev-java:junit-core, pm-documents:ref-asciidoc
   - Persona: implementer
5. **Echo and log hygiene residuals (SEC2-01-A and the PLAN-SEC2-01 layer-3 reads).** Some sites still
   place a configured name or id into a message raw: `RouteTableBuilder` (the five `formatted(...)`
   sites), the `ConfigProducer` validation-failure log (`violation.pointer()`), the route id in
   `OriginValidationStage` and `UpstreamFailureMapper` log lines, and the anchor name placed into the
   `/anchors/<name>/…` pointer in `HeaderRules`, `AuthRules`, `CookieRules`. Route every one through the
   escaping the other rules use, or record why a site cannot carry a control character. Settle by test
   whether the route id pattern's `$` terminator behaves like the `(?![\s\S])` terminator its sibling
   patterns use.
   - Verify first: for each site, find the shortest input that reaches it past the schema pattern; a site
     the pattern already makes unreachable is recorded as such, not changed.
   - Done when: each reachable site has a test with a control character in the name failing at
     `fa8dd54f`; the unreachable ones are listed in the landing message; the pattern question is settled
     by a test (`order: test-first`).
   - Skills: pm-dev-java:java-core, pm-dev-java:java-security, pm-dev-java:junit-core, pm-dev-java-cui:cui-logging
   - Persona: security-expert
6. **Reach of the key-material views and of the extension issuer gate.** The key-bearing views treat
   `files` alone, or `key-files` alone, as a certificate being configured (`ManagementPlainHttpAudit`,
   `TerminatedListenerTlsAudit`). Settle by test whether `ExtensionIssuerNamespaceGate` sees a
   `%profile`-prefixed or build-time-resolved issuer key through `config.getPropertyNames()`, and whether
   the extension's metrics collector can build its validator before the gate's `StartupEvent` observer.
   - Verify first: the three questions are HYPOTHESIS in Claim Labels; a refutation is recorded, not fixed.
   - Done when: each confirmed gap has a test failing at `fa8dd54f` and is fixed (`order: test-first`);
     each refuted one is recorded with the test that shows it.
   - Skills: pm-dev-java:java-core, pm-dev-java:java-security, pm-dev-java:junit-core, pm-dev-java:java-quarkus
   - Persona: security-expert
7. **Failed-relay head field set.** The head of a failed relay drops a fixed list of cache-lifetime
   fields (`TerminalDispatch.UNSTORABLE_ANSWER_REMOVES`), so a CDN field outside the list, for example
   `X-Accel-Expires`, survives. Decide between completing the list and replacing it with an allowlist of
   what the head may carry, state why, and test the chosen rule.
   - Done when: a failed-relay head carrying `X-Accel-Expires` no longer carries it, failing at
     `fa8dd54f`; a field the head must keep (matched control) is kept (`order: test-first`).
   - Skills: pm-dev-java:java-core, pm-dev-java:junit-core
   - Persona: implementer
8. **Management interface transport bounds (SEC2-01-B).** The tracked docs state that the management
   listener keeps the transport defaults. Pin that mechanism by a test on a live management listener
   (the 8 KiB header-list default is not pinned today), check which bounds the
   `quarkus.management.limits.*` family covers, and apply those that exist or state that none does.
   - Verify first: whether the family covers the header block, request line and idle reap.
   - Done when: the announced management header-list size and each applied bound is asserted on a live
     listener, failing at `fa8dd54f` where a bound is newly applied; the documented statement matches
     what the test shows (`order: test-first`).
   - Skills: pm-dev-java:java-core, pm-dev-java:java-quarkus, pm-dev-java:junit-core
   - Persona: security-expert
9. **Documentation and comment debts.** The compressed-block `GOAWAY` rule at 1.25× the bound is stated in
   five places and pinned by no test; the comment at `application.properties` lines 322 and 346-350 still
   describes the extension's issuer namespace as an enabled-issuer hazard; the reason the header-list bound
   went from 8 to 16 KiB is in the PR body but not in the Javadoc or ADR-0076; the `SourceKeys` Javadoc
   still says the key is the address `X-Forwarded-For` is regenerated from; the
   `@SuppressWarnings("java:S107")` in `BffRuntimeProducer` carries no rationale; and the documentation
   says the `ApiSheriff-149` WARN is logged once at startup while it is emitted inside a validation rule;
   three comments still describe the upstream breaker as skipping client-ended outcomes or overstate how they
   are attributed (`TerminalDispatch` Javadoc and the test class comments that say "the breaker skips", the
   `DispatchStage` class Javadoc on which outcome survives a client departure, and the `GrpcDispatchStage`
   comment that counts upstream timeouts "three ways").
   - Done when: the 1.25× rule has a test that goes red when the factor changes (`order: test-first`); each
     other site is corrected where it is stated, or the suppression is removed by a parameter object
     (`order: none`).
   - Skills: pm-dev-java:java-core, pm-dev-java:junit-core, pm-documents:ref-asciidoc, pm-dev-java:javadoc
   - Persona: implementer

10. **The request-head deadline gap, stated (M1).** Neither the transport nor the gateway bounds the time a
    connection may take to send a complete request head; the idle timer restarts on every byte, and the
    connection cap is one global counter shared by the terminated listeners. The operator decided to state
    the gap and the deployment requirement rather than build a deadline now. Document that a front proxy
    with its own request-head timeout is a deployment requirement, in the edge-security user documentation,
    the security-and-edge-hardening configuration page and the threat model's detection and listener
    passages, and draft the text of an upstream request for a head-timeout or per-connection hook for the
    operator to file.
    - Verify first: re-confirm at the `main` the plan starts from that nothing in
      `api-sheriff/src/main/java` installs a head deadline and that `quarkus.http.limits.max-connections`
      is one counter for the terminated listeners (both are HYPOTHESIS in Claim Labels); a refutation
      replaces this deliverable with a note in the landing message.
    - Done when: each documented statement matches the code read at the start (`order: none`); the drafted
      upstream request is in the landing message, not in a tracked document.
    - Skills: pm-documents:ref-asciidoc, pm-documents:ref-documentation
    - Persona: documenter
11. **Back-channel logout revocation is per replica: say so at boot (C1).** Back-channel logout destroys
    the session only in the store of the replica that received it, and the store is per process. Emit one
    boot WARN, with a new `LogRecord`, whenever the server-mode session store and back-channel logout are
    both enabled, naming the per-replica scope; no new configuration key, since no replica count is
    declared. The WARN stays silent in cookie mode and when back-channel logout is not enabled.
    - Verify first: confirm that no configuration key declares a replica count (HYPOTHESIS in Claim
      Labels) and that the rule can read both enabled states at validation time.
    - Done when: a test with server mode and back-channel logout enabled sees the WARN, failing at
      `fa8dd54f`; cookie mode, and server mode without back-channel logout, see none (matched controls);
      the record is in `doc/LogMessages.adoc` and the boot behaviour in `doc/variants/02-bff-session.adoc`
      and `doc/user/bff-session.adoc` (`order: test-first`).
    - Skills: pm-dev-java:java-core, pm-dev-java:java-security, pm-dev-java:junit-core, pm-dev-java-cui:cui-logging
    - Persona: security-expert
12. **Upstream breaker attribution residuals (PLAN-SEC2-02).** PLAN-SEC2-02 made an expired wait for a
    pooled connection the upstream's timeout and every client departure neutral. Settle by test, at the
    `main` the plan starts from: (a) what an expired wait is attributed to when the pool's connections are
    held by dispatches whose request body is still arriving, and, if it is charged to an upstream that is
    answering normally, change the rule and state why; (b) whether an upstream that never answers goes
    uncounted when its callers leave before the wait bound, and state the limit wherever the documentation
    says the accounting holds under load; (c) whether a state-change consumer that throws can change the
    outcome of the call that caused the transition, and make the report safe if it can. Also make the older
    tests that still build a SmallRye breaker with `skipOn` use the gateway's breaker, add a concurrent test of
    the breaker, and settle whether the SmallRye breaker's meters existed and ADR-0064's "same records and
    metrics" statement holds.
    - Verify first: (a) to (c) are HYPOTHESIS in Claim Labels; a refutation is recorded with the test that
      shows it, not fixed. Re-read `edge/upstream/**` and the ADR statements at the `main` the plan starts from.
    - Done when: a confirmed (a) has a test, failing at `14741380`, in which the pool is held by
      request bodies still arriving and legitimate requests then wait out the bound without opening the
      breaker, while a never-answering upstream with callers that wait out the bound still opens it (matched
      control); (b) and (c) are each fixed by a test or stated as a limit in ADR-0060, GW-07 and
      `doc/user/edge-security.adoc`; the fixtures use the gateway breaker and a concurrent test exists
      (`order: test-first`).
    - Skills: pm-dev-java:java-core, pm-dev-java:java-quarkus, pm-dev-java:java-security, pm-dev-java:junit-core
    - Persona: security-expert

## Standing Constraints

- Pre-1.0 rules: no deprecation, no compatibility shims.
- **No new dependency.** Logging via `CuiLogger`, `%s`, exception first; new records are `LogRecord`s in
  `doc/LogMessages.adoc` with the next free identifier at the time of editing (re-read it; PLAN-SEC2-02
  and PLAN-SEC2-03 both added records).
- Tests: JUnit 5, AAA, no Mockito/PowerMock/Hamcrest. Every fix starts with a test that FAILS against the
  unfixed code at the sha its Done-when names; a test that cannot be made to fail refutes the premise —
  record and drop that part. A deliverable stopped by its verify-first clause is reported in the landing
  message; the other deliverables continue.
- Fixed constants, no new `gateway.yaml` key unless unavoidable (then a test that goes red when the key
  is deleted).
- Documentation states the mechanism only.
- ADR body ceiling is 500 lines: `doc/adr/0060-…` is already at 517 lines, so this plan edits it by
  replacing text and never grows it; ADR-0076 and ADR-0078 are under it and are amended only where a
  decision changes.
- PLAN-SEC2-02 (`14741380`), PLAN-SEC2-03 and PLAN-SEC2-01 have shipped: re-read
  `doc/security-threat-model.adoc`, `doc/LogMessages.adoc` and `edge/upstream/**` at the `main` the plan
  starts from before editing.
- One PR, under CodeRabbit's 100-file cap (PLAN-SEC2-01 changed 63 files and PLAN-SEC2-03 55). If the work would exceed it, ship the remaining deliverables as a self-started second plan
  rather than dropping scope. Revert unrelated formatter churn.
- Sonar: fix, or dismiss only on the operator's selection; `undecidable` blocks.

## Security Findings

Possible weaknesses this plan finds and does not fix on its branch go ONLY into a dated entry appended
to the git-ignored `.plan/local/security_notes.adoc` (plan id, location, what, why not fixed) — never
into the PR, commits, docs or the inbox message. A verified result of this plan's own deliverables is
reported the same way, with the test that shows it.

## Claim Labels

- OBSERVED: the route id pattern ends in `$` where sibling patterns end in `(?![\s\S])` — read at `api-sheriff/src/main/resources/schema/endpoint.schema.json:195` against `:137`, `:177` (main `fa8dd54f`)
- OBSERVED: `UNSTORABLE_ANSWER_REMOVES` lists `Expires`, `Age`, `ETag`, `Last-Modified`, `CDN-Cache-Control`, `Surrogate-Control` and no other field — read at `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/edge/terminal/TerminalDispatch.java:118` (main `fa8dd54f`)
- OBSERVED: both key-bearing views count `files` alone or `key-files` alone as a certificate being configured — read at `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/tls/ManagementPlainHttpAudit.java:125-128` and `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/tls/TerminatedListenerTlsAudit.java:152-155` (main `fa8dd54f`)
- OBSERVED: `ConfigProducer` logs `violation.pointer()` raw, and the route id reaches `OriginValidationStage` and `UpstreamFailureMapper` log lines unescaped — read at `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/quarkus/ConfigProducer.java:232`, `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/pipeline/OriginValidationStage.java:86`, `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/edge/upstream/UpstreamFailureMapper.java:139,141` (main `fa8dd54f`)
- OBSERVED: `RouteTableBuilder` formats endpoint and route ids into five messages without the escaping helper — read at `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/config/RouteTableBuilder.java:203,206,486,494,498` (main `fa8dd54f`)
- OBSERVED: `HeaderRules` builds `/anchors/%s/…` pointers from the anchor name — read at `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/config/validation/rule/HeaderRules.java:86,119` (main `fa8dd54f`; `AuthRules` and `CookieRules` build the same form — verify-at-outline)
- OBSERVED: the same-origin proof reads only `Origin` and `Sec-Fetch-Site` — read at `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/bff/csrf/CsrfDefence.java:202-208` (main `fa8dd54f`)
- OBSERVED: the blocked-source query skips an `unknown` source and refuses an untracked source when the ledger has no room — read at `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/edge/admission/SourceGate.java:228-250` (main `fa8dd54f`)
- OBSERVED: no slice of the ledger bound is reserved for weighted keys — read at `doc/development/source-detection-substrate.adoc:80-92` and `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/detection/SourceLedger.java:329-369` (main `fa8dd54f`)
- HYPOTHESIS: the blocked-source query runs the ledger's sweep on the event loop — confirm/refute at `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/detection/SourceLedger.java` § `hasRoomFor`, `makeRoom` (verify-at-outline)
- HYPOTHESIS: `SourceLedgerPressureTest.accruingSourceStaysTrackedInAFullLedger` passes against the unchanged base ledger — confirm/refute at `api-sheriff/src/test/java/de/cuioss/sheriff/gateway/detection/SourceLedgerPressureTest.java:194` (verify-at-outline)
- HYPOTHESIS: a source keyed `unknown` is logged with the configured `block` action though the gate never answers it — confirm/refute at `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/edge/admission/SourceGate.java` § the trip listener (verify-at-outline)
- HYPOTHESIS: the confirming logout POST is refused when `session.csrf.trusted_origins` omits the gateway's own origin — confirm/refute at `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/bff/csrf/CsrfDefence.java` § `isTrusted` (verify-at-outline)
- HYPOTHESIS: `ExtensionIssuerNamespaceGate` does not see a `%profile`-prefixed or build-time-resolved issuer key through `config.getPropertyNames()` — confirm/refute at `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/quarkus/ExtensionIssuerNamespaceGate.java:124` (verify-at-outline)
- HYPOTHESIS: the extension's metrics collector can build its validator before the gate's `StartupEvent` observer — confirm/refute at `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/quarkus/ExtensionIssuerNamespaceGate.java:112` (verify-at-outline)
- OBSERVED: the documented revocation scope is per replica — read at `doc/variants/02-bff-session.adoc:303` (main `fa8dd54f`)
- HYPOTHESIS: no configuration key declares a replica count — confirm/refute by a search of `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/config/model/` (verify-at-outline; the orchestrator's search of `api-sheriff/src/main/java` found `replica` only in Javadoc, but its output was truncated)
- HYPOTHESIS: nothing in `api-sheriff/src/main/java` installs a request-head deadline, and `quarkus.http.limits.max-connections` is one counter for the terminated listeners — confirm/refute at `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/edge/admission/EdgeHardeningOptions.java` § `apply` and `api-sheriff/src/main/resources/application.properties` § `quarkus.http.limits.max-connections` (verify-at-outline; read from the private notes, not from code, by the orchestrator)
- OBSERVED: an expired wait for a pooled connection ends as `UpstreamConnectionWaitExpired`, a timeout the breaker counts as the upstream's failure and the retry predicate refuses, with no check of what holds the pool — read at `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/edge/upstream/DispatchStage.java:956-959`, `:700-707` and `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/edge/upstream/UpstreamCircuitBreaker.java:146-163` (main `14741380`)
- OBSERVED: a dispatch holds a pooled upstream connection while it forwards the request body, and the egress client sets only the wait-queue size, not the pool size — read at `doc/adr/0060-…:33` and `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/edge/upstream/EgressClients.java:169-170` (main `14741380`)
- OBSERVED: a client departure ends the dispatch as a neutral outcome before the 10 s wait bound or the guard timeout — read at `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/edge/upstream/DispatchStage.java:927-940` (main `14741380`)
- HYPOTHESIS: pooled connections held by dispatches whose request body is still arriving can make the waits of other requests expire and be counted against an upstream that answers normally — confirm/refute by a test that holds the pool with request bodies that keep arriving (verify-at-outline, deliverable 12)
- HYPOTHESIS: an upstream that accepts and never answers goes uncounted while its callers leave before the wait bound — confirm/refute at `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/edge/upstream/DispatchStage.java:927-940` by a test (verify-at-outline, deliverable 12)
- HYPOTHESIS: a state-change consumer that throws changes the outcome of the call that caused the transition — confirm/refute at `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/edge/upstream/UpstreamCircuitBreaker.java:202-214,269-271` (verify-at-outline, deliverable 12)
- Verify-first clause: the verified results of SEC2-03-A to D in the notes were measured at `fa8dd54f`; re-measure at the `main` the plan starts from, and re-ground every `OBSERVED` claim above before scoping on it.

## Expected Surface

- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/forward/ForwardedClientResolution.java`
- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/edge/admission/SourceKeys.java`
- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/edge/admission/SourceGate.java`
- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/edge/admission/ThreatClassifier.java`
- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/edge/admission/EdgeHardeningOptions.java`
- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/detection/SourceLedger.java`
- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/pipeline/BasicChecksStage.java`
- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/pipeline/OriginValidationStage.java`
- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/bff/csrf/CsrfDefence.java`
- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/bff/reserved/LogoutEndpoint.java`
- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/bff/runtime/BffRuntime.java`
- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/edge/reserved/ReservedPathHandler.java`
- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/edge/terminal/TerminalDispatch.java`
- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/edge/upstream/UpstreamFailureMapper.java`
- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/edge/upstream/DispatchStage.java`
- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/edge/upstream/GrpcDispatchStage.java`
- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/edge/upstream/UpstreamCircuitBreaker.java`
- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/edge/upstream/EgressClients.java`
- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/config/RouteTableBuilder.java`
- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/config/DeclaredKeyMaterialKeys.java`
- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/config/validation/rule/`
- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/quarkus/ConfigProducer.java`
- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/quarkus/ExtensionIssuerNamespaceGate.java`
- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/quarkus/BffRuntimeProducer.java`
- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/tls/ManagementPlainHttpAudit.java`
- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/tls/TerminatedListenerTlsAudit.java`
- OBSERVED: `api-sheriff/src/main/resources/schema/endpoint.schema.json`
- OBSERVED: `api-sheriff/src/main/resources/application.properties`
- OBSERVED: `api-sheriff/src/test/java/de/cuioss/sheriff/gateway/forward/`
- OBSERVED: `api-sheriff/src/test/java/de/cuioss/sheriff/gateway/edge/admission/`
- OBSERVED: `api-sheriff/src/test/java/de/cuioss/sheriff/gateway/detection/`
- OBSERVED: `api-sheriff/src/test/java/de/cuioss/sheriff/gateway/bff/csrf/`
- OBSERVED: `api-sheriff/src/test/java/de/cuioss/sheriff/gateway/bff/reserved/`
- OBSERVED: `api-sheriff/src/test/java/de/cuioss/sheriff/gateway/config/`
- OBSERVED: `api-sheriff/src/test/java/de/cuioss/sheriff/gateway/tls/`
- OBSERVED: `api-sheriff/src/test/java/de/cuioss/sheriff/gateway/quarkus/`
- OBSERVED: `api-sheriff/src/test/java/de/cuioss/sheriff/gateway/pipeline/`
- OBSERVED: `api-sheriff/src/test/java/de/cuioss/sheriff/gateway/edge/terminal/`
- OBSERVED: `api-sheriff/src/test/java/de/cuioss/sheriff/gateway/edge/upstream/`
- OBSERVED: `doc/adr/0003-forwarded-headers.adoc`
- OBSERVED: `doc/adr/0060-Dispatch_sends_a_bodyless_method_no_body_and_frames_every_declared-length_body_by_exactly_that_length.adoc` — replace text only, never grow
- OBSERVED: `doc/adr/0064-The_upstream_guard_is_fixed_in_the_gateway_and_a_route_declares_no_circuit_breaker_settings.adoc`
- OBSERVED: `doc/adr/0076-One_per-source_detection_substrate_counts_pre-trust_rejections_and_answers_a_tripped_source.adoc`
- OBSERVED: `doc/adr/0078-Per-source_threat_classification_raises_one_ECS_alert_per_episode_from_its_own_ledger.adoc`
- OBSERVED: `doc/configuration/routing-and-forwarding.adoc`
- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/config/ConfigLogMessages.java`
- OBSERVED: `doc/variants/02-bff-session.adoc`
- OBSERVED: `doc/configuration/security-and-edge-hardening.adoc`
- OBSERVED: `doc/user/bff-session.adoc`
- OBSERVED: `doc/user/edge-security.adoc`
- OBSERVED: `doc/development/source-detection-substrate.adoc`
- OBSERVED: `doc/architecture/tls-and-inbound-security.adoc`
- OBSERVED: `doc/security-threat-model.adoc` — BFF-09, detection and management passages only
- OBSERVED: `doc/LogMessages.adoc`
- HYPOTHESIS: `integration-tests/src/test/java/de/cuioss/sheriff/gateway/integration/` — a logout or management-listener IT, only if the unit level cannot show the behaviour (verify-at-outline)

## Dependencies and Sequencing

- Depends on: PLAN-SEC2-01, PLAN-SEC2-02 and PLAN-SEC2-03 (all shipped; PLAN-SEC2-02 edited `edge/upstream/**`, `doc/security-threat-model.adoc` and `doc/LogMessages.adoc`, which this plan also edits).
- Overlaps with: PLAN-SEC2-05 on `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/edge/admission/EdgeHardeningOptions.java`, `doc/security-threat-model.adoc` and `doc/user/edge-security.adoc`.
- Adjacent to: the items the epic Vision kept in the private notes for a design decision, now decided by the operator on 2026-11-17: the request-head deadline is stated and documented (deliverable 10), back-channel logout across replicas gets a boot WARN (deliverable 11), the early-response quiet rule is PLAN-SEC2-05, the management plain-HTTP port is accepted with its existing WARN (deliverable 8 pins management transport behaviour only and adds no boot refusal), and the Info items are closed as recorded.

## Hand-Off Command

```text
/plan-marshall task="implement .plan/orchestrator/security-hardening-tier-2/plans/PLAN-SEC2-04-leftover-hardening.md"
```

## Write-Boundary

The plan implementing this spec touches only its own repository source and tests, and appends to the
git-ignored `.plan/local/security_notes.adoc` under Security Findings. It creates and edits NO file
under `.plan/orchestrator/` other than its own `inbox/{sender}-{seq}` landing message — the
orchestrator owns every other ledger write — and reports its outcome through its PR and that inbox
message. See `persona-plan-orchestrator/standards/orchestration-model.md` § Ledger Write-Boundary.
