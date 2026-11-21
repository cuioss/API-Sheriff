# PLAN-SEC2-08: Open watches and leftovers

epic: security-hardening-tier-2
workstream: WS-04

> Staged plan spec — one shippable unit of work, ready for `/plan-marshall` hand-off.
> Lives at `plans/PLAN-SEC2-08-open-watches-and-leftovers.md` and is queued as one row file,
> `queue/PLAN-SEC2-08.json`. The orchestrator EMITS the command below; it never launches the plan
> inline. This spec is SELF-SUFFICIENT except for the detail it deliberately keeps out of tracked
> text (see Security Findings).
> See `persona-plan-orchestrator/standards/orchestration-model.md` for the tier and hand-off contract.

## Objective

Close the open Watches and leftovers of this epic that a change can close, after PLAN-SEC2-07 shipped as #647 (`2335c81d`): the missing
test of the `AcknowledgementRelay` release wiring through the real wait, three restatements the sweep of PLAN-SEC2-07 did not reach, the
boot-rule test gap and the wider-than-its-row `ApiSheriff-152` template, and the one corrected statement (the connection cap counts
after the handshake) that no test pins. No behaviour change beyond the one log template named in deliverable 3. One plan, one PR.

This is the last plan of the epic. The idle-host repetitions of `DispatchStageTest$BodyDeadlineBehindASlowConnection` are deliberately
NOT in it: they need a quiet host, not a change, and the operator decided on 2026-11-21 to carry that Watch forward as an owed lead.

## Deliverables

1. **A test of the `AcknowledgementRelay` release wiring through the real wait (Watch of PLAN-SEC2-06, L2-4).**
   `lateAcknowledgementDoesNotEndTheWaitASecondTime` re-implements the wait's ending in a lambda, so the tests stay green if
   `EarlyResponseEnd.Wait.finish` stops calling `relay.release()`. Drive the real wait (the way the neighbouring
   `unacknowledgedPingLeavesTheWaitToTheCap` cases do) to its end by an acknowledgement, hand it a second acknowledgement, and
   assert that the response is not ended twice. A package-private seam in `EarlyResponseEnd` is allowed only if the existing
   entry points cannot reach the wait, and only with its Javadoc; if reaching the wait needs more than that, drop the deliverable at
   outline and say why.
   - Verify first: that the real wait is reachable from `EarlyResponseEndTest` without a production change (HYPOTHESIS in Claim Labels).
   - Done when: the new test passes against `main`, and goes red against a variant of `Wait.finish` with the `relay.release()` call
     removed, shown once and then restored in the commit (`order: test-first`).
   - Skills: pm-dev-java:java-core, pm-dev-java:junit-core
   - Persona: module-tester
2. **Three restatements the sweep of PLAN-SEC2-07 did not reach (its landing, L2-1).** List each, read it against the code, write
   the corrected sentence once, apply all three in ONE commit, then re-read each against the code it describes:
   - `integration-tests/docker-compose.yml` (the comment above `QUARKUS_HTTP_SSL_PORT=8444`) and
     `integration-tests/src/main/docker/sheriff-config/gateway.yaml` (the passthrough comment) call 8444 the "internal loopback
     port": the image starts the application with `-Dquarkus.http.host=0.0.0.0` (`Dockerfile.native`), so the listener is bound on all
     interfaces and the front hands connections to it on `localhost:8444`; say that, as `doc/development/tls-edge.adoc` now does;
   - the `PendingAuthorizationStore` Javadoc says a cookie variant can supply an implementation where the sealed record travels in
     the cookie and no server map exists; `BffRuntimeProducer` builds one `InMemoryPendingAuthorizationStore` for both modes and the
     corrected BFF-02 passage of the threat model says what is single-use in each: state that.
   - Done when: each site is corrected where it is stated and a final read of each restatement against its code finds no
     disagreement (`order: none`); the sweep is one commit.
   - Skills: pm-dev-java:javadoc, pm-documents:ref-documentation
   - Persona: documenter
3. **The boot-rule template and its test gap (PLAN-SEC2-07 landing, L3-1 and L2-2).** The `ApiSheriff-152` template states both
   consequences for whichever combination its argument names: "an acting detection action blocks or delays all of them at once, and
   a loopback trusted proxy trusts every client's own forwarded headers". The first is untrue when a loopback trusted proxy is also
   configured (detection then keys on the header-derived sources); the document row and
   `doc/configuration/security-and-edge-hardening.adoc` carry the qualifier, the log line does not. Reword the template so each
   combination's record states its own consequence and the qualifier, and bring the template column of the `doc/LogMessages.adoc` row
   to the same text. Pin the loopback detection beyond the two literals it has today.
   - Verify first: the next free identifier is not needed (no new record); re-read `doc/LogMessages.adoc` at the `main` the plan starts from.
   - Done when: the record logged for `enumeration_detection.action` states that combination's consequence and not the other's, and
     the record logged for a loopback trusted proxy the reverse, each asserted by a test that fails at `434193b0`; the template column of
     the document row equals the template (`order: test-first`).
   - Done when: `EdgeRulesTest` pins that a covering range (`0.0.0.0/1`, `::/1`) in `trusted_proxies` warns under passthrough, and that a
     malformed entry produces no collapse warning (the configuration is refused elsewhere); both hold at `434193b0` (a coverage
     clause, `order: none`).
   - Skills: pm-dev-java:java-core, pm-dev-java:junit-core, pm-dev-java-cui:cui-logging
   - Persona: implementer
4. **Pin the statement that the connection cap counts after the handshake (optional; drop at outline if it needs more than the existing
   fixture of `MainListenerConnectionCapIT`).** PLAN-SEC2-07 corrected the connection-cap sentences to say the cap counts a
   connection on the HTTPS listener only after its TLS handshake has completed. No test pins it; it rests on Quarkus and Vert.x
   internals. Show it on the native image: open raw TCP connections that never send a ClientHello beyond the cap's number, then
   assert that a client completing a real handshake is still served.
   - Verify first: HYPOTHESIS in Claim Labels. A refutation is recorded with the test that shows it, and the sentence is then corrected
     in every document that carries it in one commit (the surface lists them).
   - Done when: the test shows the statement (handshake-pending connections do not occupy the cap) and is a case of
     `MainListenerConnectionCapIT`; or the statement is refuted by the test and every restatement is corrected in one commit
     (`order: none`).
   - Skills: pm-dev-java:junit-core, pm-dev-java:junit-integration
   - Persona: integration-tester

## Standing Constraints

- Pre-1.0 rules: no deprecation, no compatibility shims.
- **No new dependency.** Logging via `CuiLogger`, `%s`, exception first; the reworded record keeps its identifier and its place in
  `doc/LogMessages.adoc`.
- Tests: JUnit 5, AAA, no Mockito/PowerMock/Hamcrest. A behaviour change starts with a test that FAILS against the unfixed code at the
  sha its Done-when names; a test that cannot be made to fail refutes the premise: record and drop that part.
- Fixed constants, no new `gateway.yaml` key.
- **A sweep is one commit.** A change to a statement several places restate is applied to every restatement at once, and each
  restatement is checked against the code it describes.
- Re-read `doc/LogMessages.adoc` and `doc/security-threat-model.adoc` at the `main` the plan starts from.
- No Done-when of this plan depends on a quiet host.
- One PR, under CodeRabbit's 100-file cap. Revert unrelated formatter churn.
- Sonar: fix, or dismiss only on the operator's selection; `undecidable` blocks.

## Security Findings

Possible weaknesses this plan finds and does not fix on its branch go ONLY into a dated entry appended to the git-ignored
`.plan/local/security_notes.adoc` (plan id, location, what, why not fixed) — never into the PR, commits, docs or the inbox
message. A verified result of this plan's own deliverables is reported the same way, with the test that shows it.

## Claim Labels

- OBSERVED: `Wait.finish` calls `relay.release()`, and the relay test re-implements the ending in a lambda — read at `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/edge/end/EarlyResponseEnd.java:333-335` and `api-sheriff/src/test/java/de/cuioss/sheriff/gateway/edge/end/EarlyResponseEndTest.java:408-430` (main `434193b0`)
- HYPOTHESIS: the real wait is reachable from `EarlyResponseEndTest` through the entry points the neighbouring tests use, with no production change — confirm/refute at `api-sheriff/src/test/java/de/cuioss/sheriff/gateway/edge/end/EarlyResponseEndTest.java:65,224,306` and `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/edge/end/EarlyResponseEnd.java:271` (verify-at-outline, deliverable 1)
- OBSERVED: the two comments call 8444 the "internal loopback port" — read at `integration-tests/docker-compose.yml:425` and `integration-tests/src/main/docker/sheriff-config/gateway.yaml:81`; the image binds all interfaces — read at `api-sheriff/src/main/docker/Dockerfile.native:72` (main `434193b0`)
- OBSERVED: the `PendingAuthorizationStore` Javadoc describes a cookie variant with no server map — read at `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/bff/pending/PendingAuthorizationStore.java:28`; `BffRuntimeProducer` builds one store for both modes — read at `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/quarkus/BffRuntimeProducer.java:597` (main `434193b0`)
- OBSERVED: the `ApiSheriff-152` template states both consequences for either combination, and the document row carries the qualifier — read at `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/config/ConfigLogMessages.java:712` and `doc/LogMessages.adoc:114` (main `434193b0`)
- OBSERVED: the boot-rule tests pin `127.0.0.1/32` and `::1/128`, and `holdsLoopback` tests for overlap with `127.0.0.0/8` and for `::1` — read at `api-sheriff/src/test/java/de/cuioss/sheriff/gateway/config/validation/rule/EdgeRulesTest.java:288-309` and `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/config/validation/rule/EdgeRules.java:369-382` (main `434193b0`)
- OBSERVED: the sentence that the cap counts a connection only after its TLS handshake is stated in five places — read at `api-sheriff/src/main/resources/application.properties`, `doc/user/edge-security.adoc`, `doc/security-threat-model.adoc`, `doc/architecture/tls-and-inbound-security.adoc` and `doc/configuration/security-and-edge-hardening.adoc` (main `434193b0`); the test that exercises the cap is `integration-tests/src/test/java/de/cuioss/sheriff/gateway/integration/MainListenerConnectionCapIT.java:165`
- HYPOTHESIS: connections that have not completed their TLS handshake do not occupy the connection cap — confirm/refute at `integration-tests/src/test/java/de/cuioss/sheriff/gateway/integration/MainListenerConnectionCapIT.java` § `closesTheConnectionBeyondTheCap` with raw sockets that send no ClientHello (verify-at-outline, deliverable 4)
- Verify-first clause: re-ground every `OBSERVED` claim above at the `main` the plan starts from before scoping on it; line numbers move.

## Expected Surface

- OBSERVED: `api-sheriff/src/test/java/de/cuioss/sheriff/gateway/edge/end/EarlyResponseEndTest.java`
- HYPOTHESIS: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/edge/end/EarlyResponseEnd.java` — only if the real wait is not reachable and a package-private seam is allowed (deliverable 1)
- OBSERVED: `integration-tests/docker-compose.yml`
- OBSERVED: `integration-tests/src/main/docker/sheriff-config/gateway.yaml`
- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/bff/pending/PendingAuthorizationStore.java`
- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/config/ConfigLogMessages.java`
- OBSERVED: `doc/LogMessages.adoc`
- OBSERVED: `api-sheriff/src/test/java/de/cuioss/sheriff/gateway/config/validation/rule/EdgeRulesTest.java`
- OBSERVED: `integration-tests/src/test/java/de/cuioss/sheriff/gateway/integration/MainListenerConnectionCapIT.java`
- HYPOTHESIS: `api-sheriff/src/main/resources/application.properties` — only if deliverable 4 refutes the cap sentence (its cap comment)
- HYPOTHESIS: `doc/user/edge-security.adoc` — only if deliverable 4 refutes the cap sentence
- HYPOTHESIS: `doc/security-threat-model.adoc` — only if deliverable 4 refutes the cap sentence (the transport-bounds passage)
- HYPOTHESIS: `doc/architecture/tls-and-inbound-security.adoc` — only if deliverable 4 refutes the cap sentence
- HYPOTHESIS: `doc/configuration/security-and-edge-hardening.adoc` — only if deliverable 4 refutes the cap sentence
- HYPOTHESIS: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/edge/admission/ConnectionCapAudit.java` — only if deliverable 4 refutes the cap sentence (its Javadoc restates it)

## Dependencies and Sequencing

- Depends on: PLAN-SEC2-07 (shipped as #647; this plan starts from the `main` that contains it).
- Overlaps with: kidicap `PLAN-31` on `doc/security-threat-model.adoc`, and only on the refutation arm of deliverable 4; the overlap prompt of `next` decides then.
- Adjacent to: `api-sheriff/src/test/java/de/cuioss/sheriff/gateway/edge/upstream/DispatchStageTest.java`, whose `BodyDeadlineBehindASlowConnection` idle-host repetitions stay an owed lead of the epic (not carried here), and the legacy-marker and abandon-path observations of the PLAN-SEC2-06 and PLAN-SEC2-07 landings, which the operator holds outside the repository.

## Hand-Off Command

```text
/plan-marshall task="implement .plan/orchestrator/security-hardening-tier-2/plans/PLAN-SEC2-08-open-watches-and-leftovers.md"
```

## Write-Boundary

The plan implementing this spec touches only its own repository source and tests, and appends to the git-ignored
`.plan/local/security_notes.adoc` under Security Findings. It creates and edits NO file under `.plan/orchestrator/` other than
its own `inbox/{sender}-{seq}` landing message — the orchestrator owns every other ledger write — and reports its outcome through
its PR and that inbox message. See `persona-plan-orchestrator/standards/orchestration-model.md` § Ledger Write-Boundary.
