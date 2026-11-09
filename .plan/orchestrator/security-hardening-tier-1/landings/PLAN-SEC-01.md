# Landing Analysis: PLAN-SEC-01 — Edge transport hardening (SNI ClientHello deadline, WebSocket upgrade gate, relay-failure upstream reset)

epic: security-hardening-tier-1
workstream: WS-01
pr: #582 — https://github.com/cuioss/API-Sheriff/pull/582

> Landing record for one shipped plan. Written by the `analyze` verb (inbox scan, message
> `plan-sec-01-edge-transport-hardening-001.md`) after checking the inbox claims against git, the
> merged code and the PR state through the CI abstraction.

## Deliverable Fidelity vs Spec

The spec lists 5 deliverables; the plan's outline carried 6 (`deliverables_total=6`, `deliverables_done=6`). The sixth is the integration tests (`TlsPassthroughIT`, `WebSocketProxyIT`), split out of D1/D2 as their own deliverable. Functional review: 3 met, 3 met with residuals, 0 not met.

| Deliverable (spec) | Verdict | Evidence |
|--------------------|---------|----------|
| D1 — SNI front: ClientHello deadline, undecided-connection cap, incremental parse, relay-leg idle timeout (M7) | shipped-modified | `SniFrontListener` deadline 10 s + cap 1024 (`onConnect`, `Incoming.begin`/`settle`); `ClientHelloSniParser.offer` parses once the record is complete; the relay idle timeout is DERIVED from the inner bounds + 60 s (`TlsEdgeProducer.relayIdleTimeout`), where the spec asked only for "an idle timeout". Tests: `SniFrontListenerTest` (silent / half-sent / slow-chunk positive control / over-cap / idle cases), `ClientHelloSniParserTest`, `TlsEdgeProducerTest` |
| D2 — WebSocket upgrade gate before the upstream dial (M2) | shipped-modified | New `edge/terminal/WebSocketUpgradeGate`, judged in `TerminalDispatch.dispatchWebSocket` ahead of session tracking, relay permit and dial; refusals via `ResponseEnds.reject`. 426 for version/Upgrade, 400 for a malformed key or version; added beyond the spec: 405 for non-GET, 400 for repeated headers, the padded-base64 key rule, and route security headers + Set-Cookie on refusals. Tests: `GatewayEdgeWebSocketUpgradeGateTest` (HTTP/2 and plain HTTP/1.1 GET refused with zero dials, valid upgrade relayed), `WebSocketUpgradeGateTest` |
| D3 — failed client upgrade answers the client (M2) | shipped-as-specified (accepted deviation) | `WebSocketRelayStage.endBeforeUpgrade` ends with 502 through `ResponseEnds.endResponse` when the response is not ended; comment, Javadoc and the wrong `WebSocketRelayStageTest` case corrected. Accepted deviation: HTTP/2 → 502 at the stage, HTTP/1.1 keeps Vert.x's 400 (pinned by test). With D2 in place this branch is defence in depth: no HTTP/2 request reaches it through the production edge |
| D4 — relay failure resets the upstream exchange (C3) | shipped-as-specified (accepted residual D4.C2) | One reset site, `TerminalDispatch.relayOnEventLoop` `onFailure` (`TerminalDispatch.java:625`); the old `startRelay` catch reset was removed; `ResponseStage` untouched. Test `GatewayEdgeRelayFailureResetTest` (HTTP/1.1 close and HTTP/2 reset). Residual: the test fills the default pool (5) with a 2 s bound rather than a pool of 1 with about 1 s |
| D5 — documentation for the changed behaviour | shipped-as-specified | `TerminalDispatch` Javadoc; ADR-0066 WebSocket wording; threat-model rows GW-07 and GW-09 naming their tests; SNI front bounds in `doc/user/tls-edge.adoc` and `doc/development/tls-edge.adoc`; also `doc/user/protocol-routes.adoc` and `doc/development/protocol-processors.adoc`. ADR-0066 does not mention the 405 refusal (advisory) |
| (added) D6 — integration tests | added-unplanned (outline split) | `TlsPassthroughIT` (deadline close, upper bound only), `WebSocketProxyIT` (426 for plain GET, HTTP/2 GET, bad version) |

**Surface vs declaration** (`inbox landing-check` `surface_delta`, base `origin/main` = c85817d9, not stale): 12 declared, 28 realized, 19 added, 3 declared but untouched (`edge/relay/ResponseStage.java` and `GatewayEdgeHttp2AbuseTest.java`, both HYPOTHESIS entries the outline did not need, and `doc/LogMessages.adoc` — no new INFO/WARN/ERROR record, so correctly untouched). Every added file is explained by a deliverable. Against the other two specs (`corpus declaration-currency`) the footprint overlaps only `doc/security-threat-model.adoc`, the overlap the gate predicted and the operator accepted.

## Three-Layer Analysis

The landing was read on three layers — the spec, the deliverable trace and the merged code — by one read-only leaf, and the orchestrator re-read every point it marked `checked` before writing it here. The reading is **by reading, no build, no test run, no write**: anything only a run could show is tagged `not shown by reading`.

- **Dispatch:** one read-only leaf (`execution-context-level-3`, `orchestrator.analyze` resolved via `plan.effort`) — reason: every full-ship landing gets its own leaf; reading the merged code across 28 files is the depth the dispatch rule is for.
- **Re-read by the orchestrator at c85817d9:** `SniFrontListener` `onConnect` (cap, DEBUG-only refusal), `Incoming.begin`, `settle` (latch, timer cancel, single decrement); `TerminalDispatch` gate position and the single reset site; `WebSocketRelayStage.endBeforeUpgrade`; `TlsEdgeProducer.relayIdleTimeout` and `relayClientOptions` (no connect timeout set); `ClientHelloSniParser` declared-length bound and extension-block bound; `config/PolicyBlock.java:96` (method fallback); vertx-core 4.5.34 `Http2ServerRequest.toWebSocket` and `Http1xServerConnection.createHandshaker`.
- **Verdict:** layer 1 findings — 2 premises refuted (both absorbed by the shipped code); layer 2 clean apart from the accepted residuals; layer 3 findings — 6 points of attention, none blocking (see the Layer 3 section for how they are recorded).

### Layer 1 — Specified

The nine claim labels were settled and persisted onto the spec with `corpus set-verdict` (producer `security-hardening-tier-1/analyze`, `checked_at` c85817d9).

| Finding | Claim | Verdict | Polarity | Evidence (file:line) |
|---------|-------|---------|----------|----------------------|
| — | 0: front server default options, no timer, no cap | checked | confirms | `tls/SniFrontListener.java` at c85817d9^1, `start` / `Incoming.begin` |
| — | 1: full-buffer re-parse on every chunk, 32 KiB bound | checked | confirms | `tls/SniFrontListener.java` at c85817d9^1, `Incoming.onData` |
| — | 2: `PassthroughRelay` sets no idle timeout | checked | confirms | `tls/PassthroughRelay.java`, `TlsEdgeProducer` at c85817d9^1 |
| L1-1 | 3: WS dispatch checks no version/Upgrade; processor gates on GET only | checked | refutes | dial half holds; the GET-only gate is not applied at request time — `allowed_methods` falls back to every method (`config/PolicyBlock.java:96`). Absorbed by the gate's 405 branch (`rescoped: yes`) |
| — | 4: `WebSocketRelayStage` dials first; `onFailure` writes no response | checked | confirms | merge diff of `edge/relay/WebSocketRelayStage.java` |
| — | 5: Vert.x HTTP/2 `toWebSocket()` fails and writes nothing | checked | confirms | vertx-core 4.5.34 `Http2ServerRequest.toWebSocket` |
| L1-2 | 6: on HTTP/1.1 Vert.x answers any invalid upgrade itself | checked | refutes | `Http1xServerConnection.createHandshaker` checks `Connection`, method and version but not `Upgrade: websocket` or the key. The gate is needed on HTTP/1.1 too, and the shipped gate applies there (`rescoped: yes`) |
| — | 7: relay failure goes to `failRelay` without a reset | checked | confirms | `edge/terminal/TerminalDispatch.java` at c85817d9^1 |
| — | 8: `PipeImpl` drains the upstream on a client write failure | checked | confirms | vertx-core 4.5.34 `streams/impl/PipeImpl.to`; deliverable 4 stands |

**Layer 1 verdict:** findings — 2 (both refuted premises absorbed by the shipped implementation; no spec rewrite owed for a shipped plan).

### Layer 2 — Implemented

| Deliverable | Code trace | Test that can fail | Unexplained file / unborne claim | Verdict |
|-------------|------------|--------------------|---------------------------------|---------|
| D1 | `SniFrontListener` cap/deadline/settle; `ClientHelloSniParser.offer`; `TlsEdgeProducer.relayIdleTimeout` | `SniFrontListenerTest`, `ClientHelloSniParserTest`, `TlsEdgeProducerTest` | none | checked |
| D2 | `WebSocketUpgradeGate`; `TerminalDispatch.dispatchWebSocket` | `GatewayEdgeWebSocketUpgradeGateTest`, `WebSocketUpgradeGateTest` | none | checked |
| D3 | `WebSocketRelayStage.endBeforeUpgrade` | `WebSocketRelayStageTest` (HTTP/2 502, HTTP/1.1 400) | the inbox's "HTTP/2 now 502" describes the stage; at the edge an HTTP/2 client gets the gate's 426 | checked |
| D4 | `TerminalDispatch.java:625` | `GatewayEdgeRelayFailureResetTest` | residual D4.C2 (pool of 5, 2 s) | checked |
| D5 | ADR-0066, threat model GW-07/GW-09, tls-edge and protocol docs | n/a (docs) — every test name cited in the threat model exists | ADR-0066 omits the 405 | checked |
| D6 | `TlsPassthroughIT`, `WebSocketProxyIT` | themselves | deadline assertion has an upper bound only | checked |

Files no spec deliverable names, each explained by the diff: `GatewayEdgePipelineTest` (sends a valid handshake so its upstream-refusal cases pass the gate), `GatewayEdgeSessionRelayTest` (refusal carries the stage Set-Cookie), `LoopbackEphemeralBindArchTest` (comment only). The narrative's "two Sonar style commits" is one commit (955601e) carrying two fixes, plus a separate formatter-only commit (2845a46); both are behaviour-neutral by reading.

**Layer 2 verdict:** clean (accepted residuals D3.C4 and D4.C2 confirmed as stated).

### Layer 3 — Correct

| Defect | File:line | Triggering input | Effect | Severity |
|--------|-----------|------------------|--------|----------|
| L3-1 … L3-5 | `tls/` and `edge/terminal/` (new transport bounds) | — | Possible residual weaknesses in the new bounds. Not investigated and not fixed; per the project's disclosure rule they are kept out of tracked text, reported to the operator, and recorded locally if the operator agrees | minor |

No defect found by reading in: the undecided-connection counter balance (one decrement per admitted connection, latched), the `ClientHelloSniParser` bounds (both finalize out-of-bounds fixes hold), the 502 branch versus the reset site (no double end), and the gate's header parsing (case-insensitive token lists, stricter than Vert.x).

**Layer 3 not shown by reading:** the slot return on the deadline and close/error paths is untested; the `applyIdleTimeout` HOURS/DAYS branches, its final throw and the `onStartup` wiring are untested (advisories confirmed); thread-safety of the gate's header read on the virtual thread; behaviour of `quarkus.http.idle-timeout=0` on the derived relay timeout; an `accept()` failure after Netty wrote the 101.

**Layer 3 verdict:** findings — 5 minor (held outside tracked text), plus the not-shown-by-reading list above.

### Conformance to the Project's Own Rules

| Rule source | Rule | Verdict | Evidence |
|-------------|------|---------|----------|
| CLAUDE.md § Logging | CuiLogger with `%s` | conforms | all new log calls use `%s` |
| CLAUDE.md § Logging | INFO/WARN/ERROR via LogRecord in `doc/LogMessages.adoc` | conforms | every new log is DEBUG; `LogMessages.adoc` untouched |
| CLAUDE.md § Security | never log sensitive request data | conforms | new logs carry route id, status, limits only |
| CLAUDE.md § Testing | JUnit 5, AAA, no Mockito/PowerMock/Hamcrest | conforms | added tests use live Vert.x servers and Awaitility |
| CLAUDE.md § Javadoc | Javadoc + `@since`, package-info | conforms | `WebSocketUpgradeGate` fully documented; both package-infos extended |
| CLAUDE.md § Pre-1.0 | no `@Deprecated`, no shims, no new dependency | conforms | old constructors replaced; no pom change |
| ADR-0066 | every gateway answer through `ResponseEnds` | conforms | gate refusals via `reject`, 502 via `endResponse` |
| spec | no Vert.x internal API | conforms | `HttpUtils.canUpgradeToWebSocket` not used |
| spec | do not touch `edge/upstream/**` | conforms | no such path in the diff |
| CLAUDE.md § Security / never-silent-downgrade | secure defaults, nothing silent | deviates (minor) | one of the new bounds reports itself only at DEBUG; part of the layer-3 points held with the operator |

## Metrics and Anomalies

- Tokens: 34,903,306 total (`total_tokens`)
- Duration: 93,625 s wall (about 26 h)
- Model / effort: claude-opus-5-5, effort low; plugin and executor 0.1.2036
- Anomalies: the pre-merge gate refused on head binding (security-audit, simplify and functional-review last validated at 2845a46, merge head 955601e). The 0.1.2036 gate does not read refire waivers, so on the operator's instruction the PR was enqueued directly with `ci pr merge-queue`, after re-checking the findings gate (0 blocking) and reviewer participation. Tooling skew: executor 0.1.2036 vs installed plugin 0.1.2080; marshal.json provisioning reported stale.

## Routing and Merge Behavior

- Review: CodeRabbit passed; Sourcery skipped (optional). The CodeRabbit finding about refusal headers was fixed on the branch (60aac0aa).
- CI/merge: all 24 PR checks green or skipped (`ci checks status --pr-number 582`), including `build / conclusion` and `integration-tests / conclusion`. Merged through the merge queue as c85817d9 onto 4a1fc544. The post-merge Performance Benchmark run 42520067944, still in progress when the plan stopped, has since **passed**. The `main` push run for c85817d9 (`deploy-snapshot`) was not checked here — it is a Watch.
- Sonar: `step.sonar-roundtrip.new_code_issue_count=0`; the PR's SonarCloud check passed at the merge head.
- Collisions: none observed. PLAN-SEC-01 merged first; PLAN-SEC-03 (PR #581) is still running.

## Reconciliation Actions

- [x] row `status` → `shipped` — `orchestrator queue --transition PLAN-SEC-01 --status shipped`
- [x] row `pr` stamped — `orchestrator queue --set-row PLAN-SEC-01 --field pr --value 582`
- [x] row `landing` stamped — `orchestrator queue --set-row PLAN-SEC-01 --field landing --value landings/PLAN-SEC-01.md`
- [x] row `plan_marshall_plan_id` stamped — `plan-sec-01-edge-transport-hardening` (already set at `running`; re-stamped)
- [x] claim verdicts 0–8 persisted onto the spec — `corpus set-verdict` × 9
- [x] epic.md narrative reconciled (queue annotation, Watches)
- [x] resume anchor updated — `manage-status update-field --field resume_anchor --store orchestrator`
- [x] `queue-view.md` regenerated and committed with the row change — `orchestrator regenerate-view`

## Follow-Ups

- Test-coverage advisories (ADR-0066 405 wording, `onStartup` idle-timeout wiring, `applyIdleTimeout` HOURS/DAYS + throw, headers/Set-Cookie on the 503 refusals, `TlsPassthroughIT` lower bound, slot return on the deadline/close paths): not staged; held as a Watch for a follow-up decision.
- Layer-3 points of attention: with the operator (see Layer 3).
