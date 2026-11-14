# PLAN-SEC-07: Tier-2 follow-ups — connection-cap bounds, WebSocket-rule controls, leftover documentation

epic: security-hardening-tier-1
workstream: WS-04
lane: runbook

> Staged plan spec — one shippable unit of work, run through the runbook lane (a Claude Code
> session, no `/plan-marshall` lifecycle). Lives at `plans/PLAN-SEC-07-tier-2-follow-ups.md` and is
> queued as one row file, `queue/PLAN-SEC-07.json`, in the epic ledger. The orchestrator EMITS the
> command below; it never launches the plan inline. This spec is SELF-SUFFICIENT except for the
> security detail it deliberately keeps out of tracked text (see Security Findings).

## Objective

The PLAN-SEC-06 landing analysis (PR #607, `898e0100`, see `landings/PLAN-SEC-06.md`) found that the
tier-2 quick wins shipped with one major and several minor points in the new connection cap, an
incomplete documentation correction, and a test gap. This plan closes them.

The JWKS refresh points of that analysis (S6-E, S6-F and a racy `RetryingJwksLoaderTest` assertion)
are NOT in this plan: `RetryingJwksLoader`, its test and ADR-0063 are the declared surface of the
kidicap-gateway-requirements epic's launched PLAN-31 ("jwks-exact-publish-rule"), and that epic is
hands-off from here. They were handed to the operator for that epic. Do not touch those three files.

Disclosure is mechanism-level here, as for PLAN-SEC-04/05. The triggering input and effect of each
point are in the operator's git-ignored `.plan/local/security_notes.adoc`, section
"2026-11-13 — PLAN-SEC-06 landing analysis", entries S6-A … S6-D. **Read that section before D1–D2.**
It is local, not tracked, and must never be quoted into a commit, PR, doc or inbox message.

## Deliverables

1. **Bound the Quarkus-terminated connections per source (S6-A).** The connection cap
   (`quarkus.http.limits.max-connections`, `application.properties:162`) is one gateway-wide
   counter shared by the HTTPS and the plain-HTTP redirect listeners, and no per-source bound
   exists in front of it. Add a per-source connection share on the Quarkus-terminated side, keyed
   the way the SNI front keys its shares (IPv4 address, IPv6 /64), so one source cannot hold more
   than a fixed fraction of the cap; a connection over its source's share is closed at accept and
   counted (new `EventType` / counter, and a WARN `LogRecord` rate-limited the way the front's
   refusals are). Decide at implementation whether the redirect listener should count toward the
   share and the cap; state the decision in the Javadoc and docs.
   - **Verify first (HYPOTHESIS below):** find a supported hook that sees each accepted connection of
     the Quarkus HTTP server before a request exists (a Vert.x `connectionHandler` or a Netty
     pipeline handler reached through a Quarkus customizer). If no supported hook exists, STOP this
     deliverable, record why in the landing message, and continue with D2–D5 — do not patch
     Quarkus internals or add a dependency.
   - Done when: a test that opens more connections from one source than its share sees the excess
     closed while a second source is still served (matched control), failing against the unfixed
     code (`order: test-first`); the share constant is pinned by a test that goes red if it is
     removed.
   - Skills: pm-dev-java:java-core, pm-dev-java:java-quarkus, pm-dev-java:java-security, pm-dev-java:junit-core, pm-dev-java-cui:cui-logging
   - Persona: plan-marshall:persona-security-expert
2. **Make the cap and admission agree; size descriptors honestly (S6-B, S6-C, S6-D).**
   - Give the connection cap headroom above the default `admission_cap`, so on HTTP/1.1 admission
     answers its 503 before the cap closes connections and idle keep-alive connections do not crowd
     out admitted work. Rewrite the derivation comment (`application.properties:143-161`) and the
     Connection-cap row in `doc/user/edge-security.adoc` to match.
   - Correct the descriptor sizing to count the SNI front's relay sockets (three descriptors per
     terminated connection under `passthrough_sni`) and the uncounted passthrough relays; state the
     resulting nofile advice.
   - A configured `edge_hardening.admission_cap` the connection cap cannot honour must not be
     silently capped: WARN at boot with a new `LogRecord` (next free identifier, documented in
     `doc/LogMessages.adoc`), and say so in `doc/configuration/security-and-edge-hardening.adoc`
     next to `admission_cap`.
   - Done when: `ShippedApplicationPropertiesTest` pins cap > default `admission_cap` (goes red if
     the headroom is removed); a boot test with an `admission_cap` above what the cap allows sees the
     WARN, and one below it does not (matched control) — `order: test-first`.
   - Skills: pm-dev-java:java-core, pm-dev-java:java-quarkus, pm-dev-java:junit-core, pm-dev-java-cui:cui-logging, pm-dev-java-cui:cui-testing
   - Persona: plan-marshall:persona-implementer
3. **Negative controls for the WebSocket Authorization-origin rule.** No test pins that
   `ForwardingRules.validateWebSocketAuthorizationOrigins` leaves alone a `require: none` HTTP or
   gRPC route whose `headers_allow` names `Authorization`, or a non-proxy route — removing the guard
   at `ForwardingRules.java:274` goes undetected today. Add both controls to `ForwardingRulesTest`.
   - Done when: deleting the protocol / proxy-route guard turns the new tests red
     (`order: mutation-after`, mutation and output recorded).
   - Skills: pm-dev-java:junit-core
   - Persona: plan-marshall:persona-module-tester
4. **Finish the PLAN-SEC-06 documentation correction (D6 leftovers).** Documentation, Javadoc and
   log-template text only; state the mechanism, no attack narrative.
   - `doc/security-threat-model.adoc` GW-08 Assertion (≈2111-2112) and
     ADR-0076 (≈197): resets the transport sends are counted by Netty's encoder-side limit (every
     sent reset except `NO_ERROR`/`CANCEL`), as the rest of the threat model now says.
   - Management port: `doc/architecture/observability-and-errors.adoc` (≈259),
     `doc/user/container-image.adoc` (≈278), `doc/resources/diagrams/management-metrics-and-health.svg`
     (≈42) and `doc/resources/diagrams/tls-sni-split-and-seams.svg` (≈96) still call HTTPS the
     default; it is HTTPS only when key material is supplied.
   - ApiSheriff-115 (`ConfigLogMessages.java` ≈177, `doc/LogMessages.adoc` ≈79): the remedy must name
     supplying key material; `management.tls.enabled: true` alone restores nothing.
   - GW-09 Control and its summary row in the threat model: name the `require: none` WebSocket
     refusals (Authorization re-admission without `allowed_origins`, and the cookie-forwarding one).
   - The D1/D2 changes are reflected in GW-07/GW-08, `doc/user/edge-security.adoc` and
     `doc/architecture/tls-and-inbound-security.adoc`.
   - Done when: a repository-wide search finds no remaining "HTTPS by default" / "HTTPS default"
     management claim and no "gateway-sent resets never count" statement (`order: none`; record the
     search and its empty result).
   - Skills: pm-documents:ref-asciidoc, pm-documents:ref-documentation, pm-documents:ref-svg-diagrams
   - Persona: plan-marshall:persona-documenter

## Standing Constraints

- Pre-1.0 rules: no deprecation, no compatibility shims.
- **No new dependency.** Logging through `CuiLogger` with `%s`, exception first; every new
  INFO/WARN/ERROR record is a `LogRecord` documented in `doc/LogMessages.adoc` with the next free
  identifier, derived at write time.
- Tests: JUnit 5, AAA, no Mockito/PowerMock/Hamcrest. Code fixes (D1–D2) start with a test that
  FAILS against the unfixed code; a fix whose test cannot be made to fail is a refuted premise —
  record it in the landing message and drop that part.
- New limits are fixed constants (no new `gateway.yaml` key) unless D1's verify-first shows a key is
  unavoidable; a new key needs a test that goes red when it is deleted.
- Do not touch `edge/upstream/**`, `edge/terminal/**`, `auth/RetryingJwksLoader*` or ADR-0063. `tls/SniFrontListener` may be read (its share
  keying is the model for D1) but not changed.
- Documentation states the mechanism only — no attack recipe, no rates, nothing about weaknesses
  that remain open.
- One PR. Keep it under CodeRabbit's 100-file cap.
- Where Sonar is configured: an open Sonar issue is fixed in a pushed commit or dismissed with a
  stated reason; the merge gate needs the open-issue count at the head to be `0` or every remaining
  issue dismissed, and an `undecidable` read blocks.

## Security Findings

Possible weaknesses this plan finds that it does not fix on its branch are NOT written into any
tracked text (PR body, commit message, documentation, inbox message). Append a dated entry to the
git-ignored `.plan/local/security_notes.adoc` instead — plan id, location, what was observed, why
it was not fixed — and say only that a local note was recorded. Fixed findings may be described at
mechanism level once the fix is on the branch.

## Claim Labels

- OBSERVED: the connection cap is one counter shared by the HTTPS and redirect listeners, with no per-source bound — read at `api-sheriff/src/main/resources/application.properties:143-162` (main `898e0100`)
  - verdict: corroborated | checked_at: d6b90737 | by: security-hardening-tier-1/analyze | rescoped: n/a | evidence: application.properties:146-148,162 at d6b90737: one counter shared by redirect and HTTPS listeners; no per-source bound (D1 not implemented, gap stays)
- OBSERVED: the cap equals the default `admission_cap` (2048) — read at `api-sheriff/src/main/resources/application.properties:151-153,162` (main `898e0100`)
  - verdict: corroborated | checked_at: d6b90737 | by: security-hardening-tier-1/analyze | rescoped: n/a | evidence: application.properties:162 at d6b90737: max-connections=2048 = default admission_cap; #619 raises it to 4096
- OBSERVED: `admission_cap` has no schema maximum and no boot warning against the connection cap — read at `api-sheriff/src/main/resources/schema/gateway.schema.json:345` (main `898e0100`)
  - verdict: corroborated | checked_at: d6b90737 | by: security-hardening-tier-1/analyze | rescoped: n/a | evidence: gateway.schema.json:345 at d6b90737: admission_cap minimum 1, no maximum, no boot warning; #619 adds ConnectionCapAudit (ApiSheriff-148)
- OBSERVED: the GW-08 Assertion still says a gateway-sent reset never counts — read at `doc/security-threat-model.adoc:2111-2112` (main `898e0100`)
  - verdict: corroborated | checked_at: d6b90737 | by: security-hardening-tier-1/analyze | rescoped: n/a | evidence: security-threat-model.adoc:2111-2112 at d6b90737: 'never counts towards a client's Rapid-Reset limit'; corrected by #619
- OBSERVED: the ApiSheriff-115 remedy says `management.tls.enabled: true` restores the HTTPS default — read at `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/config/ConfigLogMessages.java:177` (main `898e0100`)
  - verdict: corroborated | checked_at: d6b90737 | by: security-hardening-tier-1/analyze | rescoped: n/a | evidence: ConfigLogMessages.java:177 at d6b90737 carried 'restore the HTTPS default'; corrected by #619
- OBSERVED: no `ForwardingRulesTest` case covers a `require: none` HTTP/gRPC route re-admitting `Authorization` or a non-proxy route — read at `api-sheriff/src/test/java/de/cuioss/sheriff/gateway/config/validation/rule/ForwardingRulesTest.java:235-335` (main `898e0100`)
  - verdict: corroborated | checked_at: d6b90737 | by: security-hardening-tier-1/analyze | rescoped: n/a | evidence: ForwardingRulesTest at d6b90737 had no non-WebSocket / non-proxy control; #619 adds them
- HYPOTHESIS: a supported hook sees every connection the Quarkus HTTP server accepts, before a request exists, without patching Quarkus — confirm at the Quarkus `VertxHttpRecorder` max-connections wiring and the available `HttpServerOptionsCustomizer` / Vert.x `connectionHandler` surface (verify-first, D1)
  - verdict: contradicted | checked_at: e1b8a2cb | by: security-hardening-tier-1/analyze | rescoped: yes | evidence: quarkus-vertx-http 3.40.1 VertxHttpRecorder:1397-1428: the only HttpServer.connectionHandler is Quarkus' own max-connections counter; servers are local (1329,1355); HttpServerOptionsCustomizer/HttpServerStart carry options only; Vert.x 4.5.34 connectionHandler is single-slot. Unexamined: Transport SPI (ServerBootstrap) — internals-adjacent. D1 dropped by its verify-first clause (rescoped).

## Expected Surface

- OBSERVED: `api-sheriff/src/main/resources/application.properties` — cap headroom, derivation comment
- HYPOTHESIS: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/edge/admission/` — the per-source connection share and its hook (new class), `EdgeHardeningOptions.java` Javadoc (verify-first)
- HYPOTHESIS: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/events/EventType.java` and `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/quarkus/SheriffMetrics.java` — refusal event and counter (verify-first)
- HYPOTHESIS: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/config/` — the boot WARN for an `admission_cap` the cap cannot honour, and its `LogRecord` (`ConfigLogMessages.java` or the edge log-messages class)
- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/config/ConfigLogMessages.java` — ApiSheriff-115 remedy text
- OBSERVED: `api-sheriff/src/test/java/de/cuioss/sheriff/gateway/config/validation/rule/ForwardingRulesTest.java`
- OBSERVED: `api-sheriff/src/test/java/de/cuioss/sheriff/gateway/quarkus/ShippedApplicationPropertiesTest.java`
- HYPOTHESIS: `api-sheriff/src/test/java/de/cuioss/sheriff/gateway/edge/admission/` — the per-source share test, beside `MainListenerConnectionCapTest` (verify-first)
- HYPOTHESIS: `integration-tests/src/test/java/de/cuioss/sheriff/gateway/integration/MainListenerConnectionCapIT.java` — only if the share needs an end-to-end pin
- OBSERVED: `doc/LogMessages.adoc`
- OBSERVED: `doc/security-threat-model.adoc` — GW-07/GW-08/GW-09 passages and summary rows (not the `gw-07-jwks-refresh` subsection)
- OBSERVED: `doc/adr/0076-One_per-source_detection_substrate_counts_pre-trust_rejections_and_answers_a_tripped_source.adoc`
- OBSERVED: `doc/user/edge-security.adoc`
- OBSERVED: `doc/user/container-image.adoc`
- OBSERVED: `doc/architecture/observability-and-errors.adoc`
- OBSERVED: `doc/architecture/tls-and-inbound-security.adoc`
- OBSERVED: `doc/configuration/security-and-edge-hardening.adoc`
- OBSERVED: `doc/resources/diagrams/management-metrics-and-health.svg`
- OBSERVED: `doc/resources/diagrams/tls-sni-split-and-seams.svg`

## Dependencies and Sequencing

- Depends on: PLAN-SEC-06 (shipped, #607, `898e0100`). Nothing else in this epic is staged or running.
- Overlaps with: none in this epic. kidicap-gateway-requirements PLAN-31 (launched) shares one file, `doc/security-threat-model.adoc` (different subsection: it owns `gw-07-jwks-refresh`); a single shared file does not block. Recheck that epic's running plans for `doc/LogMessages.adoc` before launching (read only).

## Hand-Off Command

```text
/plan-marshall-runbook-lane plan=.plan/orchestrator/security-hardening-tier-1/plans/PLAN-SEC-07-tier-2-follow-ups.md
```

## Write-Boundary

The plan implementing this spec touches only its own repository source and tests, and appends to
the git-ignored `.plan/local/security_notes.adoc` under Security Findings. It creates and edits NO
file under `.plan/orchestrator/` other than its own `inbox/{sender}-{seq}` landing message — the
orchestrator owns every other ledger write — and reports its outcome through its PR and that inbox
message. The inbox exception's qualifiers and the sole sanctioned write mechanism are stated in
`persona-plan-orchestrator/standards/orchestration-model.md` § Ledger Write-Boundary.
