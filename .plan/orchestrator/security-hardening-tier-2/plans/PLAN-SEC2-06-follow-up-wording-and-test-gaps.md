# PLAN-SEC2-06: Follow-up wording and test gaps

epic: security-hardening-tier-2
workstream: WS-04

> Staged plan spec — one shippable unit of work, ready for `/plan-marshall` hand-off.
> Lives at `plans/PLAN-SEC2-06-follow-up-wording-and-test-gaps.md` and is queued as one row file,
> `queue/PLAN-SEC2-06.json`. The orchestrator EMITS the command below; it never launches the plan
> inline. This spec is SELF-SUFFICIENT except for the security detail it deliberately keeps out of
> tracked text (see Security Findings).
> See `persona-plan-orchestrator/standards/orchestration-model.md` for the tier and hand-off contract.

## Objective

Close the small leftovers of PLAN-SEC2-05 and PLAN-SEC2-04 that the orchestrator's landing reads found: statements in
documents, Javadoc and log-message rows that no longer match the code, test blind spots, and two mechanism-level
questions to settle by test. This is the epic's last plan: it collects everything the two landings left, and the epic
closes when it ships. One plan, one PR. No behaviour changes unless a hypothesis below is confirmed by a test that fails first.

Disclosure is mechanism-level. Background: `landings/PLAN-SEC2-05.md` (items L2-1 to L2-3, L3-1, L3-2 and the ADR
entry), `landings/PLAN-SEC2-04.md` (items L1-2, L2-1 to L2-3, L3-1 to L3-3 and its Follow-Ups) and the git-ignored
`.plan/local/security_notes.adoc` entries SEC2-L-16 to SEC2-L-18 (local only; never quote them into tracked text).

## Deliverables

1. **One sweep over every restated statement.** The wording faults below share one cause: a term was changed in the code
   or a decision, and some of the documents and comments that restate it kept the old sentence. First list every site
   (read each against the merged code), write each corrected sentence once, apply them all in ONE commit, then re-read
   each restatement against the code it describes. The sites:
   - the 100-pending-PING bound sentence: the `InboundStreamWindow` Javadoc, the GW-08 passage of
     `doc/security-threat-model.adoc` and ADR-0066 say a client that acknowledges its PINGs never reaches the bound;
     a finished wait's PING stays counted until its acknowledgement, so the sentence is not guaranteed. State what the
     counter counts and when it is decremented;
   - the `<desc>` of `doc/resources/diagrams/http2-reset-and-body-timers.svg`, which still states the superseded rule;
   - ADR-0066: add the alternatives-rejected entry for comparing the stream's own receive window;
   - ADR-0076: "either" → "any of these", and the "forwarding header" wording;
   - the threat-model sentence on `Sec-Fetch-Site: none`, and the BFF-09 passage and the `LogoutEndpoint` Javadoc on
     what the logout proof refuses: re-read both against `CsrfDefence.provesTrustedDocumentNavigation` and state exactly
     the requests it refuses and the ones it admits;
   - the `ApiSheriff-149` template and its `doc/LogMessages.adoc` row for the blank-header case;
   - the `ApiSheriff-141` and `ApiSheriff-143` rows and the `SOURCE_TRIPPED` Javadoc: add the `unresolved-via-` source
     (the key of a trusted proxy's unresolvable forwarding header) to the list of source values;
   - the `SourceGate` "non-blank header" wording and its ambiguous antecedent, and the `EdgeRules` Javadoc;
   - the `RuleSupport` Javadoc that says `renderForMessage` / `renderForLog` are the single copy of the escaping, against
     the offline printer in `ConfigValidationCommand`, which carries its own: unify them, or state the second one;
   - the missing description of `UpstreamBodyTraffic.arrivingWithin`, over-long lines in the files this plan edits, and the
     D8 statement that the management idle reap stays at the Quarkus default (assert it, or say it is not asserted).
   - Done when: each site is corrected where it is stated and a final read of each restatement against its code finds no
     disagreement (`order: none`); the sweep is one commit.
   - Skills: pm-documents:ref-asciidoc, pm-documents:ref-documentation, pm-dev-java:javadoc
   - Persona: documenter
2. **Test blind spots of the early-response end (PLAN-SEC2-05 L2-1 to L2-3).** The trickle test ends its body after four
   frames, so every PING is made moved by the next frame and the quiet exit is never reached; the stopped-sender test and
   its IT assert only that a PING reached the client, with no elapsed bound, so a 1 s cap exit looks like a quiet exit; and
   no test drives `AcknowledgementRelay` (release drops the wait, a delivery after the end is a no-op, a late acknowledgement
   does not end the wait twice) or the PING-refused-then-retried glue end to end. Add the missing tests; each new test
   must be shown to fail against a deliberately broken variant of the behaviour it pins.
   - Done when: a slow sender that keeps going is proven safe and a stopped sender is proven to end quietly inside a stated
     elapsed bound, in the unit test and the IT; `AcknowledgementRelay` is driven by the three cases above
     (`order: test-first`).
   - Skills: pm-dev-java:java-core, pm-dev-java:junit-core, pm-dev-java:junit-integration
   - Persona: module-tester
3. **Timing-test attribution and two untested answers (PLAN-SEC2-04 L2-3, lessons).** (a) Settle by test whether
   `DispatchStageTest$BodyDeadlineBehindASlowConnection` has a test-side race on reading `upstreamBodyBytes`: reproduce
   with many runs under `-XX:ActiveProcessorCount=1` on an idle host (check `uptime` first), not a handful of runs under
   load; fix the race or record it as refuted. (b) Settle by test whether the extension's metrics collector can build its
   validator before the issuer gate's `StartupEvent` observer (the third D6 question, which PLAN-SEC2-04 left without a
   test). (c) Settle by test the route-id question of PLAN-SEC2-04 D5: the shortest input that carries a control character
   in an endpoint or route id past the schema pattern into `RouteTableBuilder.java` (lines 203, 206, 486, 494) and the
   route-id log lines; list the sites the pattern makes unreachable in the landing message, as the Done-when of D5 required.
   - Verify first: (a) to (c) are HYPOTHESIS in Claim Labels; a refutation is recorded with the test that shows it, not fixed.
   - Done when: each confirmed gap has a test failing at `00ebea64` and is fixed; each refuted one is recorded with the
     test that shows it; the unreachable-site list is in the landing message (`order: test-first`).
   - Skills: pm-dev-java:java-core, pm-dev-java:java-quarkus, pm-dev-java:junit-core, pm-dev-java-cui:cui-logging
   - Persona: module-tester
4. **Two mechanism-level questions of the logout leg and the body bookkeeping.** (a) Whether a request carrying the legacy
   prefetch markers `Purpose` or `X-Moz` and no `Sec-Purpose` is treated as speculative by the logout leg
   (`ReservedPathHandler.java:171-177`, `CsrfDefence.java:241-253`); if it is not, add the markers to the speculative test.
   (b) Whether the open-body mark in `DispatchStage.java:1483-1488` is released when the body stream's constructor refuses;
   if not, open after the stream is built or release on the failure path.
   - Verify first: both are HYPOTHESIS in Claim Labels; a refutation is recorded, not fixed.
   - Done when: each confirmed gap has a test failing at `00ebea64` and passing after the change, with a matched control
     (a request without the markers still logs out on a trusted navigation; a refusal-free dispatch still releases exactly
     once) (`order: test-first`).
   - Skills: pm-dev-java:java-core, pm-dev-java:java-security, pm-dev-java:junit-core
   - Persona: security-expert

## Standing Constraints

- Pre-1.0 rules: no deprecation, no compatibility shims.
- **No new dependency.** Logging via `CuiLogger`, `%s`, exception first; a new record is a `LogRecord` in `doc/LogMessages.adoc`
  with the next free identifier at the time of editing (re-read it).
- Tests: JUnit 5, AAA, no Mockito/PowerMock/Hamcrest. A behaviour change starts with a test that FAILS against the unfixed code
  at the sha its Done-when names; a test that cannot be made to fail refutes the premise: record and drop that part.
- **One sweep, one commit.** A change to a term several documents restate is applied to every restatement at once, and each
  restatement is checked against the code it describes; piecemeal sweeps cost a review round each (PLAN-SEC2-04 needed four).
- **Timing tests: no attribution from a loaded host.** Before attributing an intermittent timing failure to a commit, read
  `uptime`, reproduce with many runs, and prefer `-XX:ActiveProcessorCount=1`.
- Documentation states the mechanism only. ADR body ceiling is 500 lines: `doc/adr/0060-…` is at 517 and is not edited here;
  ADR-0066 and ADR-0076 are under the ceiling and are amended only where a statement is corrected.
- Re-read `doc/security-threat-model.adoc`, `doc/LogMessages.adoc` and the files named above at the `main` the plan starts from
  before editing.
- One PR, under CodeRabbit's 100-file cap. Revert unrelated formatter churn.
- Sonar: fix, or dismiss only on the operator's selection; `undecidable` blocks.

## Security Findings

Possible weaknesses this plan finds and does not fix on its branch go ONLY into a dated entry appended to the git-ignored
`.plan/local/security_notes.adoc` (plan id, location, what, why not fixed) — never into the PR, commits, docs or the inbox
message. A verified result of this plan's own deliverables is reported the same way, with the test that shows it.

## Claim Labels

- OBSERVED: the `InboundStreamWindow` Javadoc, the GW-08 passage and ADR-0066 state that a client acknowledging its PINGs never reaches the 100-PING bound, while the count is decremented only when the PING future completes — read at `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/edge/end/InboundStreamWindow.java:74-76,186-187` (main `7f09b60b`; landing PLAN-SEC2-05 L3-1)
- OBSERVED: `doc/LogMessages.adoc` rows 141 and 143 list the source as IPv4, IPv6 `/64`, `/48` or `unknown`, while `SourceKeyResolver` produces `unresolved-via-` plus the proxy's address — read at `doc/LogMessages.adoc:103` and `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/detection/SourceKeyResolver.java:84-88` (main `00ebea64`)
- OBSERVED: the offline `--validate-config` printer escapes a control character as `?` with its own routine, while the `RuleSupport` Javadoc names `renderForMessage` / `renderForLog` as the single copy — read at `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/ConfigValidationCommand.java:325,344` and `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/config/validation/rule/RuleSupport.java:44-46` (main `00ebea64`)
- OBSERVED: `UpstreamBodyTraffic.arrivingWithin` has only `@param` / `@return` tags and no description — read at `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/edge/assembly/UpstreamBodyTraffic.java:58-65` (main `00ebea64`)
- OBSERVED: the logout proof admits a request with no `Sec-Purpose`, a mode that is absent or `navigate`, a destination that is absent or `document`, and a same-origin site or a trusted `Origin`; the threat-model BFF-09 passage says same-origin provenance alone does not end a session — read at `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/bff/csrf/CsrfDefence.java:241-253` and `doc/security-threat-model.adoc:3333-3337` (main `00ebea64`)
- OBSERVED: the trickle test ends its body after four frames and no test touches `AcknowledgementRelay` — read at `api-sheriff/src/test/java/de/cuioss/sheriff/gateway/edge/GatewayEdgeEarlyResponseResetTest.java:186-193,526` and `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/edge/end/EarlyResponseEnd.java:378-398` (main `7f09b60b`; landing PLAN-SEC2-05 L2-1, L2-3)
- OBSERVED: the stopped-sender test asserts only that a PING reached the client — read at `api-sheriff/src/test/java/de/cuioss/sheriff/gateway/edge/GatewayEdgeEarlyResponseResetTest.java:592` and `integration-tests/src/test/java/de/cuioss/sheriff/gateway/integration/Http2EarlyResponseResetIT.java:182,214` (main `7f09b60b`; landing PLAN-SEC2-05 L2-2)
- OBSERVED: `RouteTableBuilder` still formats `endpoint.id()` and `route.id()` without the escaper — read at `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/config/RouteTableBuilder.java:203,206,486,494` (main `00ebea64`)
- HYPOTHESIS: `DispatchStageTest$BodyDeadlineBehindASlowConnection` has a test-side race on reading `upstreamBodyBytes`; the intermittent failure under host load 11–56 was not reproduced in 220 runs and the commit blamed for it was cleared — confirm/refute at `api-sheriff/src/test/java/de/cuioss/sheriff/gateway/edge/upstream/DispatchStageTest.java` § `BodyDeadlineBehindASlowConnection` (verify-at-outline)
  - verdict: unverifiable | checked_at: d5288639400ca254538d7007204d3633b0d55c04 | by: security-hardening-tier-2/analyze | rescoped: n/a | evidence: The landing says the idle-host repetitions of BodyDeadlineBehindASlowConnection were not run (host never idle) and that 160 loaded-host repeats under ActiveProcessorCount=1 showed no failure of the suspected read; the test-side race is neither shown nor refuted, and the repeats surfaced a different ordering (chunk after the deadline reset) instead
- HYPOTHESIS: the extension's metrics collector can build its validator before the issuer gate's `StartupEvent` observer — confirm/refute at `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/quarkus/ExtensionIssuerNamespaceGate.java` § the `StartupEvent` observer (verify-at-outline)
- HYPOTHESIS: the endpoint and route id patterns of the schema make a control character in an id unreachable at the `RouteTableBuilder` and route-id log sites — confirm/refute at `api-sheriff/src/main/resources/schema/endpoint.schema.json:114,195` by a test that feeds the shortest candidate input (verify-at-outline)
- HYPOTHESIS: a request carrying `Purpose: prefetch` or `X-Moz: prefetch` and no `Sec-Purpose` passes the logout proof — confirm/refute at `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/edge/reserved/ReservedPathHandler.java:171-177` and `CsrfDefence.java:241-253` by a test (verify-at-outline)
- HYPOTHESIS: the open-body mark opened at `DispatchStage.java:1483` is not released if `ByteCappedBodyStream`'s constructor throws — confirm/refute at `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/edge/upstream/DispatchStage.java:1480-1489` by a test that forces the refusal (verify-at-outline)
- Verify-first clause: re-ground every `OBSERVED` claim above at the `main` the plan starts from before scoping on it; line numbers move.

## Expected Surface

- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/edge/end/InboundStreamWindow.java`
- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/edge/end/EarlyResponseEnd.java`
- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/edge/assembly/UpstreamBodyTraffic.java`
- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/edge/upstream/DispatchStage.java`
- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/edge/reserved/ReservedPathHandler.java`
- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/edge/admission/SourceGate.java`
- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/bff/csrf/CsrfDefence.java`
- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/bff/reserved/LogoutEndpoint.java`
- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/ApiSheriffLogMessages.java`
- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/ConfigValidationCommand.java`
- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/config/ConfigLogMessages.java`
- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/config/RouteTableBuilder.java`
- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/config/validation/rule/EdgeRules.java`
- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/config/validation/rule/RuleSupport.java`
- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/quarkus/ExtensionIssuerNamespaceGate.java`
- OBSERVED: `api-sheriff/src/test/java/de/cuioss/sheriff/gateway/edge/GatewayEdgeEarlyResponseResetTest.java`
- OBSERVED: `api-sheriff/src/test/java/de/cuioss/sheriff/gateway/edge/end/`
- OBSERVED: `api-sheriff/src/test/java/de/cuioss/sheriff/gateway/edge/upstream/`
- OBSERVED: `api-sheriff/src/test/java/de/cuioss/sheriff/gateway/bff/csrf/`
- OBSERVED: `api-sheriff/src/test/java/de/cuioss/sheriff/gateway/bff/reserved/`
- OBSERVED: `api-sheriff/src/test/java/de/cuioss/sheriff/gateway/config/`
- OBSERVED: `api-sheriff/src/test/java/de/cuioss/sheriff/gateway/quarkus/`
- OBSERVED: `integration-tests/src/test/java/de/cuioss/sheriff/gateway/integration/Http2EarlyResponseResetIT.java`
- OBSERVED: `doc/adr/0066-An_HTTP2_response_answered_while_its_request_body_is_open_is_ended_once_request_data_stops_arriving_or_a_time_cap_passes_and_its_stream_is_then_reset_with_NO_ERROR.adoc`
- OBSERVED: `doc/adr/0076-One_per-source_detection_substrate_counts_pre-trust_rejections_and_answers_a_tripped_source.adoc`
- OBSERVED: `doc/resources/diagrams/http2-reset-and-body-timers.svg`
- OBSERVED: `doc/security-threat-model.adoc` — GW-08, BFF-09 and the `Sec-Fetch-Site: none` passages only
- OBSERVED: `doc/LogMessages.adoc`
- OBSERVED: `doc/user/edge-security.adoc`
- OBSERVED: `api-sheriff/src/main/resources/schema/endpoint.schema.json`

## Dependencies and Sequencing

- Depends on: PLAN-SEC2-04 and PLAN-SEC2-05 (both shipped; this plan edits what they merged).
- Overlaps with: none in this epic; kidicap PLAN-31 shares `doc/security-threat-model.adoc` with PLAN-SEC2-04 (shipped), so re-read it at the `main` the plan starts from.
- Adjacent to: the behaviour direction of the logout leg (decided by the operator on 2026-11-17: a trusted-navigation proof, not a POST-only logout) stays as shipped; deliverable 1 only makes the text say what the code refuses.

## Hand-Off Command

```text
/plan-marshall task="implement .plan/orchestrator/security-hardening-tier-2/plans/PLAN-SEC2-06-follow-up-wording-and-test-gaps.md"
```

## Write-Boundary

The plan implementing this spec touches only its own repository source and tests, and appends to the git-ignored
`.plan/local/security_notes.adoc` under Security Findings. It creates and edits NO file under `.plan/orchestrator/` other than
its own `inbox/{sender}-{seq}` landing message — the orchestrator owns every other ledger write — and reports its outcome through
its PR and that inbox message. See `persona-plan-orchestrator/standards/orchestration-model.md` § Ledger Write-Boundary.
